package com.chartering.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two desks working side by side, and everything one can reach of the other's.
 *
 * <p>The answer this file pins is "nothing": not in a list, not by id, not by writing through
 * an id it guessed, not in the change log, not in a setting. Where an id belongs to the other
 * desk the answer is 404 - the same answer as an id that does not exist at all, so a caller
 * cannot even learn that it does.
 */
class TenantIsolationTest extends IntegrationTest {

    private String north;
    private String south;

    @BeforeEach
    void twoDesks() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        north = deskWithAdmin(root, "North " + suffix, "north-" + suffix);
        south = deskWithAdmin(root, "South " + suffix, "south-" + suffix);
    }

    private String deskWithAdmin(String root, String name, String admin) throws Exception {
        JsonNode created = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", name, "adminUsername", admin))
                .andExpect(status().isCreated()));
        String temporary = created.get("admin").get("temporaryPassword").asText();
        return read(postAs(login(admin, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", admin + "-own-password")))
                .get("token").asText();
    }

    private long create(String token, String path, Object body) throws Exception {
        return read(postAs(token, path, body).andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    @Test
    void aDeskListsOnlyItsOwnCompanies() throws Exception {
        create(north, "/api/v1/companies", Map.of("name", "Northern Shipping"));
        create(south, "/api/v1/companies", Map.of("name", "Southern Chartering"));

        assertThat(names(getAs(north, "/api/v1/companies?size=200"))).contains("Northern Shipping")
                .doesNotContain("Southern Chartering");
        assertThat(names(getAs(south, "/api/v1/companies?size=200"))).contains("Southern Chartering")
                .doesNotContain("Northern Shipping");
    }

    @Test
    void anotherDesksRecordIsNotFoundToReadChangeOrDelete() throws Exception {
        long company = create(north, "/api/v1/companies", Map.of("name", "Private Owners Ltd"));

        getAs(south, "/api/v1/companies/" + company).andExpect(status().isNotFound());
        putAs(south, "/api/v1/companies/" + company, Map.of("name", "Taken over"))
                .andExpect(status().isNotFound());
        deleteAs(south, "/api/v1/companies/" + company).andExpect(status().isNotFound());

        getAs(north, "/api/v1/companies/" + company)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.name").value("Private Owners Ltd"));
    }

    @Test
    void aRecordCannotBeAttachedToAnotherDesksRecord() throws Exception {
        long company = create(north, "/api/v1/companies", Map.of("name", "Attach Target"));

        postAs(south, "/api/v1/contacts", Map.of(
                "companyId", company, "contactKind", "email", "contactValue", "desk@attach.example"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSameHullCanBeOnEveryDeskUnderHerImo() throws Exception {
        String imo = String.valueOf(9_000_000 + (int) (Math.random() * 999_999));
        create(north, "/api/v1/vessels", Map.of("name", "SHARED HULL", "imoNumber", imo));
        create(south, "/api/v1/vessels", Map.of("name", "SHARED HULL", "imoNumber", imo));
    }

    @Test
    void eachDeskHasItsOwnDefaultFooter() throws Exception {
        long northFooter = create(north, "/api/v1/email-footers",
                Map.of("name", "North block", "html", "<p>North</p>", "defaultFooter", true));
        create(south, "/api/v1/email-footers",
                Map.of("name", "South block", "html", "<p>South</p>", "defaultFooter", true));

        // Making South's footer the default cleared "the" default with a bulk update; it must
        // have cleared South's only.
        getAs(north, "/api/v1/email-footers/" + northFooter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultFooter").value(true));
    }

    @Test
    void aDesksSettingsAreItsOwn() throws Exception {
        putAs(south, "/api/v1/matches/settings", Map.of("ballastSpeedKnots", 9.5))
                .andExpect(status().isOk());
        putAs(north, "/api/v1/matches/settings", Map.of("ballastSpeedKnots", 12.0))
                .andExpect(status().isOk());

        getAs(south, "/api/v1/matches/settings").andExpect(jsonPath("$.ballastSpeedKnots").value(9.5));
        getAs(north, "/api/v1/matches/settings").andExpect(jsonPath("$.ballastSpeedKnots").value(12.0));
    }

    @Test
    void aDeskAdministratorCannotChangeASettingEveryDeskShares() throws Exception {
        putAs(north, "/api/v1/feed/settings", Map.of("contextWindowTokens", 4096))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theChangeLogShowsADeskOnlyItsOwnEdits() throws Exception {
        String marker = "Logged " + UUID.randomUUID();
        create(north, "/api/v1/companies", Map.of("name", marker));

        assertThat(getAs(north, "/api/v1/data-changes?size=200").andReturn().getResponse().getContentAsString())
                .contains(marker);
        assertThat(getAs(south, "/api/v1/data-changes?size=200").andReturn().getResponse().getContentAsString())
                .doesNotContain(marker);
    }

    @Test
    void aDeskAdministratorSeesAndManagesOnlyTheirOwnDesksAccounts() throws Exception {
        newAccount(north, "north-broker-" + UUID.randomUUID(), "USER", null);

        JsonNode southUsers = read(getAs(south, "/api/v1/admin/users").andExpect(status().isOk()));
        southUsers.forEach(u -> assertThat(u.get("username").asText()).doesNotStartWith("north-"));

        getAs(south, "/api/v1/admin/tenants").andExpect(status().isForbidden());
    }

    @Test
    void eachDeskChoosesForItselfWhichBoardsItReadsIntoIntake() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        long board = create(root, "/api/v1/feed/sources", Map.of(
                "kind", "RSS", "url", "https://board-" + UUID.randomUUID() + ".example/feed", "enabled", false));
        // A desk administrator may not edit the board itself, only their desk's use of it.
        putAs(north, "/api/v1/feed/sources/" + board, Map.of("kind", "RSS", "url", "https://elsewhere.example/feed"))
                .andExpect(status().isForbidden());

        putAs(north, "/api/v1/feed/sources/" + board + "/intake?on=true", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intoIntake").value(true));

        JsonNode southView = read(getAs(south, "/api/v1/feed/sources").andExpect(status().isOk()));
        southView.forEach(s -> {
            if (s.get("id").asLong() == board) assertThat(s.get("intoIntake").asBoolean()).isFalse();
        });
    }

    private List<String> names(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        JsonNode page = read(result.andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        page.get("content").forEach(c -> names.add(c.get("name").asText()));
        return names;
    }
}
