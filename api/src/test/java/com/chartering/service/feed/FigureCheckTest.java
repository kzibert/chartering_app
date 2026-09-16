package com.chartering.service.feed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FigureCheckTest {

    private static final List<String> MATERIAL = List.of(
            "[Example News, 14 Sep 2026] Rates up\nThe cost of carrying corn from the Danube to Egypt by coaster reached about $110 per ton.",
            "[Example Board, 12 Sep 2026]\nAny Dwt 6-8,000 Mt steel scrap\nM/V EXAMPLE STAR 27.500 dwt, draft 6.265 m, open Iskenderun 22-25 September");

    @Test
    void figuresCopiedFromTheMaterialPassWhateverTheirFormatting() {
        String summary = """
                Coaster freight from the Danube held firm.
                - Corn Danube to Egypt about $110/t (Example News, 2026-09-14).
                - EXAMPLE STAR, 27,500 dwt, opens Iskenderun 22-25 September (Example Board, 12 Sep 2026).
                - Scrap cargoes of 6-8,000 mt are on offer.""";

        assertThat(FigureCheck.unverified(summary, MATERIAL)).isEmpty();
    }

    @Test
    void anInventedRateIsListedWithItsUnit() {
        // The shape of the failure this exists for: a ladder of rates nobody quoted.
        String summary = """
                - $12.50/t for the Danube-Mediterranean corridor.
                - $13.00/t for the Danube-Mediterranean corridor.
                - Corn about $110/t.""";

        assertThat(FigureCheck.unverified(summary, MATERIAL)).containsExactly("$12.50/t", "$13.00/t");
    }

    @Test
    void attributionsYearsAndSingleDigitsAreNotChecked() {
        String summary = "Up to 8 bullet points were asked for in 2026 (Somewhere Else, 2026-09-01).";

        assertThat(FigureCheck.unverified(summary, MATERIAL)).isEmpty();
    }

    @Test
    void anAmbiguousTokenIsFoundUnderEitherReading() {
        assertThat(FigureCheck.readings("6.265")).containsExactly("6265", "6.265");
        assertThat(FigureCheck.readings("27,500")).containsExactly("27500", "27.5");
        assertThat(FigureCheck.unverified("Draft 6.265 m.", MATERIAL)).isEmpty();
    }
}
