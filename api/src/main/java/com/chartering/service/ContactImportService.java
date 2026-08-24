package com.chartering.service;

import com.chartering.audit.ChangeContext;
import com.chartering.dto.ContactImportPreview;
import com.chartering.dto.ContactImportPreview.ImportCompany;
import com.chartering.dto.ContactImportPreview.ImportContact;
import com.chartering.dto.ContactImportPreview.ImportCounts;
import com.chartering.dto.ContactImportPreview.ImportPerson;
import com.chartering.dto.ContactImportRequest;
import com.chartering.dto.ContactImportRequest.ImportCompanyRequest;
import com.chartering.dto.ContactImportRequest.ImportContactRequest;
import com.chartering.dto.ContactImportRequest.ImportPersonRequest;
import com.chartering.dto.ContactImportResult;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Person;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.PersonRepository;
import com.chartering.service.imports.ContactCsvParser;
import com.chartering.service.imports.ParsedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Importing a contacts file, in two halves that never run together.
 *
 * <p><b>Preview</b> parses the file, works out what each row would become, and hands the
 * whole thing back without writing anything. <b>Commit</b> takes that answer as the user
 * edited it and writes it. Splitting them is the whole point: this data cannot be trusted
 * to land unattended. The export that prompted this feature had a company whose name was a
 * marketing tagline, a website column holding an email address, and one mailbox claimed by
 * two different people — none of it malformed enough for a parser to reject, all of it
 * wrong enough to matter once it is in the database.
 *
 * <p>Nothing is stored between the two calls. See {@link ContactImportPreview} for why
 * there is no staging table.
 */
@Service
@RequiredArgsConstructor
public class ContactImportService {

    private final ContactCsvParser parser;
    private final CompanyRepository companyRepository;
    private final PersonRepository personRepository;
    private final ContactRepository contactRepository;

