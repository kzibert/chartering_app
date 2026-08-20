package com.chartering.repository;

import com.chartering.model.MailMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MailMessageRepository
        extends JpaRepository<MailMessage, Long>, JpaSpecificationExecutor<MailMessage> {

    /**
     * The paged search, with the three associations every row displays already loaded.
     *
     * <p>Overridden purely for the graph: without it, rendering a page of twenty rows costs
     * sixty extra selects for the folder, company and person names. All three are
     * many-to-one, so fetching them cannot multiply the rows and paging stays honest.
     */
    @Override
    @EntityGraph(attributePaths = {"folder", "company", "person"})
    Page<MailMessage> findAll(Specification<MailMessage> spec, Pageable pageable);

    /** The dedupe lookup: a message already stored under this Message-ID is not stored again. */
    Optional<MailMessage> findByMessageId(String messageId);

    /** Fallback identity for a message that arrived without a Message-ID header. */
    Optional<MailMessage> findByImapFolderAndImapValidityAndImapUid(
            String imapFolder, Long imapValidity, Long imapUid);

    /** Have we seen any of these Message-IDs? One query per fetch batch, not one per message. */
    @Query("select m.messageId from MailMessage m where m.messageId in :ids")
    List<String> findExistingMessageIds(@Param("ids") Collection<String> ids);

    /**
     * Unread counts per folder in one query, the null key being the Inbox. The rail shows
     * every folder's badge on every load, so this is deliberately one round trip rather than
     * one count per folder.
     *
     * <p>The left join is what keeps the Inbox in the result: an unfiled message has no
     * folder, and joining to it any other way would count every folder except the one most
     * of the mail is actually in.
     */
    @Query("""
            select f.id, count(m) from MailMessage m
              left join m.folder f
            where m.read = false
            group by f.id
            """)
    List<Object[]> countUnreadByFolder();

    /** Total messages per folder, same shape and same reason as the unread counts. */
    @Query("""
            select f.id, count(m) from MailMessage m
              left join m.folder f
            group by f.id
            """)
    List<Object[]> countByFolder();

    long countByReadFalse();

    /**
     * Messages whose sender matches one of these addresses and whose link was not set by
     * hand — the re-link pass after contacts change. Case-insensitive, because an address
     * is stored as it was written but matched as it is meant.
     */
    @Query("""
            select m from MailMessage m
            where m.linkManual = false
              and lower(m.fromAddress) in :addresses
            """)
    List<MailMessage> findAutoLinkableByFromAddresses(
            @Param("addresses") Collection<String> addresses);

    /** Every distinct sender that has no company link yet — the input to a re-link run. */
    @Query("""
            select distinct lower(m.fromAddress) from MailMessage m
            where m.linkManual = false
            """)
    List<String> findAutoLinkableSenders();

    /**
     * Clear a rule's fingerprints when it is deleted. The folder assignment stays: the mail
     * was filed, and un-filing it because the rule that filed it was tidied away would move
     * mail nobody asked to move.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailMessage m set m.filedByRuleId = null where m.filedByRuleId = :ruleId")
    void clearRuleReference(@Param("ruleId") Long ruleId);
}
