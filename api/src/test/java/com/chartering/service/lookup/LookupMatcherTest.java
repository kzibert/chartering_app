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
    void takesAnAgreeingImoAsTheAnswerAndNotAsMoreEvidence() {
        // She has been renamed and one database rounds her deadweight. Neither is a reason
        // for doubt: the number is unique and it survives a rename, which is the whole reason
        // former names are kept at all.
        LookupMatcher.Known known = new LookupMatcher.Known(
                "9133513", "TARANTO OLD NAME", 1998, new BigDecimal("2800"), null);

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(withImo("9133513", "TARANTO", 2001, "3005")), 55);

        assertThat(best).isPresent();
        assertThat(best.get().confidence()).isEqualTo(100);
        assertThat(best.get().corroborated()).isTrue();
        assertThat(best.get().reasons()).first().asString().contains("IMO 9133513 matches");
        // The disagreements are still printed - "she is called TARANTO now" is exactly what
        // somebody opening this needs to read. They just no longer move the figure.
        assertThat(best.get().disagreements()).isNotEmpty();
    }

    @Test
    void refusesACandidateWhoseImoContradictsTheOneWeHold() {
        // The more important half. A name search brings back ships that answer to the name,
        // and one of them can carry a hundred per cent of it while being another owner's
        // vessel - which the number says outright.
        LookupMatcher.Known known = new LookupMatcher.Known(
                "9133513", "TARANTO", 2001, new BigDecimal("3005"), "Panama");

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(withImo("9222222", "TARANTO", 2001, "3005")), 55);

        assertThat(best).isEmpty();
        assertThat(LookupMatcher.score(known, List.of(withImo("9222222", "TARANTO", 2001, "3005")))
                .get(0).disagreements())
                .anyMatch(d -> d.contains("IMO 9222222, not 9133513"));
    }

    @Test
    void scoresOnTheOtherFactsWhenOnlyOneSideCarriesANumber() {
        // Most of this mail: a circular names a ship and not her number. The IMO test abstains
        // exactly as every other test does when either side is silent.
        LookupMatcher.Known known = new LookupMatcher.Known(
                null, "HACI HILMI II", 1992, new BigDecimal("6976"), null);

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(
                known, List.of(candidate("HACI HILMI-II", 1992, "6977", null)), 55);

        assertThat(best).isPresent();
        assertThat(best.get().reasons()).noneMatch(r -> r.contains("IMO"));
    }

    @Test
    void doesNotCountASubstringHitAsAnotherShipAnsweringToTheName() {
        // The source matches a name as a substring, so a search for TARANTO brings back MSC
        // TARANTO and SPIRIT OF TARANTO as well. They are two other ships, they score
        // nothing, and counting them as competition had the real hull withheld as ambiguous
        // on a name-only lookup - which is every lookup where the email gave only a name.
        LookupMatcher.Known known = new LookupMatcher.Known("TARANTO", null, null, null);

        Optional<LookupMatcher.Scored> best = LookupMatcher.best(known, List.of(
                withImo("9133513", "TARANTO", null, null),
                withImo("9475258", "MSC TARANTO", null, null),
                withImo("9911111", "SPIRIT OF TARANTO", null, null)), 55);

        assertThat(best).isPresent();
        assertThat(best.get().candidate().imo()).isEqualTo("9133513");
        // Offered, and said out loud to rest on nothing but the name.
        assertThat(best.get().corroborated()).isFalse();
    }

    @Test
    void stillRefusesWhenTwoHullsGenuinelyAnswerToTheName() {
        // The rule the test above must not have broken. Two ships of one name, nothing else
        // to tell them apart, is a question for a person and not a match.
        LookupMatcher.Known known = new LookupMatcher.Known("TARANTO", null, null, null);

        assertThat(LookupMatcher.best(known, List.of(
                withImo("9133513", "TARANTO", null, null),
                withImo("9222222", "TARANTO", null, null)), 55)).isEmpty();
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
