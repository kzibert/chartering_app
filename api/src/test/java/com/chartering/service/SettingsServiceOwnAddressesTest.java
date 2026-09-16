package com.chartering.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsServiceOwnAddressesTest {

    @Test
    void trimsLowerCasesAndDropsRepeatsAndBlanks() {
        assertThat(SettingsService.normaliseAddresses(
                Arrays.asList(" Desk@Example.com ", "", null, "desk@example.com", "chartering@example.com")))
                .containsExactly("desk@example.com", "chartering@example.com");
    }

    /** The list is split on commas in SQL, so a comma cannot be let into an entry. */
    @Test
    void refusesWhatIsNotAnAddress() {
        assertThatThrownBy(() -> SettingsService.normaliseAddresses(List.of("desk@example")))
                .hasMessageContaining("desk@example");
        assertThatThrownBy(() -> SettingsService.normaliseAddresses(List.of("a,b@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesAnArrivalWhateverItsCase() {
        Set<String> own = Set.of("desk@example.com");
        assertThat(SettingsService.isOwn(" DESK@example.com", own)).isTrue();
        assertThat(SettingsService.isOwn("broker@example.com", own)).isFalse();
        assertThat(SettingsService.isOwn(null, own)).isFalse();
    }
}
