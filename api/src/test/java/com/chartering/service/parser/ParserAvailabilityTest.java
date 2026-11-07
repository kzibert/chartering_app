package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.exception.FeatureDisabledException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserAvailabilityTest {

    private static final ParserRoute CLOUD = ParserRoute.cloud("gsk_test-key");

    @Test
    void shouldReadLocally_whenTheLocalModelIsEnabled() {
        ParserAvailability availability = availability(true, false);

        assertThat(availability.isAvailable()).isTrue();
        availability.require();
    }

    @Test
    void shouldRefuseEveryone_whenNeitherRouteIsOffered() {
        ParserAvailability availability = availability(false, false);

        assertThat(availability.isAvailable()).isFalse();
        assertThat(ParserRoute.callWith(CLOUD, availability::isAvailable)).isFalse();
        assertThatThrownBy(availability::require)
                .isInstanceOf(FeatureDisabledException.class)
                .hasMessage("The email parser is not enabled on this deployment (PARSER_ENABLED).");
    }

    @Test
    void shouldAskForAKey_whenOnlyTheCloudIsOfferedAndNoneWasSent() {
        ParserAvailability availability = availability(false, true);

        assertThat(availability.isAvailable()).isFalse();
        assertThatThrownBy(availability::require)
                .isInstanceOf(FeatureDisabledException.class)
                .hasMessageContaining("your own key");
    }

    @Test
    void shouldLetAnyoneWithAKeyRead_whenTheCloudIsOffered() {
        ParserAvailability availability = availability(false, true);

        assertThat(ParserRoute.callWith(CLOUD, availability::isAvailable)).isTrue();
        ParserRoute.runWith(CLOUD, availability::require);
    }

    @Test
    void shouldRefuseACloudRoute_whenTheCloudIsNotOffered() {
        ParserAvailability availability = availability(true, false);

        assertThat(ParserRoute.callWith(CLOUD, availability::isAvailable)).isFalse();
    }

    @Test
    void shouldRunTimers_onlyWithALocalModel() {
        assertThat(availability(true, false).runsTimers()).isTrue();
        assertThat(availability(true, true).runsTimers()).isTrue();
        assertThat(availability(false, true).runsTimers()).isFalse();
        assertThat(availability(false, false).runsTimers()).isFalse();
    }

    @Test
    void shouldCarryTheRouteToAnotherThread() throws Exception {
        ParserAvailability availability = availability(false, true);
        boolean[] seen = new boolean[1];
        Runnable carried = ParserRoute.callWith(CLOUD,
                () -> ParserRoute.carry(() -> seen[0] = availability.isAvailable()));

        Thread worker = new Thread(carried);
        worker.start();
        worker.join();

        assertThat(seen[0]).isTrue();
        assertThat(ParserRoute.current()).isEqualTo(ParserRoute.LOCAL);
    }

    @Test
    void shouldNeverPrintTheKey() {
        assertThat(CLOUD.toString()).doesNotContain("gsk_test-key");
    }

    private static ParserAvailability availability(boolean local, boolean cloud) {
        ParserProperties props = new ParserProperties();
        props.setEnabled(local);
        props.setCloudEnabled(cloud);
        return new ParserAvailability(props);
    }
}
