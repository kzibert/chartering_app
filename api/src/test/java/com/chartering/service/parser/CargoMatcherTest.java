package com.chartering.service.parser;

import com.chartering.model.Cargo;
import com.chartering.model.Port;
import com.chartering.model.TradeArea;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recognising the same cargo arriving twice — and, more importantly, refusing to.
 *
 * <p>The refusals are the half worth the tests. A merge cannot be undone: fold two cargoes
 * into one wrongly and the second one's laycan, freight idea and broker are gone, with
 * nothing on screen ever saying they existed. Missing a duplicate costs a second row on the
 * Cargoes tab, which somebody notices and fixes in a click.
 */
class CargoMatcherTest {

    private static final TradeArea WEST_MED = area(1L, "West Med");
    private static final TradeArea BLACK_SEA = area(2L, "Black Sea");

    private static TradeArea area(Long id, String name) {
        TradeArea a = new TradeArea();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static Port port(Long id, String name, TradeArea area) {
        Port p = new Port();
        p.setId(id);
        p.setName(name);
        p.setTradeArea(area);
        return p;
    }

    private static Cargo existing(String commodity, String quantity, TradeArea loadArea,
                                  LocalDate from, LocalDate to) {
        Cargo c = new Cargo();
        c.setId(7L);
        c.setCommodity(commodity);
        if (quantity != null) c.setQuantity(new BigDecimal(quantity));
        c.setLoadArea(loadArea);
        c.setLaycanFrom(from);
        c.setLaycanTo(to);
        return c;
    }

    private static Extraction.ExtractedCargo parsed(String commodity, String quantity,
                                                    String from, String to) {
        return new Extraction.ExtractedCargo(
                commodity,
                quantity == null ? null : new BigDecimal(quantity),
                "MT", "", null, null, null,
                "", "", "", "",
                from == null ? "" : from, to == null ? "" : to, "",
                "", "", null, null, null, null, null, null, null,
                "", "", "", "", "");
    }

    @Test
    void mergesTheSameEnquiryArrivingFromASecondBroker() {
        Cargo onFile = existing("Wheat", "25000", WEST_MED,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 15));

        Optional<CargoMatcher.Candidate> found = CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", "2026-09-12", "2026-09-18"),
                List.of(onFile), null, WEST_MED);

        assertThat(found).isPresent();
        assertThat(found.get().cargo()).isSameAs(onFile);
        // The evidence, in the words the screen prints. A merge is a judgement and a
        // judgement needs reasons rather than a score.
        assertThat(found.get().reasons())
                .anyMatch(r -> r.contains("Same load area"))
                .anyMatch(r -> r.contains("Quantity"))
                .anyMatch(r -> r.contains("Laycans overlap"));
    }

    @Test
    void recognisesTheCommodityThroughTheWordsBrokersAttachToIt() {
        // Both of these came out of one morning's mail for one enquiry. The model folded the
        // tolerance into the commodity on the second, which an equality test never forgave.
        Cargo onFile = existing("Wheat", "25000", WEST_MED, null, null);

        assertThat(CargoMatcher.findDuplicate(
                parsed("wheat moloo", "25000", null, null), List.of(onFile), null, WEST_MED))
                .isPresent();
        assertThat(CargoMatcher.findDuplicate(
                parsed("abt 25000mt wheat in bulk", "25000", null, null),
                List.of(onFile), null, WEST_MED))
                .isPresent();
    }

    @Test
    void doesNotConfuseTwoGrainsForEachOther() {
        Cargo onFile = existing("Wheat", "25000", WEST_MED, null, null);

        assertThat(CargoMatcher.findDuplicate(
                parsed("Barley", "25000", null, null), List.of(onFile), null, WEST_MED))
                .isEmpty();
        // Sharing a word is not enough, which is why the test is a subset rather than an
        // intersection: pig iron and iron ore share "iron" and are different cargoes.
        Cargo ore = existing("Iron ore", "25000", WEST_MED, null, null);
        assertThat(CargoMatcher.findDuplicate(
                parsed("Pig iron", "25000", null, null), List.of(ore), null, WEST_MED))
                .isEmpty();
    }

    @Test
    void refusesTwoCargoesLoadingInDifferentSeas() {
        Cargo onFile = existing("Wheat", "25000", BLACK_SEA, null, null);

        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", null, null), List.of(onFile), null, WEST_MED))
                .isEmpty();
    }

    @Test
    void refusesQuantitiesTooFarApartToBeOneCargo() {
        Cargo onFile = existing("Wheat", "25000", WEST_MED, null, null);

        // Within the tolerance: the same cargo rounded differently by two brokers.
        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "27000", null, null), List.of(onFile), null, WEST_MED))
                .isPresent();
        // A handysize apart is two cargoes.
        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "50000", null, null), List.of(onFile), null, WEST_MED))
                .isEmpty();
    }

    @Test
    void refusesLaycansAMonthApart() {
        Cargo onFile = existing("Wheat", "25000", WEST_MED,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 15));

        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", "2026-10-10", "2026-10-15"),
                List.of(onFile), null, WEST_MED))
                .isEmpty();

        // Windows that nearly touch are one cargo: a laycan is a promise each broker makes
        // slightly differently, and five days of grace is what covers that.
        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", "2026-09-17", "2026-09-22"),
                List.of(onFile), null, WEST_MED))
                .isPresent();
    }

    @Test
    void abstainsRatherThanGuessingWhenOneSideSaysNothing() {
        // No laycan on file, one in the email. The test does not fire and does not object —
        // a cargo email is mostly silent, and reading silence as disagreement would find no
        // duplicates at all.
        Cargo onFile = existing("Wheat", "25000", WEST_MED, null, null);

        Optional<CargoMatcher.Candidate> found = CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", "2026-09-12", "2026-09-18"),
                List.of(onFile), null, WEST_MED);

        assertThat(found).isPresent();
        // ...and it says so by not claiming the laycans matched.
        assertThat(found.get().reasons()).noneMatch(r -> r.contains("Laycan"));
    }

    @Test
    void willNotProposeAMergeOnTheCommodityAlone() {
        // Neither side names a place this application can compare. Wheat is not evidence:
        // this desk sees wheat every day.
        Cargo onFile = existing("Wheat", null, null, null, null);

        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", null, null, null), List.of(onFile), null, null))
                .isEmpty();
    }

    @Test
    void prefersThePortOverTheAreaWhenBothSidesNameOne() {
        Port salerno = port(10L, "Salerno", WEST_MED);
        Port valencia = port(11L, "Valencia", WEST_MED);

        Cargo onFile = existing("Wheat", "25000", null, null, null);
        onFile.setLoadPort(salerno);

        // Same water, different berths. The port is the more precise statement and it wins.
        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", null, null), List.of(onFile), valencia, WEST_MED))
                .isEmpty();
        assertThat(CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", null, null), List.of(onFile), salerno, WEST_MED))
                .isPresent();
    }

    @Test
    void takesTheCandidateWithTheMostEvidence() {
        Cargo thin = existing("Wheat", null, WEST_MED, null, null);
        thin.setId(3L);
        Cargo thorough = existing("Wheat", "25000", WEST_MED,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 15));
        thorough.setId(4L);

        Optional<CargoMatcher.Candidate> found = CargoMatcher.findDuplicate(
                parsed("Wheat", "25000", "2026-09-12", "2026-09-18"),
                List.of(thin, thorough), null, WEST_MED);

        assertThat(found).isPresent();
        assertThat(found.get().cargo().getId()).isEqualTo(4L);
    }
}
