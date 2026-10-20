package com.chartering.it;

import com.chartering.dto.CompanyResponse;
import com.chartering.dto.IntakeItemResponse;
import com.chartering.dto.PageResponse;
import com.chartering.model.Company;
import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.IntakeItemRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.service.CompanyService;
import com.chartering.service.CompanyService.CompanyFilter;
import com.chartering.service.parser.IntakeQueryService;
import com.chartering.service.parser.IntakeService;
import com.chartering.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A minor company question is a mark on the firm, not a row in the queue: it is left out of the
 * queue's lists and counts, stays PENDING and answerable from its firm, and shows on the companies
 * list as "Update from correspondence" with a filter to find it.
 *
 * <p>The database is shared, so every test names its own firms and reads back only its own ids.
 * The queue's newest rows are fetched first, which is where these tests' items always are. Invented
 * firms only.
 */
// The queue's list is read through the parser's gate, which is off unless the switch is on.
@TestPropertySource(properties = "PARSER_ENABLED=true")
class CompanyDetailsQueueIntegrationTest extends IntegrationTest {

    /** The desk the migrations seed (V30). */
    private static final long DESK = 1L;

    @Autowired
    private IntakeItemRepository items;

    @Autowired
    private IntakeQueryService queries;

    @Autowired
    private IntakeService intake;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private CompanyService companyService;

    @Autowired
    private MailMessageRepository messages;

    @Autowired
    private ParsedEmailRepository parsedEmails;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMinorCompanyQuestionIsLeftOutOfTheQueueButStaysOnItsFirm() {
        Company minorFirm = firm("Minorfirm " + tag());
        Company majorFirm = firm("Majorfirm " + tag());
        long minorItem = item(IntakeItemKind.COMPANY_DETAILS, true, minorFirm.getId(), IntakeItemStatus.PENDING);
        long majorItem = item(IntakeItemKind.COMPANY_DETAILS, false, majorFirm.getId(), IntakeItemStatus.PENDING);

        // Left out of every list of pending items, whichever minor filter asked for it.
        assertThat(queueIds(IntakeItemKind.COMPANY_DETAILS, IntakeItemStatus.PENDING, null))
                .doesNotContain(minorItem).contains(majorItem);
        assertThat(queueIds(IntakeItemKind.COMPANY_DETAILS, IntakeItemStatus.PENDING, true))
                .doesNotContain(minorItem);
        assertThat(queueIds(IntakeItemKind.COMPANY_DETAILS, IntakeItemStatus.PENDING, false))
                .contains(majorItem).doesNotContain(minorItem);

        // Still PENDING and still answerable from its firm, which is where a minor question lives.
        assertThat(TenantContext.callAs(DESK, () -> items.findById(minorItem).orElseThrow().getStatus()))
                .isEqualTo(IntakeItemStatus.PENDING);
        assertThat(TenantContext.callAs(DESK, () -> items.pendingForCompany(minorFirm.getId())))
                .extracting(IntakeItem::getId).containsExactly(minorItem);
    }

    @Test
    void aMinorCompanyQuestionIsNotCountedAsMinorUpdatesButOtherMinorItemsAre() {
        Company firm = firm("Countfirm " + tag());
        long minorBefore = TenantContext.callAs(DESK, () -> intake.counts().minor());

        item(IntakeItemKind.COMPANY_DETAILS, true, firm.getId(), IntakeItemStatus.PENDING);
        assertThat(TenantContext.callAs(DESK, () -> intake.counts().minor())).isEqualTo(minorBefore);

        // A minor item of another kind still counts: only company details are kept off the tab.
        item(IntakeItemKind.NEW_VESSEL, true, null, IntakeItemStatus.PENDING);
        assertThat(TenantContext.callAs(DESK, () -> intake.counts().minor())).isEqualTo(minorBefore + 1);
    }

