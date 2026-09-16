package com.chartering.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status column sorts on a SQL CASE written out by hand, because a formula has to be a
 * constant. A status added to the enum and not to the CASE would sort as 99, after everything,
 * and nothing else would notice.
 */
class CargoStatusRankTest {

    @Test
    void everyStatusIsRankedInDeclarationOrder() {
        for (CargoStatus s : CargoStatus.values()) {
            assertThat(Cargo.STATUS_RANK_SQL)
                    .as("rank of %s", s)
                    .contains("when '" + s.name() + "' then " + s.ordinal() + " ");
        }
    }

    @Test
    void notWorkableIsKeptForTheDuplicateTestButNeverMatched() {
        assertThat(CargoStatus.NOT_WORKABLE.isLive()).isFalse();
        assertThat(CargoStatus.NOT_WORKABLE.isRecognisedOnArrival()).isTrue();
        assertThat(CargoStatus.FIXED.isRecognisedOnArrival()).isFalse();
    }
}
