package com.chartering.service;

import com.chartering.dto.AnalysisEmbeddingStatusResponse;
import com.chartering.it.IntegrationTest;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.tenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The index run against the real schema and the desk wall, with a stub embedding server: only READY
 * samples get a vector, and the status counts agree with what is stored.
 *
 * <p>Lives in the service package so it can call the package-private {@code runNow()}, the
 * synchronous form of the run that {@code start()} uses on its worker thread.
 */
@TestPropertySource(properties = "chartering.analysis.enabled=true")
class SampleEmbeddingIndexerIntegrationTest extends IntegrationTest {

    private static final String MODEL = "test-model";

    @MockitoBean
    private EmbeddingClient client;

    @Autowired
    private SampleEmbeddingIndexer indexer;

    @Autowired
    private SampleEmbeddingStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private long desk;

    @BeforeEach
    void stubServerAndCreateDesk() throws Exception {
        when(client.isEnabled()).thenReturn(true);
        when(client.model()).thenReturn(MODEL);
        when(client.textFor(any(), anyString())).thenAnswer(inv -> "p:" + inv.getArgument(1));
        when(client.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{t.length(), 1f, 0f}).toList();
        });

        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Index desk " + suffix, "adminUsername", "index-" + suffix))
                .andExpect(status().isCreated()));
        desk = created.get("tenant").get("id").asLong();
    }

    /** A sample on the desk bound to the thread, with the given status. */
    private long sample(String status) {
        return TenantContext.callAs(desk, () -> jdbc.queryForObject("""
                insert into analysis_samples (tenant_id, subject, body_text, status)
                values (?, 'a subject', 'a labelled email', ?) returning id
                """, Long.class, desk, status));
    }

    @Test
    void onlyReadySamplesAreEmbedded() {
        long ready1 = sample("READY");
        long ready2 = sample("READY");
        sample("NEW");
        sample("SKIPPED");

        TenantContext.runAs(desk, () -> indexer.runNow());

        Map<Long, String> stored = TenantContext.callAs(desk, () -> store.hashesFor(MODEL));
        assertThat(stored).containsOnlyKeys(ready1, ready2);
        assertThat(TenantContext.callAs(desk, () -> store.count(MODEL))).isEqualTo(2);

        AnalysisEmbeddingStatusResponse s = TenantContext.callAs(desk, () -> indexer.status());
        assertThat(s.ready()).isEqualTo(2);
        assertThat(s.indexed()).isEqualTo(2);
        assertThat(s.stale()).isZero();
        assertThat(s.running()).isFalse();
        assertThat(s.lastError()).isNull();
    }

    @Test
    void aSecondRunWritesNothingNewWhileTheTextIsUnchanged() {
        sample("READY");
        TenantContext.runAs(desk, () -> indexer.runNow());

        TenantContext.runAs(desk, () -> indexer.runNow());

        AnalysisEmbeddingStatusResponse s = TenantContext.callAs(desk, () -> indexer.status());
        assertThat(s.total()).isZero();
        assertThat(s.indexed()).isEqualTo(1);
    }
}
