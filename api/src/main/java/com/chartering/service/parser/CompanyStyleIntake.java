package com.chartering.service.parser;

import com.chartering.dto.IntakePasteCompanyRequest;
import com.chartering.dto.IntakePasteCompanyComparison;
import com.chartering.dto.IntakePasteDraftResponse;
import com.chartering.dto.CompanyRequest;
import com.chartering.model.Company;
import com.chartering.service.CompanyNames;
import com.chartering.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

/**
 * The firm that signed the circular, set against the firm on file.
 *
 * <p><b>Why this is worth doing at all.</b> Every circular ends in a full style — the name, the
 * street, the site, the people and their numbers — and it is the only part of the mail that is
 * <em>about the sender</em> rather than about the market. The contacts database goes stale in
 * exactly that place, and the market keeps posting its current details through the door twice a
 * day. A broker moves office, a desk address changes, a new charterer joins the list, and
 * nothing here notices until somebody writes to an address that bounces.
 *
 * <p><b>Silence is the normal answer, and it has to be.</b> A signature arrives with every list
 * a broker sends, not only when something has changed. If this raised a question per circular
 * the queue would be unreadable inside a week and the three kinds that matter would be buried
 * under it. Three things keep it quiet:
 *
 * <ul>
 *   <li><b>Nothing new, nothing asked.</b> A firm whose record already holds the name, the site,
 *       the people and every address in the block produces no item — which, after the first one
 *       is answered, is what a regular correspondent produces for ever.</li>
 *   <li><b>One pending item per firm</b> — {@code IntakeItemRepository.pendingForCompany}, and
 *       on the name as signed for a firm not on file yet. Further circulars merge into the
 *       waiting item rather than queueing behind it.</li>
 *   <li><b>A discarded reading stays discarded.</b> {@link #styleHash} fingerprints what the
 *       signature actually said, and a reading already rejected is not asked again. Only a
 *       signature that has <em>moved</em> comes back, which is the same rule the vessel
 *       questions follow: a rejected item is re-raised when the incoming figures change.</li>
 * </ul>
 *
 * <p><b>It proposes; the reviewer decides which firm.</b> {@link CompanyMatcher} is unchanged
 * and still never picks — but a question has to be about something, so one strong match (the
 * same address, the same name, the same number) is carried as the firm this is about. Weak
 * evidence alone — a shared domain, a similar name — is carried as candidates and the item asks
 * which, because two firms a broker keeps apart must not be merged by a parser.
 *
 * <p>The reading itself is {@link CompanyStyleReader}'s, not the model's, for the reason the
 * paste screen uses it: the finetune was trained on cargoes and positions and knows a sender
 * only as company, person and email. Which means this half keeps working with the model server
 * switched off — though nothing calls it then, because nothing is being parsed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyStyleIntake {

    private final CompanyMatcher matcher;
    private final CompanyRepository companies;
    private final IntakePasteService paste;

    /**
     * A signature read and placed.
     *
     * @param style     what the block actually said
     * @param matches   the firms on file it might be, strongest first
     * @param companyId the firm it is, when one piece of identity evidence says so and only
     *                  one firm carries it — used as the reporter of a position and the broker
     *                  of a cargo read off the same text, which is what the mail sync gets from
     *                  the envelope and a board post has nowhere else to get
     */
    public record Reading(CompanyStyleReader.Style style,
                          List<CompanyMatcher.Match> matches,
                          Long companyId) {

        public boolean isEmpty() {
            return style == null || style.isEmpty();
        }
    }

    /**
     * Read the signature out of one arrival's text and place it against the companies on file.
     *
     * @param extraction the model's answer for this arrival, or null where it did not run; its
     *                   company reading is used when it gave one (see
     *                   {@link CompanyStyleReader#readWithModel})
     */
    public Reading read(String text, Extraction extraction) {
        CompanyStyleReader.Style style = CompanyStyleReader.readWithModel(text, extraction);
        if (style.isEmpty()) return new Reading(style, List.of(), null);
        List<CompanyMatcher.Match> matches = matcher.match(style);
        // The same test the paste screen makes before it dares fill in a cargo's broker: one
        // firm, identified by something that is identity rather than resemblance. Two firms
        // sharing an address is the shape that makes "the only strong match" worth insisting
        // on - it happened once already, on the Introduced list.
        List<CompanyMatcher.Match> strong = matches.stream().filter(CompanyMatcher.Match::strong).toList();
        Long companyId = strong.size() == 1 ? strong.get(0).companyId() : null;
        return new Reading(style, matches, companyId);
    }

    /**
     * The question this signature raises, or nothing when it raises none.
     *
     * <p>Three shapes come out of here and they are one kind of item because they are one
     * decision — "what should the contacts database say about this firm":
     *
     * <ul>
     *   <li>a firm nothing on file resembles, offered to be created;</li>
     *   <li>a firm identified beyond doubt whose record the block disagrees with or could fill
     *       gaps in, offered field by field and address by address;</li>
     *   <li>a firm only resembled — a shared domain, a name that differs by its legal form —
     *       where the candidates are offered and the reviewer says which, or none.</li>
     * </ul>
     *
     * <p>The second is the one that needs the comparison, and it is {@link IntakePasteService}'s,
     * called rather than copied: the paste screen and this queue must not drift into two ideas
     * of what "the record already has that" means. It is also what makes the common case silent
     * — a comparison with no rows in it is a signature that told us nothing we did not hold.
     */
    public Optional<IntakePayloads.CompanyDetails> question(Reading reading) {
        if (reading.isEmpty()) return Optional.empty();
        CompanyStyleReader.Style style = reading.style();
        // A block with no firm name is a person's sign-off, not a full style. It could still
        // carry an address worth having, and filing it under a company nobody named is how a
        // contacts table fills up with rows nobody can attribute.
        if (style.name() == null || style.name().isBlank()) return Optional.empty();

        IntakePasteDraftResponse.CompanyDraft draft = IntakePasteService.companyDraft(style, reading.matches());
        String hash = styleHash(style);

        if (reading.companyId() == null) {
            // Nothing identified it. Either nothing on file resembles it at all - a firm to
            // create - or something does and a person has to say whether it is the same firm.
            List<String> lines = reading.matches().isEmpty()
                    ? List.of("Not on file. " + describe(style))
                    : List.of(reading.matches().size() + " similar firm(s) on file — or create it. "
                            + describe(style));
            return Optional.of(new IntakePayloads.CompanyDetails(
                    draft, null, null, null, lines, hash));
        }

        Company onFile = companies.findById(reading.companyId()).orElse(null);
        if (onFile == null) return Optional.empty();

        IntakePasteCompanyComparison comparison = paste.compare(request(reading));
        List<String> lines = changes(comparison);
        // The whole reason a regular correspondent is quiet: their block says what the record
        // already says, so there is nothing to decide and nothing is asked.
        if (lines.isEmpty()) return Optional.empty();

        return Optional.of(new IntakePayloads.CompanyDetails(
                draft, onFile.getId(), onFile.getName(),
                reading.matches().stream().filter(CompanyMatcher.Match::strong)
                        .findFirst().map(CompanyMatcher.Match::how).orElse(null),
                lines, hash, isMinor(onFile.getId(), draft, comparison), List.of(hash)));
    }

    /**
     * Fold a fresh signature into the question already waiting about the same firm.
     *
     * <p><b>Why a union and not the newest.</b> The item used to carry whichever signature
     * arrived last, and signatures from one firm are not one document: the chartering desk signs
     * with its two mobiles, the operations desk with a direct line and a different person, and
     * the Friday list with the office block only. Keeping the newest meant the item said less
     * the more mail arrived. So the draft is every person and every address any of the firm's
     * mail has carried, with the newest reading winning a field where two speak — the later
     * block is the later statement — and the comparison is made again on the whole.
     *
     * <p>Which is also what decides whether the question is minor: a firm that moved its website
     * on Monday and gained a new desk address on Thursday is a minor question on Monday and a
     * real one on Thursday.
     */
    public IntakePayloads.CompanyDetails aggregate(IntakePayloads.CompanyDetails waiting,
                                                   IntakePayloads.CompanyDetails fresh) {
        if (waiting == null || waiting.draft() == null) return fresh;
        IntakePasteDraftResponse.CompanyDraft draft = mergeDrafts(waiting.draft(), fresh.draft());
        Long companyId = fresh.companyId() != null ? fresh.companyId() : waiting.companyId();

        List<String> seen = new ArrayList<>(waiting.seenStyles() != null ? waiting.seenStyles()
                : waiting.styleHash() == null ? List.of() : List.of(waiting.styleHash()));
        if (fresh.styleHash() != null && !seen.contains(fresh.styleHash())) seen.add(fresh.styleHash());

        if (companyId == null) {
            // Still a firm nobody has on file: nothing to compare with, and a firm to create is
            // never minor. The row's line is the newest one's, which describes the newest block.
            return new IntakePayloads.CompanyDetails(draft, null, null, null,
                    fresh.changes(), fresh.styleHash(), false, List.copyOf(seen));
        }
        IntakePasteCompanyComparison comparison = paste.compare(requestFor(companyId, draft));
        List<String> lines = changes(comparison);
        return new IntakePayloads.CompanyDetails(draft, companyId,
                fresh.companyName() != null ? fresh.companyName() : waiting.companyName(),
                fresh.matchedBy() != null ? fresh.matchedBy() : waiting.matchedBy(),
                lines.isEmpty() ? List.of("Nothing the record does not already hold") : lines,
                fresh.styleHash(), isMinor(companyId, draft, comparison), List.copyOf(seen));
    }

    /**
     * Whether a question about a firm on file can wait on its own record rather than the queue.
     *
     * <p>The rule is the desk's: <b>the name and the email addresses are what the contacts
     * database is for</b>, and everything else in a signature — a website, a city, a phone, a
     * new face, a job title — is worth having and not worth a morning. So it is minor unless the
     * firm's name has actually changed (not merely its legal form: "Fednav Ltd." is FEDNAV), or
     * the block carries an email address the firm does not have. A firm not on file is never
     * minor; that is the question the whole kind exists for.
     */
    static boolean isMinor(Long companyId, IntakePasteDraftResponse.CompanyDraft draft,
                           IntakePasteCompanyComparison comparison) {
        if (companyId == null || comparison == null) return false;
        if (comparison.fields() != null) {
            for (IntakePasteCompanyComparison.FieldRow f : comparison.fields()) {
                if ("name".equals(f.field()) && !CompanyNames.similarityKey(f.current())
                        .equals(CompanyNames.similarityKey(f.parsed()))) {
                    return false;
                }
            }
        }
        List<IntakePasteCompanyComparison.ContactRow> rows =
                comparison.contacts() == null ? List.of() : comparison.contacts();
        List<IntakePasteDraftResponse.ContactDraft> contacts = nullToEmpty(draft.contacts());
        // The comparison answers the request's contacts in order, and the request was built from
        // these - so the two lists line up index for index.
        for (int i = 0; i < Math.min(rows.size(), contacts.size()); i++) {
            if ("email".equalsIgnoreCase(contacts.get(i).kind()) && rows.get(i).existingContactId() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Two drafts of one firm as one: every person and every address either carried, the newer
     * reading winning where both speak.
     */
    static IntakePasteDraftResponse.CompanyDraft mergeDrafts(IntakePasteDraftResponse.CompanyDraft older,
                                                             IntakePasteDraftResponse.CompanyDraft newer) {
        if (older == null) return newer;
        if (newer == null) return older;
        CompanyRequest company = newer.company() != null ? newer.company() : older.company();
        CompanyRequest before = older.company();
        if (company != null && before != null && company != before) {
            if (isBlank(company.getName())) company.setName(before.getName());
            if (isBlank(company.getCityName())) company.setCityName(before.getCityName());
            if (isBlank(company.getCountry())) company.setCountry(before.getCountry());
            if (isBlank(company.getWebsite())) company.setWebsite(before.getWebsite());
            if (isBlank(company.getNotes())) company.setNotes(before.getNotes());
        }

        Map<String, IntakePasteDraftResponse.PersonDraft> people = new LinkedHashMap<>();
        for (IntakePasteDraftResponse.PersonDraft p : nullToEmpty(older.people())) {
            people.put(lower(p.fullName()), p);
        }
        for (IntakePasteDraftResponse.PersonDraft p : nullToEmpty(newer.people())) {
            IntakePasteDraftResponse.PersonDraft was = people.get(lower(p.fullName()));
            people.put(lower(p.fullName()), was == null ? p : new IntakePasteDraftResponse.PersonDraft(
                    p.fullName(),
                    isBlank(p.title()) ? was.title() : p.title(),
                    isBlank(p.jobTitle()) ? was.jobTitle() : p.jobTitle()));
        }

        Map<String, IntakePasteDraftResponse.ContactDraft> contacts = new LinkedHashMap<>();
        for (IntakePasteDraftResponse.ContactDraft c : nullToEmpty(older.contacts())) {
            contacts.put(contactKey(c), c);
        }
        for (IntakePasteDraftResponse.ContactDraft c : nullToEmpty(newer.contacts())) {
            IntakePasteDraftResponse.ContactDraft was = contacts.get(contactKey(c));
            contacts.put(contactKey(c), was == null ? c : new IntakePasteDraftResponse.ContactDraft(
                    c.kind(), c.value(),
                    isBlank(c.label()) ? was.label() : c.label(),
                    isBlank(c.personName()) ? was.personName() : c.personName()));
        }

        return new IntakePasteDraftResponse.CompanyDraft(company,
                isBlank(newer.address()) ? older.address() : newer.address(),
                List.copyOf(people.values()), List.copyOf(contacts.values()),
                nullToEmpty(newer.matches()).isEmpty() ? older.matches() : newer.matches());
    }

    /** One address however it was written - the paste screen's own key, so the two agree. */
    private static String contactKey(IntakePasteDraftResponse.ContactDraft c) {
        return c.kind() == null || c.value() == null ? lower(c.kind()) + ":" + lower(c.value())
                : IntakePasteService.contactKey(c.kind(), c.value());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * The style as the compare and accept endpoints take it.
     *
     * <p>Public because both this class and the resolve path build one: an item's accept is the
     * paste screen's accept with the reviewer's ticks on it, and building the request two ways
     * would be two ideas of which parts of a signature are which.
     */
    public IntakePasteCompanyRequest request(Reading reading) {
        return requestFor(reading.companyId(),
                IntakePasteService.companyDraft(reading.style(), reading.matches()));
    }

    /** The same, from a draft - which is what an aggregated item holds. */
    public static IntakePasteCompanyRequest requestFor(Long companyId,
                                                       IntakePasteDraftResponse.CompanyDraft draft) {
        IntakePasteCompanyRequest req = new IntakePasteCompanyRequest();
        req.setCompanyId(companyId);
        req.setCompany(draft.company());
        req.setPeople(draft.people().stream()
                .map(p -> new IntakePasteCompanyRequest.PersonChange(
                        p.fullName(), p.title(), p.jobTitle(), null))
                .toList());
        req.setContacts(draft.contacts().stream()
                .map(c -> new IntakePasteCompanyRequest.ContactChange(
                        c.kind(), c.value(), c.label(), c.personName(), null))
                .toList());
        return req;
    }

    /** What the comparison found, in the words the queue row prints. */
    private static List<String> changes(IntakePasteCompanyComparison comparison) {
        List<String> lines = new ArrayList<>();
        if (comparison.fields() != null && !comparison.fields().isEmpty()) {
            lines.add(comparison.fields().size() == 1
                    ? "1 field differs" : comparison.fields().size() + " fields differ");
        }
        long newPeople = comparison.people() == null ? 0
                : comparison.people().stream().filter(p -> p.existingPersonId() == null).count();
        if (newPeople > 0) {
            lines.add(newPeople == 1 ? "1 person not on file" : newPeople + " people not on file");
        }
        long newContacts = comparison.contacts() == null ? 0
                : comparison.contacts().stream().filter(c -> c.existingContactId() == null).count();
        if (newContacts > 0) {
            lines.add(newContacts == 1 ? "1 address not on file"
                    : newContacts + " addresses not on file");
        }
        return lines;
    }

    /** What a firm nobody has heard of came with, so the row says whether it is worth opening. */
    private static String describe(CompanyStyleReader.Style style) {
        long emails = style.contacts().stream().filter(c -> "email".equals(c.kind())).count();
        long phones = style.contacts().size() - emails;
        List<String> parts = new ArrayList<>();
        if (!style.people().isEmpty()) {
            parts.add(style.people().size() + (style.people().size() == 1 ? " person" : " people"));
        }
        if (emails > 0) parts.add(emails + (emails == 1 ? " address" : " addresses"));
        if (phones > 0) parts.add(phones + (phones == 1 ? " number" : " numbers"));
        return parts.isEmpty() ? "Nothing but the name." : String.join(", ", parts) + ".";
    }

    /**
     * A fingerprint of what the block said — the name, the site, the people and every address.
     *
     * <p><b>What a discard suppresses.</b> Without it, "no, do not file these details" would
     * last exactly until the same broker sent his next list, because the comparison would find
     * the same rows and raise the same question. With it, only a signature that has actually
     * moved comes back — which is the answer a reviewer meant, and the rule the vessel
     * questions already follow.
     *
     * <p>Order-insensitive within each kind and case-folded, because a signature is text a
     * person retypes: the same details with two lines swapped is the same reading, and a
     * fingerprint that said otherwise would be no suppression at all.
     */
    public static String styleHash(CompanyStyleReader.Style style) {
        List<String> parts = new ArrayList<>();
        parts.add("name=" + lower(style.name()));
        parts.add("web=" + lower(style.website()));
        parts.add("city=" + lower(style.city()));
        parts.add("country=" + lower(style.country()));
        style.people().stream().map(p -> "person=" + lower(p.fullName())).sorted().forEach(parts::add);
        style.contacts().stream()
                .map(c -> "contact=" + lower(c.kind()) + ":" + lower(c.value()))
                .sorted().forEach(parts::add);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    sha.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
