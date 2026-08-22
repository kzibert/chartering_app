package com.chartering.specification;

import com.chartering.model.MailMessage;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class MailMessageSpecification {

    private MailMessageSpecification() {
    }

    /**
     * The one search box.
     *
     * <p>Every whitespace-separated term has to match somewhere, but no term is tied to a
     * particular field — so "maersk laycan" finds the message from Maersk that mentions a
     * laycan, and typing an address, a person or a company into the same box all work
     * without the user first deciding which kind of thing they are typing. That is the same
     * rule the circulation-list search follows, deliberately: two search boxes in one app
     * that behave differently is worse than either behaviour.
     *
     * <p><b>What is searched, and what it costs.</b> Sender, subject, recipients and the
     * linked company/person are indexed (trigram, see db/mailbox.sql) and are searched
     * always. The message text is not indexed and is searched only when {@code includeBody}
     * is on: a substring scan over every stored body is a sequential read of the largest
     * columns in the table, which is exactly why the checkbox exists rather than the
     * behaviour being on by default.
     *
     * <p>Only {@code bodyText} is scanned, never {@code bodyHtml} — the two carry the same
     * words, and the HTML one would also match tag names and inline styles, so searching it
     * costs twice as much to return worse answers. A message that arrived as HTML only still
     * has its text extracted at sync time, so nothing is unsearchable for lack of a text part.
     */
    public static Specification<MailMessage> matches(String search, boolean includeBody) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) return null;

            // The linked company and person are part of the haystack, so a company name
            // finds its mail even when the address gives nothing away. Left joins: mail
            // from an unknown sender has neither, and must still be searchable.
            var company = root.join("company", JoinType.LEFT);
            var person = root.join("person", JoinType.LEFT);

            List<Predicate> perTerm = new ArrayList<>();
            for (String term : search.trim().toLowerCase().split("\\s+")) {
                if (term.isBlank()) continue;
                String pattern = "%" + term + "%";

                List<Predicate> fields = new ArrayList<>();
                fields.add(like(cb, root.get("fromAddress"), pattern));
                fields.add(like(cb, root.get("fromName"), pattern));
                fields.add(like(cb, root.get("subject"), pattern));
                fields.add(like(cb, root.get("toAddresses"), pattern));
                fields.add(like(cb, company.get("name"), pattern));
                fields.add(like(cb, person.get("fullName"), pattern));
                if (includeBody) {
                    fields.add(like(cb, root.get("bodyText"), pattern));
                }
                perTerm.add(cb.or(fields.toArray(Predicate[]::new)));
            }
            if (perTerm.isEmpty()) return null;
            // Joining to company/person can multiply nothing here (both are many-to-one),
            // so no distinct is needed and paging stays one row per message.
            return cb.and(perTerm.toArray(Predicate[]::new));
        };
    }

    private static Predicate like(jakarta.persistence.criteria.CriteriaBuilder cb,
                                  Expression<String> field, String pattern) {
        return cb.like(cb.lower(field), pattern);
    }

    /**
     * LIKE's own wildcards, escaped. Only the folder prefix below needs this — a folder
     * called "Q_1" is a name, not a pattern for "Q" plus any character.
     */
    private static final char LIKE_ESCAPE = '!';

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** One app folder. Pair with {@link #unfiled} to mean the Inbox instead. */
    public static Specification<MailMessage> inFolder(Long folderId) {
        return (root, query, cb) -> folderId == null ? null
                : cb.equal(root.get("folder").get("id"), folderId);
    }

    /**
     * One folder on the mail server, and everything under it.
     *
     * <p>Descendants included because a folder with children is still a place mail sits:
     * picking "Brokers" and being shown none of the mail in "Brokers/Handy" would read as an
     * empty folder rather than as a narrow question. The prefix is the folder's own name plus
     * the server's delimiter, so "Brokers" never catches "Brokerage".
     */
    public static Specification<MailMessage> inServerFolder(String fullName, String separator) {
        return (root, query, cb) -> {
            if (fullName == null || fullName.isBlank()) return null;
            Predicate itself = cb.equal(root.get("imapFolder"), fullName);
            if (separator == null || separator.isBlank()) return itself;
            return cb.or(itself, cb.like(root.get("imapFolder"),
                    escapeLike(fullName + separator) + "%", LIKE_ESCAPE));
        };
    }

    /**
     * The Inbox: mail no rule and no hand has filed. Passing false inverts it (everything
     * that has been filed somewhere), which is what "All filed mail" would mean.
     */
    public static Specification<MailMessage> unfiled(Boolean unfiled) {
        return (root, query, cb) -> unfiled == null ? null
                : unfiled ? cb.isNull(root.get("folder")) : cb.isNotNull(root.get("folder"));
    }

    public static Specification<MailMessage> readEquals(Boolean read) {
        return (root, query, cb) -> read == null ? null : cb.equal(root.get("read"), read);
    }

    /** Mail from one company — what the company drawer's mail list asks for. */
    public static Specification<MailMessage> companyIdEquals(Long companyId) {
        return (root, query, cb) -> companyId == null ? null
                : cb.equal(root.get("company").get("id"), companyId);
    }

    /**
     * Whether the message is attached to a company at all. false is the useful direction:
     * it lists the senders worth adding to the contacts, since an unlinked message is one
     * the rest of the app cannot see.
     */
    public static Specification<MailMessage> hasCompany(Boolean linked) {
        return (root, query, cb) -> linked == null ? null
                : linked ? cb.isNotNull(root.get("company")) : cb.isNull(root.get("company"));
    }

    /** Received on or after this instant. */
    public static Specification<MailMessage> receivedFrom(LocalDateTime from) {
        return (root, query, cb) -> from == null ? null
                : cb.greaterThanOrEqualTo(root.get("receivedAt"), from);
    }

    public static Specification<MailMessage> receivedTo(LocalDateTime to) {
        return (root, query, cb) -> to == null ? null
                : cb.lessThanOrEqualTo(root.get("receivedAt"), to);
    }
}
