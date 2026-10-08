package com.chartering.service;

import com.chartering.config.AuthProperties;
import com.chartering.dto.ChangePasswordRequest;
import com.chartering.dto.LoginRequest;
import com.chartering.dto.LoginResponse;
import com.chartering.exception.AuthNotConfiguredException;
import com.chartering.exception.AuthenticationFailedException;
import com.chartering.model.AppUser;
import com.chartering.model.Tenant;
import com.chartering.model.TenantStatus;
import com.chartering.model.UserRole;
import com.chartering.repository.AppUserRepository;
import com.chartering.security.AuthenticatedUser;
import com.chartering.security.JwtService;
import com.chartering.security.TokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * No Spring context and no database: the repository is a mock answering with one account,
 * which is all a login ever reads. Standing a context up would only make the failures less
 * specific.
 */
class AuthServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256";
    private static final String PASSWORD = "correct horse battery staple";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private AuthProperties props;
    private AppUser skipper;

    @BeforeEach
    void setUp() {
        props = new AuthProperties();
        props.setJwtSecret(SECRET);

        Tenant desk = new Tenant();
        desk.setId(7L);
        desk.setName("North desk");

        skipper = new AppUser();
        skipper.setId(42L);
        skipper.setTenant(desk);
        skipper.setUsername("skipper");
        skipper.setPasswordHash(encoder.encode(PASSWORD));
        skipper.setRole(UserRole.USER);

        when(users.count()).thenReturn(1L);
        when(users.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase("skipper")).thenReturn(Optional.of(skipper));
        when(users.findWithTenant(anyLong())).thenReturn(Optional.empty());
        when(users.findWithTenant(42L)).thenReturn(Optional.of(skipper));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AuthService service() {
        return new AuthService(props, encoder, new JwtService(props), users);
    }

    @Test
    void issuesATokenNamingTheAccountAndItsDesk() {
        LoginResponse res = service().login(login("skipper", PASSWORD));

        assertThat(res.username()).isEqualTo("skipper");
        assertThat(res.mustChangePassword()).isFalse();
        assertThat(new JwtService(props).claimsOf(res.token()))
                .contains(new TokenClaims(42L, 7L, 0));
        assertThat(skipper.getLastLoginAt()).isNotNull();
    }

    @Test
    void rejectsTheWrongPasswordAndAnUnknownUsernameIdentically() {
        AuthService service = service();

        assertThatThrownBy(() -> service.login(login("skipper", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Wrong username or password.");
        assertThatThrownBy(() -> service.login(login("someone-else", PASSWORD)))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Wrong username or password.");
    }

    @Test
    void locksOutThatAccountAfterTooManyFailures() {
        props.setMaxFailedAttempts(3);
        props.setLockoutSeconds(60);
        AuthService service = service();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.login(login("skipper", "wrong")))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        // The right password is refused too — that is the point of a lockout.
        assertThatThrownBy(() -> service.login(login("skipper", PASSWORD)))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("Too many failed attempts");
        assertThat(skipper.isLocked()).isTrue();
    }

    @Test
    void saysADisabledAccountIsDisabledOnlyAfterTheRightPassword() {
        skipper.setEnabled(false);
        AuthService service = service();

        assertThatThrownBy(() -> service.login(login("skipper", "wrong")))
                .hasMessage("Wrong username or password.");
        assertThatThrownBy(() -> service.login(login("skipper", PASSWORD)))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void refusesALoginToASuspendedDesk() {
        skipper.getTenant().setStatus(TenantStatus.SUSPENDED);

        assertThatThrownBy(() -> service().login(login("skipper", PASSWORD)))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void refusesEveryLoginWhileNoAccountExists() {
        when(users.count()).thenReturn(0L);

        assertThatThrownBy(() -> service().login(login("skipper", PASSWORD)))
                .isInstanceOf(AuthNotConfiguredException.class);
    }

    @Test
    void changingThePasswordRevokesEveryEarlierToken() {
        skipper.setMustChangePassword(true);
        AuthService service = service();
        String before = service.login(login("skipper", PASSWORD)).token();

        ChangePasswordRequest change = new ChangePasswordRequest();
        change.setCurrentPassword(PASSWORD);
        change.setNewPassword("a much better passphrase");
        LoginResponse after = service.changePassword(caller(), change);

        assertThat(skipper.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("a much better passphrase", skipper.getPasswordHash())).isTrue();
        // The filter compares the token's version with the row's; the old one no longer matches.
        JwtService jwt = new JwtService(props);
        assertThat(jwt.claimsOf(before).orElseThrow().version()).isNotEqualTo(skipper.getTokenVersion());
        assertThat(jwt.claimsOf(after.token()).orElseThrow().version()).isEqualTo(skipper.getTokenVersion());
    }

    @Test
    void refusesANewPasswordTheShortRuleOrTheWrongCurrentOneWouldLetThrough() {
        AuthService service = service();

        ChangePasswordRequest wrongCurrent = new ChangePasswordRequest();
        wrongCurrent.setCurrentPassword("not it");
        wrongCurrent.setNewPassword("a much better passphrase");
        assertThatThrownBy(() -> service.changePassword(caller(), wrongCurrent))
                .isInstanceOf(IllegalArgumentException.class);

        ChangePasswordRequest tooShort = new ChangePasswordRequest();
        tooShort.setCurrentPassword(PASSWORD);
        tooShort.setNewPassword("short");
        assertThatThrownBy(() -> service.changePassword(caller(), tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void refusesToStartWithASigningKeyTooShortForHs256() {
        props.setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtService(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        String token = service().login(login("skipper", PASSWORD)).token();

        AuthProperties theirs = new AuthProperties();
        theirs.setJwtSecret("a-completely-different-key-of-sufficient-length");
        assertThat(new JwtService(theirs).claimsOf(token)).isEmpty();
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(42L, 7L, "skipper", UserRole.USER, skipper.isMustChangePassword());
    }

    private LoginRequest login(String username, String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }
}
