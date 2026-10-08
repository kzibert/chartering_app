package com.chartering.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Accounts end to end: the first one from the environment, the rest made by an administrator. */
class AuthFlowTest extends IntegrationTest {

    @Test
    void theEnvironmentCredentialBecomesAPlatformAdministratorOnTheDefaultDesk() throws Exception {
        String token = login(ROOT, ROOT_PASSWORD);

        getAs(token, "/api/v1/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.tenantId").value(1))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void anAccountAnAdministratorMadeMustChooseItsOwnPasswordBeforeDoingAnythingElse() throws Exception {
        String admin = login(ROOT, ROOT_PASSWORD);
        String username = "broker-" + UUID.randomUUID();

        JsonNode created = read(postAs(admin, "/api/v1/admin/users",
                Map.of("username", username, "role", "USER"))
                .andExpect(status().isCreated()));
        String temporary = created.get("temporaryPassword").asText();

        String first = login(username, temporary);
        getAs(first, "/api/v1/companies").andExpect(status().isForbidden());
        getAs(first, "/api/v1/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true));

        String second = read(postAs(first, "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "a phrase only I know"))
                .andExpect(status().isOk()))
                .get("token").asText();

        getAs(second, "/api/v1/companies").andExpect(status().isOk());
        // The token the change was made with is revoked by it.
        getAs(first, "/api/v1/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAnAccountEndsItsSessionAtOnce() throws Exception {
        String admin = login(ROOT, ROOT_PASSWORD);
        String username = "leaver-" + UUID.randomUUID();
        JsonNode created = read(postAs(admin, "/api/v1/admin/users",
                Map.of("username", username, "role", "USER", "password", "chosen by the admin")));
        long id = created.get("user").get("id").asLong();

        String token = login(username, "chosen by the admin");
        getAs(token, "/api/v1/auth/me").andExpect(status().isOk());

        postAs(admin, "/api/v1/admin/users/" + id + "/disable", Map.of()).andExpect(status().isOk());

        getAs(token, "/api/v1/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void anOrdinaryAccountCannotReachTheAdminScreens() throws Exception {
        String admin = login(ROOT, ROOT_PASSWORD);
        String token = newAccount(admin, "plain-" + UUID.randomUUID(), "USER", null);

        getAs(token, "/api/v1/admin/users").andExpect(status().isForbidden());
        getAs(token, "/api/v1/companies").andExpect(status().isOk());
    }
}
