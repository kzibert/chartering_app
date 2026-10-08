package com.chartering.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

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
 * <p>Requests go through MockMvc with the real security filter chain, holding real tokens
 * issued by the real login - so what is under test is what a caller can actually reach, not
 * what a service method returns when called directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
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
