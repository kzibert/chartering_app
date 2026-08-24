package com.chartering.repository;

import com.chartering.model.MailReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface MailReplyRepository extends JpaRepository<MailReply, Long> {

    /** The day's replies, for the outgoing counters. */
    int countBySentAtGreaterThanEqualAndSentAtLessThan(LocalDateTime from, LocalDateTime until);

    /**
     * When this message was last replied to, for the badge on the opened message. Asked
     * only when a message is opened — the list does not carry it, because a column that
     * costs a query per page to say "no" fifty times is not worth the row it sits in.
     */
    @Query("select max(r.sentAt) from MailReply r where r.mailMessage.id = :id")
    LocalDateTime lastRepliedAt(@Param("id") Long messageId);
}
