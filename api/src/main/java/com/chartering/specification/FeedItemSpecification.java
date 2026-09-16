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

/** The Items tab's filter: a source, words in the title or text, and a date range. */
public final class FeedItemSpecification {

    private FeedItemSpecification() {
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