    @Test
    void theCompaniesListMarksAFirmWithAQuestionWaitingAndFiltersOnIt() {
        String tag = tag();
        Company waiting = firm("Waitfirm " + tag);
        Company clear = firm("Clearfirm " + tag);
        // An accepted question is history, not a waiting one, so it must not mark its firm.
        item(IntakeItemKind.COMPANY_DETAILS, true, waiting.getId(), IntakeItemStatus.PENDING);
        item(IntakeItemKind.COMPANY_DETAILS, true, clear.getId(), IntakeItemStatus.ACCEPTED);

        List<CompanyResponse> all = search(byName(tag, null));
        assertThat(all).extracting(CompanyResponse::id).containsExactlyInAnyOrder(waiting.getId(), clear.getId());
        assertThat(all).filteredOn(c -> c.id().equals(waiting.getId()))
                .extracting(CompanyResponse::correspondenceUpdate).containsExactly(true);
        assertThat(all).filteredOn(c -> c.id().equals(clear.getId()))
                .extracting(CompanyResponse::correspondenceUpdate).containsExactly(false);

        assertThat(search(byName(tag, true))).extracting(CompanyResponse::id).containsExactly(waiting.getId());
        assertThat(search(byName(tag, false))).extracting(CompanyResponse::id).containsExactly(clear.getId());

        CompanyResponse detail = TenantContext.callAs(DESK, () -> companyService.getDetail(waiting.getId()))
                .company();
        assertThat(detail.correspondenceUpdate()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A company filter that names only this test's firms: every other criterion is left open. */
    private static CompanyFilter byName(String name, Boolean correspondenceUpdate) {
        return new CompanyFilter(name, null, null, null, null, null, null, null, null, null, null,
                false, null, null, correspondenceUpdate);
    }

    private List<CompanyResponse> search(CompanyFilter filter) {
        PageResponse<CompanyResponse> page = TenantContext.callAs(DESK,
                () -> companyService.search(filter, PageRequest.of(0, 50)));
        return page.content();
    }

    private List<Long> queueIds(IntakeItemKind kind, IntakeItemStatus status, Boolean minor) {
        PageResponse<IntakeItemResponse> page = TenantContext.callAs(DESK, () -> queries.search(kind, status,
                minor, PageRequest.of(0, 50, Sort.by("id").descending())));
        return page.content().stream().map(IntakeItemResponse::id).toList();
    }

    private Company firm(String name) {
        return TenantContext.callAs(DESK, () -> {
            Company c = new Company();
            c.setName(name);
            return companies.save(c);
        });
    }

    private long item(IntakeItemKind kind, boolean minor, Long companyId, IntakeItemStatus status) {
        MailMessage message = mail();
        return TenantContext.callAs(DESK, () -> {
            ParsedEmail parsed = new ParsedEmail();
            parsed.setMailMessage(message);
            parsed.setStatus(ParseStatus.PARSED);
            IntakeItem item = new IntakeItem();
            item.setParsedEmail(parsedEmails.save(parsed));
            item.setKind(kind);
            item.setStatus(status);
            item.setMinor(minor);
            item.setCompanyId(companyId);
            item.setSubjectLabel("Test " + tag());
            item.setPayload("{}");
            return items.save(item).getId();
        });
    }

    /** Mail is personal (V32): written as a person on the desk, as the existing arrivals tests do. */
    private MailMessage mail() {
        Long owner = TenantContext.callAs(DESK, () ->
                jdbc.queryForObject("select min(id) from users", Long.class));
        return TenantContext.callAs(DESK, owner, () -> {
            MailMessage m = new MailMessage();
            m.setMessageId("<" + UUID.randomUUID() + "@example.test>");
            m.setFromAddress("sender@example.test");
            m.setFromName("Test sender");
            m.setSubject("Signature " + tag());
            m.setReceivedAt(LocalDateTime.now());
            m.setSyncedAt(LocalDateTime.now());
            m.setBodyText("Body");
            m.setSnippet("Snippet");
            return messages.save(m);
        });
    }
}
