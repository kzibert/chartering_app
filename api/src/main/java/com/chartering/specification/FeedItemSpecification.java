package com.chartering.specification;

import com.chartering.model.FeedItem;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Picking items out of the collected posts: for the Items tab, and for a corpus capture. */
public final class FeedItemSpecification {

    private FeedItemSpecification() {
    }

    /**
     * The Analysis tab's capture: some boards, words anywhere in the post, a date range.
     *
     * <p>Its own method rather than a fourth parameter on {@link #filter}, because the two ask
     * different questions of the same table. The Items tab shows one source at a time and takes
     * plain dates off a picker; a capture takes several boards at once — "everything I read
     * circulars off" is one run, not three — and a timestamp range, because it shares the
     * mailbox capture's form and that form is in timestamps.
     *
     * <p>Ordered here rather than by the caller's {@code Pageable}, as {@link #filter} is: the
     * date to sort on is the published one falling back to the fetched one, and a post that
     * gave no date line must not sort as though it had none.
     */
    public static Specification<FeedItem> forCapture(List<Long> sourceIds, String q,
                                                     LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            Expression<LocalDateTime> when = cb.coalesce(root.get("publishedAt"), root.get("fetchedAt"));
            if (sourceIds != null && !sourceIds.isEmpty()) {
                where.add(root.get("source").get("id").in(sourceIds));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("text")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("title"), "")), like)));
            }
            if (from != null) where.add(cb.greaterThanOrEqualTo(when, from));
            if (to != null) where.add(cb.lessThanOrEqualTo(when, to));
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(cb.desc(when), cb.desc(root.get("id")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    public static Specification<FeedItem> filter(Long sourceId, String q, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            // An item's date is when it was published, or when it was fetched where the page gave none.
            Expression<LocalDateTime> when = cb.coalesce(root.get("publishedAt"), root.get("fetchedAt"));
            if (sourceId != null) where.add(cb.equal(root.get("source").get("id"), sourceId));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("text")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("title"), "")), like)));
            }
            if (from != null) where.add(cb.greaterThanOrEqualTo(when, from.atStartOfDay()));
            if (to != null) where.add(cb.lessThan(when, to.plusDays(1).atStartOfDay()));
            // Newest first by that same date. Not on the count query, which has no use for an order.
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(cb.desc(when), cb.desc(root.get("id")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
