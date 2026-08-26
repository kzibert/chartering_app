package com.chartering.repository;

import com.chartering.model.AnalysisSample;
import com.chartering.model.AnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AnalysisSampleRepository
        extends JpaRepository<AnalysisSample, Long>, JpaSpecificationExecutor<AnalysisSample> {

    /**
     * Which of these Message-IDs are already in the corpus. One query per capture run rather
     * than one per candidate — a run over a year of a busy folder asks about a few thousand.
     */
    @Query("select s.messageId from AnalysisSample s where s.messageId in :ids")
    List<String> findExistingMessageIds(@Param("ids") Collection<String> ids);

    /**
     * The same question for mail that arrived without a Message-ID, which is rare but real
     * (a badly behaved sender, a message reconstructed by a migration). Without this a
     * second capture over the same folder would take those again, one copy per run.
     */
    @Query("select s.mailMessage.id from AnalysisSample s where s.mailMessage.id in :ids")
    List<Long> findExistingMailMessageIds(@Param("ids") Collection<Long> ids);

    /** The tab's counters, in one query rather than one per label. */
    @Query("select s.label, count(s) from AnalysisSample s group by s.label")
    List<Object[]> countByLabel();

    @Query("select s.status, count(s) from AnalysisSample s group by s.status")
    List<Object[]> countByStatus();

    /**
     * The export, in one stream and in a stable order.
     *
     * <p>Ordered by id, not by date: a training file that is regenerated with the rows in a
     * different order is a different file for no reason, and diffing two exports is how you
     * see what a labelling session actually added.
     */
    List<AnalysisSample> findByStatusOrderByIdAsc(AnalysisStatus status);

    long countByStatus(AnalysisStatus status);
}
