package com.chartering.it;

import com.chartering.service.SampleEmbeddingStore;
import com.chartering.service.SampleEmbeddingStore.Neighbour;
import com.chartering.tenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The retrieval store (V38) against a real pgvector: nearest-first ordering, exclusion, the READY
 * filter, model isolation and the desk wall. Three-dimensional vectors so the expected order can
 * be worked out by hand.
 *
 * <p>Fixtures are inserted as plain SQL on the application's role, inside the desk they belong
 * to, the same way a sample is written by the capture path.
 */
class SampleEmbeddingStoreTest extends IntegrationTest {

    private static final String MODEL = "test-model";

    @Autowired
    private SampleEmbeddingStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private long desk;
    private long otherDesk;

    @BeforeEach
    void twoDesks() throws Exception {
        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode mine = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Embed desk " + suffix, "adminUsername", "embed-a-" + suffix))
                .andExpect(status().isCreated()));
        JsonNode theirs = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Embed other " + suffix, "adminUsername", "embed-b-" + suffix))
                .andExpect(status().isCreated()));
        desk = mine.get("tenant").get("id").asLong();
        otherDesk = theirs.get("tenant").get("id").asLong();
    }

    /** A labelled sample on the desk bound to the thread, with the given status. */
    private long sample(long tenantId, String status) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForObject("""
                insert into analysis_samples (tenant_id, body_text, status)
                values (?, 'a labelled email', ?) returning id
                """, Long.class, tenantId, status));
    }

    private void embed(long sampleId, String model, float... v) {
        TenantContext.runAs(desk, () -> store.upsert(sampleId, model, "hash-" + sampleId, v));
    }

    private List<Long> ids(List<Neighbour> neighbours) {
        return neighbours.stream().map(Neighbour::sampleId).toList();
    }

    @Test
    void theClosestSampleComesFirst() {
        long a = sample(desk, "READY");
        long b = sample(desk, "READY");
        long c = sample(desk, "READY");
        embed(a, MODEL, 1f, 0f, 0f);
        embed(b, MODEL, 0f, 1f, 0f);
        embed(c, MODEL, 0.9f, 0.1f, 0f);

        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 3, List.of()));

        // Same direction, then nearly the same direction, then orthogonal.
        assertThat(ids(found)).containsExactly(a, c, b);
        assertThat(found.get(0).distance()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void kLimitsTheAnswer() {
        long a = sample(desk, "READY");
        long b = sample(desk, "READY");
        embed(a, MODEL, 1f, 0f, 0f);
        embed(b, MODEL, 0f, 1f, 0f);

        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 1, List.of()));

        assertThat(ids(found)).containsExactly(a);
    }

    @Test
    void excludedSamplesAreNotReturned() {
        long a = sample(desk, "READY");
        long c = sample(desk, "READY");
        long b = sample(desk, "READY");
        embed(a, MODEL, 1f, 0f, 0f);
        embed(c, MODEL, 0.9f, 0.1f, 0f);
        embed(b, MODEL, 0f, 1f, 0f);

        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 3, List.of(a)));

        assertThat(ids(found)).containsExactly(c, b);
    }

    @Test
    void aSampleThatIsNotReadyIsNeverANeighbour() {
        long ready = sample(desk, "READY");
        long unlabelled = sample(desk, "NEW");
        long skipped = sample(desk, "SKIPPED");
        embed(ready, MODEL, 0f, 1f, 0f);
        // The two closest vectors belong to samples nobody has judged fit to train on.
        embed(unlabelled, MODEL, 1f, 0f, 0f);
        embed(skipped, MODEL, 1f, 0f, 0f);

        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 3, List.of()));

        assertThat(ids(found)).containsExactly(ready);
    }

    @Test
    void anotherModelsVectorsAreIgnored() {
        long mine = sample(desk, "READY");
        long theirs = sample(desk, "READY");
        embed(mine, MODEL, 0f, 1f, 0f);
        embed(theirs, "other-model", 1f, 0f, 0f);

        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 3, List.of()));

        assertThat(ids(found)).containsExactly(mine);
        assertThat(TenantContext.callAs(desk, () -> store.count(MODEL))).isEqualTo(1);
        assertThat(TenantContext.callAs(desk, () -> store.hashesFor(MODEL)))
                .containsOnlyKeys(mine);
    }

    @Test
    void anUpsertReplacesTheVectorAndItsHash() {
        long a = sample(desk, "READY");
        embed(a, MODEL, 0f, 1f, 0f);
        TenantContext.runAs(desk, () -> store.upsert(a, MODEL, "changed-text-hash", new float[]{1f, 0f, 0f}));

        assertThat(TenantContext.callAs(desk, () -> store.count(MODEL))).isEqualTo(1);
        assertThat(TenantContext.callAs(desk, () -> store.hashesFor(MODEL)))
                .containsEntry(a, "changed-text-hash");
        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 1, List.of()));
        assertThat(ids(found)).containsExactly(a);
    }

    @Test
    void deleteOtherModelsKeepsOnlyTheCurrentModel() {
        long a = sample(desk, "READY");
        long b = sample(desk, "READY");
        embed(a, MODEL, 1f, 0f, 0f);
        embed(b, "old-model", 0f, 1f, 0f);

        int removed = TenantContext.callAs(desk, () -> store.deleteOtherModels(MODEL));

        assertThat(removed).isEqualTo(1);
        assertThat(TenantContext.callAs(desk, () -> store.hashesFor("old-model"))).isEmpty();
        assertThat(TenantContext.callAs(desk, () -> store.count(MODEL))).isEqualTo(1);
    }

    @Test
    void anotherDesksVectorsAreInvisible() {
        long mine = sample(desk, "READY");
        long theirs = sample(otherDesk, "READY");
        embed(mine, MODEL, 0f, 1f, 0f);
        TenantContext.runAs(otherDesk, () -> store.upsert(theirs, MODEL, "hash-theirs", new float[]{1f, 0f, 0f}));

        List<Neighbour> found = TenantContext.callAs(desk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 3, List.of()));
        assertThat(ids(found)).containsExactly(mine);
        assertThat(TenantContext.callAs(desk, () -> store.count(MODEL))).isEqualTo(1);

        List<Neighbour> seenFromOther = TenantContext.callAs(otherDesk, () ->
                store.nearest(new float[]{1f, 0f, 0f}, MODEL, 3, List.of()));
        assertThat(ids(seenFromOther)).containsExactly(theirs);
    }

    @Test
    void aDeskCannotWriteAnotherDesksSampleVector() {
        long theirs = sample(otherDesk, "READY");

        // The sample is another desk's: invisible from here, so the store writes nothing and says so.
        assertThatThrownBy(() -> TenantContext.runAs(desk, () ->
                store.upsert(theirs, MODEL, "hash", new float[]{1f, 0f, 0f})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on this desk");
        assertThat(TenantContext.callAs(otherDesk, () -> store.count(MODEL))).isZero();
    }

    @Test
    void nothingBoundMeansNothingFound() {
        long a = sample(desk, "READY");
        embed(a, MODEL, 1f, 0f, 0f);

        // No desk on the thread: the store refuses rather than reading across desks.
        assertThatThrownBy(() -> store.count(MODEL)).isInstanceOf(IllegalStateException.class);
    }
}
