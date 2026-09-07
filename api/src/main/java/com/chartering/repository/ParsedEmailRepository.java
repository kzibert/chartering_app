package com.chartering.repository;

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
     * The sweep's queue: synced mail nobody has read yet, newest first.
     *
     * <p>Newest first for the reason the analysis capture takes the recent end of a range —
     * a sweep that stops at its cap should have read today's circulars rather than a
     * three-year-old thread. A message with a row in {@code parsed_emails} of any status is
     * excluded, which is what makes the sweep idempotent; {@link #retryable} brings the
     * failures back separately, so "never read" and "read and failed" stay two different
     * questions with two different answers.
     *
     * <p>Bodies are required, not merely preferred: a message with none has nothing to send
     * a model, and filtering here is cheaper than writing a SKIPPED row for every calendar
     * invite the mailbox has ever carried.
     *
     * <p>{@code since} is the lookback window, and it is what stops the feature reading a
     * mailbox's whole history the first time it is switched on. A three-year-old position
     * list is not information — the ship sailed, the cargo fixed — so parsing it spends GPU
     * to fill Open Fleet with rows that are wrong by construction.
     *
     * <p>Always a date, never null, and "no limit" is {@link ParserSettings#NO_LIMIT_SINCE}
     * rather than a null to test for. That is not style: Postgres cannot infer the type of a
     * bare parameter in {@code (? is null or ...)} and refuses the statement outright —
     * "could not determine data type of parameter $1". A sentinel keeps the predicate a plain
     * comparison, which is also the form the index can serve.
     */
    @Query("""
            select m from MailMessage m
            where m.bodyText is not null and m.bodyText <> ''
              and m.receivedAt >= :since
              and not exists (select 1 from ParsedEmail p where p.mailMessage = m)
            order by m.receivedAt desc
            """)
    List<MailMessage> unparsed(@Param("since") LocalDateTime since, Pageable pageable);

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
            join fetch p.mailMessage
            where p.status = com.chartering.model.ParseStatus.FAILED
              and p.attempts < :maxAttempts
            order by p.parsedAt asc
            """)
    List<ParsedEmail> retryable(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    @EntityGraph(attributePaths = "mailMessage")
    Optional<ParsedEmail> findWithMessageById(Long id);

    Optional<ParsedEmail> findByMailMessageId(Long mailMessageId);

    boolean existsByMailMessageId(Long mailMessageId);

    @EntityGraph(attributePaths = "mailMessage")
    Page<ParsedEmail> findAllByOrderByParsedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "mailMessage")
    Page<ParsedEmail> findByStatusOrderByParsedAtDesc(ParseStatus status, Pageable pageable);

    long countByStatus(ParseStatus status);

    /** How much is left to read, for the tab's header and for "is a sweep worth running". */
    @Query("""
            select count(m) from MailMessage m
            where m.bodyText is not null and m.bodyText <> ''
              and m.receivedAt >= :since
              and not exists (select 1 from ParsedEmail p where p.mailMessage = m)
            """)
    long countUnparsed(@Param("since") LocalDateTime since);

    @Query("select max(p.parsedAt) from ParsedEmail p")
    OffsetDateTime lastParsedAt();
}
