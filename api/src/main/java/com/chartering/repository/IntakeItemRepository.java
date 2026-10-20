package com.chartering.repository;

import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IntakeItemRepository
        extends JpaRepository<IntakeItem, Long>, JpaSpecificationExecutor<IntakeItem> {

    @Override
    @EntityGraph(attributePaths = {"parsedEmail", "parsedEmail.mailMessage",
            "parsedEmail.feedItem", "parsedEmail.feedItem.source"})
    Page<IntakeItem> findAll(org.springframework.data.jpa.domain.Specification<IntakeItem> spec,
                             Pageable pageable);

    @EntityGraph(attributePaths = {"parsedEmail", "parsedEmail.mailMessage",
            "parsedEmail.feedItem", "parsedEmail.feedItem.source"})
    Optional<IntakeItem> findWithEmailById(Long id);

    long countByStatus(IntakeItemStatus status);

    /** The queue's own count, or the "Minor updates" sub-tab's - see {@code IntakeItem.minor}. */
    long countByStatusAndMinor(IntakeItemStatus status, boolean minor);

    /**
     * The "Minor updates" count the Intake tab prints, which leaves out company details.
     *
     * <p>A minor company question is not queued at all: it waits as a mark on the firm's own
     * record ({@code CompanyResponse.correspondenceUpdate}) and is answered there. Counting it
     * here would print a number on the tab for questions the tab never lists.
     */
    @Query("""
            select count(i) from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.minor = true
              and i.kind <> com.chartering.model.IntakeItemKind.COMPANY_DETAILS
            """)
    long countPendingMinorUpdates();

    /**
     * Which of these firms have a question waiting about their own details, minor or not.
     *
     * <p>One query for a whole page of companies, for the "Update from correspondence" mark on
     * the companies list. Minor or not, because the mark says a firm has something to look at on
     * its record, and a major question is no less worth finding there.
     */
    @Query("""
            select distinct i.companyId from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = com.chartering.model.IntakeItemKind.COMPANY_DETAILS
              and i.companyId in ?1
            """)
    List<Long> companiesWithPendingDetails(Collection<Long> companyIds);

    @Query("select i.kind, count(i) from IntakeItem i where i.status = ?1 group by i.kind")
    List<Object[]> countByKind(IntakeItemStatus status);

    List<IntakeItem> findByParsedEmailIdOrderByIdAsc(Long parsedEmailId);

    /**
     * A pending item already asking this question about this vessel.
     *
     * <p>Why it is asked at all: a broker's list arrives every morning, and every morning it
     * disagrees with the database about the same deadweight. Without this, one unanswered
     * disagreement becomes thirty queue rows in a month and the queue stops being read —
     * which is the failure mode that matters, because a review screen nobody opens is worse
     * than no review screen. Answering it once, either way, is what clears it: an accepted
     * item has written the value so the next list agrees, and a rejected one is re-raised
     * only if the incoming figures have moved since (see {@code IntakeService}).
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = ?1 and i.vesselId = ?2
            order by i.id desc
            """)
    List<IntakeItem> pendingForVessel(IntakeItemKind kind, Long vesselId);

    /**
     * Every waiting item of one kind, and nothing hanging off it.
     *
     * <p>For the timed passes that re-weigh the queue ({@code IntakeReconcileRunner}), which run
     * every few minutes whether or not anything arrived. They read the item's own payload and
     * a company or vessel by id, so the parse is a lazy reference that is never initialised
     * unless a row actually needs its message. Fetching the parse here would drag each one's
     * raw model output and the whole email, body included, over the wire on every tick, for
     * every waiting item - a cost that scales with the queue and is paid whether or not any
     * item changes.
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = ?1
            order by i.id asc
            """)
    List<IntakeItem> pendingOfKind(IntakeItemKind kind);

    /**
     * A pending question already open about this firm.
     *
     * <p>The suppression {@code COMPANY_DETAILS} cannot do without. A signature arrives with
     * every list a broker sends rather than only when something is wrong, so one unanswered
     * question about a firm would be twenty rows in a month and the queue would be unreadable
     * — the failure the vessel rule already exists to prevent, at ten times the rate.
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = com.chartering.model.IntakeItemKind.COMPANY_DETAILS
              and i.companyId = ?1
            order by i.id desc
            """)
    List<IntakeItem> pendingForCompany(Long companyId);

    /**
     * The same question about a firm that is not on file yet, matched on the name as signed.
     *
     * <p>{@code NEW_VESSEL}'s rule, for the same situation: there is no id to group on until
     * somebody accepts the item, and the name as written is the only handle there is.
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = com.chartering.model.IntakeItemKind.COMPANY_DETAILS
              and i.companyId is null
              and lower(i.subjectLabel) = lower(?1)
            order by i.id desc
            """)
    List<IntakeItem> pendingNewCompany(String name);

    /**
     * Whether a signature reading exactly like this one has already been turned down.
     *
     * <p>What a discarded company question suppresses. The other kinds can re-raise when the
     * incoming figures move, because figures are what they are about; a signature is about a
     * firm, and "do not file these details" would be worthless if the same broker's next list
     * undid it. The fingerprint is in the payload, so this is a {@code like} over a text
     * column — a scan of a queue table, run once per circular, against a hash that cannot
     * collide with anything else stored in there.
     */
    @Query("""
            select count(i) from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.REJECTED
              and i.kind = com.chartering.model.IntakeItemKind.COMPANY_DETAILS
              and i.payload like concat('%', ?1, '%')
            """)
    long countRejectedWithStyle(String styleHash);

    /**
     * A merge question already waiting about this cargo.
     *
     * <p>The vessel rule, for the cargo side: cargo 122 was asked about seven times, one item per
     * email, and answering one left six still asking. Another sighting of the same candidate is
     * added to the question already open rather than queued behind it.
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = com.chartering.model.IntakeItemKind.CARGO_MERGE
              and i.cargoId = ?1
            order by i.id desc
            """)
    List<IntakeItem> pendingCargoMerge(Long cargoId);

    /** Whether any question raised off one board is still waiting — asked before deleting it. */
    @Query("""
            select count(i) from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.parsedEmail.feedItem.source.id = ?1
            """)
    long countPendingFromSource(Long sourceId);

    /** The same question about a hull that is not on file yet, matched on the name as spelled. */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = com.chartering.model.IntakeItemKind.NEW_VESSEL
              and lower(i.subjectLabel) = lower(?1)
            order by i.id desc
            """)
    List<IntakeItem> pendingNewVessel(String name);

    /**
     * A name the desk has turned down as a ship to create, answered since {@code since}.
     *
     * <p>The discard memory for {@code NEW_VESSEL}. Matched the way {@link #pendingNewVessel}
     * matches a waiting one, so the two cannot disagree about whether a name is the same. A
     * discard is the reviewer saying this is not a ship to create; without this, a broker's
     * "TBN" or a one-off name he keeps re-sending is asked again every morning.
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.REJECTED
              and i.kind = com.chartering.model.IntakeItemKind.NEW_VESSEL
              and lower(i.subjectLabel) = lower(?1)
              and i.resolvedAt >= ?2
            order by i.id desc
            """)
    List<IntakeItem> rejectedNewVesselSince(String name, OffsetDateTime since);
}
