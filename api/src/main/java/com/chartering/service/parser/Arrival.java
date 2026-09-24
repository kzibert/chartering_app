package com.chartering.service.parser;

import com.chartering.model.Company;
import com.chartering.model.FeedItem;
import com.chartering.model.MailMessage;
import com.chartering.model.ParsedEmail;
import com.chartering.model.Person;
import com.chartering.model.SourceKind;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * One circular, and the door it came in through.
 *
 * <p><b>Why this exists at all.</b> Everything downstream of the model asks the same four
 * questions of an arrival — who told us, when, what to file it against, and what to call it in
 * a change-set description — and for a synced message all four came off the {@link MailMessage}
 * itself. A board post answers them from different places: the reporter is read out of the
 * signature block rather than resolved from an envelope, the date is the board's date line, and
 * there is no subject. Threading a nullable message through {@code IntakeService} and testing
 * for it at each of a dozen call sites is how one of them ends up filing a position against
 * nobody, silently, for a month.
 *
 * <p><b>Who "told us" is the same fact read two ways, and that is the point.</b> A mailed
 * circular has been through the mail sync, which matched the sending address against the
 * contacts table — a better answer than a name in prose, and the reason
 * {@code IntakeService.createCargo} deliberately prefers the envelope to the signature. A board
 * post has no envelope at all, so the signature is not the second-best answer there; it is the
 * only one. Both end up here as a {@link Company}, and nothing downstream has to know which.
 *
 * <p>Not an entity and not stored. What is stored is what it produced: the reporter on the
 * position, the broker on the cargo, the link back to the message or the post.
 */
public record Arrival(MailMessage message,
                      FeedItem post,
                      Company company,
                      Person person,
                      String fromAddress,
                      OffsetDateTime reportedAt,
                      String label,
                      SourceKind kind) {

    public boolean isMail() {
        return kind == SourceKind.MAIL;
    }

    /** Whether two arrivals are the same one — the message, or the post. */
    public boolean is(MailMessage other) {
        return message != null && other != null && message.getId().equals(other.getId());
    }

    public boolean is(FeedItem other) {
        return post != null && other != null && post.getId().equals(other.getId());
    }

    /**
     * A synced message, with the sender the mail sync already resolved.
     *
     * <p>{@code reportedAt} prefers the sender's own clock, for the reason the corpus export
     * does: a message that sat in a queue overnight would otherwise be dated a day after the
     * laycan it announces. Received is the fallback for mail carrying no Date header.
     */
    public static Arrival of(MailMessage m) {
        var when = m.getSentAt() != null ? m.getSentAt() : m.getReceivedAt();
        String subject = m.getSubject();
        return new Arrival(m, null, m.getCompany(), m.getPerson(), m.getFromAddress(),
                when == null ? OffsetDateTime.now() : when.atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                subject == null || subject.isBlank() ? "(no subject)" : subject.strip(),
                SourceKind.MAIL);
    }

    /**
     * A post off a board, with whatever its signature block turned out to be.
     *
     * <p><b>{@code reportedAt} is the board's own date line, not when we fetched it.</b> A board
     * is a standing page: the entry read this morning was posted on the 14th and says "SPOT AT
     * MARMARA", and dating it today would make a three-day-old position look like this
     * morning's. Staleness is the first thing Open Fleet has to show, so it has to be the
     * publisher's date wherever the page gives one.
     *
     * <p>{@code company} may be null, and that is an honest answer rather than a failure: a
     * signature nothing on file matches is a firm nobody here has met, which is exactly what the
     * {@code COMPANY_DETAILS} question raised beside it is for. The position is still filed —
     * where a ship is open is worth having from a firm we cannot name yet — and it gains its
     * reporter once somebody answers that question, including the rows already filed from
     * this post ({@code IntakeService.attributeUnreported}).
     */
    public static Arrival of(FeedItem post, CompanyStyleIntake.Reading signature,
                             Company company, Person person) {
        var when = post.getPublishedAt() != null ? post.getPublishedAt() : post.getFetchedAt();
        String firm = signature == null || signature.style() == null ? null : signature.style().name();
        String source = post.getSource() == null ? "a board" : post.getSource().getName();
        return new Arrival(null, post, company, person, firstEmail(signature),
                when == null ? OffsetDateTime.now() : when.atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                firm == null ? source : firm + " on " + source,
                SourceKind.WEB);
    }

    /**
     * The address the block signs with, kept as text beside the link.
     *
     * <p>{@code CargoSource} keeps one for mail for a reason that applies here twice over: a
     * sender with no contact row still has to be attributable, and on a board that is the
     * ordinary case rather than the exception.
     */
    private static String firstEmail(CompanyStyleIntake.Reading signature) {
        if (signature == null || signature.style() == null) return null;
        return signature.style().contacts().stream()
                .filter(c -> "email".equals(c.kind()))
                .map(CompanyStyleReader.ContactLine::value)
                .findFirst().orElse(null);
    }

    /** What a parse row was read out of, for the paths that start from one. */
    public static boolean isPost(ParsedEmail parsed) {
        return parsed != null && parsed.getFeedItem() != null;
    }
}
