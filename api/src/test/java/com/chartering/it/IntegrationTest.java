package com.chartering.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application against a Postgres built from empty by every migration in this build.
 *
 * <p>One container for every test class, started once and never stopped by the tests (the
 * Testcontainers reaper removes it when the JVM exits): the Spring context is cached across
 * classes with the same configuration, and a context outliving its database would be a
 * context pointed at nothing.
 *
 * <p><b>The application connects as an ordinary role, not as the container's superuser</b>, and
 * owns the schema it migrates - the shape of a hosted database, where the owner role is not a
 * superuser. A superuser bypasses row-level security outright, so connecting as one would let
 * V36's policies pass every test without ever being consulted.
 *
 * <p>Requests go through MockMvc with the real security filter chain, holding real tokens
 * issued by the real login - so what is under test is what a caller can actually reach, not
 * what a service method returns when called directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    // pgvector's image, not plain postgres: V38 needs the vector extension, and a hosted database
    // ships it as the provider installs it. asCompatibleSubstituteFor keeps Testcontainers treating
    // it as a PostgreSQL container (the JDBC URL, the default user and database).
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final String APP_ROLE = "chartering_app";
    static final String APP_PASSWORD = "chartering-app-password";

    static {
        POSTGRES.start();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            // Extensions need a superuser; a hosted database ships with the ones V1 asks for,
            // as the provider installs them, and this does the same.
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            // pgvector too (V38). The migration's own CREATE EXTENSION IF NOT EXISTS is then a
            // no-op, because the application role cannot create extensions and must not need to.
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("CREATE ROLE " + APP_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'");
            st.execute("ALTER SCHEMA public OWNER TO " + APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the application role", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    protected static final String ROOT = "root";
    protected static final String ROOT_PASSWORD = "root-password-for-tests";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    protected ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    protected ResultActions getAs(String token, String path) throws Exception {
        return as(token, get(path));
    }

    protected ResultActions postAs(String token, String path, Object body) throws Exception {
        return as(token, post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions putAs(String token, String path, Object body) throws Exception {
        return as(token, put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions deleteAs(String token, String path) throws Exception {
        return as(token, delete(path));
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /**
     * An account with a password of its own, ready to work: created by {@code adminToken},
     * logged in once with the one-time password, and past the forced change.
     */
    protected String newAccount(String adminToken, String username, String role, Long tenantId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("username", username, "role", role));
        if (tenantId != null) body.put("tenantId", tenantId);
        String temporary = read(postAs(adminToken, "/api/v1/admin/users", body)
                .andExpect(status().isCreated()))
                .get("temporaryPassword").asText();
        return read(postAs(login(username, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", username + "-own-password")))
                .get("token").asText();
    }
}
