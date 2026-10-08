package com.chartering.service;

import com.chartering.dto.TenantCreateRequest;
import com.chartering.dto.TenantCreatedResponse;
import com.chartering.dto.TenantResponse;
import com.chartering.dto.UserCreateRequest;
import com.chartering.dto.UserPasswordResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.Tenant;
import com.chartering.model.TenantStatus;
import com.chartering.model.UserRole;
import com.chartering.repository.AppUserRepository;
import com.chartering.repository.TenantRepository;
import com.chartering.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Desks, as the platform administrator manages them. Reached by that role only
 * (SecurityConfig).
 *
 * <p>A desk is never deleted: every row of its data carries a foreign key to it, and a
 * deletion would be either refused by those keys or - if they cascaded - the quietest way to
 * lose a customer's whole history. Suspending stops every login and every background job for
 * it and keeps the data, which is the reversible version of the same decision.
 *
 * <p>Nothing here reads a desk's data. Managing desks and reading what is on them are
 * different powers, and this role has only the first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAdminService {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final UserAdminService userAdmin;

    @Transactional(readOnly = true)
    public List<TenantResponse> list() {
        return tenants.findAll().stream()
                .sorted(Comparator.comparing(Tenant::getId))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TenantCreatedResponse create(AuthenticatedUser caller, TenantCreateRequest request) {
        String name = request.getName().trim();
        requireNameFree(name, null);

        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenants.save(tenant);

        UserCreateRequest admin = new UserCreateRequest();
        admin.setUsername(request.getAdminUsername());
        admin.setDisplayName(request.getAdminDisplayName());
        admin.setRole(UserRole.TENANT_ADMIN);
        admin.setTenantId(tenant.getId());
        admin.setPassword(request.getAdminPassword());
        UserPasswordResponse created = userAdmin.create(caller, admin);

        log.info("Admin: '{}' created tenant {} '{}' with administrator '{}'",
                caller.username(), tenant.getId(), name, created.user().username());
        return new TenantCreatedResponse(toResponse(tenant), created);
    }

    @Transactional
    public TenantResponse rename(Long id, String name) {
        Tenant tenant = load(id);
        String trimmed = name.trim();
        requireNameFree(trimmed, id);
        tenant.setName(trimmed);
        return toResponse(tenant);
    }

    /**
     * Suspend or reactivate. Suspending one's own desk is refused for the reason disabling
     * one's own account is: the screen to undo it is behind the login it just switched off.
     */
    @Transactional
    public TenantResponse setStatus(AuthenticatedUser caller, Long id, TenantStatus status) {
        Tenant tenant = load(id);
        if (status == TenantStatus.SUSPENDED && id.equals(caller.tenantId())) {
            throw new IllegalArgumentException("You cannot suspend the desk your own account is on.");
        }
        tenant.setStatus(status);
        log.info("Admin: '{}' set tenant {} to {}", caller.username(), id, status);
        return toResponse(tenant);
    }

    private Tenant load(Long id) {
        return tenants.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
    }

    private void requireNameFree(String name, Long except) {
        tenants.findByNameIgnoreCase(name)
                .filter(t -> !t.getId().equals(except))
                .ifPresent(t -> {
                    throw new IllegalStateException("A desk called '" + name + "' already exists.");
                });
    }

    private TenantResponse toResponse(Tenant t) {
        return new TenantResponse(t.getId(), t.getName(), t.getStatus(), t.getCreatedAt(),
                users.countByTenantId(t.getId()));
    }
}
