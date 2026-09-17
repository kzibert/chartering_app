package com.chartering.repository;

import com.chartering.model.CargoSource;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
