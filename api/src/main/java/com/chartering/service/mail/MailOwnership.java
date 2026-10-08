package com.chartering.service.mail;

import com.chartering.model.AppUser;
import com.chartering.model.Tenant;
import com.chartering.repository.AppUserRepository;
import com.chartering.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Hands the mailbox that existed before accounts did to the account it belongs to.
 *
 * <p>Every message, folder, rule, cursor and reply synced or written before V32 has no owner:
 * there was one mailbox and nobody to own it. They are the server mailbox's, so they go to its
 * owner ({@code MAILBOX_OWNER}) - or, where that names nobody, to the default desk's first
 * account, which on an upgraded installation is the person who was using it. Until this runs
 * those rows are on nobody's Mailbox tab, which is why it runs at startup, after the first
 * account exists (UserBootstrap is ordered first).
 *
 * <p>Runs as the default desk with no person bound, so the owner filter reads the whole desk
 * and the update can reach rows nobody owns yet. Idempotent: once claimed there is nothing
 * left with a null owner, and the queries match nothing.
 */
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class MailOwnership implements ApplicationRunner {

    private static final List<String> OWNED = List.of(
            "MailMessage", "MailFolder", "MailRule", "MailServerFolder", "MailSyncState", "MailReply");

    private final MailAccounts accounts;
    private final AppUserRepository users;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        Optional<Long> owner = accounts.environmentOwner()
                .filter(u -> u.getTenant().getId() == Tenant.DEFAULT_ID)
                .map(AppUser::getId)
                .or(() -> users.findByTenant(Tenant.DEFAULT_ID).stream()
                        .map(AppUser::getId).min(Long::compare));
        if (owner.isEmpty()) return;

        TenantContext.runAs(Tenant.DEFAULT_ID, () -> transactions.executeWithoutResult(status -> {
            int claimed = 0;
            for (String entity : OWNED) {
                claimed += entityManager
                        .createQuery("update " + entity + " e set e.ownerUserId = :owner where e.ownerUserId is null")
                        .setParameter("owner", owner.get())
                        .executeUpdate();
            }
            if (claimed > 0) {
                log.info("Mail ownership: {} row(s) from before accounts now belong to user {}", claimed, owner.get());
            }
        }));
    }
}
