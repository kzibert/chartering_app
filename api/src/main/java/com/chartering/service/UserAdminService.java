package com.chartering.service;

import com.chartering.dto.UserCreateRequest;
import com.chartering.dto.UserPasswordResponse;
import com.chartering.dto.UserResponse;
import com.chartering.dto.UserUpdateRequest;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.AppUser;
import com.chartering.model.Tenant;
import com.chartering.model.UserRole;
import com.chartering.repository.AppUserRepository;
import com.chartering.repository.TenantRepository;
import com.chartering.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Accounts, as administrators manage them. There is no self-registration: every account is
 * made here, by somebody already trusted with the desk.
 *
 * <p><b>Which desk</b> is decided in this class and nowhere else. A desk administrator acts on
 * their own desk only, and an account on another desk answers 404 rather than 403 - "not
 * allowed" would confirm the id exists, which is a fact about somebody else's desk. A platform
 * administrator may name any desk.
 *
 * <p>Three guards keep a desk from administering itself into a corner: nobody changes their
 * own role or disables themselves (the Admin screen is the wrong place to discover you just
 * locked yourself out), a desk always keeps one enabled administrator, and only a platform
 * administrator touches a platform administrator's account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminService {

    private static final List<UserRole> ADMIN_ROLES = List.of(UserRole.TENANT_ADMIN, UserRole.PLATFORM_ADMIN);

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserResponse> list(AuthenticatedUser caller, Long tenantId) {
        List<AppUser> rows;
        if (isPlatform(caller) && tenantId == null) {
            rows = users.findAllWithTenant();
        } else {
            rows = users.findByTenant(desk(caller, tenantId).getId());
        }
        return rows.stream().map(UserAdminService::toResponse).toList();
    }

    @Transactional
    public UserPasswordResponse create(AuthenticatedUser caller, UserCreateRequest request) {
        Tenant tenant = desk(caller, request.getTenantId());
        requireMayGrant(caller, request.getRole());

        String username = request.getUsername().trim();
        if (username.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("The username must not contain spaces.");
        }
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            // Unique across the installation, so this can be another desk's account. Saying so
            // is unavoidable - the alternative is a save that silently fails - and it gives
            // away a login name, never what is behind it.
            throw new IllegalStateException("The username '" + username + "' is already taken.");
        }

        String typed = blankToNull(request.getPassword());
        String password = typed != null ? typed : PasswordPolicy.temporary();
        PasswordPolicy.check(password, username);

        AppUser user = new AppUser();
        user.setTenant(tenant);
        user.setUsername(username);
        user.setDisplayName(blankToNull(request.getDisplayName()));
        user.setRole(request.getRole());
        user.setPasswordHash(passwordEncoder.encode(password));
        // Whoever typed it, an administrator has seen it.
        user.setMustChangePassword(true);
        user.setCreatedBy(caller.username());
        users.save(user);
        log.info("Admin: '{}' created account '{}' ({}) on tenant {}",
                caller.username(), username, user.getRole(), tenant.getId());
        return new UserPasswordResponse(toResponse(user), typed == null ? password : null);
    }

    @Transactional
    public UserResponse update(AuthenticatedUser caller, Long id, UserUpdateRequest request) {
        AppUser user = load(caller, id);
        requireMayManage(caller, user);
        user.setDisplayName(blankToNull(request.getDisplayName()));

        if (user.getRole() != request.getRole()) {
            if (isSelf(caller, user)) {
                throw new IllegalArgumentException("You cannot change your own role.");
            }
            requireMayGrant(caller, request.getRole());
            if (ADMIN_ROLES.contains(user.getRole()) && !ADMIN_ROLES.contains(request.getRole())) {
                requireAnotherAdmin(user);
            }
            user.setRole(request.getRole());
            user.revokeTokens();
            log.info("Admin: '{}' changed the role of '{}' to {}", caller.username(), user.getUsername(), user.getRole());
        }
        return toResponse(user);
    }

    @Transactional
    public UserResponse setEnabled(AuthenticatedUser caller, Long id, boolean enabled) {
        AppUser user = load(caller, id);
        requireMayManage(caller, user);
        if (user.isEnabled() == enabled) {
            return toResponse(user);
        }
        if (!enabled) {
            if (isSelf(caller, user)) {
                throw new IllegalArgumentException("You cannot disable your own account.");
            }
            if (ADMIN_ROLES.contains(user.getRole())) {
                requireAnotherAdmin(user);
            }
            user.revokeTokens();
        }
        user.setEnabled(enabled);
        log.info("Admin: '{}' {} account '{}'", caller.username(), enabled ? "enabled" : "disabled", user.getUsername());
        return toResponse(user);
    }

    /**
     * A new server-chosen password, shown once. Also the unlock: an account locked out by
     * failed attempts is usually one whose owner forgot the password.
     */
    @Transactional
    public UserPasswordResponse resetPassword(AuthenticatedUser caller, Long id) {
        AppUser user = load(caller, id);
        requireMayManage(caller, user);
        if (isSelf(caller, user)) {
            throw new IllegalArgumentException("Change your own password from your account menu.");
        }
        String password = PasswordPolicy.temporary();
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMustChangePassword(true);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.revokeTokens();
        log.info("Admin: '{}' reset the password of '{}'", caller.username(), user.getUsername());
        return new UserPasswordResponse(toResponse(user), password);
    }

    // ---------------------------------------------------------------------------------------

    /** The desk to act on: the caller's own, or - for a platform administrator - the one named. */
    private Tenant desk(AuthenticatedUser caller, Long requested) {
        Long id = requested == null ? caller.tenantId() : requested;
        if (!isPlatform(caller) && !Objects.equals(id, caller.tenantId())) {
            throw new ResourceNotFoundException("Tenant", id);
        }
        return tenants.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
    }

    private AppUser load(AuthenticatedUser caller, Long id) {
        return users.findWithTenant(id)
                .filter(u -> isPlatform(caller) || u.getTenant().getId().equals(caller.tenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private void requireMayGrant(AuthenticatedUser caller, UserRole role) {
        if (role == UserRole.PLATFORM_ADMIN && !isPlatform(caller)) {
            throw new IllegalArgumentException("Only a platform administrator can grant that role.");
        }
    }

    private void requireMayManage(AuthenticatedUser caller, AppUser target) {
        if (target.getRole() == UserRole.PLATFORM_ADMIN && !isPlatform(caller)) {
            throw new IllegalArgumentException(
                    "Only a platform administrator can change a platform administrator's account.");
        }
    }

    private void requireAnotherAdmin(AppUser leaving) {
        long admins = users.countEnabledInRoles(leaving.getTenant().getId(), ADMIN_ROLES);
        if (leaving.isEnabled() && admins <= 1) {
            throw new IllegalArgumentException(
                    "'" + leaving.getUsername() + "' is the desk's only administrator. Make somebody else one first.");
        }
    }

    private static boolean isPlatform(AuthenticatedUser caller) {
        return caller.role() == UserRole.PLATFORM_ADMIN;
    }

    private static boolean isSelf(AuthenticatedUser caller, AppUser user) {
        return user.getId().equals(caller.userId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static UserResponse toResponse(AppUser u) {
        return new UserResponse(
                u.getId(),
                u.getUsername(),
                u.getDisplayName(),
                u.getRole(),
                u.isEnabled(),
                u.isMustChangePassword(),
                u.isLocked(),
                u.getLastLoginAt(),
                u.getCreatedAt(),
                u.getCreatedBy(),
                u.getTenant().getId(),
                u.getTenant().getName());
    }
}
