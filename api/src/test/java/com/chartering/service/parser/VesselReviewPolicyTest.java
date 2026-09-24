package com.chartering.service.parser;

import com.chartering.model.Company;
import com.chartering.model.IntakeFieldDecision;
import com.chartering.model.VesselFieldReport;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which disagreements about a hull are worth the queue.
 *
 * <p>The cases are the ones the live queue produced: CARLOW asked seven times over two figures
 * as each broker's list arrived, deadweights re-rounded by a percent, one broker's figure
 * against a record three others confirm. None of them is dropped — a minor row is still on the
 * item — so what these pin is only which side of the queue a row lands on.
 */
class VesselReviewPolicyTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 24, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final Long GN = 1L;
    private static final Long EKSEN = 2L;
    private static final Long INTERSCAN = 3L;
    private static final Long ANGORA = 4L;

    private static FieldDiff row(String field) {
        return new FieldDiff(field, field, "on file", "in the email");
    }

    private static FieldDiff weigh(String field, String record, String incoming, Long sender,
                                   List<IntakeFieldDecision> decisions,
                                   List<VesselFieldReport> reports) {
        List<Long> senders = new ArrayList<>();
        senders.add(sender);
        return VesselReviewPolicy.weighOne(row(field), senders, decisions, reports,
                incoming, record, NOW);
    }

    private static IntakeFieldDecision decision(String field, String decision, String value,
                                                String replaced, OffsetDateTime at) {
        IntakeFieldDecision d = new IntakeFieldDecision();
        d.setField(field);
        d.setDecision(decision);
        d.setValueText(value);
        d.setReplacedValue(replaced);
        d.setDecidedAt(at);
        return d;
    }

    private static VesselFieldReport report(String field, Long company, String value,
                                            OffsetDateTime lastSeen) {
        VesselFieldReport r = new VesselFieldReport();
        r.setField(field);
        if (company != null) {
            Company c = new Company();
            c.setId(company);
            r.setReportedByCompany(c);
        }
        r.setValueText(value);
        r.setFirstSeenAt(lastSeen);
        r.setLastSeenAt(lastSeen);
        return r;
    }

    @Test
    void aSignificantChangeNobodyHasWeighedIsAsked() {
        FieldDiff out = weigh("deadweightTonnage", "28000", "31000", GN, List.of(), List.of());
        assertThat(out.isMinor()).isFalse();
    }

    @Test
    void aReRoundedDeadweightIsMinor() {
        FieldDiff out = weigh("deadweightTonnage", "28400", "28150", GN, List.of(), List.of());
        assertThat(out.isMinor()).isTrue();
        assertThat(out.note()).startsWith("Within 2% of the record");
    }

    @Test
    void aBuildYearOneApartIsMinorAndTwoApartIsNot() {
        assertThat(weigh("yearBuilt", "2004", "2005", GN, List.of(), List.of()).isMinor()).isTrue();
        assertThat(weigh("yearBuilt", "2004", "2006", GN, List.of(), List.of()).isMinor()).isFalse();
    }

    @Test
    void aValueTheDeskReplacedIsMinorWhoeverReportsIt() {
        // CARLOW: GN said 6 hatches, the desk accepted it over 5. Eksen's list still says 5, and
        // used to raise the same pair the other way round.
        List<IntakeFieldDecision> decided = List.of(decision("hatches",
                IntakeFieldDecision.ACCEPTED, "6", "5", NOW.minusDays(3)));

        FieldDiff out = weigh("hatches", "6", "5", EKSEN, decided, List.of());

        assertThat(out.isMinor()).isTrue();
        assertThat(out.note()).contains("this value was replaced on the record");
    }

    @Test
    void aValueTheDeskKeptTheRecordOverIsMinorFromAnotherFirmToo() {
        List<IntakeFieldDecision> decided = List.of(decision("baleCapacityM3",
                IntakeFieldDecision.KEPT, "8240", null, NOW.minusDays(3)));

        FieldDiff out = weigh("baleCapacityM3", "7900", "8240", INTERSCAN, decided, List.of());

        assertThat(out.isMinor()).isTrue();
        assertThat(out.note()).contains("the record was kept over this value");
    }

    @Test
    void aTurnedDownValueComesBackWhenTheMarketMovesToIt() {
        OffsetDateTime decidedAt = NOW.minusDays(10);
        List<IntakeFieldDecision> decided = List.of(decision("hatches",
                IntakeFieldDecision.KEPT, "5", null, decidedAt));
        // Two firms reporting it since the decision: that is no longer one broker's figure.
        List<VesselFieldReport> heard = List.of(
                report("hatches", EKSEN, "5", NOW.minusDays(2)),
                report("hatches", INTERSCAN, "5", NOW.minusDays(1)));

        FieldDiff out = weigh("hatches", "6", "5", EKSEN, decided, heard);

        assertThat(out.isMinor()).isFalse();
        assertThat(out.note()).contains("2 firms have reported it since");
    }

    @Test
    void oneBrokerAgainstARecordTheMarketBacksIsMinor() {
        List<VesselFieldReport> heard = List.of(
                report("maximumDraft", GN, "7.9", NOW.minusDays(5)),
                report("maximumDraft", INTERSCAN, "7.9", NOW.minusDays(4)),
                report("maximumDraft", ANGORA, "7.9", NOW.minusDays(3)),
                report("maximumDraft", EKSEN, "8.4", NOW));

        FieldDiff out = weigh("maximumDraft", "7.9", "8.4", EKSEN, List.of(), heard);

        assertThat(out.isMinor()).isTrue();
        assertThat(out.note()).isEqualTo("3 other firms report what is on file");
    }

    @Test
    void oldHistoryDoesNotBackTheRecord() {
        List<VesselFieldReport> heard = List.of(
                report("maximumDraft", GN, "7.9", NOW.minusYears(2)),
                report("maximumDraft", INTERSCAN, "7.9", NOW.minusYears(2)),
                report("maximumDraft", EKSEN, "8.4", NOW));

        assertThat(weigh("maximumDraft", "7.9", "8.4", EKSEN, List.of(), heard).isMinor()).isFalse();
    }

    @Test
    void theSendersOwnRepeatsDoNotCountAsTheMarketBackingTheRecord() {
        // Eksen reported 7.9 last month and 8.4 today: its own earlier figure is not a second
        // opinion against itself.
        List<VesselFieldReport> heard = List.of(
                report("maximumDraft", EKSEN, "7.9", NOW.minusDays(30)),
                report("maximumDraft", GN, "7.9", NOW.minusDays(5)),
                report("maximumDraft", EKSEN, "8.4", NOW));

        assertThat(weigh("maximumDraft", "7.9", "8.4", EKSEN, List.of(), heard).isMinor()).isFalse();
    }

    @Test
    void aDifferentNameIsAlwaysAsked() {
        List<IntakeFieldDecision> decided = List.of(decision("name",
                IntakeFieldDecision.KEPT, "CELIA", null, NOW.minusDays(3)));

        assertThat(weigh("name", "LIUDMILA", "CELIA", GN, decided, List.of()).isMinor()).isFalse();
    }

    @Test
    void anItemIsMinorOnlyWhenEveryRowIs() {
        FieldDiff small = row("deadweightTonnage").weighed(true, "small");
        FieldDiff real = row("holds").weighed(false, null);

        assertThat(VesselReviewPolicy.allMinor(List.of(small))).isTrue();
        assertThat(VesselReviewPolicy.allMinor(List.of(small, real))).isFalse();
        assertThat(VesselReviewPolicy.allMinor(List.of())).isFalse();
    }
}
