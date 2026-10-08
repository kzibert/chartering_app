package com.chartering.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.chartering.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two people on one desk, and what each can reach of the other's mailbox.
 *
 * <p>The desk's market data is shared; a mailbox is not. A colleague's mail is invisible on the
 * Mailbox tab and answers 404 by id - except a message the desk shares as the source of a
 * record, which a colleague may open from that record to read, and still not mark or answer.
 */
class PersonalMailboxTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String anna;
    private String boris;
    private long annaId;

    @BeforeEach
    void twoPeopleOnOneDesk() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode desk = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Mail desk " + suffix, "adminUsername", "anna-" + suffix))
                .andExpect(status().isCreated()));
        String temporary = desk.get("admin").get("temporaryPassword").asText();
        annaId = desk.get("admin").get("user").get("id").asLong();
        anna = read(postAs(login("anna-" + suffix, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "anna-own-password")))
                .get("token").asText();
        boris = newAccount(anna, "boris-" + suffix, "USER", null);
    }

    /** What a sync would have stored in Anna's mailbox: written directly, there is no IMAP here. */
    private long annasMessage(String subject) {
        return asAnnasDesk(() -> jdbc.queryForObject("""
                insert into mail_messages (tenant_id, owner_user_id, message_id, subject, from_address)
                values (?, ?, ?, ?, 'broker@example.test') returning id
                """, Long.class, annasDesk(), annaId, "<" + UUID.randomUUID() + "@example.test>", subject));
    }

    private Long annasDesk() {
        return jdbc.queryForObject("select tenant_id from users where id = ?", Long.class, annaId);
    }

    /** Direct SQL is subject to row-level security like the application's own, so it names the desk. */
    private <T> T asAnnasDesk(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(annasDesk(), work);
    }

    @Test
    void eachPersonSeesOnlyTheirOwnMailbox() throws Exception {
        long message = annasMessage("Open tonnage for Anna");

        assertThat(getAs(anna, "/api/v1/mailbox/messages").andReturn().getResponse().getContentAsString())
                .contains("Open tonnage for Anna");
        assertThat(getAs(boris, "/api/v1/mailbox/messages").andReturn().getResponse().getContentAsString())
                .doesNotContain("Open tonnage for Anna");

        getAs(boris, "/api/v1/mailbox/messages/" + message + "?markRead=false").andExpect(status().isNotFound());
        getAs(anna, "/api/v1/mailbox/messages/" + message).andExpect(status().isOk());
    }

    @Test
    void aColleagueMayReadAMessageTheDeskSharesAsASourceButNotMarkOrAnswerIt() throws Exception {
        long message = annasMessage("25,000 mt wheat Chornomorsk / Spain Med");
        long cargo = read(postAs(anna, "/api/v1/cargoes", Map.of("commodity", "Wheat"))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        asAnnasDesk(() -> jdbc.update("update cargoes set source_mail_message_id = ? where id = ?", message, cargo));

        getAs(boris, "/api/v1/mailbox/messages/" + message + "?markRead=false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.subject").value("25,000 mt wheat Chornomorsk / Spain Med"));
        getAs(boris, "/api/v1/mailbox/messages/" + message + "?markRead=true").andExpect(status().isNotFound());
        postAs(boris, "/api/v1/mailbox/messages/" + message + "/reply",
                Map.of("to", "broker@example.test", "subject", "Re: wheat", "bodyHtml", "<p>Interested</p>"))
                .andExpect(status().isNotFound());
    }

    @Test
    void foldersArePersonalAndTwoPeopleMayUseTheSameName() throws Exception {
        postAs(anna, "/api/v1/mailbox/folders", Map.of("name", "Brokers")).andExpect(status().is2xxSuccessful());
        postAs(boris, "/api/v1/mailbox/folders", Map.of("name", "Brokers")).andExpect(status().is2xxSuccessful());

        JsonNode borisFolders = read(getAs(boris, "/api/v1/mailbox/folders").andExpect(status().isOk()));
        // His own "Brokers" beside the built-in Inbox entry; Anna's is not there.
        long brokers = 0;
        for (JsonNode f : borisFolders) if ("Brokers".equals(f.get("name").asText())) brokers++;
        assertThat(brokers).isEqualTo(1);
    }

    @Test
    void aSavedMailboxPasswordIsStoredEncryptedAndNeverReturned() throws Exception {
        getAs(boris, "/api/v1/me/mail-account").andExpect(jsonPath("$.source").value("NONE"));

        putAs(boris, "/api/v1/me/mail-account", Map.of(
                "emailAddress", "boris@example.test", "imapHost", "imap.example.test", "imapPort", 993,
                "smtpHost", "smtp.example.test", "smtpPort", 465, "password", "boris-mail-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PERSONAL"))
                .andExpect(jsonPath("$.passwordSet").value(true));

        String stored = asAnnasDesk(() -> jdbc.queryForObject(
                "select password_enc from mail_accounts where email_address = 'boris@example.test'", String.class));
        assertThat(stored).startsWith("v1:").doesNotContain("boris-mail-secret");
        assertThat(getAs(boris, "/api/v1/me/mail-account").andReturn().getResponse().getContentAsString())
                .doesNotContain("boris-mail-secret");

        // Saved, the mailbox is what the Mailbox tab reports as Boris's.
        getAs(boris, "/api/v1/mailbox/status").andExpect(jsonPath("$.username").value("boris@example.test"));
    }
}
