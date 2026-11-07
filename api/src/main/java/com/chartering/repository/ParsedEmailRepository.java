package com.chartering.repository;

import com.chartering.dto.WaitingArrivalResponse;
import com.chartering.model.Company;
import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ParsedEmailRepository extends JpaRepository<ParsedEmail, Long> {

    /**
     * The sweep's queue: synced mail nobody has read yet, with the messages a person has queued
     * ahead of it.
     *
     * <p>Newest first for the reason the analysis capture takes the recent end of a range —
     * a sweep that stops at its cap should have read today's circulars rather than a
     * three-year-old thread. A message with a row in {@code parsed_emails} is excluded, which is
     * what makes the sweep idempotent, with one exception: a row {@link ParseStatus#QUEUED} is a
     * person's request to read it, so it is in the queue whatever its age, and ahead of everything
     * else. {@link #retryable} brings the failures back separately, so "never read" and "read and
     * failed" stay two different questions with two different answers.
     *
     * <p>Bodies are required, not merely preferred: a message with none has nothing to send
     * a model, and filtering here is cheaper than writing a SKIPPED row for every calendar
     * invite the mailbox has ever carried.
     *
     * <p>The window: a message is inside it when its mail server received it since
     * {@code since}, or when it reached this application since {@code since} and its server had it
     * after {@code floor}. The first clause is the ordinary lookback. The second exists because the
     * mailbox poller can be down for days: mail synced on the 16th for a sending date of the 12th
     * would otherwise be older than the window on the day it arrived, and never read. Counting
     * from arrival gives it a full window from when it reached us, and {@code floor} (twice the
     * lookback) keeps the first sync of a mailbox, which brings history with {@code syncedAt} = now,
     * from being read whole. A queued message is outside the window by design: the window limits
     * what a timer reads on its own, and a queued row is not that.
     *
     * <p>Queued rows come first, and newest first among themselves as among the rest: a person who
     * asked for a message a fortnight ago should not wait behind this morning's circulars.
     *
     * <p>{@code since} and {@code floor} are both always dates, never null, and "no limit" is
     * {@link ParserSettings#NO_LIMIT_SINCE} for both rather than a null to test for. That is not
     * style: Postgres cannot infer the type of a bare parameter in {@code (? is null or ...)} and
     * refuses the statement outright — "could not determine data type of parameter $1". A sentinel
     * keeps the predicate a plain comparison, which is also the form the index can serve.
     */
    @Query("""
            select m from MailMessage m
            where m.bodyText is not null and m.bodyText <> ''
              and ((m.receivedAt >= :since or (m.syncedAt >= :since and m.receivedAt >= :floor))
                   and not exists (select 1 from ParsedEmail p where p.mailMessage = m)
                   or exists (select 1 from ParsedEmail q where q.mailMessage = m
                              and q.status = com.chartering.model.ParseStatus.QUEUED))
            order by case when exists (select 1 from ParsedEmail r where r.mailMessage = m
                                       and r.status = com.chartering.model.ParseStatus.QUEUED) then 0 else 1 end,
                     m.receivedAt desc
            """)
    List<MailMessage> unparsed(@Param("since") LocalDateTime since, @Param("floor") LocalDateTime floor,
                               Pageable pageable);

    /**
     * The mail half of the Intake tab's "waiting" list: the same rows {@link #unparsed} hands the
     * sweep, in the same order, as the columns a list row prints.
     *
     * <p>A projection rather than entities, and that is the point. A {@code MailMessage} carries
     * both bodies, and a page of twenty of them would pull the whole of their text across the
     * wire to print a sender, a subject and a line of preview. {@code snippet} is the preview
     * the sync already stores, which is the one part of the body a list needs.
     *
     * <p>The window predicate is the one {@link #unparsed} documents, copied rather than shared,
     * because JPQL has no way to share a {@code where} clause; the {@code countQuery} must stay in
     * step with it, and the integration test checks the two agree. Queued rows lead, as they do in
     * {@link #unparsed}, so the list shows what the sweep will read next.
     */
    @Query(value = """
            select new com.chartering.dto.WaitingArrivalResponse(
                'MAIL', m.id, m.receivedAt, m.fromName, m.fromAddress, c.name, m.subject, m.snippet)
            from MailMessage m left join m.company c
            where m.bodyText is not null and m.bodyText <> ''
              and ((m.receivedAt >= :since or (m.syncedAt >= :since and m.receivedAt >= :floor))
                   and not exists (select 1 from ParsedEmail p where p.mailMessage = m)
                   or exists (select 1 from ParsedEmail q where q.mailMessage = m
                              and q.status = com.chartering.model.ParseStatus.QUEUED))
            order by case when exists (select 1 from ParsedEmail r where r.mailMessage = m
                                       and r.status = com.chartering.model.ParseStatus.QUEUED) then 0 else 1 end,
                     m.receivedAt desc
            """,
            countQuery = """
            select count(m) from MailMessage m
            where m.bodyText is not null and m.bodyText <> ''
              and ((m.receivedAt >= :since or (m.syncedAt >= :since and m.receivedAt >= :floor))
                   and not exists (select 1 from ParsedEmail p where p.mailMessage = m)
                   or exists (select 1 from ParsedEmail q where q.mailMessage = m
                              and q.status = com.chartering.model.ParseStatus.QUEUED))
            """)
    Page<WaitingArrivalResponse> waiting(@Param("since") LocalDateTime since,
                                         @Param("floor") LocalDateTime floor, Pageable pageable);

    /**
     * Failures worth another go, oldest first.
     *
     * <p>Oldest first here and newest first above, deliberately: a retry is catching up on
     * something already skipped, and taking the oldest is what stops one message that keeps
     * timing out from being retried ahead of ten that were merely unlucky. The attempt
     * ceiling is the caller's, because it is configuration rather than a fact about the row.
     */
    @Query("""
            select p from ParsedEmail p
            left join fetch p.mailMessage
            left join fetch p.feedItem
            where p.status = com.chartering.model.ParseStatus.FAILED
              and p.attempts < :maxAttempts
            order by p.parsedAt asc
            """)
    List<ParsedEmail> retryable(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    @EntityGraph(attributePaths = {"mailMessage", "feedItem", "feedItem.source"})
    Optional<ParsedEmail> findWithMessageById(Long id);

    /**
     * The firm a parse's mail was sent by, without loading the mail.
     *
     * <p>For the sender of an item that has no sources of its own, which the timed re-weigh asks
     * for on every tick. Loading the message to read one foreign key would pull its body across
     * on each of those ticks; this selects the company alone. Empty where the parse came from a
     * board (no message) or where the sync could not place the sender - both answer "nobody",
     * the same as a null company always has.
     */
    @Query("select m.company from ParsedEmail p join p.mailMessage m where p.id = ?1")
    Optional<Company> senderOf(Long parsedEmailId);

    Optional<ParsedEmail> findByMailMessageId(Long mailMessageId);

    Optional<ParsedEmail> findByFeedItemId(Long feedItemId);

    boolean existsByMailMessageId(Long mailMessageId);

    /**
     * The Log's unfiltered list: every parse outcome, newest first, except the requests still
     * waiting for the sweep. A queued row is not a result yet and has no answer to show; it stays
     * on the waiting list until it is read or left out.
     */
    @EntityGraph(attributePaths = {"mailMessage", "feedItem", "feedItem.source"})
    Page<ParsedEmail> findByStatusNotOrderByParsedAtDesc(ParseStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"mailMessage", "feedItem", "feedItem.source"})
    Page<ParsedEmail> findByStatusOrderByParsedAtDesc(ParseStatus status, Pageable pageable);

    long countByStatus(ParseStatus status);

    /**
     * How much is left to read, for the tab's header and for "is a sweep worth running".
     *
     * <p>Same window as {@link #unparsed} and {@link #waiting}, queued rows included — the header and
     * the queue must agree about what is waiting, so this takes the same {@code since} and
     * {@code floor} from the same settings snapshot.
     */
    @Query("""
            select count(m) from MailMessage m
            where m.bodyText is not null and m.bodyText <> ''
              and ((m.receivedAt >= :since or (m.syncedAt >= :since and m.receivedAt >= :floor))
                   and not exists (select 1 from ParsedEmail p where p.mailMessage = m)
                   or exists (select 1 from ParsedEmail q where q.mailMessage = m
                              and q.status = com.chartering.model.ParseStatus.QUEUED))
            """)
    long countUnparsed(@Param("since") LocalDateTime since, @Param("floor") LocalDateTime floor);

    @Query("select max(p.parsedAt) from ParsedEmail p")
    OffsetDateTime lastParsedAt();
}
