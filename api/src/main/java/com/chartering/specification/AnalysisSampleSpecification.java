package com.chartering.specification;

import com.chartering.model.AnalysisLabel;
import com.chartering.model.AnalysisSample;
import com.chartering.model.AnalysisStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Filters for the corpus: what kind of email, how far through review, and free text. */
public final class AnalysisSampleSpecification {

    private AnalysisSampleSpecification() {
    }

    public static Specification<AnalysisSample> hasLabel(AnalysisLabel label) {
        return (root, q, cb) -> label == null ? null : cb.equal(root.get("label"), label);
    }

    public static Specification<AnalysisSample> hasStatus(AnalysisStatus status) {
        return (root, q, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<AnalysisSample> hasSource(String source) {
        return (root, q, cb) ->
                source == null || source.isBlank() ? null : cb.equal(root.get("source"), source);
    }

    public static Specification<AnalysisSample> receivedFrom(LocalDateTime from) {
        return (root, q, cb) ->
                from == null ? null : cb.greaterThanOrEqualTo(root.get("receivedAt"), from);
    }

    public static Specification<AnalysisSample> receivedTo(LocalDateTime to) {
        return (root, q, cb) ->
                to == null ? null : cb.lessThanOrEqualTo(root.get("receivedAt"), to);
    }

    /**
     * One free-text box, every term having to match somewhere — the same rule as the mailbox
     * search and the circulation lists, deliberately: two search boxes in one app that
     * behave differently is worse than either behaviour.
     *
     * <p>The body <em>is</em> searched here, where the mailbox makes it opt-in. The
     * difference is what the box is for. In the mailbox you are looking for a message you
     * half remember; here you are looking for examples of a phrase — every sample that says
     * "abt 5,000 mts" — and a search that could not see the text would answer nothing worth
     * having. The corpus is also a few thousand rows against a mailbox's hundreds of
     * thousands, so the scan it costs is one a person can wait for.
     */
    public static Specification<AnalysisSample> matches(String search) {
        return (root, q, cb) -> {
            if (search == null || search.isBlank()) return null;

            List<Predicate> perTerm = new ArrayList<>();
            for (String term : search.trim().toLowerCase().split("\s+")) {
                if (term.isBlank()) continue;
                String pattern = "%" + term + "%";
                perTerm.add(cb.or(
                        cb.like(cb.lower(root.get("fromAddress")), pattern),
                        cb.like(cb.lower(root.get("fromName")), pattern),
                        cb.like(cb.lower(root.get("subject")), pattern),
                        cb.like(cb.lower(root.get("bodyText")), pattern),
                        cb.like(cb.lower(root.get("notes")), pattern)));
            }
            return perTerm.isEmpty() ? null : cb.and(perTerm.toArray(Predicate[]::new));
        };
    }
}
