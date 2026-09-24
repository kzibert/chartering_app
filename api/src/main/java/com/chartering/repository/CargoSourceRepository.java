package com.chartering.repository;

import com.chartering.model.CargoSource;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CargoSourceRepository extends JpaRepository<CargoSource, Long> {

    /**
     * Everyone who has told us about this cargo, newest first.
     *
     * <p>The associations are fetched with it because every one of them is on screen: the
     * drawer shows a broker's name and the subject of the mail it came out of, and a list of
     * five sources would otherwise be eleven queries to render one panel.
     */
    @EntityGraph(attributePaths = {"mailMessage", "feedItem", "feedItem.source",
            "reportedByCompany", "reportedByPerson"})
    List<CargoSource> findByCargoIdOrderByReportedAtDesc(Long cargoId);

    /** The same, for a page of cargoes at once - the Cargoes list prints a sender on every row. */
    @EntityGraph(attributePaths = {"reportedByCompany", "reportedByPerson"})
    List<CargoSource> findByCargoIdInOrderByReportedAtDesc(List<Long> cargoIds);

    boolean existsByCargoIdAndMailMessageId(Long cargoId, Long mailMessageId);

    /** The same guard for an arrival that came off a board rather than out of the mailbox. */
    boolean existsByCargoIdAndFeedItemId(Long cargoId, Long feedItemId);

    long countByCargoId(Long cargoId);

    /**
     * The cargoes this firm has already told us about.
     *
     * <p>The strongest anchor a duplicate test has: a broker re-sending his own enquiry is not
     * "two firms working one cargo or two similar cargoes", which is the question a merge item
     * asks — it is one firm saying the same thing twice. See {@code CargoMatcher}.
     */
    @Query("select distinct s.cargo.id from CargoSource s where s.reportedByCompany.id = ?1")
    java.util.Set<Long> cargoIdsReportedBy(Long companyId);

    /** The same, for a sender the sync could not place: the address is the only handle. */
    @Query("select distinct s.cargo.id from CargoSource s "
            + "where s.reportedByCompany is null and lower(s.fromAddress) = lower(?1)")
    java.util.Set<Long> cargoIdsReportedFrom(String fromAddress);

    /** Arrivals on a cargo whose sender could not be named when they were filed. */
    @Query("select s from CargoSource s left join fetch s.mailMessage left join fetch s.feedItem "
            + "where s.reportedByCompany is null "
            + "and (s.mailMessage is not null or s.feedItem is not null)")
    List<CargoSource> findUnreportedWithSource();
}
