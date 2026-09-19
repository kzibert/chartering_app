package com.chartering.service.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A company's "full style" read out of pasted text: the block a broker signs with, or a
 * business card typed out, or the header of a firm's introduction.
 *
 * <p><b>Not the model, and deliberately.</b> The finetuned parser reads cargoes and positions
 * and knows a sender only as company, person and email — it was never trained on addresses,
 * phones or websites, and asking it for them would get fluent guesses. What a signature holds
 * is mostly <em>shaped</em> text instead: an address has an {@code @}, a phone a run of digits
 * behind a label, a website a {@code www.}. Those are read exactly, and the soft parts — which
 * line is the firm's name, which is a person — are read loosely, because the review screen
 * puts the original beside every one of them and a person corrects what is wrong.
 *
 * <p><b>A phone needs a reason to be a phone.</b> A circular is full of seven-digit runs that
 * are not numbers anybody answers — an IMO, a date written 01.09.2026, a reference. So a run
 * of digits is taken only behind a label (Tel, Mob, Fax…) or when it is written the way only
 * phone numbers are written, with a leading {@code +} or {@code 00}. A label governs every
 * number after it on its line until the next label — the importer's rule, from a real export
 * that wrote a label, a number, another label and another number all in one cell.
 *
 * <p><b>Whose number it is follows what kind of number it is.</b> A mobile or a direct line
 * is a person's; the office line and the fax are the firm's, even printed inside one person's
 * signature — filing the switchboard under whoever signed would make it vanish from the
 * company the day they leave.
 *
 * <p>Pure: no database, no Spring. What it reads is matched against the companies table
 * separately, by {@link CompanyMatcher}.
 */
public final class CompanyStyleReader {

    public record Style(String name,
                        String website,
                        String city,
                        String country,
                        /** The line(s) that looked like an address, kept whole for the notes. */
                        String address,
                        List<Person> people,
                        List<ContactLine> contacts) {

        public boolean isEmpty() {
            return name == null && website == null && contacts.isEmpty() && people.isEmpty();
        }
    }

    public record Person(String fullName, String title, String jobTitle) {
    }