    /**
     * Legal-form suffixes, stripped only when looking for a <em>similar</em> company.
     *
     * <p>Never used to decide an exact match. "Fednav Ltd" and "Fednav" are probably the
     * same firm and the user should be shown that; deciding it for them is how an import
     * quietly merges two companies that a broker keeps apart on purpose.
     */
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "ltd", "limited", "llc", "lc", "inc", "incorporated", "corp", "corporation",
            "co", "company", "gmbh", "ag", "sa", "sas", "srl", "spa", "bv", "nv", "as",
            "asa", "oy", "ab", "aps", "plc", "pte", "pty", "kg", "sarl", "sl", "sti",
            "ltdsti", "lp", "llp", "group", "holding", "holdings", "shipping");

    // ---- preview ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ContactImportPreview preview(MultipartFile file) {
        ContactCsvParser.ParseResult parsed;
        try (InputStream in = file.getInputStream()) {
            parsed = parser.parse(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the file: " + e.getMessage(), e);
        }

        // Rows in file order, grouped by the company they name. A LinkedHashMap so the
        // review screen lists companies in the order the file introduced them, which is the
        // order the user will scroll the file in if they go looking.
        Map<String, Group> groups = new LinkedHashMap<>();
        List<String> fileWarnings = new ArrayList<>(parsed.fileWarnings());

        for (ParsedRow row : parsed.rows()) {
            if (isBlank(row.organisationName)) {
                fileWarnings.add("Line " + row.lineNumber + ": no company named, row skipped"
                        + (isBlank(row.fullName) ? "" : " (" + row.fullName + ")"));
                continue;
            }
            Group group = groups.computeIfAbsent(key(row.organisationName), k -> new Group());
            if (row.organisation) {
                group.organisationRow = row;
            } else {
                group.peopleRows.add(row);
            }
            group.sourceName = row.organisationName;
        }

        Map<String, Company> byExactName = loadCompaniesByName(groups);
        List<Company> allCompanies = groups.isEmpty() ? List.of() : companyRepository.findAll();
        Map<String, Company> bySimilarName = new HashMap<>();
        for (Company c : allCompanies) {
            bySimilarName.putIfAbsent(similarityKey(c.getName()), c);
        }

        // Match every company first, so the person and duplicate lookups can be done for the
        // whole file in one query each rather than per row.
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            Company exact = byExactName.get(entry.getKey());
            if (exact != null) {
                group.matched = exact;
                group.matchType = "exact";
            } else {
                Company similar = bySimilarName.get(similarityKey(group.sourceName));
                if (similar != null) {
                    group.matched = similar;
                    group.matchType = "similar";
                } else {
                    group.matchType = "new";
                }
            }
        }

        List<Long> matchedIds = groups.values().stream()
                .filter(g -> g.matched != null)
                .map(g -> g.matched.getId())
                .toList();

        Map<Long, Map<String, Person>> peopleByCompany = new HashMap<>();
        Map<Long, Set<String>> valuesByCompany = new HashMap<>();
        if (!matchedIds.isEmpty()) {
            for (Person p : personRepository.findByCompanyIds(matchedIds)) {
                peopleByCompany
                        .computeIfAbsent(p.getCompany().getId(), k -> new HashMap<>())
                        .putIfAbsent(key(p.getFullName()), p);
            }
            for (Contact c : contactRepository.findByCompanyIds(matchedIds)) {
                valuesByCompany
                        .computeIfAbsent(c.getCompany().getId(), k -> new HashSet<>())
                        .add(key(c.getContactValue()));
            }
        }

        List<ImportCompany> companies = new ArrayList<>();
        List<ImportPerson> people = new ArrayList<>();
        Counter counter = new Counter();

        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            String companyKey = entry.getKey();
            Group group = entry.getValue();
            Long companyId = group.matched != null ? group.matched.getId() : null;
            Set<String> onFile = companyId != null
                    ? valuesByCompany.getOrDefault(companyId, Set.of())
                    : Set.of();
            Map<String, Person> known = companyId != null
                    ? peopleByCompany.getOrDefault(companyId, Map.of())
                    : Map.of();

            Set<String> shared = sharedEmails(group.peopleRows);
            List<ImportContact> companyContacts = new ArrayList<>();
            List<String> companyWarnings = new ArrayList<>(
                    group.organisationRow != null ? group.organisationRow.warnings : List.of());

            // Addresses the file put on the organisation's own row.
            if (group.organisationRow != null) {
                for (ParsedRow.ParsedContact c : group.organisationRow.contacts) {
                    companyContacts.add(toImportContact(c, onFile, counter));
                }
            }
            // ...plus the ones the file gave to more than one person there. A Contact in
            // this system is one address, not a person, and the shape for an address
            // belonging to a desk rather than to anybody is already supported and already
            // understood by RecipientSelectionService: company set, person null. Filing it
            // under whichever of the two claimants happened to be listed first would make
            // the choice an accident of row order; duplicating it would mail the desk twice.
            for (String value : shared) {
                if (companyContacts.stream().anyMatch(c -> c.value().equalsIgnoreCase(value))) {
                    continue;
                }
                ImportContact contact = toImportContact(
                        new ParsedRow.ParsedContact("email", value, null), onFile, counter);
                companyContacts.add(new ImportContact(
                        contact.kind(), contact.value(), contact.label(), contact.duplicate(),
                        "Listed against more than one person in the file — imported as a "
                                + "company-wide address so it is only mailed once."));
                counter.warnings++;
            }

            ParsedRow source = group.organisationRow != null
                    ? group.organisationRow
                    : group.peopleRows.isEmpty() ? null : group.peopleRows.get(0);

            if ("similar".equals(group.matchType)) {
                companyWarnings.add("Close but not identical to \"" + group.matched.getName()
                        + "\" already on file. Check this is the same company before importing.");
                counter.warnings++;
            }
            if (looksLikeATagline(group.sourceName)) {
                companyWarnings.add("This reads as a slogan rather than a company name. "
                        + "Worth correcting before importing — every person below will be "
                        + "filed under whatever it says here.");
                counter.warnings++;
            }

            if (group.matched != null) counter.companiesMatched++;
            else counter.companiesNew++;

            companies.add(new ImportCompany(
                    companyKey,
                    group.sourceName,
                    group.sourceName,
                    companyId,
                    group.matched != null ? group.matched.getName() : null,
                    group.matchType,
                    // City, country and website are commonly only on the people's rows —
                    // the organisation row in this export carries a name and nothing else —
                    // so take the first of either that has one.
                    firstNonBlank(group, r -> r.cityName),
                    firstNonBlank(group, r -> r.country),
                    firstNonBlank(group, r -> r.website),
                    source != null ? notesFrom(source) : null,
                    companyContacts,
                    companyWarnings));

            for (ParsedRow row : group.peopleRows) {
                Person existing = known.get(key(row.fullName));
                if (existing != null) counter.peopleMatched++;
                else counter.peopleNew++;

                List<ImportContact> contacts = new ArrayList<>();
                for (ParsedRow.ParsedContact c : row.contacts) {
                    // Already hoisted to the company above — leaving a copy here as well
                    // would put the same address on the desk and on the person.
                    if ("email".equals(c.kind()) && shared.contains(key(c.value()))) continue;
                    contacts.add(toImportContact(c, onFile, counter));
                }

                List<String> warnings = new ArrayList<>(row.warnings);
                counter.warnings += row.warnings.size();

                people.add(new ImportPerson(
                        "p" + row.lineNumber,
                        companyKey,
                        row.fullName,
                        row.fullName,
                        row.title,
                        row.jobTitle,
                        // The greeting a circular opens with. Seeded from the First Name
                        // column, which is the closest thing an export has to one; a person
                        // whose file gave only a full name gets none rather than a guess
                        // split off the front of it, since "Van der Berg" is not a surname
                        // you can find by taking the first word.
                        row.firstName,
                        existing != null ? existing.getId() : null,
                        existing != null ? "exact" : "new",
                        notesFrom(row),
                        contacts,
                        warnings));
            }
        }

        return new ContactImportPreview(
                file.getOriginalFilename(),
                companies,
                people,
                fileWarnings,
                new ImportCounts(
                        counter.companiesNew, counter.companiesMatched,
                        counter.peopleNew, counter.peopleMatched,
                        counter.emails, counter.phones,
                        counter.duplicates, counter.warnings));
    }

    // ---- commit -------------------------------------------------------------------

    /**
     * Writes the reviewed preview. One transaction for the whole file: a half-applied
     * import is worse than none, because the second attempt would then have to be reviewed
     * against a database that already holds an unknown part of the first.
     */
    @Transactional
    public ContactImportResult commit(ContactImportRequest request) {
        List<ImportCompanyRequest> companyRequests =
                request.companies() == null ? List.of() : request.companies();
        List<ImportPersonRequest> personRequests =
                request.people() == null ? List.of() : request.people();

        // Everything this transaction writes shares one change-set id in the change log.
        // Naming it is what turns eighty separate creates into one readable event — and
        // what makes "show me everything that import did" a single query.
        ChangeContext.describe("Contact import");

        Counter counter = new Counter();
        List<String> messages = new ArrayList<>();
        Map<String, Company> resolved = new LinkedHashMap<>();
        // Addresses already stored, per company id, filled on first use — see saveContact.
        Map<Long, Set<String>> onFile = new HashMap<>();

        for (ImportCompanyRequest req : companyRequests) {
            Company company;
            if (req.matchedId() != null) {
                company = companyRepository.findById(req.matchedId())
                        .orElseThrow(() -> new ResourceNotFoundException("Company", req.matchedId()));
                // An existing company keeps the details it already has. The file is a lead
                // sheet, not a source of record: overwriting a city somebody typed with one
                // scraped off a signature block loses the better of the two, and it does it
                // silently. Only genuinely empty fields are filled in.
                if (isBlank(company.getCityName())) company.setCityName(trimToNull(req.cityName()));
                if (isBlank(company.getCountry())) company.setCountry(trimToNull(req.country()));
                if (isBlank(company.getWebsite())) company.setWebsite(trimToNull(req.website()));
                counter.companiesMatched++;
            } else {
                company = new Company();
                company.setName(req.name().trim());
                company.setCityName(trimToNull(req.cityName()));
                company.setCountry(trimToNull(req.country()));
                company.setWebsite(trimToNull(req.website()));
                company.setNotes(trimToNull(req.notes()));
                // Imported, but not "legacy": that flag means a row carried over from the
                // old database, and the Source filter on the People tab exists to tell those
                // apart from data entered since. A contact met at a trade fair last month is
                // new data whichever door it came in through.
                company.setLegacy(false);
                counter.companiesNew++;
            }
            company = companyRepository.save(company);
            resolved.put(req.key(), company);

            for (ImportContactRequest contact : nullToEmpty(req.contacts())) {
                // Person null, company set: the chartering@ desk shape.
                if (saveContact(contact, null, company, counter, onFile)) {
                    messages.add("Added " + contact.value() + " to " + company.getName());
                }
            }
        }

        for (ImportPersonRequest req : personRequests) {
            Company company = resolved.get(req.companyKey());
            if (company == null) {
                // The client sent a person pointing at a company it did not send. Refused
                // rather than imported company-less: a person under no company is a row the
                // People tab groups under nothing and the company drawer never lists.
                throw new IllegalArgumentException(
                        "Person \"" + req.fullName() + "\" names company \"" + req.companyKey()
                                + "\", which is not in this import.");
            }

            Person person;
            if (req.matchedId() != null) {
                person = personRepository.findById(req.matchedId())
                        .orElseThrow(() -> new ResourceNotFoundException("Person", req.matchedId()));
                // Same rule as for a matched company: fill the gaps, overwrite nothing.
                if (isBlank(person.getJobTitle())) person.setJobTitle(trimToNull(req.jobTitle()));
                if (isBlank(person.getGreetingName())) person.setGreetingName(trimToNull(req.greetingName()));
                if (isBlank(person.getTitle())) person.setTitle(trimToNull(req.title()));
                counter.peopleMatched++;
            } else {
                person = new Person();
                person.setFullName(req.fullName().trim());
                person.setTitle(trimToNull(req.title()));
                person.setJobTitle(trimToNull(req.jobTitle()));
                person.setGreetingName(trimToNull(req.greetingName()));
                person.setNotes(trimToNull(req.notes()));
                person.setCompany(company);
                person.setLegacy(false);
                counter.peopleNew++;
            }
            person = personRepository.save(person);

            for (ImportContactRequest contact : nullToEmpty(req.contacts())) {
                if (saveContact(contact, person, company, counter, onFile)) {
                    messages.add("Added " + contact.value() + " to " + person.getFullName());
                }
            }
        }

        return new ContactImportResult(
                counter.companiesNew, counter.companiesMatched,
                counter.peopleNew, counter.peopleMatched,
                counter.contactsCreated, counter.duplicates,
                messages);
    }

    /**
     * Stores one address, unless the company already has it.
     *
     * <p>The duplicate check runs here as well as in the preview, and not out of caution:
     * the preview was taken against the database as it stood when the file was uploaded, and
     * nothing stops the same file being imported twice, or two files overlapping. The
     * cheaper alternative — trusting the {@code duplicate} flag the client sends back —
     * would mean the database's idea of what it already holds arriving by way of a browser.
     *
     * @return true if a row was written
     */
    private boolean saveContact(ImportContactRequest req, Person person, Company company,
                                Counter counter, Map<Long, Set<String>> onFile) {
        String value = trimToNull(req.value());
        if (value == null) return false;
        if ("email".equals(req.kind())) {
            value = value.toLowerCase(Locale.ROOT);
        }

        // Loaded once per company and then kept current as rows are written, rather than
        // re-queried per address. Both halves matter: a query per address would be one round
        // trip per line of the file, and the set has to grow as we go, or a file listing the
        // same address against two rows would store it twice.
        Set<String> stored = onFile.computeIfAbsent(company.getId(), id -> {
            Set<String> values = new HashSet<>();
            for (Contact c : contactRepository.findByCompanyIds(List.of(id))) {
                values.add(key(c.getContactValue()));
            }
            return values;
        });
        if (!stored.add(key(value))) {
            counter.duplicates++;
            return false;
        }

        Contact contact = new Contact();
        contact.setPerson(person);
        contact.setCompany(company);
        contact.setContactKind(req.kind());
        contact.setContactValue(value);
        // Phones only — an email has no such thing, and the parser never sets one on an
        // email, but a hand-edited preview could.
        contact.setLabel("phone".equals(req.kind()) ? trimToNull(req.label()) : null);
        contact.setLegacy(false);
        // Every flag left at its default. An imported address is unconfirmed, not main, not
        // flagged for circulation: nobody has yet checked that it works, and a file of
        // eighty addresses that arrived pre-flagged for circulation is one send away from a
        // bounce storm. The flags are what the user sets once they have looked at the row.
        contactRepository.save(contact);
        counter.contactsCreated++;
        return true;
    }

    // ---- matching helpers ----------------------------------------------------------

    private Map<String, Company> loadCompaniesByName(Map<String, Group> groups) {
        if (groups.isEmpty()) return Map.of();
        Map<String, Company> out = new HashMap<>();
        for (Company c : companyRepository.findByLowercaseNames(groups.keySet())) {
            out.putIfAbsent(key(c.getName()), c);
        }
        return out;
    }

    /**
     * Addresses this file gives to more than one person at the same company.
     *
     * <p>The shape is common and not a mistake — {@code falline.commercial@fednav.com}
     * appears against two managers in the file that prompted this, because it is the desk
     * they both read. What it is not is either of their personal addresses.
     */
    private static Set<String> sharedEmails(List<ParsedRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (ParsedRow row : rows) {
            // Per row, not per occurrence: one person listing the same address in both the
            // Email and Work Email columns is one claim on it, not two.
            Set<String> seen = new HashSet<>();
            for (ParsedRow.ParsedContact c : row.contacts) {
                if (!"email".equals(c.kind())) continue;
                if (seen.add(key(c.value()))) {
                    counts.merge(key(c.value()), 1, Integer::sum);
                }
            }
        }
        Set<String> shared = new HashSet<>();
        counts.forEach((value, count) -> {
            if (count > 1) shared.add(value);
        });
        return shared;
    }

    private ImportContact toImportContact(ParsedRow.ParsedContact c, Set<String> onFile, Counter counter) {
        boolean duplicate = onFile.contains(key(c.value()));
        if (duplicate) counter.duplicates++;
        else if ("email".equals(c.kind())) counter.emails++;
        else counter.phones++;
        return new ImportContact(
                c.kind(), c.value(), c.label(), duplicate,
                duplicate ? "Already on file for this company." : null);
    }

    /**
     * A "company name" that reads as a sentence — a strapline scraped in place of the name.
     *
     * <p>The test is capitalisation, not length. "Soluciones tecnológicas que refuerzan tu
     * logística" is fifty characters, so any length threshold generous enough to let
     * "CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti." through lets the slogan through too.
     * What separates them is that a name title-cases its words and a sentence does not: five
     * of the slogan's six words start lower-case, and none of the Turkish name's do.
     *
     * <p>Only ever produces a warning. A real name can be written however its owner writes
     * it, so this flags the row for a human and does nothing else.
     */
    static boolean looksLikeATagline(String name) {
        if (name == null) return false;
        String[] words = name.trim().split("\\s+");
        if (words.length < 4) return false;
        long lowercase = Arrays.stream(words).skip(1)
                .filter(w -> !w.isEmpty() && Character.isLowerCase(w.charAt(0)))
                .count();
        return lowercase >= 3 || name.length() > 80;
    }

    /** Comparison key for a name or an address: trimmed, lowercased, spaces collapsed. */
    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Comparison key that also drops punctuation and legal-form suffixes, so "Fednav Ltd."
     * and "FEDNAV" collide. Only ever used to <em>suggest</em> a match — see
     * {@link #LEGAL_SUFFIXES}.
     */
    private static String similarityKey(String s) {
        if (s == null) return "";
        String[] words = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty() || LEGAL_SUFFIXES.contains(word)) continue;
            out.append(word);
        }
        return out.toString();
    }

    /**
     * Notes for a row: the file's own notes with its tags folded in.
     *
     * <p>Tags land in notes rather than in a table of their own. The value in this file is
     * {@code BBEU26} — a trade fair, and the answer to "where did this address come from",
     * which is exactly the sort of thing the notes field is already carrying for every other
     * record in the database.
     */
    private static String notesFrom(ParsedRow row) {
        return isBlank(row.tags) ? null : "Imported — tags: " + row.tags.trim();
    }

    private static String firstNonBlank(Group group, Function<ParsedRow, String> field) {
        if (group.organisationRow != null) {
            String value = field.apply(group.organisationRow);
            if (!isBlank(value)) return value;
        }
        for (ParsedRow row : group.peopleRows) {
            String value = field.apply(row);
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** One company's rows, before anything has been looked up. */
    private static final class Group {
        String sourceName;
        ParsedRow organisationRow;
        final List<ParsedRow> peopleRows = new ArrayList<>();
        Company matched;
        String matchType = "new";
    }

    /** Running totals, threaded through both halves so the two report the same way. */
    private static final class Counter {
        int companiesNew;
        int companiesMatched;
        int peopleNew;
        int peopleMatched;
        int emails;
        int phones;
        int duplicates;
        int contactsCreated;
        int warnings;
    }
}
