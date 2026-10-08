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

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Synced mail. Desk-scoped like everything else; <b>whose mailbox</b> a query is about is a
 * parameter here rather than a filter, and that is deliberate. A message is one person's mail
 * on the Mailbox tab and, once the parser has read a cargo or a position out of it, the source
 * the whole desk opens from that cargo - so a filter hiding other people's mail everywhere
 * would empty the desk's shared screens. The Mailbox code names the owner; everything else
 * reads the desk.
 */
public interface MailMessageRepository
        extends JpaRepository<MailMessage, Long>, JpaSpecificationExecutor<MailMessage> {

    @Override
    @EntityGraph(attributePaths = {"folder", "company", "person"})
    Page<MailMessage> findAll(Specification<MailMessage> spec, Pageable pageable);

    Optional<MailMessage> findByOwnerUserIdAndMessageId(Long ownerUserId, String messageId);

    Optional<MailMessage> findByOwnerUserIdAndImapFolderAndImapValidityAndImapUid(
            Long ownerUserId, String imapFolder, Long imapValidity, Long imapUid);

    @Query("select m.messageId from MailMessage m where m.ownerUserId = :owner and m.messageId in :ids")
    List<String> findExistingMessageIds(@Param("owner") Long owner, @Param("ids") Collection<String> ids);

    @Query("""
            select f.id, count(m) from MailMessage m
              left join m.folder f
            where m.ownerUserId = :owner and m.read = false
            group by f.id
            """)
    List<Object[]> countUnreadByFolder(@Param("owner") Long owner);

    @Query("""
            select f.id, count(m) from MailMessage m
              left join m.folder f
            where m.ownerUserId = :owner
            group by f.id
            """)
    List<Object[]> countByFolder(@Param("owner") Long owner);

    @Query("select m.imapFolder, count(m) from MailMessage m where m.ownerUserId = :owner group by m.imapFolder")
    List<Object[]> countByImapFolder(@Param("owner") Long owner);

    @Query("""
            select m.imapFolder, count(m) from MailMessage m
            where m.ownerUserId = :owner and m.read = false
            group by m.imapFolder
            """)
    List<Object[]> countUnreadByImapFolder(@Param("owner") Long owner);

    long countByOwnerUserId(Long ownerUserId);

    long countByOwnerUserIdAndReadFalse(Long ownerUserId);

    long countByOwnerUserIdAndImapFolderAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
            Long ownerUserId, String imapFolder, LocalDateTime from, LocalDateTime until);

    /**
     * Whether a message is the source of something the desk shares - a cargo, a position, a
     * question on Intake, an example in the corpus. Those are the messages a colleague may open
     * from that record; anything else in somebody's mailbox stays theirs.
     */
    @Query("""
            select count(m) > 0 from MailMessage m where m.id = :id and (
                 exists (select 1 from CargoSource s where s.mailMessage = m)
              or exists (select 1 from Cargo c where c.sourceMailMessage = m)
              or exists (select 1 from VesselPosition p where p.sourceMailMessage = m)
              or exists (select 1 from IntakeItemSource i where i.mailMessage = m)
              or exists (select 1 from IntakeItem i where i.parsedEmail.mailMessage = m)
              or exists (select 1 from AnalysisSample a where a.mailMessage = m))
            """)
    boolean isSharedSource(@Param("id") Long id);

    /** Auto-linking reads the whole desk's mail: which firm a sender is, is the desk's fact. */
    @Query("""
            select m from MailMessage m
            where m.linkManual = false
              and lower(m.fromAddress) in :addresses
            """)
    List<MailMessage> findAutoLinkableByFromAddresses(
            @Param("addresses") Collection<String> addresses);

    @Query("""
            select distinct lower(m.fromAddress) from MailMessage m
            where m.linkManual = false
            """)
    List<String> findAutoLinkableSenders();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailMessage m set m.filedByRuleId = null where m.filedByRuleId = :ruleId")
    void clearRuleReference(@Param("ruleId") Long ruleId);
}
