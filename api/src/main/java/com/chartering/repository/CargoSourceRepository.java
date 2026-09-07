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
    @EntityGraph(attributePaths = {"mailMessage", "reportedByCompany", "reportedByPerson"})
    List<CargoSource> findByCargoIdOrderByReportedAtDesc(Long cargoId);

    boolean existsByCargoIdAndMailMessageId(Long cargoId, Long mailMessageId);

    long countByCargoId(Long cargoId);
}
