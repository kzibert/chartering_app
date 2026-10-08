package com.chartering.it;

import com.chartering.tenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Who a circular is from, and whose Brevo account carries it, are the sender's own.
 *
 * <p>The regression this pins: a person on a new desk opened Settings and found the server
 * mailbox's From address and SMTP host on the Circulations card as if they were theirs, and the
 * only Brevo key there was the default desk's.
 */
class PersonalSendingIdentityTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String anna;
    private String boris;
    private long annaId;

    @BeforeEach
    void twoPeopleOnANewDesk() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode desk = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Sending desk " + suffix, "adminUsername", "anna-s-" + suffix))
                .andExpect(status().isCreated()));
        String temporary = desk.get("admin").get("temporaryPassword").asText();
        annaId = desk.get("admin").get("user").get("id").asLong();
        anna = read(postAs(login("anna-s-" + suffix, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "anna-own-password")))
                .get("token").asText();
        boris = newAccount(anna, "boris-s-" + suffix, "USER", null);
    }

    @Test
    void somebodyWithNoMailboxIsShownNoFromRatherThanTheServers() throws Exception {
        getAs(anna, "/api/v1/settings/circulation")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identitySource").value("NONE"))
                .andExpect(jsonPath("$.fromAddress").doesNotExist())
                .andExpect(jsonPath("$.smtpHost").doesNotExist())
                .andExpect(jsonPath("$.defaults.fromAddress").doesNotExist())
                .andExpect(jsonPath("$.brevoConfigured").value(false));
    }

    @Test
    void aBrevoKeyIsStoredEncryptedAndIsItsOwnersAlone() throws Exception {
        getAs(anna, "/api/v1/me/brevo-account").andExpect(jsonPath("$.source").value("NONE"));

        String saved = putAs(anna, "/api/v1/me/brevo-account", Map.of(
                "apiKey", "xkeysib-anna-brevo-secret-7f3a", "senderAddress", "anna@desk.example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PERSONAL"))
                .andExpect(jsonPath("$.keyHint").value("...7f3a"))
                .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain("anna-brevo-secret");

        String stored = TenantContext.callAs(annasDesk(), () -> jdbc.queryForObject(
                "select api_key_enc from brevo_accounts where user_id = ?", String.class, annaId));
        assertThat(stored).startsWith("v1:").doesNotContain("anna-brevo-secret");

        putAs(anna, "/api/v1/settings/circulation/provider", Map.of("useBrevo", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brevoConfigured").value(true))
                .andExpect(jsonPath("$.identitySource").value("BREVO"))
                .andExpect(jsonPath("$.fromAddress").value("anna@desk.example"));

        // The provider is the desk's choice; the key and the sender are not. Boris is on Brevo
        // too now, and has neither.
        getAs(boris, "/api/v1/me/brevo-account").andExpect(jsonPath("$.source").value("NONE"));
        getAs(boris, "/api/v1/settings/circulation")
                .andExpect(jsonPath("$.provider").value("BREVO"))
                .andExpect(jsonPath("$.brevoConfigured").value(false))
                .andExpect(jsonPath("$.fromAddress").doesNotExist());
    }

    @Test
    void savingPacingDoesNotFileAFromAgainstTheDesk() throws Exception {
        putAs(anna, "/api/v1/settings/circulation", Map.of(
                "fromAddress", "planted@desk.example", "fromName", "Planted", "smtpHost", "smtp.planted.example",
                "smtpPort", 587, "minDelayMs", 1000, "maxDelayMs", 2000,
                "maxRecipientsPerCampaign", 40, "batchPauseMs", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRecipientsPerCampaign").value(40))
                .andExpect(jsonPath("$.fromAddress").doesNotExist());

        getAs(boris, "/api/v1/settings/circulation")
                .andExpect(jsonPath("$.maxRecipientsPerCampaign").value(40))
                .andExpect(jsonPath("$.fromAddress").doesNotExist())
                .andExpect(jsonPath("$.smtpHost").doesNotExist());
    }

    private Long annasDesk() {
        return jdbc.queryForObject("select tenant_id from users where id = ?", Long.class, annaId);
    }
}
