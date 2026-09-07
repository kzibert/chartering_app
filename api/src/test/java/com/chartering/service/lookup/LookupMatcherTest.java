package com.chartering.service.lookup;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing between the hulls a public database returns for one name.
 *
 * <p>The failure worth testing is not "found nothing" — it is "found somebody else's ship and
 * said so confidently". Accepting a wrong IMO files this hull's whole history under a vessel
 * that was never on the desk, and nothing on screen will ever look odd afterwards. So most of
 * what follows is about refusing.
 */
class LookupMatcherTest {

    private static VesselParticulars candidate(String name, Integer built, String dwt, String flag) {
        return new VesselParticulars("9014561", name, "General Cargo Ship", flag, built,
                new BigDecimal("3989"), dwt == null ? null : new BigDecimal(dwt),
                null, null, "https://example.test/9014561");
    }

    private static VesselParticulars withImo(String imo, String name, Integer built, String dwt) {
        return new VesselParticulars(imo, name, "General Cargo Ship", "Panama", built,
                null, dwt == null ? null : new BigDecimal(dwt), null, null,
                "https://example.test/" + imo);
    }

    @Test
    void acceptsAHullThatAgreesOnEveryFactThereIsToCheck() {
        LookupMatcher.Known known = new LookupMatcher.Known(
                "HACI HILMI II", 1992, new BigDecimal("6976"), "Panama");

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(candidate("HACI HILMI-II", 1992, "6977", "Panama")), 55);

        assertThat(best).isPresent();
        assertThat(best.get().confidence()).isEqualTo(100);
        // The evidence, in figures. A person is being asked to take another database's word
        // for a ship's identity and can only judge that from what actually matched.
        assertThat(best.get().reasons())
                .anyMatch(r -> r.contains("Name matches exactly"))
                .anyMatch(r -> r.contains("Built 1992"))
                .anyMatch(r -> r.contains("DWT 6977"));
        assertThat(best.get().corroborated()).isTrue();
    }

    @Test
    void refusesTheRightNameOnTheWrongShip() {
        // Same name, twenty years and fifteen thousand tonnes apart. This is the case the
        // floor exists for: without it she would be the only candidate and would win.
        LookupMatcher.Known known = new LookupMatcher.Known(
                "ATLANTIC", 1992, new BigDecimal("6976"), "Panama");

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(candidate("ATLANTIC", 2012, "22000", "Liberia")), 55);

        assertThat(best).isEmpty();
    }

    @Test
    void refusesToChooseBetweenTwoEquallyGoodCandidates() {
        // Two ships answering equally well is not a match, it is a question — and it is the
        // moment a machine picking one would be most confidently wrong.
        LookupMatcher.Known known = new LookupMatcher.Known("LADY MERAL", 2004, null, null);

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(known, List.of(
                withImo("9111111", "LADY MERAL", 2004, null),
                withImo("9222222", "LADY MERAL", 2004, null)), 55);

        assertThat(best).isEmpty();
    }

    @Test
    void marksAMatchOnTheNameAloneAsUncorroborated() {
        // An email that gave a name and nothing else. The ratio is 100% because the one thing
        // that could be checked agreed - and that is exactly why the ratio is not enough on
        // its own: the name is the query coming back, not evidence. A single unambiguous hit
        // is still worth offering, flagged for what it rests on.
        LookupMatcher.Known nameOnly = new LookupMatcher.Known("HACI HILMI II", null, null, null);
        List<VesselParticulars> one = List.of(candidate("HACI HILMI-II", 1992, "6977", "Panama"));

        List<LookupMatcher.Scored> scored = LookupMatcher.score(nameOnly, one);
        assertThat(scored.get(0).confidence()).isEqualTo(100);
        assertThat(scored.get(0).corroborated()).isFalse();
        assertThat(scored.get(0).reasons()).containsExactly("Name matches exactly");

        assertThat(LookupMatcher.best(nameOnly, one, 55)).isPresent();
    }

    @Test
    void refusesANameOnlyMatchWhenSeveralShipsAnswerToIt() {
        // The shape of the mistake the whole class exists to avoid. Nothing but the name
        // agreed, the name is what was searched for, and there is no reason to prefer one of
        // these hulls over the other - so none is offered.
        LookupMatcher.Known nameOnly = new LookupMatcher.Known("LADY MERAL", null, null, null);

        assertThat(LookupMatcher.best(nameOnly, List.of(
                withImo("9111111", "LADY MERAL", 2004, "31000"),
                withImo("9222222", "LADY MERAL", 1998, "12000")), 55))
                .isEmpty();
    }

    @Test
    void forgivesTheYearTwoDatabasesRecordDifferently() {
        LookupMatcher.Known known = new LookupMatcher.Known(
                "HACI HILMI II", 1991, new BigDecimal("6976"), null);

        // Keel laid in one year, delivered the next. Still her.
        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(candidate("HACI HILMI-II", 1992, "6977", null)), 55);

        assertThat(best).isPresent();
        assertThat(best.get().reasons()).anyMatch(r -> r.contains("Built 1992 against 1991"));
    }

    @Test
    void treatsADisagreeingFlagAsWeakEvidenceRatherThanADisqualification() {
        // Ships are reflagged, and this database's flag is often the one she wore when
        // somebody last looked. It is recorded and it costs almost nothing.
        LookupMatcher.Known known = new LookupMatcher.Known(
                "HACI HILMI II", 1992, new BigDecimal("6976"), "Turkey");

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(candidate("HACI HILMI-II", 1992, "6977", "Panama")), 55);

        assertThat(best).isPresent();
        assertThat(best.get().disagreements())
                .anyMatch(d -> d.contains("Flies Panama, on file as Turkey"));
    }

    @Test
    void scoresNothingWhenThereIsNothingToCheck() {
        LookupMatcher.Known blank = new LookupMatcher.Known(null, null, null, null);
        List<LookupMatcher.Scored> scored =
                LookupMatcher.score(blank, List.of(candidate("ANYTHING", 2000, "5000", "Panama")));

        // Zero rather than a vacuous hundred: an unverifiable candidate is the one most in
        // need of being marked unverifiable.
        assertThat(scored.get(0).confidence()).isZero();
    }
}
