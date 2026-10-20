package com.chartering.it;

import com.chartering.dto.WaitingArrivalResponse;
import com.chartering.model.FeedIntakeSubscription;
import com.chartering.model.FeedItem;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.FeedIntakeSubscriptionRepository;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedSourceRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.service.ParserSettings;
import com.chartering.service.parser.EmailParseRunner;
import com.chartering.tenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two projection queries behind the Intake tab's waiting list, and the leave-out that takes
 * rows off it. These are the risky parts: a constructor expression that does not match the record
 * fails only when Hibernate reads it, and the cross-desk check depends on the tenant filter
 * applying to the {@code findById} that resolves each picked id, which only a real database can show.
 *
 * <p>The database is shared by every test class, so each test creates its own arrivals and asserts
 * on those ids alone. The totals are compared against the count queries, which read the same
 * predicate: a drift between a list and its count shows up as a mismatch, whatever else is there.
 * Everything here is invented: the firms, addresses and boards are named for the test.
 */
class WaitingArrivalsIntegrationTest extends IntegrationTest {

    /** The desk the migrations seed (V30). */
    private static final long DESK = 1L;

    private static final PageRequest ALL = PageRequest.of(0, 500);

    @Autowired
    private ParsedEmailRepository parsedEmails;

    @Autowired
    private MailMessageRepository messages;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private FeedIntakeSubscriptionRepository subscriptions;

    @Autowired
    private FeedItemRepository feedItems;

    @Autowired
    private EmailParseRunner runner;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theMailListIsTheSweepsQueueWithoutBodies() {
        String tag = tag();
        long waiting = mail("Waiting " + tag, "Ops desk " + tag);
        long read = mail("Already read " + tag, "Ops desk " + tag);
        TenantContext.runAs(DESK, () -> {
            MailMessage message = messages.findById(read).orElseThrow();
            parsedEmails.save(parsed(message, null, ParseStatus.PARSED));
        });

        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK,
                () -> parsedEmails.waiting(ParserSettings.NO_LIMIT_SINCE, ALL));

