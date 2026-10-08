package com.chartering.service;

import com.chartering.dto.UserCreateRequest;
import com.chartering.dto.UserPasswordResponse;
import com.chartering.dto.UserUpdateRequest;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.AppUser;
import com.chartering.model.Tenant;
import com.chartering.model.UserRole;
import com.chartering.repository.AppUserRepository;
import com.chartering.repository.TenantRepository;
import com.chartering.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The rules that decide which desk an administrator may act on, and what they may not do to it. */
class UserAdminServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserAdminService service =
            new UserAdminService(users, tenants, new BCryptPasswordEncoder(4));

    private Tenant north;
    private Tenant south;
    private AppUser northAdmin;
    private AppUser northBroker;
    private AppUser southBroker;

    @BeforeEach
    void setUp() {
        north = tenant(1L, "North");
        south = tenant(2L, "South");
        northAdmin = user(10L, north, "anna", UserRole.TENANT_ADMIN);
        northBroker = user(11L, north, "boris", UserRole.USER);
        southBroker = user(20L, south, "chris", UserRole.USER);

        when(tenants.findById(anyLong())).thenReturn(Optional.empty());
        when(tenants.findById(1L)).thenReturn(Optional.of(north));
        when(tenants.findById(2L)).thenReturn(Optional.of(south));
        when(users.findWithTenant(anyLong())).thenReturn(Optional.empty());
        for (AppUser u : new AppUser[]{northAdmin, northBroker, southBroker}) {
            when(users.findWithTenant(u.getId())).thenReturn(Optional.of(u));
        }
        when(users.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase("chris")).thenReturn(Optional.of(southBroker));
        when(users.countEnabledInRoles(eq(1L), anyList())).thenReturn(1L);
        when(users.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(99L);
            return u;
        });
    }

    @Test
    void anAccountOnAnotherDeskIsNotFoundRatherThanForbidden() {
        assertThatThrownBy(() -> service.resetPassword(northAdminCaller(), southBroker.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(northAdminCaller(), south.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createsAnAccountOnTheCallersOwnDeskWithAOneTimePassword() {
        UserCreateRequest req = new UserCreateRequest();
        req.setUsername("dora");
        req.setRole(UserRole.USER);
        // Naming another desk is ignored for nobody: it is refused.
        req.setTenantId(null);

        UserPasswordResponse res = service.create(northAdminCaller(), req);

        assertThat(res.user().tenantId()).isEqualTo(north.getId());
        assertThat(res.user().mustChangePassword()).isTrue();
        assertThat(res.temporaryPassword()).hasSize(16);
    }

    @Test
    void aDeskAdministratorCannotCreateOnAnotherDeskNorGrantThePlatformRole() {
        UserCreateRequest elsewhere = new UserCreateRequest();
        elsewhere.setUsername("dora");
        elsewhere.setRole(UserRole.USER);
        elsewhere.setTenantId(south.getId());
        assertThatThrownBy(() -> service.create(northAdminCaller(), elsewhere))
                .isInstanceOf(ResourceNotFoundException.class);

        UserCreateRequest platform = new UserCreateRequest();
        platform.setUsername("dora");
        platform.setRole(UserRole.PLATFORM_ADMIN);
        assertThatThrownBy(() -> service.create(northAdminCaller(), platform))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTakenUsernameIsRefusedEvenWhenItBelongsToAnotherDesk() {
        UserCreateRequest req = new UserCreateRequest();
        req.setUsername("chris");
        req.setRole(UserRole.USER);

        assertThatThrownBy(() -> service.create(northAdminCaller(), req))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeskKeepsItsLastAdministrator() {
        AuthenticatedUser platform = new AuthenticatedUser(1L, 1L, "root", UserRole.PLATFORM_ADMIN, false);

        UserUpdateRequest demote = new UserUpdateRequest();
        demote.setRole(UserRole.USER);
        assertThatThrownBy(() -> service.update(platform, northAdmin.getId(), demote))
                .hasMessageContaining("only administrator");
        assertThatThrownBy(() -> service.setEnabled(platform, northAdmin.getId(), false))
                .hasMessageContaining("only administrator");
    }

    @Test
    void nobodyDisablesThemselvesOrChangesTheirOwnRole() {
        assertThatThrownBy(() -> service.setEnabled(northAdminCaller(), northAdmin.getId(), false))
                .hasMessageContaining("your own account");

        UserUpdateRequest promote = new UserUpdateRequest();
        promote.setRole(UserRole.USER);
        assertThatThrownBy(() -> service.update(northAdminCaller(), northAdmin.getId(), promote))
                .hasMessageContaining("your own role");
    }

    @Test
    void disablingAnAccountRevokesItsTokens() {
        int before = northBroker.getTokenVersion();

        service.setEnabled(northAdminCaller(), northBroker.getId(), false);

        assertThat(northBroker.isEnabled()).isFalse();
        assertThat(northBroker.getTokenVersion()).isGreaterThan(before);
    }

    private AuthenticatedUser northAdminCaller() {
        return new AuthenticatedUser(northAdmin.getId(), north.getId(), "anna", UserRole.TENANT_ADMIN, false);
    }

    private static Tenant tenant(Long id, String name) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static AppUser user(Long id, Tenant tenant, String username, UserRole role) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setTenant(tenant);
        u.setUsername(username);
        u.setRole(role);
        u.setPasswordHash("x");
        return u;
    }
}
