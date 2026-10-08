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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The second wall (V36): plain SQL, which Hibernate's tenant filter never sees, still meets only
 * its own desk's rows - because the database itself refuses the rest.
 *
 * <p>Plain JDBC on the application's own pool and role, exactly the path a hand-written query
 * would take. The connection is told its desk on checkout (TenantAwareDataSource) from
 * whatever is bound on the thread.
 */
class RowLevelSecurityTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long north;
    private long south;
    private String marker;

    @BeforeEach
    void aCompanyOnTheNorthDesk() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode n = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "RLS north " + suffix, "adminUsername", "rls-north-" + suffix)).andExpect(status().isCreated()));
        JsonNode s = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "RLS south " + suffix, "adminUsername", "rls-south-" + suffix)).andExpect(status().isCreated()));
        north = n.get("tenant").get("id").asLong();
        south = s.get("tenant").get("id").asLong();

        String temporary = n.get("admin").get("temporaryPassword").asText();
        String token = read(postAs(login("rls-north-" + suffix, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "rls-north-own-password"))).get("token").asText();
        marker = "RLS marker " + suffix;
        postAs(token, "/api/v1/companies", Map.of("name", marker)).andExpect(status().is2xxSuccessful());
    }

    private Integer count() {
        return jdbc.queryForObject("select count(*) from companies where name = ?", Integer.class, marker);
    }

    @Test
    void plainSqlSeesOnlyTheDeskTheConnectionWasGiven() {
        assertThat(TenantContext.callAs(north, this::count)).isEqualTo(1);
        assertThat(TenantContext.callAs(south, this::count)).isZero();
    }

    @Test
    void aConnectionWithNoDeskSeesNothingAtAll() {
        assertThat(count()).isZero();
    }

    @Test
    void plainSqlCannotWriteARowIntoAnotherDesk() {
        assertThatThrownBy(() -> TenantContext.runAs(south, () ->
                jdbc.update("insert into companies (name, tenant_id) values (?, ?)", "Planted", north)))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void noRoleOfTheApplicationIsExempt() {
        Boolean exempt = jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        assertThat(exempt).as("the test would prove nothing as a superuser").isFalse();
    }
}
