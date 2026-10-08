package com.chartering.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The current list is each person's scratch pad; a saved list is the desk's.
 */
class PersonalCirculationTest extends IntegrationTest {

    private String anna;
    private String boris;

    @BeforeEach
    void twoPeopleOnOneDesk() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode desk = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Circular desk " + suffix, "adminUsername", "anna-c-" + suffix))
                .andExpect(status().isCreated()));
        String temporary = desk.get("admin").get("temporaryPassword").asText();
        anna = read(postAs(login("anna-c-" + suffix, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "anna-own-password")))
                .get("token").asText();
        boris = newAccount(anna, "boris-c-" + suffix, "USER", null);
    }

    private long currentListId(String token) throws Exception {
        return read(getAs(token, "/api/v1/circulation-lists/current").andExpect(status().isOk())).get("id").asLong();
    }

    @Test
    void eachPersonCollectsIntoTheirOwnCurrentList() throws Exception {
        long annas = currentListId(anna);
        long boriss = currentListId(boris);
        assertThat(annas).isNotEqualTo(boriss);

        postAs(anna, "/api/v1/circulation-lists/" + annas + "/entries",
                List.of(Map.of("email", "owner@anna.example"))).andExpect(status().isOk());

        assertThat(getAs(boris, "/api/v1/circulation-lists/current").andReturn().getResponse().getContentAsString())
                .doesNotContain("owner@anna.example");
        // Nor can Boris reach Anna's pad by its id.
        getAs(boris, "/api/v1/circulation-lists/" + annas).andExpect(status().isNotFound());
        postAs(boris, "/api/v1/circulation-lists/" + annas + "/entries",
                List.of(Map.of("email", "sneaky@boris.example"))).andExpect(status().isNotFound());
    }

    @Test
    void aSavedListIsTheDesks() throws Exception {
        long annas = currentListId(anna);
        postAs(anna, "/api/v1/circulation-lists/" + annas + "/entries",
                List.of(Map.of("email", "desk@shared.example"))).andExpect(status().isOk());
        long saved = read(postAs(anna, "/api/v1/circulation-lists/" + annas + "/copy",
                Map.of("name", "Handy owners " + UUID.randomUUID()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();

        getAs(boris, "/api/v1/circulation-lists/" + saved)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(false));
    }

    @Test
    void anotherPersonsSenderIsIdleOnMyScreen() throws Exception {
        getAs(boris, "/api/v1/campaigns/current")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
    }
}
