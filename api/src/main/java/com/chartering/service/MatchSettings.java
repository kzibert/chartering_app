package com.chartering.service;

import com.chartering.model.AppSetting;
import com.chartering.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The four numbers the matching rule cannot derive and should not hard-code.
 *
 * <p><b>Runtime settings rather than constants, for the reason the parser's knobs are:</b>
 * these are turned while looking at a list of ships and disagreeing with it. "That is not a
 * handysize speed", "we do take part cargoes on the small ships" — an argument a broker has
 * with the screen, settled in the same sitting. A constant would make it a redeploy, and an
 * environment variable would make it a redeploy on a machine somebody has to find.
 *
 * <p>Kept apart from {@link SettingsService} and {@link ParserSettings} on the same grounds
 * those two are kept apart from each other: one is about sending circulars and the other
 * about reading mail, and this is about neither. Only overridden values are stored; an absent
 * row means the default below is in force, which is this table's standing rule and is what
 * makes "reset" a delete rather than a write.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchSettings {

    /** Knots. What a ballast leg in miles is turned into days at. */
    public static final String BALLAST_SPEED_KNOTS = "match.ballastSpeedKnots";

    /** Hours added to every passage for the two ends of it. */
    public static final String PORT_ALLOWANCE_HOURS = "match.portAllowanceHours";

    /** Percent. Below this share of the ship filled, the pairing is ruled out. */
    public static final String MIN_UTILISATION = "match.minUtilisationPercent";

    /** Percent. At or above this, the intake check scores full marks. */
    public static final String IDEAL_UTILISATION = "match.idealUtilisationPercent";

    /**
     * Eleven and a half knots.
     *
     * <p>A laden handysize does twelve and a ballasting one a little more, and owners slow
     * steam when the market is poor. Half a knot either way moves a five-day ballast by four
     * hours, which is inside the noise of a laycan quoted as a three-day spread — so the
     * figure is a working assumption rather than a measurement, and it is a setting because
     * this desk's tonnage may not be the tonnage it was when the number was written down.
     */
    public static final double DEFAULT_BALLAST_SPEED = 11.5;

    /**
     * Twelve hours, for both ends of the passage together.
     *
     * <p>Getting off a berth, out through a pilot station, in through another and alongside.
     * It is not the waiting at a strait — that is on the waypoint and is counted per passage,
     * because a Black Sea ship pays it and a Med one does not.
     */
    public static final int DEFAULT_PORT_ALLOWANCE_HOURS = 12;

    /**
     * Fifty-five percent.
     *
     * <p>The answer to "do not offer a 14,000-tonner for a 4,000-tonne cargo". Freight is
     * earned by the tonne and paid for by the ship: a hull sailing 29% full earns 29% of what
     * she costs to run, and no owner takes it. Below this share the pairing is ruled out
     * rather than merely scored down, because a broker should not have to scroll past it.
     *
     * <p>Not higher, because part cargoes are real and this desk does them — 6,000 tonnes of
     * steel in a 9,000-tonner with something else underneath is an ordinary week. The floor
     * is set where a pairing stops being arguable rather than where it stops being ideal, and
     * {@link #IDEAL_UTILISATION} carries the second question.
     */
    public static final int DEFAULT_MIN_UTILISATION = 55;

    /**
     * Eighty-five percent.
     *
     * <p>Where a cargo stops being a good fit and starts being the same good fit. Above it
     * the check scores full marks; between the two figures it scores the share of the way it
     * has come, which is what makes a 92%-full ship outrank an otherwise identical 60% one
     * without ruling the second out.
     */
    public static final int DEFAULT_IDEAL_UTILISATION = 85;

    private static final double MIN_SPEED = 4;
    private static final double MAX_SPEED = 25;
    private static final int MAX_ALLOWANCE_HOURS = 168;

    private final AppSettingRepository repository;

    /** What the Settings tab shows and sends back. */
    public record Values(double ballastSpeedKnots,
                         int portAllowanceHours,
                         int minUtilisationPercent,
                         int idealUtilisationPercent) {

        /** Miles into days, at this speed and with the allowance for both ends. */
        public double daysFor(double distanceNm, double delayHours) {
            double hours = distanceNm / ballastSpeedKnots + delayHours + portAllowanceHours;
            return hours / 24.0;
        }
    }

    @Transactional(readOnly = true)
    public Values values() {
        Map<String, String> stored = repository
                .findByKeyIn(List.of(BALLAST_SPEED_KNOTS, PORT_ALLOWANCE_HOURS,
                        MIN_UTILISATION, IDEAL_UTILISATION))
                .stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        return new Values(
                readDouble(stored, BALLAST_SPEED_KNOTS, DEFAULT_BALLAST_SPEED),
                readInt(stored, PORT_ALLOWANCE_HOURS, DEFAULT_PORT_ALLOWANCE_HOURS),
                readInt(stored, MIN_UTILISATION, DEFAULT_MIN_UTILISATION),
                readInt(stored, IDEAL_UTILISATION, DEFAULT_IDEAL_UTILISATION));
    }

    public static Values defaults() {
        return new Values(DEFAULT_BALLAST_SPEED, DEFAULT_PORT_ALLOWANCE_HOURS,
                DEFAULT_MIN_UTILISATION, DEFAULT_IDEAL_UTILISATION);
    }

    @Transactional
    public Values update(Double ballastSpeedKnots, Integer portAllowanceHours,
                         Integer minUtilisation, Integer idealUtilisation) {
        if (ballastSpeedKnots != null) {
            if (ballastSpeedKnots < MIN_SPEED || ballastSpeedKnots > MAX_SPEED) {
                throw new IllegalArgumentException(
                        "A ballast speed must be between " + (int) MIN_SPEED + " and "
                                + (int) MAX_SPEED + " knots.");
            }
            put(BALLAST_SPEED_KNOTS, String.valueOf(ballastSpeedKnots));
        }
        if (portAllowanceHours != null) {
            if (portAllowanceHours < 0 || portAllowanceHours > MAX_ALLOWANCE_HOURS) {
                throw new IllegalArgumentException(
                        "The port allowance must be between 0 and " + MAX_ALLOWANCE_HOURS
                                + " hours.");
            }
            put(PORT_ALLOWANCE_HOURS, String.valueOf(portAllowanceHours));
        }

        // Validated against each other and against what is already stored, rather than each
        // on its own: sending only the floor must not be able to push it above a ceiling the
        // caller never saw.
        Values current = values();
        int floor = minUtilisation != null ? minUtilisation : current.minUtilisationPercent();
        int ideal = idealUtilisation != null ? idealUtilisation : current.idealUtilisationPercent();
        if (minUtilisation != null || idealUtilisation != null) {
            if (floor < 0 || floor > 100 || ideal < 0 || ideal > 100) {
                throw new IllegalArgumentException("Both utilisation figures are percentages.");
            }
            if (floor > ideal) {
                throw new IllegalArgumentException(
                        "The floor cannot be above the ideal - a ship would be ruled out for "
                                + "filling less of herself than the figure that scores full marks.");
            }
            if (minUtilisation != null) put(MIN_UTILISATION, String.valueOf(minUtilisation));
            if (idealUtilisation != null) put(IDEAL_UTILISATION, String.valueOf(idealUtilisation));
        }

        Values values = values();
        log.info("Match settings updated: {} kn ballast, {}h allowance, ruled out below {}%, "
                        + "full marks at {}%",
                values.ballastSpeedKnots(), values.portAllowanceHours(),
                values.minUtilisationPercent(), values.idealUtilisationPercent());
        return values;
    }

    @Transactional
    public Values reset() {
        repository.deleteByKeyIn(List.of(BALLAST_SPEED_KNOTS, PORT_ALLOWANCE_HOURS,
                MIN_UTILISATION, IDEAL_UTILISATION));
        return values();
    }

    private void put(String key, String value) {
        AppSetting s = repository.findById(key).orElseGet(() -> {
            AppSetting fresh = new AppSetting();
            fresh.setKey(key);
            return fresh;
        });
        s.setValue(value);
        repository.save(s);
    }

    /** A malformed row must not stop matching; fall back and say so, as the other two do. */
    private static int readInt(Map<String, String> stored, String key, int fallback) {
        String raw = stored.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting {} holds an unreadable value '{}', using the default", key, raw);
            return fallback;
        }
    }

    private static double readDouble(Map<String, String> stored, String key, double fallback) {
        String raw = stored.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting {} holds an unreadable value '{}', using the default", key, raw);
            return fallback;
        }
    }
}
