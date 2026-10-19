package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.UserRole;
import com.chartering.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserAvailabilityTest {

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldLetAnyUserUseIntake_whenLocalAndEnabled() {
        ParserAvailability availability = availability(false, true);
        signIn(UserRole.USER);

        assertThat(availability.isAvailable()).isTrue();
        availability.require();
    }

    @Test
    void shouldRefuseEveryone_whenSwitchIsOff() {
        ParserAvailability availability = availability(false, false);
        signIn(UserRole.PLATFORM_ADMIN);

        assertThat(availability.isAvailable()).isFalse();
        assertThatThrownBy(availability::require)
                .isInstanceOf(FeatureDisabledException.class)
                .hasMessage("The email parser is not enabled on this deployment (PARSER_ENABLED).");
    }

    @Test
    void shouldRefuseAUser_whenCloud() {
        ParserAvailability availability = availability(true, true);
        signIn(UserRole.USER);

        assertThat(availability.isAvailable()).isFalse();
        assertThatThrownBy(availability::require)
                .isInstanceOf(FeatureDisabledException.class)
                .hasMessage("Intake on this deployment reads with a cloud model and is open to administrators only.");
    }

    @Test
    void shouldAllowTenantAndPlatformAdmins_whenCloud() {
        ParserAvailability availability = availability(true, true);

        signIn(UserRole.TENANT_ADMIN);
        assertThat(availability.isAvailable()).isTrue();
        availability.require();

        signIn(UserRole.PLATFORM_ADMIN);
        assertThat(availability.isAvailable()).isTrue();
        availability.require();
    }

    @Test
    void shouldLetTheSweepWorkerThrough_whenCloud() {
        ParserAvailability availability = availability(true, true);

        assertThat(availability.isAvailable()).isTrue();
        availability.require();
    }

    @Test
    void shouldRefuseAnAnonymousRequest_whenCloud() {
        ParserAvailability availability = availability(true, true);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(availability.isAvailable()).isFalse();
        assertThatThrownBy(availability::require).isInstanceOf(FeatureDisabledException.class);
    }

    @Test
    void shouldRefuseAForeignPrincipal_whenCloud() {
        ParserAvailability availability = availability(true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of()));

        assertThat(availability.isAvailable()).isFalse();
    }

    @Test
    void shouldRunTimers_onlyWhenEnabledAndLocal() {
        assertThat(availability(false, true).runsTimers()).isTrue();
        assertThat(availability(false, false).runsTimers()).isFalse();
        assertThat(availability(true, true).runsTimers()).isFalse();
        assertThat(availability(true, false).runsTimers()).isFalse();
    }

    private static ParserAvailability availability(boolean cloud, boolean enabled) {
        ParserProperties props = new ParserProperties();
        props.setEnabled(enabled);
        props.setProvider(cloud ? ParserProperties.Provider.CLOUD : ParserProperties.Provider.LOCAL);
        return new ParserAvailability(props);
    }

    private static void signIn(UserRole role) {
        AuthenticatedUser user = new AuthenticatedUser(42L, 7L, "pat", role, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