    /**
     * @param kind       {@code email} or {@code phone}, the two values {@code contacts} holds
     * @param label      Work / Mobile / Fax / Direct — phones only, as the contact form has it
     * @param personName the person this line belongs to, when it belongs to one
     */
    public record ContactLine(String kind, String value, String label, String personName) {
    }

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /**
     * A web address the text actually writes: {@code www.} or a scheme, or a bare host behind a
     * label ("Web: example.com"). Never an email's domain — see {@link #readWebsite}.
     */
    private static final Pattern WEBSITE = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})"
                    + "|(?i)https?://([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})"
                    + "|(?i)\\b(?:web\\s*site|website|web\\s*page|webpage|web|url|homepage|home\\s*page|site)"
                    + "\\s*[:\\-–]\\s*(?:https?://)?(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})\\b(?![@.\\w])");

    /**
     * A label or a number, in the order they appear on the line. The label list is what
     * signatures in this mailbox actually write; the single letters only count when followed
     * by a colon or a dot, or "M" in a line of prose would make every number a mobile.
     */
    private static final Pattern PHONE_TOKEN = Pattern.compile(
            "(?i)(?<label>\\b(?:tel|tél|phone|ph|office|off|work|mob|mobile|cell|gsm|whatsapp|wa|"
                    + "fax|direct|dir|dl)\\b\\.?|\\b[tmfd]\\s*[:.])"
                    + "|(?<number>(?:\\+|00)?\\(?\\d[\\d\\s().\\-/]{5,}\\d)");

    private static final Pattern JOB_WORDS = Pattern.compile(
            "(?i)\\b(manager|director|broker|chartering|operations|operator|ceo|coo|cfo|md|"
                    + "managing|head|executive|assistant|coordinator|agent|superintendent|"
                    + "owner|partner|founder|president|chairman|officer|sales|commercial|"
                    + "department|dept|desk)\\b");

    private static final Pattern FIRM_WORDS = Pattern.compile(
            "(?i)(\\b(ltd|limited|llc|inc|corp|corporation|gmbh|s\\.?a\\.?|srl|s\\.r\\.l\\.?|spa|"
                    + "b\\.?v\\.?|a\\.?s\\.?|plc|pte|pty|co\\.|shipping|maritime|marine|chartering|"
                    + "denizcilik|navigation|lines|logistics|trading|agency|shipmanagement|"
                    + "carriers|bulk|freight|tic\\.?|sti\\.?|d\\.o\\.o\\.?|oy|aps|ab)\\b"
                    + "|\\b[ak]/s\\b)");

    /** "Linkedin: North Sea Example Chartering A/S" — the firm is what follows the label. */
    private static final Pattern FIRM_LABEL = Pattern.compile(
            "(?i)^(linkedin|company|firm|company name|name|from|registered as)\\s*[:\\-–]\\s*");

    private static final Pattern TITLE = Pattern.compile("(?i)^(capt\\.?|captain|mr\\.?|mrs\\.?|ms\\.?|dr\\.?)\\s+");

    /** Prefixes that make a capitalised line a ship rather than a person. */
    private static final Set<String> SHIP_PREFIXES = Set.of("mv", "mt", "ms", "ss", "m/v", "m/t");

    /**
     * Countries this desk corresponds with, in the spellings a signature uses. A list rather
     * than a library: the long tail is not worth a dependency, and a country the list misses
     * is one field a person types.
     */
    private static final Map<String, String> COUNTRIES = countries();

    private static final Set<String> WEBMAIL = Set.of(
            "gmail.com", "hotmail.com", "yahoo.com", "outlook.com", "mail.ru", "yandex.ru",
            "ukr.net", "bk.ru", "list.ru", "inbox.ru", "rambler.ru", "yandex.com", "live.com",
            "aol.com", "icloud.com", "hotmail.co.uk", "yahoo.co.uk", "gmx.de", "gmx.net",
            "t-online.de", "abv.bg", "mynet.com", "superonline.com", "i.ua", "meta.ua",
            "rediffmail.com", "yahoo.gr", "otenet.gr", "protonmail.com", "proton.me");

    private CompanyStyleReader() {
    }

    public static boolean isWebmail(String domain) {
        return domain != null && WEBMAIL.contains(domain.toLowerCase(Locale.ROOT));
    }

    private static final Set<String> PHONE_LABELS = Set.of("Work", "Mobile", "Direct", "Fax");

    /**
     * The model's reading of the signature when it gave one, this class's own reading otherwise.
     *
     * <p><b>Why the model wins where it answered.</b> Everything below reads shapes, and it is
     * blind to exactly the parts a shape cannot settle - which line is the firm and which a
     * division, whose mobile is whose, that "Chartering Dept." is a job title. A model trained
     * on the corpus's company section (the V3 parser, 2026-09-19) reads those, and it hands
     * back this record's own shape. A model trained before that section answers
     * {@code broker} and no {@code company}, and so does a signature-less email; both fall
     * through to the shape reader, which is what they got before.
     *
     * <p><b>Nothing the text does not contain is kept.</b> A contact is identity - a number
     * matched on file decides which firm a position is filed against - so a digit the model
     * invented is worse than one it missed. Every email must appear in the text (read through
     * "(@)" and "(.)" obfuscation), every phone's digits must appear in order, every name,
     * city and address must be there letter for letter once spacing and punctuation are set
     * aside. A person dropped by that test takes the ownership of their lines with them.
     */
    public static Style readWithModel(String text, Extraction extraction) {
        if (extraction != null && extraction.company() != null) {
            Style modelled = fromModel(text, extraction.company());
            if (!modelled.isEmpty()) return modelled;
        }
        return read(text, extraction == null ? null : extraction.broker());
    }

    static Style fromModel(String text, Extraction.ExtractedCompany company) {
        String letters = squeeze(text);
        String digits = text == null ? "" : text.replaceAll("\\D", "");

        List<Person> people = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (Extraction.ExtractedPerson p : company.peopleOrEmpty()) {
            String name = inText(p.fullName(), letters);
            if (name == null || !named.add(name)) continue;
            people.add(new Person(name, Extraction.text(p.title()), Extraction.text(p.jobTitle())));
        }

        List<ContactLine> contacts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Extraction.ExtractedContact c : company.contactsOrEmpty()) {
            String value = Extraction.text(c.value());
            if (value == null) continue;
            boolean email = "email".equalsIgnoreCase(Extraction.text(c.kind()));
            String key = email ? value.toLowerCase(Locale.ROOT) : value.replaceAll("\\D", "").replaceFirst("^0+", "");
            boolean present = email
                    ? key.contains("@") && letters.contains(squeeze(key))
                    : key.length() >= 7 && digits.contains(key);
            if (!present || !seen.add(key)) continue;
            String label = email ? null : PHONE_LABELS.contains(c.label()) ? c.label() : "Work";
            String owner = Extraction.text(c.personName());
            contacts.add(new ContactLine(email ? "email" : "phone", email ? key : value, label,
                    owner != null && named.contains(owner) ? owner : null));
        }

        String website = inText(company.website(), letters);
        return new Style(inText(company.name(), letters),
                website == null ? null : website.toLowerCase(Locale.ROOT),
                inText(company.city(), letters),
                inText(company.country(), letters),
                inText(company.address(), letters),
                people, contacts);
    }

    /** The value, stripped, when the text holds it once spacing and punctuation are set aside. */
    private static String inText(String value, String squeezedText) {
        String v = Extraction.text(value);
        if (v == null) return null;
        String s = squeeze(v);
        return !s.isEmpty() && squeezedText.contains(s) ? v : null;
    }

    /** Letters, digits and '@' only, lower-cased: how two spellings of one line are compared. */
    private static String squeeze(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        s.toLowerCase(Locale.ROOT).codePoints()
                .filter(cp -> Character.isLetterOrDigit(cp) || cp == '@')
                .forEach(b::appendCodePoint);
        return b.toString();
    }

    /**
     * @param text   the pasted text, whole
     * @param broker the model's reading of who signed it, when the model ran; its company and
     *               person win over the loose line-reading below, which is only a fallback
     */
    public static Style read(String text, Extraction.Broker broker) {
        List<String> lines = text == null ? List.of() : text.lines().map(String::strip).toList();

        List<Person> people = readPeople(lines, broker);
        List<ContactLine> contacts = new ArrayList<>();
        contacts.addAll(readEmails(text, people));
        contacts.addAll(readPhones(lines, people));

        String website = readWebsite(text);
        String name = firmName(lines, broker);
        CountryLine where = readCountry(lines);

        return new Style(name, website,
                where == null ? null : where.city(),
                where == null ? null : where.country(),
                where == null ? null : where.address(),
                people, contacts);
    }

    // ------------------------------------------------------------------ emails and web

    private static List<ContactLine> readEmails(String text, List<Person> people) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = EMAIL.matcher(text == null ? "" : text);
        while (m.find()) {
            seen.add(m.group().toLowerCase(Locale.ROOT));
        }
        List<ContactLine> out = new ArrayList<>();
        for (String email : seen) {
            out.add(new ContactLine("email", email, null, personForEmail(email, people)));
        }
        return out;
    }

    /**
     * The person an address belongs to, read off its local part: {@code jane.doe@} is Jane
     * Doe's. A desk address — {@code chartering@}, {@code ops@} — names nobody and stays
     * company-wide, which is the supported shape for exactly those.
     */
    private static String personForEmail(String email, List<Person> people) {
        String local = email.substring(0, email.indexOf('@'));
        for (Person p : people) {
            for (String part : p.fullName().toLowerCase(Locale.ROOT).split("[\\s.\\-]+")) {
                if (part.length() >= 3 && local.contains(part)) return p.fullName();
            }
        }
        return null;
    }

    /**
     * The website, only when the text writes one.
     *
     * <p><b>An email's domain is not a website, and is not offered as one.</b> It used to be the
     * fallback, on the reasoning that a firm's mail domain usually serves its site too — but
     * "usually" filled the website box on nearly every paste with a guess that looked read, and
     * brokers mail from group domains, agency domains and hosted desks that have no site at all.
     * A website the text does not give stays empty. The mail domain still counts where it is
     * evidence rather than a guess: {@link CompanyMatcher} matches companies on it.
     *
     * <p>A host directly after an {@code @} is part of an address and is skipped, so
     * "info@www.example.com" cannot be read as a site either.
     */
    private static String readWebsite(String text) {
        String t = text == null ? "" : text;
        Matcher m = WEBSITE.matcher(t);
        while (m.find()) {
            String host = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (host == null) continue;
            if (m.start() > 0 && t.charAt(m.start() - 1) == '@') continue;
            return host.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    // ------------------------------------------------------------------ phones

    private static List<ContactLine> readPhones(List<String> lines, List<Person> people) {
        List<ContactLine> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String currentPerson = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                currentPerson = null;
                continue;
            }
            Person named = personOnLine(line, people);
            if (named != null) currentPerson = named.fullName();
            if (line.toLowerCase(Locale.ROOT).contains("imo")) continue;

            String label = null;
            Matcher m = PHONE_TOKEN.matcher(line);
            while (m.find()) {
                if (m.group("label") != null) {
                    label = labelFor(m.group("label"));
                    continue;
                }
                String raw = m.group("number").strip();
                String digits = raw.replaceAll("\\D", "");
                boolean shaped = raw.startsWith("+") || raw.startsWith("00");
                if (digits.length() < 7 || digits.length() > 15 || (label == null && !shaped)) continue;
                if (!seen.add(digits)) continue;
                String kind = label == null ? "Work" : label;
                out.add(new ContactLine("phone", raw, kind, personal(kind) ? currentPerson : null));
            }
        }
        // One person in the whole text: their mobile is theirs even when a blank line sits
        // between the name and the number, which is how a typed-out card is usually spaced.
        if (people.size() == 1) {
            String only = people.get(0).fullName();
            for (int i = 0; i < out.size(); i++) {
                ContactLine c = out.get(i);
                if (c.personName() == null && personal(c.label())) {
                    out.set(i, new ContactLine(c.kind(), c.value(), c.label(), only));
                }
            }
        }
        return out;
    }

    private static boolean personal(String label) {
        return "Mobile".equals(label) || "Direct".equals(label);
    }

    private static String labelFor(String token) {
        String t = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (t) {
            case "mob", "mobile", "cell", "gsm", "m", "whatsapp", "wa" -> "Mobile";
            case "fax", "f" -> "Fax";
            case "direct", "dir", "dl", "d" -> "Direct";
            default -> "Work";
        };
    }

    // ------------------------------------------------------------------ people and firm

    /**
     * People, read two ways, both the way signatures in this trade are laid out:
     *
     * <ul>
     *   <li>a short line of capitalised words sitting directly above a line of job words —
     *       "Jane Doe" over "Chartering Manager";</li>
     *   <li>a name standing alone at the head of the block, or directly above the address,
     *       phone or email lines it signs — "John Smith" over a street and a phone number,
     *       with no position given at all.</li>
     * </ul>
     *
     * The model's own reading of the signer is added when the lines produced nobody by that name.
     */
    private static List<Person> readPeople(List<String> lines, Extraction.Broker broker) {
        Map<String, Person> byName = new LinkedHashMap<>();
        int first = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isEmpty()) {
                first = i;
                break;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!looksLikeName(line)) continue;
            String next = nextNonEmpty(lines, i);
            boolean overJob = i + 1 < lines.size() && isJobLine(lines.get(i + 1));
            boolean heads = i == first || (next != null && signsFor(next));
            if (!overJob && !heads) continue;
            Person p = person(line, overJob ? lines.get(i + 1) : null);
            byName.putIfAbsent(p.fullName().toLowerCase(Locale.ROOT), p);
        }
        String signer = broker == null ? null : Extraction.text(broker.person());
        if (signer != null && looksLikeName(signer)) {
            Person p = person(signer, null);
            byName.putIfAbsent(p.fullName().toLowerCase(Locale.ROOT), p);
        }
        return new ArrayList<>(byName.values());
    }

    private static boolean isJobLine(String line) {
        return line.length() <= 60 && JOB_WORDS.matcher(line).find() && !EMAIL.matcher(line).find()
                && !FIRM_WORDS.matcher(line.replaceAll("(?i)\\bchartering\\b", "")).find();
    }

    /** A line that belongs to a signature block: an address, a phone, an email, a country. */
    private static boolean signsFor(String line) {
        if (EMAIL.matcher(line).find()) return true;
        Matcher m = PHONE_TOKEN.matcher(line);
        while (m.find()) {
            if (m.group("label") != null) return true;
        }
        String lower = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\s]", " ") + " ";
        for (String country : COUNTRIES.keySet()) {
            if (lower.contains(" " + country + " ")) return true;
        }
        return false;
    }

    private static String nextNonEmpty(List<String> lines, int from) {
        for (int j = from + 1; j < lines.size() && j <= from + 2; j++) {
            if (!lines.get(j).isEmpty()) return lines.get(j);
        }
        return null;
    }

    private static Person person(String nameLine, String jobLine) {
        String name = nameLine.replaceAll("[,;|]+$", "").strip();
        String title = null;
        Matcher t = TITLE.matcher(name);
        if (t.find()) {
            title = t.group(1).substring(0, 1).toUpperCase(Locale.ROOT)
                    + t.group(1).substring(1).toLowerCase(Locale.ROOT);
            if (!title.endsWith(".") && title.length() <= 4) title += ".";
            name = name.substring(t.end()).strip();
        }
        return new Person(name, title, jobLine == null ? null : jobLine.replaceAll("[,;|]+$", "").strip());
    }

    private static boolean looksLikeName(String line) {
        if (line == null || line.isEmpty() || line.length() > 40) return false;
        if (line.matches(".*[\\d@/:].*") || FIRM_WORDS.matcher(line).find()) return false;
        String bare = TITLE.matcher(line).replaceFirst("");
        String[] words = bare.split("\\s+");
        if (words.length < 1 || words.length > 4) return false;
        if (SHIP_PREFIXES.contains(words[0].toLowerCase(Locale.ROOT).replace(".", ""))) return false;
        String lower = bare.toLowerCase(Locale.ROOT);
        if (lower.startsWith("best") || lower.startsWith("kind") || lower.startsWith("regards")
                || lower.startsWith("thanks") || lower.startsWith("dear") || lower.startsWith("yours")
                || lower.startsWith("sincerely") || lower.startsWith("cheers")) return false;
        for (String w : words) {
            if (w.isEmpty() || !Character.isUpperCase(w.codePointAt(0))) return false;
        }
        // A single word is a name only with a title in front of it ("Capt. Ozcan").
        return words.length > 1 || TITLE.matcher(line).find();
    }

    private static Person personOnLine(String line, List<Person> people) {
        for (Person p : people) {
            if (line.contains(p.fullName())) return p;
        }
        return null;
    }

    /**
     * The firm's name: the model's reading of the signer's company when there is one, else
     * the last short line that reads like a firm — last, because the signature is at the foot
     * of the text and a cargo offer names the charterer's firm higher up. A label in front of
     * it ("Linkedin:", "Company:") is taken off.
     */
    private static String firmName(List<String> lines, Extraction.Broker broker) {
        String fromModel = broker == null ? null : Extraction.text(broker.company());
        if (fromModel != null) return fromModel;
        String found = null;
        for (String raw : lines) {
            String line = FIRM_LABEL.matcher(raw).replaceFirst("");
            if (line.isEmpty() || line.length() > 80) continue;
            if (EMAIL.matcher(line).find() || line.toLowerCase(Locale.ROOT).contains("www.")) continue;
            if (!FIRM_WORDS.matcher(line).find()) continue;
            // A sentence mentioning a shipping company is not the company's name line.
            if (line.split("\\s+").length > 8 || line.endsWith(".") && line.length() > 40) continue;
            if (line.matches(".*\\d{3,}.*")) continue;
            found = line.replaceAll("^[-–—*•\\s]+|[,;|]+$", "").strip();
        }
        return found;
    }

    // ------------------------------------------------------------------ where

    private record CountryLine(String country, String city, String address) {
    }

    /**
     * The last line naming a country: the city is whatever sits just before the country on
     * that line, postcodes stripped, and the line above is taken into the address too when it
     * carries a street number.
     */
    private static CountryLine readCountry(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isEmpty() || line.length() > 120 || EMAIL.matcher(line).find()) continue;
            String lower = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\s]", " ") + " ";
            for (Map.Entry<String, String> e : COUNTRIES.entrySet()) {
                int at = lower.indexOf(" " + e.getKey() + " ");
                if (at < 0) continue;
                String before = line.substring(0, Math.min(Math.max(0, at), line.length()));
                String city = cityFrom(before);
                String address = line;
                String above = i > 0 ? lines.get(i - 1) : "";
                if (above.matches(".*\\d.*") && !EMAIL.matcher(above).find() && !signsFor(above)) {
                    address = above + ", " + line;
                }
                return new CountryLine(e.getValue(), city, address);
            }
        }
        return null;
    }

    private static String cityFrom(String before) {
        String[] parts = before.split("[,/|\\-–]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].replaceAll("\\d", "").strip();
            if (part.length() >= 3 && part.split("\\s+").length <= 3 && !FIRM_WORDS.matcher(part).find()) {
                return part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static Map<String, String> countries() {
        Map<String, String> m = new LinkedHashMap<>();
        // Longer names first, so "united arab emirates" is found before any shorter entry
        // that happens to sit inside it.
        String[][] rows = {
                {"united arab emirates", "United Arab Emirates"}, {"united kingdom", "United Kingdom"},
                {"united states", "United States"}, {"marshall islands", "Marshall Islands"},
                {"saudi arabia", "Saudi Arabia"}, {"south korea", "South Korea"},
                {"hong kong", "Hong Kong"}, {"great britain", "United Kingdom"},
                {"türkiye", "Turkey"}, {"turkiye", "Turkey"}, {"turkey", "Turkey"},
                {"greece", "Greece"}, {"hellas", "Greece"}, {"ukraine", "Ukraine"},
                {"russia", "Russia"}, {"russian federation", "Russia"}, {"romania", "Romania"},
                {"bulgaria", "Bulgaria"}, {"georgia", "Georgia"}, {"egypt", "Egypt"},
                {"italy", "Italy"}, {"spain", "Spain"}, {"malta", "Malta"}, {"cyprus", "Cyprus"},
                {"lebanon", "Lebanon"}, {"syria", "Syria"}, {"libya", "Libya"}, {"tunisia", "Tunisia"},
                {"algeria", "Algeria"}, {"morocco", "Morocco"}, {"israel", "Israel"},
                {"croatia", "Croatia"}, {"slovenia", "Slovenia"}, {"montenegro", "Montenegro"},
                {"albania", "Albania"}, {"serbia", "Serbia"}, {"moldova", "Moldova"},
                {"germany", "Germany"}, {"netherlands", "Netherlands"}, {"holland", "Netherlands"},
                {"belgium", "Belgium"}, {"france", "France"}, {"denmark", "Denmark"},
                {"norway", "Norway"}, {"sweden", "Sweden"}, {"finland", "Finland"},
                {"estonia", "Estonia"}, {"latvia", "Latvia"}, {"lithuania", "Lithuania"},
                {"poland", "Poland"}, {"switzerland", "Switzerland"}, {"austria", "Austria"},
                {"portugal", "Portugal"}, {"ireland", "Ireland"}, {"uae", "United Arab Emirates"},
                {"uk", "United Kingdom"}, {"usa", "United States"}, {"iran", "Iran"},
                {"iraq", "Iraq"}, {"jordan", "Jordan"}, {"qatar", "Qatar"}, {"oman", "Oman"},
                {"kuwait", "Kuwait"}, {"india", "India"}, {"pakistan", "Pakistan"},
                {"bangladesh", "Bangladesh"}, {"china", "China"}, {"singapore", "Singapore"},
                {"japan", "Japan"}, {"korea", "South Korea"}, {"azerbaijan", "Azerbaijan"},
                {"kazakhstan", "Kazakhstan"}, {"turkmenistan", "Turkmenistan"},
                {"monaco", "Monaco"}, {"luxembourg", "Luxembourg"}, {"panama", "Panama"},
                {"liberia", "Liberia"}, {"nigeria", "Nigeria"}, {"senegal", "Senegal"},
                {"brazil", "Brazil"}, {"canada", "Canada"}, {"australia", "Australia"},
        };
        java.util.Arrays.sort(rows, (a, b) -> Integer.compare(b[0].length(), a[0].length()));
        for (String[] r : rows) m.put(r[0], r[1]);
        return m;
    }
}
