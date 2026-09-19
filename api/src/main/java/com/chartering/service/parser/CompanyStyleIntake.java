package com.chartering.service.parser;

import com.chartering.dto.IntakePasteCompanyRequest;
import com.chartering.dto.IntakePasteCompanyComparison;
import com.chartering.dto.IntakePasteDraftResponse;
import com.chartering.model.Company;
import com.chartering.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
                lines, hash));
    }

    /**
     * The style as the compare and accept endpoints take it.
     *
     * <p>Public because both this class and the resolve path build one: an item's accept is the
     * paste screen's accept with the reviewer's ticks on it, and building the request two ways
     * would be two ideas of which parts of a signature are which.
     */
    public IntakePasteCompanyRequest request(Reading reading) {
        IntakePasteCompanyRequest req = new IntakePasteCompanyRequest();
        req.setCompanyId(reading.companyId());
        IntakePasteDraftResponse.CompanyDraft draft =
                IntakePasteService.companyDraft(reading.style(), reading.matches());
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
