package com.chartering.it;

import com.chartering.model.Cargo;
import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import com.chartering.model.MailMessage;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.CargoRepository;
import com.chartering.repository.IntakeItemRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query behind {@code GET /intake/cargoes/{id}/possible-duplicates}: the waiting merges
 * stacked on one cargo, and nothing else.
 *
 * <p>Asked of the repository rather than through MockMvc because the intake controller has no
 * integration coverage and the endpoint is a pass-through of this query, mapped by the same
 * {@code get} every item uses. The tenant filter and the status predicate are what can go wrong
 * here, and only a real database shows them.
 */
class CargoMergeQueueIntegrationTest extends IntegrationTest {

    /** The desk the migrations seed (V30). */
    private static final long DESK = 1L;

    @Autowired
    private CargoRepository cargoes;

    @Autowired
    private IntakeItemRepository items;

    @Autowired
    private ParsedEmailRepository parsedEmails;

    @Autowired
    private MailMessageRepository messages;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aCargoListsOnlyTheWaitingMergesStackedOnIt() {
        Cargo target = cargo();
        Cargo other = cargo();

        long waiting = merge(target, IntakeItemStatus.PENDING);
        merge(target, IntakeItemStatus.ACCEPTED);
        merge(other, IntakeItemStatus.PENDING);

        List<Long> stacked = TenantContext.callAs(DESK, () -> items.pendingCargoMerge(target.getId()))
                .stream().map(IntakeItem::getId).toList();

        assertThat(stacked).containsExactly(waiting);
    }

    @Test
    void aCargoWithNothingWaitingListsNothing() {
        Cargo alone = cargo();

        assertThat(TenantContext.callAs(DESK, () -> items.pendingCargoMerge(alone.getId()))).isEmpty();
    }

    private Cargo cargo() {
        return TenantContext.callAs(DESK, () -> {
            Cargo c = new Cargo();
            c.setCommodity("Wheat");
            return cargoes.save(c);
        });
    }

    private long merge(Cargo cargo, IntakeItemStatus status) {
        return TenantContext.callAs(DESK, () -> {
            IntakeItem item = new IntakeItem();
            item.setParsedEmail(parsedEmail());
            item.setKind(IntakeItemKind.CARGO_MERGE);
            item.setCargoId(cargo.getId());
            item.setStatus(status);
            item.setMinor(true);
            item.setSubjectLabel("Wheat");
            item.setPayload("{}");
            return items.save(item).getId();
        });
    }

    private ParsedEmail parsedEmail() {
        // Mail is personal (V32): a row is written as a person on the desk, not as the desk alone.
        Long owner = TenantContext.callAs(DESK, () ->
                jdbc.queryForObject("select min(id) from users", Long.class));
        return TenantContext.callAs(DESK, owner, () -> {
            MailMessage m = new MailMessage();
            m.setMessageId("<" + UUID.randomUUID() + "@example.test>");
            m.setFromAddress("broker@example.test");
            m.setSubject("Wheat enquiry");
            m.setReceivedAt(LocalDateTime.of(2026, 9, 4, 8, 31));
            m.setSyncedAt(LocalDateTime.of(2026, 9, 4, 8, 32));
            m.setBodyText("25,000 MT wheat");
            m.setSnippet("25,000 MT wheat");
            ParsedEmail row = new ParsedEmail();
            row.setMailMessage(messages.save(m));
            return parsedEmails.save(row);
        });
    }
}
