package com.chartering.repository;

import com.chartering.model.AppUser;
import com.chartering.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** The login lookup. The desk comes with it, since its status decides the login too. */
    @Query("select u from AppUser u join fetch u.tenant where lower(u.username) = lower(:username)")
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    /** The per-request lookup behind every token. */
    @Query("select u from AppUser u join fetch u.tenant where u.id = :id")
    Optional<AppUser> findWithTenant(Long id);

    @Query("select u from AppUser u join fetch u.tenant where u.tenant.id = :tenantId order by lower(u.username)")
    List<AppUser> findByTenant(Long tenantId);

    @Query("select u from AppUser u join fetch u.tenant order by u.tenant.id, lower(u.username)")
    List<AppUser> findAllWithTenant();

    @Query("""
            select count(u) from AppUser u
            where u.tenant.id = :tenantId and u.enabled = true and u.role in :roles
            """)
    long countEnabledInRoles(Long tenantId, List<UserRole> roles);

    long countByTenantId(Long tenantId);
}
