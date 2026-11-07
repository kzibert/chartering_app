package com.chartering.service.ask;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The index and the questions it answers, against a real pgvector and the application's own
 * row-level security role.
 *
 * <p>Vectors are all the same, so the vector side ranks every passage equally and the keyword side
 * decides what is found: the question is the word "adriatic", which is in every fixture that should
 * be findable and in none that should not. What is under test is who may see what, and which
 * sources the indexer picks up - not how close two embeddings are.
 *
 * <p>Lives in the service package because the run is {@code runNow()}, the synchronous form the
 * worker's {@code start()} uses.
 */
@TestPropertySource(properties = "chartering.parser.enabled=true")
class SearchIndexIntegrationTest extends IntegrationTest {

    private static final String MODEL = "search-test-model";
    private static final float[] QUERY = {1f, 0f, 0f};

    @MockitoBean
    private EmbeddingClient client;

    @Autowired
    private SearchIndexService indexer;

    @Autowired
    private SearchChunkStore store;

    @Autowired
    private AskRetriever retriever;

    @Autowired
    private JdbcTemplate jdbc;

    private long desk;
    private long otherDesk;
    private long alice;
    private long bob;

    @BeforeEach
    void aDeskWithTwoPeopleAndAnotherDesk() throws Exception {
        when(client.isEnabled()).thenReturn(true);
        when(client.model()).thenReturn(MODEL);
        when(client.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{1f, 0f, 0f}).toList();
        });

        String root = login(ROOT, ROOT_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode mine = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Ask desk " + suffix, "adminUsername", "alice-" + suffix))
                .andExpect(status().isCreated()));
        desk = mine.get("tenant").get("id").asLong();
        alice = mine.get("admin").get("user").get("id").asLong();
        String temporary = mine.get("admin").get("temporaryPassword").asText();
        String aliceToken = read(postAs(login("alice-" + suffix, temporary), "/api/v1/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "alice-own-password")))
                .get("token").asText();
        newAccount(aliceToken, "bob-" + suffix, "USER", null);
        bob = jdbc.queryForObject("select id from users where username = ?", Long.class, "bob-" + suffix);

        otherDesk = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Ask other " + suffix, "adminUsername", "other-" + suffix))
                .andExpect(status().isCreated())).get("tenant").get("id").asLong();
    }

    // ------------------------------------------------------------------ fixtures

    private long mail(long owner, String folder, String subject, String body) {
        return TenantContext.callAs(desk, () -> jdbc.queryForObject("""
                insert into mail_messages (tenant_id, owner_user_id, imap_folder, from_address, subject, body_text)
                values (?, ?, ?, 'broker@example.test', ?, ?) returning id
                """, Long.class, desk, owner, folder, subject, body));
    }

    private long cargo(String commodity) {
        return TenantContext.callAs(desk, () -> jdbc.queryForObject("""
                insert into cargoes (tenant_id, commodity, quantity, quantity_unit)
                values (?, ?, 25000, 'MT') returning id
                """, Long.class, desk, commodity));
    }

    /** The message is the source of the cargo, which is what makes the desk share it. */
    private void shareWithTheDesk(long mailId, long cargoId) {
        TenantContext.runAs(desk, () -> jdbc.update(
                "insert into cargo_sources (tenant_id, cargo_id, mail_message_id) values (?, ?, ?)",
                desk, cargoId, mailId));
    }

    private void index() {
        TenantContext.runAs(desk, () -> indexer.runNow());
    }

    private List<AskRetriever.Hit> askAs(long tenant, long user) {
        return TenantContext.callAs(tenant, () -> retriever.search("adriatic", QUERY, MODEL, user));
    }

    private List<String> seenBy(long user) {
        return askAs(desk, user).stream().map(AskRetriever.Hit::content).toList();
    }

    private List<Long> pending(SourceKind kind) {
        return TenantContext.callAs(desk, () -> store.pendingIds(kind, MODEL, 1_000));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aPersonSeesTheirOwnMailAndTheMailTheDeskShares_andNoOthers() {
        TenantContext.runAs(desk, () -> jdbc.update("""
                insert into mail_server_folders (tenant_id, owner_user_id, full_name, display_name, special_use)
                values (?, ?, 'Junk', 'Junk', 'JUNK')
                """, desk, alice));
        mail(alice, "INBOX", "Private", "adriatic private note");
        long shared = mail(alice, "INBOX", "Shared", "adriatic shared note");
        shareWithTheDesk(shared, cargo("Wheat"));
        mail(bob, "INBOX", "Bob", "adriatic bob note");
        mail(alice, "Junk", "Spam", "adriatic junk note");

        index();

        assertThat(seenBy(alice)).anyMatch(c -> c.contains("private note"))
                .anyMatch(c -> c.contains("shared note"))
                .noneMatch(c -> c.contains("bob note"))
                .noneMatch(c -> c.contains("junk note"));
        assertThat(seenBy(bob)).anyMatch(c -> c.contains("bob note"))
                .anyMatch(c -> c.contains("shared note"))
                .noneMatch(c -> c.contains("private note"))
                .noneMatch(c -> c.contains("junk note"));
    }

    @Test
    void anotherDeskSeesNothingOfThisOne() {
        mail(alice, "INBOX", "Ours", "adriatic desk note");
        index();

        long mine = TenantContext.callAs(desk, () -> jdbc.queryForObject(
                "select count(*) from search_chunks", Long.class));
        long theirs = TenantContext.callAs(otherDesk, () -> jdbc.queryForObject(
                "select count(*) from search_chunks", Long.class));

        assertThat(mine).isPositive();
        assertThat(theirs).isZero();
        assertThat(askAs(otherDesk, alice)).isEmpty();
    }

    /**
     * A board post the desk reads into Intake, and a vessel's open position: the two kinds the other
     * tests leave out, so their text is read and rendered by the indexer too. Both are the whole desk's.
     */
    @Test
    void aBoardPostAndAVesselPositionAreIndexedForTheWholeDesk() {
        long source = jdbc.queryForObject("""
                insert into feed_sources (name, kind, url) values ('Board test', 'RSS', 'https://feeds.example.test/board')
                returning id
                """, Long.class);
        jdbc.update("""
                insert into feed_items (source_id, external_id, title, text, content_hash, published_at)
                values (?, 'post-1', 'Open list', 'adriatic board post text, handysize open', ?, now())
                """, source, "h".repeat(64));
        TenantContext.runAs(desk, () -> jdbc.update("""
                insert into feed_intake_subscriptions (tenant_id, feed_source_id) values (?, ?)
                """, desk, source));
        long vessel = TenantContext.callAs(desk, () -> jdbc.queryForObject(
                "insert into vessels (tenant_id, name) values (?, 'ADRIATIC STAR') returning id", Long.class, desk));
        TenantContext.runAs(desk, () -> jdbc.update("""
                insert into vessel_positions (tenant_id, vessel_id, open_text, reported_at)
                values (?, ?, 'adriatic open 1/3 sept', now())
                """, desk, vessel));

        index();

        assertThat(pending(SourceKind.POST)).isEmpty();
        assertThat(pending(SourceKind.POSITION)).isEmpty();
        assertThat(seenBy(bob)).anyMatch(c -> c.contains("handysize open"))
                .anyMatch(c -> c.contains("Vessel: ADRIATIC STAR") && c.contains("open 1/3 sept"));
    }

    @Test
    void theIndexerFindsNewAndChangedSourcesByIdAndPrunesWhatIsGone() {
        long kept = mail(alice, "INBOX", "Kept", "adriatic kept note");
        long gone = mail(alice, "INBOX", "Gone", "adriatic gone note");
        long wheat = cargo("Wheat");
        index();

        assertThat(pending(SourceKind.MAIL)).isEmpty();
        assertThat(pending(SourceKind.CARGO)).isEmpty();

        // New mail is pending by id, and only that mail.
        long fresh = mail(alice, "INBOX", "Fresh", "adriatic fresh note");
        assertThat(pending(SourceKind.MAIL)).containsExactly(fresh);

        // A changed cargo is pending again, and an untouched one is not.
        TenantContext.runAs(desk, () -> jdbc.update(
                "update cargoes set updated_at = now() + interval '1 minute' where id = ?", wheat));
        assertThat(pending(SourceKind.CARGO)).containsExactly(wheat);

        index();
        assertThat(pending(SourceKind.MAIL)).isEmpty();
        assertThat(pending(SourceKind.CARGO)).isEmpty();
        assertThat(seenBy(alice)).anyMatch(c -> c.contains("fresh note"));

        // A message deleted from the mirror has its passages pruned by the next run.
        TenantContext.runAs(desk, () -> jdbc.update("delete from mail_messages where id = ?", gone));
        index();
        long goneChunks = TenantContext.callAs(desk, () -> jdbc.queryForObject("""
                select count(*) from search_chunks where source_kind = 'MAIL' and source_id = ?
                """, Long.class, gone));
        long keptChunks = TenantContext.callAs(desk, () -> jdbc.queryForObject("""
                select count(*) from search_chunks where source_kind = 'MAIL' and source_id = ?
                """, Long.class, kept));
        assertThat(goneChunks).isZero();
        assertThat(keptChunks).isPositive();
    }
}
