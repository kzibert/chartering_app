package com.chartering.service;

import com.chartering.config.AuthProperties;
import com.chartering.dto.ChangePasswordRequest;
import com.chartering.dto.LoginRequest;
import com.chartering.dto.LoginResponse;
import com.chartering.dto.SessionResponse;
import com.chartering.exception.AuthNotConfiguredException;
import com.chartering.exception.AuthenticationFailedException;
import com.chartering.model.AppUser;
import com.chartering.model.TenantStatus;
import com.chartering.repository.AppUserRepository;
import com.chartering.security.AuthenticatedUser;
import com.chartering.security.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Checks a person's credential against their row and hands back a token.
 *
 * <p>The login is one of the few reads in this application that is not scoped to a desk: the
 * desk is what the login is about to find out. Everything after it is.
 */
@Service
@Slf4j
public class AuthService {

    private final AuthProperties props;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppUserRepository users;

    /**
     * Checked against when the username matches nobody, so that a wrong username costs the
     * same BCrypt round as a wrong password. Skipping it would make an unknown name answer
     * measurably faster, which is enough to enumerate the accounts one guess at a time.
     */
    private final String dummyHash;

    public AuthService(AuthProperties props, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AppUserRepository users) {
        this.props = props;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.users = users;
        this.dummyHash = passwordEncoder.encode("no account has this password");
    }

    /**
     * Verify and issue. Throws {@link AuthenticationFailedException} for a wrong username or
     * a wrong password with one message, because telling an attacker which of the two it was
     * is how a username gets confirmed.
     *
     * <p>Not rolled back on that exception: the failure counter it raised has to stick, or
     * the lockout would never trip.
     *
     * <p>A disabled account or a suspended desk is said plainly, but only <em>after</em> the
     * right password: by then the caller has proved they are the person, and "wrong password"
     * would send them hunting for a typo that is not there.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public LoginResponse login(LoginRequest request) {
        if (users.count() == 0) {
            throw new AuthNotConfiguredException(
                    "No account exists yet. Set AUTH_PASSWORD (or AUTH_PASSWORD_HASH) and restart "
                            + "to create the first one.");
        }

        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        Optional<AppUser> found = users.findByUsernameIgnoreCase(username);
        if (found.isPresent() && found.get().isLocked()) {
            long seconds = Duration.between(OffsetDateTime.now(), found.get().getLockedUntil()).toSeconds() + 1;
            throw new AuthenticationFailedException(
                    "Too many failed attempts. Try again in " + seconds + "s.");
        }

        boolean passwordOk = passwordEncoder.matches(
                password, found.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !passwordOk) {
            found.ifPresent(this::registerFailure);
            throw new AuthenticationFailedException("Wrong username or password.");
        }

        AppUser user = found.get();
        if (!user.isEnabled()) {
            throw new AuthenticationFailedException("This account is disabled. Ask your administrator.");
        }
        if (user.getTenant().getStatus() != TenantStatus.ACTIVE) {
            throw new AuthenticationFailedException("This desk is suspended. Ask your administrator.");
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(OffsetDateTime.now());
        log.info("Auth: login for '{}' (tenant {})", user.getUsername(), user.getTenant().getId());
        return responseFor(user);
    }

    /**
     * Replace the caller's password. The current one is asked for again, and every token the
     * account holds is revoked - including the one making this call, which is why a fresh one
     * comes back.
     */
    @Transactional
    public LoginResponse changePassword(AuthenticatedUser caller, ChangePasswordRequest request) {
        AppUser user = users.findWithTenant(caller.userId())
                .orElseThrow(() -> new AuthenticationFailedException("Not authenticated — please log in."));
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("The current password is not correct.");
        }
        if (request.getNewPassword().equals(request.getCurrentPassword())) {
            throw new IllegalArgumentException("Choose a password different from the current one.");
        }
        PasswordPolicy.check(request.getNewPassword(), user.getUsername());

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        user.revokeTokens();
        log.info("Auth: '{}' changed their password", user.getUsername());
        return responseFor(user);
    }

    @Transactional(readOnly = true)
    public SessionResponse session(AuthenticatedUser caller) {
        AppUser user = users.findWithTenant(caller.userId())
                .orElseThrow(() -> new AuthenticationFailedException("Not authenticated — please log in."));
        return new SessionResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getTenant().getId(),
                user.getTenant().getName(),
                user.isMustChangePassword());
    }

    private LoginResponse responseFor(AppUser user) {
        return new LoginResponse(
                jwtService.issue(user),
                user.getUsername(),
                jwtService.expiryOfNewToken(),
                user.isMustChangePassword());
    }

    /**
     * Per account, on the row. A lockout therefore only stops guesses at the name being
     * guessed - the rest of the desk keeps working - and survives a restart, which an
     * in-memory counter did not.
     */
    private void registerFailure(AppUser user) {
        user.setFailedAttempts(user.getFailedAttempts() + 1);
        int max = props.getMaxFailedAttempts();
        if (max > 0 && user.getFailedAttempts() >= max) {
            user.setLockedUntil(OffsetDateTime.now().plusSeconds(props.getLockoutSeconds()));
            user.setFailedAttempts(0);
            log.warn("Auth: {} failed logins for '{}' — locked until {}",
                    max, user.getUsername(), user.getLockedUntil());
        }
        users.save(user);
    }
}
