package com.chartering.service.parser;

import com.chartering.model.IntakeFieldDecision;
import com.chartering.model.VesselFieldReport;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a disagreement about a hull is worth the queue, weighed against what the desk has
 * decided and what the market has said.
 *
 * <p><b>Why detecting a difference stopped being enough.</b> {@code VesselFieldDiff} says the
 * record and an email differ; {@code intake_field_decisions} stops the firm that was answered
 * from asking again. Neither stopped the repeat that filled the queue: CARLOW was raised seven
 * times over her build year and hatches, because each broker's list carried its own figure and
 * the desk accepted one, then the other, as each arrived. Every one of those was a real
 * difference and none of them was a question the desk had not already answered.
 *
 * <p><b>Nothing is dropped here — a row is either asked or minor.</b> Minor rows are kept on
 * the item and the item waits on the Intake tab's "Minor updates" sub-tab when all of its rows
 * are minor, so a judgement this class gets wrong costs a click to find rather than a figure
 * nobody ever sees. The only thing dropped outright is what the same firm has already been
 * answered about, which {@code IntakeService#withoutSettled} does before this is asked.
 *
 * <p>The rules, in the order they are asked:
 *
 * <ol>
 *   <li><b>Identity is always asked.</b> A different name or IMO is a question about which
 *       hull this is, and no amount of history makes that routine.</li>
 *   <li><b>A value the desk has moved away from is minor</b> — kept over it, or replaced it by
 *       accepting or correcting — whoever reports it now. Unless {@value #CORROBORATION} or
 *       more firms have reported it <em>since</em> that decision: then the market has moved
 *       and it is asked again, with the count beside it.</li>
 *   <li><b>A small difference is minor</b> — see {@link VesselFieldDiff#smallDifference}.
 *       A hull re-rounded is not a hull changed.</li>
 *   <li><b>A record the market backs is minor.</b> Where {@value #CORROBORATION} or more other
 *       firms have reported what is on file, and more of them than report this value, one
 *       broker's different figure is his, not hers.</li>
 *   <li>Everything else is asked. A significant change nobody has weighed is exactly what the
 *       queue is for — a new owner re-rating her deadweight, a conversion changing her holds.</li>
 * </ol>
 *
 * <p>Pure: the caller loads the decisions and reports for the hull once and hands them in, so
 * this is a function of its arguments and is tested as one.
 */
public final class VesselReviewPolicy {

    private VesselReviewPolicy() {
    }

    /** How many distinct firms make a figure the market's rather than one broker's. */
    static final int CORROBORATION = 2;

    /** History older than this says what she was, not what she is. */
    private static final long HISTORY_DAYS = 365;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /**
     * Weigh each row.
     *
     * @param senders   the firms behind the reading (null for one the sync could not place);
     *                  excluded from "other firms", and counted among those reporting the value
     * @param decisions everything the desk has decided about this hull
     * @param reports   everything every firm has reported about it, including this arrival
     * @param rawValues each row's incoming value canonically, keyed by field — the display
     *                  strings on {@link FieldDiff} carry units and cannot be compared
     * @param recordValues the record's value for each row's field, canonically
     */
    public static List<FieldDiff> weigh(List<FieldDiff> rows,
                                        Collection<Long> senders,
                                        List<IntakeFieldDecision> decisions,
                                        List<VesselFieldReport> reports,
                                        Map<String, String> rawValues,
                                        Map<String, String> recordValues,
                                        OffsetDateTime now) {
        List<FieldDiff> out = new ArrayList<>(rows.size());
        for (FieldDiff row : rows) {
            out.add(weighOne(row, senders, decisions, reports,
                    rawValues.get(row.field()), recordValues.get(row.field()), now));
        }
        return List.copyOf(out);
    }

    /** Whether every row is minor, which is what makes the whole item minor. */
    public static boolean allMinor(List<FieldDiff> rows) {
        return !rows.isEmpty() && rows.stream().allMatch(FieldDiff::isMinor);
    }

    static FieldDiff weighOne(FieldDiff row, Collection<Long> senders,
                              List<IntakeFieldDecision> decisions,
                              List<VesselFieldReport> reports,
                              String incoming, String record, OffsetDateTime now) {
        String field = row.field();
        if (VesselFieldDiff.isIdentity(field) || incoming == null) return row.weighed(false, null);

        // 2. The desk has already moved away from this value.
        IntakeFieldDecision against = latestAgainst(field, incoming, decisions);
        if (against != null) {
            int since = firmsReporting(field, incoming, reports, against.getDecidedAt(), null);
            String when = against.getDecidedAt() == null ? "earlier"
                    : "on " + DAY.format(against.getDecidedAt());
            if (since >= CORROBORATION) {
                return row.weighed(false, "Turned down " + when + ", but " + since
                        + " firms have reported it since");
            }
            return row.weighed(true, IntakeFieldDecision.KEPT.equals(against.getDecision())
                    ? "Already decided " + when + ": the record was kept over this value"
                    : "Already decided " + when + ": this value was replaced on the record");
        }

        // 3. Too small to be a different ship.
        String small = VesselFieldDiff.smallDifference(field, record, incoming);
        if (small != null) return row.weighed(true, small);

        // 4. The market backs what is on file.
        OffsetDateTime horizon = now.minusDays(HISTORY_DAYS);
        int forRecord = record == null ? 0
                : firmsReporting(field, record, reports, horizon, senders);
        int forIncoming = firmsReporting(field, incoming, reports, horizon, null);
        if (forRecord >= CORROBORATION && forRecord > forIncoming) {
            return row.weighed(true, forRecord + " other firms report what is on file");
        }

        return row.weighed(false, forIncoming >= CORROBORATION
                ? "Reported by " + forIncoming + " firms" : null);
    }

    /**
     * The newest decision that turned this value down: kept the record over it, or moved the
     * record off it by accepting or correcting something else.
     */
    private static IntakeFieldDecision latestAgainst(String field, String value,
                                                     List<IntakeFieldDecision> decisions) {
        IntakeFieldDecision latest = null;
        for (IntakeFieldDecision d : decisions) {
            if (!field.equals(d.getField())) continue;
            boolean kept = IntakeFieldDecision.KEPT.equals(d.getDecision())
                    && VesselFieldDiff.sameValue(field, d.getValueText(), value);
            boolean replaced = VesselFieldDiff.sameValue(field, d.getReplacedValue(), value);
            if (!kept && !replaced) continue;
            if (latest == null || isAfter(d.getDecidedAt(), latest.getDecidedAt())) latest = d;
        }
        return latest;
    }

    /**
     * Distinct firms that have reported this value since a moment, an unplaced sender counting
     * as one voice.
     *
     * @param excluding firms not to count, or null to count everybody
     */
    private static int firmsReporting(String field, String value, List<VesselFieldReport> reports,
                                      OffsetDateTime since, Collection<Long> excluding) {
        Set<Object> firms = new HashSet<>();
        for (VesselFieldReport r : reports) {
            if (!field.equals(r.getField())) continue;
            if (since != null && r.getLastSeenAt() != null && r.getLastSeenAt().isBefore(since)) continue;
            if (!VesselFieldDiff.sameValue(field, r.getValueText(), value)) continue;
            Long who = r.companyId();
            if (excluding != null && excluding.contains(who)) continue;
            firms.add(who == null ? "unplaced" : who);
        }
        return firms.size();
    }

    private static boolean isAfter(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) return false;
        return b == null || a.isAfter(b);
    }
}
