package com.chartering.service.imports;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a contacts export into {@link ParsedRow}s. Knows nothing about the database.
 *
 * <p>Written against a real export rather than a spec, because there is no spec: the file
 * that prompted this has 71 columns of which 48 are empty, six near-identical address
 * blocks, and two record types stacked in one sheet. Most of what follows is a defence
 * against something that file actually does.
 *
 * <p><b>Columns are matched by alias, not by position.</b> Google, Outlook and the various
 * phone-book apps each export "the email column" under a different heading, and a
 * positional reader breaks on the first file that adds a column. Headers are squashed to
 * letters and digits before lookup, so {@code Work Email}, {@code work_email} and
 * {@code E-mail 1 - Value} all arrive at the same field.
 */
@Component
public class ContactCsvParser {

    /**
     * Header aliases, keyed by the squashed form. Several aliases mapping to one field is
     * normal and wanted — an export commonly fills both {@code Email} and {@code Work
     * Email} with the same address, and both are read into the same de-duplicated set.
     */
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("type", "type"),
            Map.entry("name", "name"),
            Map.entry("fullname", "name"),
            Map.entry("displayname", "name"),
            Map.entry("firstname", "firstName"),
            Map.entry("givenname", "firstName"),
            Map.entry("lastname", "lastName"),
            Map.entry("familyname", "lastName"),
            Map.entry("surname", "lastName"),
            Map.entry("title", "title"),
            Map.entry("nameprefix", "title"),
            Map.entry("jobtitle", "jobTitle"),
            Map.entry("position", "jobTitle"),
            Map.entry("organization", "organization"),
            Map.entry("organisation", "organization"),
            Map.entry("company", "organization"),
            Map.entry("organizationname", "organization"),
            Map.entry("tags", "tags"),
            Map.entry("labels", "tags"),
            Map.entry("groups", "tags"),
            Map.entry("notes", "notes"),
            Map.entry("note", "notes"));

    /**
     * Columns whose value is an email address, whatever the heading calls it. Read in this
     * order, and the first sighting of a value wins the de-duplication — which is why the
     * plain {@code Email} column leads.
     */
    private static final List<String> EMAIL_HEADERS = List.of(
            "email", "emailaddress", "primaryemail",
            "email1value", "email2value", "email3value",
            "emailaddress1", "emailaddress2", "emailaddress3",
            "workemail", "businessemail", "homeemail", "otheremail");

    /**
     * Phone columns, paired with the label to apply to whatever sits in them. The general
     * {@code Phone Number} column carries its own labels inline, so it contributes none of
     * its own and is mapped to the empty string.
     */
    private static final Map<String, String> PHONE_HEADERS = Map.ofEntries(
            Map.entry("phonenumber", ""),
            Map.entry("phone", ""),
            Map.entry("workphone", "Work"),
            Map.entry("businessphone", "Work"),
            Map.entry("officephone", "Work"),
            Map.entry("mainphone", "Work"),
            Map.entry("homephone", "Home"),
            Map.entry("mobilephone", "Mobile"),
            Map.entry("cellphone", "Mobile"),
            Map.entry("faxphone", "Fax"),
            Map.entry("workfax", "Fax"),
            Map.entry("homefax", "Fax"),
            Map.entry("directphone", "Direct"),
            Map.entry("otherphone", "Other"));

    /**
     * Address block prefixes, in the order they are trusted.
     *
     * <p>The export writes an address into up to six blocks — plain, Home, Postal, Office,
     * Billing, Shipping, Mailing — and fills whichever ones its own source happened to
     * populate. In the file that prompted this, one person's city sits only in {@code Office
     * City} and another's only in {@code Mailing City}. Reading one block and calling it the
     * address loses both; reading them all and concatenating invents an address nobody
     * wrote. So the first block with anything in it wins, in an order that puts the business
     * blocks ahead of the private ones.
     */
    private static final List<String> ADDRESS_PREFIXES =
            List.of("", "office", "organization", "postal", "mailing", "billing", "shipping", "home");

    /**
     * Words that name a kind of phone line rather than being one. Used to split a cell like
     * {@code Work,+32.3.821.13.35,Mobile,+32.475.89.02.67} back into labelled numbers.
     */
    private static final Map<String, String> PHONE_LABELS = Map.ofEntries(
            Map.entry("work", "Work"),
            Map.entry("business", "Work"),
            Map.entry("office", "Work"),
            Map.entry("main", "Work"),
            Map.entry("mobile", "Mobile"),
            Map.entry("cell", "Mobile"),
            Map.entry("cellular", "Mobile"),
            Map.entry("whatsapp", "Mobile"),
            Map.entry("home", "Home"),
            Map.entry("direct", "Direct"),
            Map.entry("fax", "Fax"),
            Map.entry("workfax", "Fax"),
            Map.entry("homefax", "Fax"),
            Map.entry("pager", "Other"),
            Map.entry("other", "Other"));

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /**
     * A token is a phone number if it holds at least four digits. Deliberately loose about
     * everything else: this data writes numbers as {@code +32.3.821.13.35}, {@code 605 75 98
     * 82} and {@code +90 216 532 30 00-01}, and any pattern strict enough to call one of
     * those malformed would reject the other two.
     */
    private static final Pattern HAS_ENOUGH_DIGITS = Pattern.compile("(\\D*\\d){4,}");

    /** Everything the parser produced, plus what it could not make sense of. */
    public record ParseResult(List<ParsedRow> rows, List<String> fileWarnings) {
    }

    public ParseResult parse(InputStream in) throws IOException {
        List<ParsedRow> rows = new ArrayList<>();
        List<String> fileWarnings = new ArrayList<>();

        // A BOM in front of the first heading would stop it matching any alias, and the file
        // would import as rows with no names in them. Strip it before the header is read.
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != 0xFEFF) {
            reader.reset();
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .setTrim(true)
                .build();

        try (CSVParser parser = CSVParser.parse(reader, format)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            Set<String> squashed = new LinkedHashSet<>();
            for (String header : headers.keySet()) {
                squashed.add(squash(header));
            }
            if (!squashed.contains("name") && !squashed.contains("firstname")
                    && !squashed.contains("email") && !squashed.contains("organization")) {
                fileWarnings.add("No Name, First Name, Email or Organization column was found — "
                        + "check that the first line of the file is a header row.");
            }
            for (CSVRecord record : parser) {
                ParsedRow row = readRow(record, headers);
                if (row != null) {
                    rows.add(row);
                }
            }
        }

        if (rows.isEmpty() && fileWarnings.isEmpty()) {
            fileWarnings.add("Nothing importable was found in the file.");
        }
        return new ParseResult(rows, fileWarnings);
    }

    private ParsedRow readRow(CSVRecord record, Map<String, Integer> headers) {
        ParsedRow row = new ParsedRow();
        row.lineNumber = (int) record.getRecordNumber() + 1;

        String type = nullToEmpty(get(record, headers, "type"));
        String name = get(record, headers, "name");
        String first = get(record, headers, "firstName");
        String last = get(record, headers, "lastName");
        String org = get(record, headers, "organization");

        if (isBlank(name)) {
            name = joinName(first, last);
        }

        // Two record types in one sheet: the file lists its organisations first, then the
        // people, and says which is which in a Type column. The second half of this test is
        // for the many exports that have no such column — a row with an organisation name,
        // no first or last name, and a Name identical to the organisation is that
        // organisation's own row rather than somebody who happens to work there.
        boolean typeSaysOrganisation = type.equalsIgnoreCase("organization")
                || type.equalsIgnoreCase("organisation")
                || type.equalsIgnoreCase("company");
        row.organisation = typeSaysOrganisation
                || (type.isEmpty() && isBlank(first) && isBlank(last)
                    && !isBlank(org) && org.equalsIgnoreCase(nullToEmpty(name)));

        row.organisationName = !isBlank(org) ? org : (row.organisation ? name : null);
        if (!row.organisation) {
            row.fullName = name;
            row.firstName = first;
            row.title = get(record, headers, "title");
            row.jobTitle = get(record, headers, "jobTitle");
        }

        // An export's trailing blank line, or a row whose every useful column was empty:
        // nothing to file, and nowhere to file it.
        if (isBlank(name) && isBlank(row.organisationName)) {
            return null;
        }

        readAddress(record, headers, row);
        readWebsite(record, headers, row);
        readEmails(record, headers, row);
        readPhones(record, headers, row);

        row.tags = get(record, headers, "tags");
        String notes = get(record, headers, "notes");
        if (!isBlank(notes)) {
            row.tags = isBlank(row.tags) ? notes : row.tags + "; " + notes;
        }

        if (!row.organisation && isBlank(row.organisationName)) {
            row.warnings.add("No company named on this row.");
        }
        if (row.contacts.isEmpty() && !row.organisation) {
            row.warnings.add("No email address or phone number on this row.");
        }
        return row;
    }

    /** First address block with anything in it — see {@link #ADDRESS_PREFIXES}. */
    private void readAddress(CSVRecord record, Map<String, Integer> headers, ParsedRow row) {
        for (String prefix : ADDRESS_PREFIXES) {
            String city = raw(record, headers, prefix + "city");
            String country = raw(record, headers, prefix + "country");
            if (!isBlank(city) || !isBlank(country)) {
                row.cityName = trimToNull(city);
                row.country = trimToNull(country);
                return;
            }
        }
    }

    /**
     * The website column, which is not reliably a website.
     *
     * <p>One row of the file that prompted this holds
     * {@code info@caspianlines.com/www.caspianlines.com} — an address and a host typed into
     * one cell with a slash between them. Splitting on the separators and sorting the pieces
     * by what each looks like recovers both. Taking the cell at face value would store a
     * "website" no link can resolve, and would lose the only email address on that company.
     */
    private void readWebsite(CSVRecord record, Map<String, Integer> headers, ParsedRow row) {
        for (String header : List.of("website", "workwebsite", "homewebsite", "url", "webpage")) {
            String value = raw(record, headers, header);
            if (isBlank(value)) continue;
            for (String part : value.split("[\\s,;|]+")) {
                for (String piece : splitHostsAndEmails(part)) {
                    if (EMAIL.matcher(piece).matches()) {
                        addContact(row, "email", piece.toLowerCase(Locale.ROOT), null);
                    } else if (row.website == null && piece.contains(".")) {
                        row.website = piece;
                    }
                }
            }
            if (row.website != null) return;
        }
    }

    /**
     * Splits {@code info@caspianlines.com/www.caspianlines.com} into its two halves without
     * mangling a legitimate URL path: a slash separates only when what follows it looks like
     * a host or an address in its own right, so {@code fednav.com/about} stays whole.
     */
    static List<String> splitHostsAndEmails(String part) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : stripScheme(part).split("/")) {
            if (segment.isEmpty()) continue;
            boolean startsFresh = EMAIL.matcher(segment).matches()
                    || segment.toLowerCase(Locale.ROOT).startsWith("www.");
            if (startsFresh && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('/');
            current.append(segment);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private void readEmails(CSVRecord record, Map<String, Integer> headers, ParsedRow row) {
        for (String header : EMAIL_HEADERS) {
            String value = raw(record, headers, header);
            if (isBlank(value)) continue;
            // One cell can hold several, separated by whatever the exporter felt like.
            for (String candidate : value.split("[\\s,;|]+")) {
                String cleaned = candidate.trim().replaceAll("^<|>$", "");
                if (cleaned.isEmpty()) continue;
                if (EMAIL.matcher(cleaned).matches()) {
                    // Lowercased on the way in. The local part of an address is technically
                    // case-sensitive and in practice never is, and storing two spellings of
                    // one mailbox is how the same person gets a circular twice.
                    addContact(row, "email", cleaned.toLowerCase(Locale.ROOT), null);
                } else {
                    row.warnings.add("Not an email address, skipped: " + cleaned);
                }
            }
        }
    }

    private void readPhones(CSVRecord record, Map<String, Integer> headers, ParsedRow row) {
        for (Map.Entry<String, String> entry : PHONE_HEADERS.entrySet()) {
            String value = raw(record, headers, entry.getKey());
            if (isBlank(value)) continue;
            for (ParsedRow.ParsedContact phone : splitLabelledPhones(value, entry.getValue())) {
                addContact(row, "phone", phone.value(), phone.label());
            }
        }
    }

    /**
     * Reads a cell like {@code Work,+32.3.821.13.35,Mobile,+32.475.89.02.67}.
     *
     * <p>The exporter joins a label and the numbers it applies to into one cell, and a label
     * governs every number after it until the next label appears. The count is not fixed:
     * {@code Work,605 75 98 82,943 371 849} is one label and two numbers, and
     * {@code Work,+90 216 532 30 00-01,+90 532 267 23 97,+90 216 532 00 02} is one label and
     * three. Pairing the tokens off two at a time — the obvious reading — gets both wrong,
     * and gets them wrong silently, by storing a phone number called "Mobile".
     *
     * <p>Tokens with fewer than four digits are dropped rather than kept. What they are is
     * the tail of a number written as a range, {@code …30 00-01}; what they are not is
     * anything anybody can ring, and storing one would put a row in the contacts table that
     * no circular, no WhatsApp check and no human could use.
     *
     * @param fallbackLabel the label the column itself implies, for a dedicated Mobile Phone
     *                      or Fax Phone column whose cell carries no label of its own
     */
    static List<ParsedRow.ParsedContact> splitLabelledPhones(String cell, String fallbackLabel) {
        List<ParsedRow.ParsedContact> out = new ArrayList<>();
        String current = trimToNull(fallbackLabel);
        for (String token : cell.split(",")) {
            String piece = token.trim();
            if (piece.isEmpty()) continue;
            String asLabel = PHONE_LABELS.get(squash(piece));
            if (asLabel != null) {
                current = asLabel;
                continue;
            }
            if (!HAS_ENOUGH_DIGITS.matcher(piece).find()) continue;
            out.add(new ParsedRow.ParsedContact("phone", piece, current));
        }
        return out;
    }

    /** Adds a contact unless the same value is already on the row, case-insensitively. */
    private static void addContact(ParsedRow row, String kind, String value, String label) {
        String cleaned = value.trim();
        if (cleaned.isEmpty()) return;
        boolean seen = row.contacts.stream()
                .anyMatch(c -> c.kind().equals(kind) && c.value().equalsIgnoreCase(cleaned));
        if (seen) return;
        row.contacts.add(new ParsedRow.ParsedContact(kind, cleaned, trimToNull(label)));
    }

    // ---- column access -------------------------------------------------------------

    /** Reads by canonical field name, resolving whichever alias this file happens to use. */
    private static String get(CSVRecord record, Map<String, Integer> headers, String field) {
        for (Map.Entry<String, Integer> entry : headers.entrySet()) {
            if (field.equals(FIELDS.get(squash(entry.getKey())))) {
                String value = valueAt(record, entry.getValue());
                if (!isBlank(value)) return value.trim();
            }
        }
        return null;
    }

    /** Reads by squashed heading, for the column families matched by name rather than alias. */
    private static String raw(CSVRecord record, Map<String, Integer> headers, String squashedHeader) {
        for (Map.Entry<String, Integer> entry : headers.entrySet()) {
            if (squash(entry.getKey()).equals(squashedHeader)) {
                String value = valueAt(record, entry.getValue());
                if (!isBlank(value)) return value.trim();
            }
        }
        return null;
    }

    /**
     * A short row is a fact of life in exported CSV — the trailing columns are absent rather
     * than present and empty — and {@code CSVRecord.get(int)} throws on one.
     */
    private static String valueAt(CSVRecord record, int index) {
        return index < record.size() ? record.get(index) : null;
    }

    /** Heading to comparison key: letters and digits only, lowercased. */
    static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String joinName(String first, String last) {
        String joined = (nullToEmpty(first) + " " + nullToEmpty(last)).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static String stripScheme(String s) {
        return s.replaceFirst("(?i)^https?://", "").replaceFirst("/+$", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