        assertThat(page.getContent()).extracting(WaitingArrivalResponse::id).contains(waiting).doesNotContain(read);
        WaitingArrivalResponse row = page.getContent().stream()
                .filter(r -> r.id().equals(waiting)).findFirst().orElseThrow();
        assertThat(row.source()).isEqualTo("MAIL");
        assertThat(row.subject()).isEqualTo("Waiting " + tag);
        assertThat(row.fromName()).isEqualTo("Ops desk " + tag);
        assertThat(row.preview()).isEqualTo("Snippet for Waiting " + tag);
        assertThat(row.receivedAt()).isNotNull();
        assertThat(row.boardName()).isNull();
        assertThat(page.getTotalElements()).isEqualTo(
                TenantContext.callAs(DESK, () -> parsedEmails.countUnparsed(ParserSettings.NO_LIMIT_SINCE)));
    }

    @Test
    void theBoardListIsTheIntakeQueueCutToItsOpening() {
        String tag = tag();
        long source = board("Board " + tag);
        long subscribed = post(source, "Position list " + tag, "x".repeat(400) + tag);
        long unsubscribed = post(sourceWithoutSubscription("Unread board " + tag),
                "Unread " + tag, "text " + tag);

        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK,
                () -> feedItems.waitingForIntake(ParserSettings.NO_LIMIT_SINCE, ALL));

        assertThat(page.getContent()).extracting(WaitingArrivalResponse::id)
                .contains(subscribed).doesNotContain(unsubscribed);
        WaitingArrivalResponse row = page.getContent().stream()
                .filter(r -> r.id().equals(subscribed)).findFirst().orElseThrow();
        assertThat(row.source()).isEqualTo("WEB");
        assertThat(row.boardName()).isEqualTo("Board " + tag);
        assertThat(row.subject()).isEqualTo("Position list " + tag);
        assertThat(row.preview()).hasSize(300);
        assertThat(row.fromName()).isEqualTo("Board author");
        assertThat(row.fromAddress()).isNull();
        assertThat(row.companyName()).isNull();
        assertThat(page.getTotalElements()).isEqualTo(
                TenantContext.callAs(DESK, () -> feedItems.countUnparsedForIntake(ParserSettings.NO_LIMIT_SINCE)));
    }

    @Test
    void leavingOutTakesArrivalsOffTheListAndIsNotRepeated() {
        String tag = tag();
        long mail = mail("Chain letter " + tag, "Friend " + tag);
        long post = post(board("Board " + tag), "Newsletter " + tag, "news " + tag);

        int first = TenantContext.callAs(DESK,
                () -> runner.leaveOut(List.of(mail), List.of(post), "newsletter"));
        int second = TenantContext.callAs(DESK,
                () -> runner.leaveOut(List.of(mail), List.of(post), "newsletter"));

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
        assertThat(TenantContext.callAs(DESK, () -> parsedEmails.waiting(ParserSettings.NO_LIMIT_SINCE, ALL)).getContent())
                .extracting(WaitingArrivalResponse::id).doesNotContain(mail);
        assertThat(TenantContext.callAs(DESK, () -> feedItems.waitingForIntake(ParserSettings.NO_LIMIT_SINCE, ALL)).getContent())
                .extracting(WaitingArrivalResponse::id).doesNotContain(post);
        ParsedEmail row = TenantContext.callAs(DESK,
                () -> parsedEmails.findByMailMessageId(mail).orElseThrow());
        assertThat(row.getStatus()).isEqualTo(ParseStatus.IGNORED);
        assertThat(row.getError()).isEqualTo("Left out of the queue — newsletter");
    }

    @Test
    void anotherDesksArrivalCannotBeLeftOut() throws Exception {
        String tag = tag();
        long mail = mail("Desk one only " + tag, "Someone " + tag);
        String root = login(ROOT, ROOT_PASSWORD);
        JsonNode other = read(postAs(root, "/api/v1/admin/tenants",
                Map.of("name", "Other desk " + tag, "adminUsername", "other-" + tag))
                .andExpect(status().isCreated()));
        long otherDesk = other.get("tenant").get("id").asLong();

        int created = TenantContext.callAs(otherDesk, () -> runner.leaveOut(List.of(mail), null, null));

        assertThat(created).isZero();
        assertThat(TenantContext.callAs(DESK, () -> parsedEmails.findByMailMessageId(mail))).isEmpty();
        assertThat(TenantContext.callAs(DESK, () -> parsedEmails.waiting(ParserSettings.NO_LIMIT_SINCE, ALL)).getContent())
                .extracting(WaitingArrivalResponse::id).contains(mail);
    }

    // ------------------------------------------------------------------ fixtures

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long mail(String subject, String fromName) {
        // Mail is personal (V32): a row is written as a person on the desk, not as the desk alone.
        Long owner = TenantContext.callAs(DESK, () ->
                jdbc.queryForObject("select min(id) from users", Long.class));
        return TenantContext.callAs(DESK, owner, () -> {
            MailMessage m = new MailMessage();
            m.setMessageId("<" + UUID.randomUUID() + "@example.test>");
            m.setFromAddress("sender@example.test");
            m.setFromName(fromName);
            m.setSubject(subject);
            m.setReceivedAt(LocalDateTime.now());
            m.setBodyText("Body of " + subject);
            m.setSnippet("Snippet for " + subject);
            return messages.save(m).getId();
        });
    }

    private static ParsedEmail parsed(MailMessage message, FeedItem post, ParseStatus status) {
        ParsedEmail row = new ParsedEmail();
        row.setMailMessage(message);
        row.setFeedItem(post);
        row.setStatus(status);
        return row;
    }

    /** A board with its intake subscription, so it is one the sweep reads. */
    private long board(String name) {
        return TenantContext.callAs(DESK, () -> {
            long id = sourceWithoutSubscription(name);
            FeedIntakeSubscription sub = new FeedIntakeSubscription();
            sub.setFeedSourceId(id);
            subscriptions.save(sub);
            return id;
        });
    }

    /** A board nobody subscribes the desk to: its posts are not in the Intake queue. */
    private long sourceWithoutSubscription(String name) {
        FeedSource source = new FeedSource();
        source.setName(name);
        source.setKind(FeedSourceKind.RSS);
        source.setUrl("https://example.test/" + UUID.randomUUID());
        source.setEnabled(true);
        return sources.save(source).getId();
    }

    private long post(long sourceId, String title, String text) {
        FeedItem item = new FeedItem();
        item.setSource(sources.getReferenceById(sourceId));
        item.setExternalId(UUID.randomUUID().toString());
        item.setTitle(title);
        item.setText(text);
        item.setAuthor("Board author");
        item.setContentHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        item.setPublishedAt(LocalDateTime.now());
        return feedItems.save(item).getId();
    }
}
