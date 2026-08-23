package com.chartering.service;

import com.chartering.config.AuthProperties;
import com.chartering.dto.LoginRequest;
import com.chartering.dto.LoginResponse;
import com.chartering.exception.AuthNotConfiguredException;
import com.chartering.exception.AuthenticationFailedException;
import com.chartering.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit, no Spring context and no database: everything here is decided by two objects
 * holding configuration, and standing a context up to test them would only make the test
 * slower and its failures less specific.
 */
class AuthServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256";
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AuthService serviceWith(AuthProperties props) {
        return new AuthService(props, encoder, new JwtService(props));
    }

    private AuthProperties props() {
        AuthProperties p = new AuthProperties();
        p.setUsername("skipper");
        p.setPassword("correct horse battery staple");
        p.setJwtSecret(SECRET);
        return p;
    }

    @Test
    void issuesATokenForTheRightCredentials() {
        AuthProperties p = props();
        AuthService service = serviceWith(p);

        LoginResponse res = service.login(login("skipper", "correct horse battery staple"));

        assertThat(res.username()).isEqualTo("skipper");
        assertThat(res.expiresAt()).isAfter(java.time.Instant.now());

        // The token has to verify against a service holding the same key, which is what the
        // request filter does on every call.
        assertThat(new JwtService(p).subjectOf(res.token())).contains("skipper");
    }

    @Test
    void rejectsTheWrongPasswordAndTheWrongUsernameIdentically() {
        AuthService service = serviceWith(props());

        assertThatThrownBy(() -> service.login(login("skipper", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Wrong username or password.");

        assertThatThrownBy(() -> service.login(login("someone-else", "correct horse battery staple")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Wrong username or password.");
    }

    @Test
    void acceptsAPreHashedPasswordAndIgnoresThePlaintextBesideIt() {
        AuthProperties p = props();
        p.setPassword("this one is ignored");
        p.setPasswordHash(encoder.encode("the real one"));

        AuthService service = serviceWith(p);

        assertThat(service.login(login("skipper", "the real one")).username()).isEqualTo("skipper");
        assertThatThrownBy(() -> service.login(login("skipper", "this one is ignored")))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void locksOutAfterTooManyFailuresAndSaysSo() {
        AuthProperties p = props();
        p.setMaxFailedAttempts(3);
        p.setLockoutSeconds(60);
        AuthService service = serviceWith(p);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.login(login("skipper", "wrong")))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        // The right password is refused too — that is the point of a lockout.
        assertThatThrownBy(() -> service.login(login("skipper", "correct horse battery staple")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("Too many failed attempts");
    }

    @Test
    void refusesEveryLoginWhenNoPasswordIsConfigured() {
        AuthProperties p = props();
        p.setPassword(null);
        p.setPasswordHash(null);
        AuthService service = serviceWith(p);

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.login(login("skipper", "anything")))
                .isInstanceOf(AuthNotConfiguredException.class);
    }

    @Test
    void refusesToStartWithASigningKeyTooShortForHs256() {
        AuthProperties p = props();
        p.setJwtSecret("too-short");

        assertThatThrownBy(() -> serviceWith(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        AuthProperties mine = props();
        AuthService service = serviceWith(mine);
        String token = service.login(login("skipper", "correct horse battery staple")).token();

        AuthProperties theirs = props();
        theirs.setJwtSecret("a-completely-different-key-of-sufficient-length");
        assertThat(new JwtService(theirs).subjectOf(token)).isEmpty();
    }

    private LoginRequest login(String username, String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }
}
