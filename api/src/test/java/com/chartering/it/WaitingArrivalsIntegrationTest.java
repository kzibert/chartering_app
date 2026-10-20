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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** No lookback at all: every message with a body, which is the widest window the list can have. */
    private static final ParserSettings.Values NO_LIMIT = new ParserSettings.Values(30, 20, 0);

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
                () -> parsedEmails.waiting(ParserSettings.NO_LIMIT_SINCE, ParserSettings.NO_LIMIT_SINCE, ALL));

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
                TenantContext.callAs(DESK, () -> parsedEmails.countUnparsed(ParserSettings.NO_LIMIT_SINCE, ParserSettings.NO_LIMIT_SINCE)));
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
        assertThat(TenantContext.callAs(DESK, () -> parsedEmails.waiting(ParserSettings.NO_LIMIT_SINCE, ParserSettings.NO_LIMIT_SINCE, ALL)).getContent())
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
        assertThat(TenantContext.callAs(DESK, () -> parsedEmails.waiting(ParserSettings.NO_LIMIT_SINCE, ParserSettings.NO_LIMIT_SINCE, ALL)).getContent())
                .extracting(WaitingArrivalResponse::id).contains(mail);
    }

    /*
     * The lookback counts mail from when it reached us, within twice the lookback. Each case is one
     * shape of message against a seven-day window, and the Values record supplies both cutoffs the
     * way the sweep does, so the test exercises the same helpers production calls.
     */

    @Test
    void mailSyncedLateGetsAWindowFromArrival() {
        ParserSettings.Values lookback7 = new ParserSettings.Values(30, 20, 7);
        LocalDateTime now = LocalDateTime.now();
        String tag = tag();
        // Sent 10 days ago, synced 2 days ago: older than 7 days by the send date, inside by arrival.
        long lateSynced = mailAt("Late " + tag, "Ops desk " + tag, now.minusDays(10), now.minusDays(2));

        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK, () ->
                parsedEmails.waiting(lookback7.receivedSince(), lookback7.receivedFloor(), ALL));

        assertThat(page.getContent()).extracting(WaitingArrivalResponse::id).contains(lateSynced);
        assertThat(page.getTotalElements()).isEqualTo(TenantContext.callAs(DESK, () ->
                parsedEmails.countUnparsed(lookback7.receivedSince(), lookback7.receivedFloor())));
    }

    @Test
    void mailOlderThanTwiceTheLookbackIsNeverReadEvenWhenSyncedToday() {
        ParserSettings.Values lookback7 = new ParserSettings.Values(30, 20, 7);
        LocalDateTime now = LocalDateTime.now();
        String tag = tag();
        // Sent 20 days ago, synced today: the first sync of a mailbox brings exactly this, and it is
        // past the 14-day floor, so it must stay out.
        long history = mailAt("History " + tag, "Ops desk " + tag, now.minusDays(20), now);

        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK, () ->
                parsedEmails.waiting(lookback7.receivedSince(), lookback7.receivedFloor(), ALL));

        assertThat(page.getContent()).extracting(WaitingArrivalResponse::id).doesNotContain(history);
        assertThat(page.getTotalElements()).isEqualTo(TenantContext.callAs(DESK, () ->
                parsedEmails.countUnparsed(lookback7.receivedSince(), lookback7.receivedFloor())));
    }

    @Test
    void mailSentAndSyncedBeforeTheLookbackIsNotWaiting() {
        ParserSettings.Values lookback7 = new ParserSettings.Values(30, 20, 7);
        LocalDateTime now = LocalDateTime.now();
        String tag = tag();
        long stale = mailAt("Stale " + tag, "Ops desk " + tag, now.minusDays(10), now.minusDays(10));

        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK, () ->
                parsedEmails.waiting(lookback7.receivedSince(), lookback7.receivedFloor(), ALL));

        assertThat(page.getContent()).extracting(WaitingArrivalResponse::id).doesNotContain(stale);
        assertThat(page.getTotalElements()).isEqualTo(TenantContext.callAs(DESK, () ->
                parsedEmails.countUnparsed(lookback7.receivedSince(), lookback7.receivedFloor())));
    }

    @Test
    void noLimitTakesEveryMessageWithABody() {
        ParserSettings.Values noLimit = new ParserSettings.Values(30, 20, 0);
        LocalDateTime now = LocalDateTime.now();
        String tag = tag();
        long ancient = mailAt("Ancient " + tag, "Ops desk " + tag, now.minusDays(400), now.minusDays(400));
        long lateSynced = mailAt("Late " + tag, "Ops desk " + tag, now.minusDays(30), now);

        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK, () ->
                parsedEmails.waiting(noLimit.receivedSince(), noLimit.receivedFloor(), ALL));

        assertThat(page.getContent()).extracting(WaitingArrivalResponse::id).contains(ancient, lateSynced);
        assertThat(page.getTotalElements()).isEqualTo(TenantContext.callAs(DESK, () ->
                parsedEmails.countUnparsed(noLimit.receivedSince(), noLimit.receivedFloor())));
    }

    /*
     * "Add to waiting list": the picked messages become QUEUED rows, which the sweep's queue takes
     * whatever their age and ahead of everything else. Each case is one state a picked message can
     * be in when it is queued, and each one asserts both the list and its count.
     */

    @Test
    void aQueuedMessageFromLongAgoIsWaitingAndComesFirst() {
        ParserSettings.Values lookback7 = new ParserSettings.Values(30, 20, 7);
        LocalDateTime now = LocalDateTime.now();
        String tag = tag();
        long old = mailAt("Old " + tag, "Ops desk " + tag, now.minusDays(40), now.minusDays(40));
        long fresh = mail("Fresh " + tag, "Ops desk " + tag);

        assertThat(waitingIds(lookback7)).doesNotContain(old);

        assertThat(queue(old)).isEqualTo(new EmailParseRunner.QueueOutcome(1, 0));
        // Idempotent: a second press counts the same request and changes nothing.
        assertThat(queue(old)).isEqualTo(new EmailParseRunner.QueueOutcome(1, 0));

        List<Long> waiting = waitingIds(lookback7);
        assertThat(waiting).contains(old, fresh);
        assertThat(waiting.indexOf(old)).isLessThan(waiting.indexOf(fresh));

        List<Long> unparsed = TenantContext.callAs(DESK, () ->
                parsedEmails.unparsed(lookback7.receivedSince(), lookback7.receivedFloor(), ALL))
                .stream().map(MailMessage::getId).toList();
        assertThat(unparsed).contains(old, fresh);
        assertThat(unparsed.indexOf(old)).isLessThan(unparsed.indexOf(fresh));

        assertCountMatchesWaiting(lookback7);
        assertThat(rowFor(old).orElseThrow().getStatus()).isEqualTo(ParseStatus.QUEUED);

        Page<ParsedEmail> log = TenantContext.callAs(DESK, () ->
                parsedEmails.findByStatusNotOrderByParsedAtDesc(ParseStatus.QUEUED, ALL));
        assertThat(log.getContent()).extracting(ParsedEmail::getStatus).doesNotContain(ParseStatus.QUEUED);
    }

    @Test
    void queueingAMessageAlreadyReadIsSkipped() {
        String tag = tag();
        long read = mail("Already read " + tag, "Ops desk " + tag);
        TenantContext.runAs(DESK, () -> parsedEmails.save(
                parsed(messages.findById(read).orElseThrow(), null, ParseStatus.PARSED)));

        assertThat(queue(read)).isEqualTo(new EmailParseRunner.QueueOutcome(0, 1));

        assertThat(rowFor(read).orElseThrow().getStatus()).isEqualTo(ParseStatus.PARSED);
        assertThat(waitingIds(NO_LIMIT)).doesNotContain(read);
        assertCountMatchesWaiting(NO_LIMIT);
    }

    @Test
    void aFailedOrIgnoredMessageIsQueuedAgainAsAFirstAttempt() {
        String tag = tag();
        long failed = mail("Failed " + tag, "Ops desk " + tag);
        long ignored = mail("Ignored " + tag, "Ops desk " + tag);
        TenantContext.runAs(DESK, () -> {
            ParsedEmail f = parsed(messages.findById(failed).orElseThrow(), null, ParseStatus.FAILED);
            f.setAttempts(3);
            f.setError("Timed out");
            parsedEmails.save(f);
            ParsedEmail i = parsed(messages.findById(ignored).orElseThrow(), null, ParseStatus.IGNORED);
            i.setAttempts(1);
            i.setError("Ignored — newsletter");
            parsedEmails.save(i);
        });

        assertThat(queue(failed, ignored)).isEqualTo(new EmailParseRunner.QueueOutcome(2, 0));

        for (long id : List.of(failed, ignored)) {
            ParsedEmail row = rowFor(id).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(ParseStatus.QUEUED);
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getError()).isNull();
            assertThat(row.getParsedAt()).isNotNull();
        }
        assertThat(waitingIds(NO_LIMIT)).contains(failed, ignored);
        assertCountMatchesWaiting(NO_LIMIT);
    }

    @Test
    void aMessageWithNoBodyCannotBeQueued() {
        String tag = tag();
        long empty = mail("No body " + tag, "Ops desk " + tag);
        TenantContext.runAs(DESK, () -> {
            MailMessage m = messages.findById(empty).orElseThrow();
            m.setBodyText(null);
            messages.save(m);
        });

        assertThat(queue(empty)).isEqualTo(new EmailParseRunner.QueueOutcome(0, 1));

        assertThat(rowFor(empty)).isEmpty();
        assertThat(waitingIds(NO_LIMIT)).doesNotContain(empty);
        assertCountMatchesWaiting(NO_LIMIT);
    }

    @Test
    void leavingOutAQueuedMessageTakesItOffTheListAsIgnored() {
        String tag = tag();
        long queued = mail("Forwarded " + tag, "Friend " + tag);
        assertThat(queue(queued)).isEqualTo(new EmailParseRunner.QueueOutcome(1, 0));

        int leftOut = TenantContext.callAs(DESK,
                () -> runner.leaveOut(List.of(queued), null, "forwarded thread"));

        assertThat(leftOut).isEqualTo(1);
        ParsedEmail row = rowFor(queued).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ParseStatus.IGNORED);
        assertThat(row.getError()).isEqualTo("Left out of the queue — forwarded thread");
        assertThat(waitingIds(NO_LIMIT)).doesNotContain(queued);
        assertCountMatchesWaiting(NO_LIMIT);
    }

    // ------------------------------------------------------------------ fixtures

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A message received and synced just now, which is what a live sync writes. */
    private long mail(String subject, String fromName) {
        LocalDateTime now = LocalDateTime.now();
        return mailAt(subject, fromName, now, now);
    }

    private EmailParseRunner.QueueOutcome queue(long... mailMessageIds) {
        List<Long> ids = Arrays.stream(mailMessageIds).boxed().toList();
        return TenantContext.callAs(DESK, () -> runner.queue(ids));
    }

    private Optional<ParsedEmail> rowFor(long mailMessageId) {
        return TenantContext.callAs(DESK, () -> parsedEmails.findByMailMessageId(mailMessageId));
    }

    private List<Long> waitingIds(ParserSettings.Values window) {
        return TenantContext.callAs(DESK, () ->
                parsedEmails.waiting(window.receivedSince(), window.receivedFloor(), ALL))
                .getContent().stream().map(WaitingArrivalResponse::id).toList();
    }

    /** The list's total and the count query must be the same number for the same window. */
    private void assertCountMatchesWaiting(ParserSettings.Values window) {
        Page<WaitingArrivalResponse> page = TenantContext.callAs(DESK, () ->
                parsedEmails.waiting(window.receivedSince(), window.receivedFloor(), ALL));
        assertThat(page.getTotalElements()).isEqualTo(TenantContext.callAs(DESK, () ->
                parsedEmails.countUnparsed(window.receivedSince(), window.receivedFloor())));
    }

    private long mailAt(String subject, String fromName, LocalDateTime receivedAt, LocalDateTime syncedAt) {
        // Mail is personal (V32): a row is written as a person on the desk, not as the desk alone.
        Long owner = TenantContext.callAs(DESK, () ->
                jdbc.queryForObject("select min(id) from users", Long.class));
        return TenantContext.callAs(DESK, owner, () -> {
            MailMessage m = new MailMessage();
            m.setMessageId("<" + UUID.randomUUID() + "@example.test>");
            m.setFromAddress("sender@example.test");
            m.setFromName(fromName);
            m.setSubject(subject);
            m.setReceivedAt(receivedAt);
            m.setSyncedAt(syncedAt);
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
