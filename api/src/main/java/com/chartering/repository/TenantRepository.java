package com.chartering.repository;

import com.chartering.model.Tenant;
import com.chartering.model.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    @Query("select t from Tenant t where lower(t.name) = lower(:name)")
    Optional<Tenant> findByNameIgnoreCase(String name);

    @Query("select t.id from Tenant t where t.status = :status order by t.id")
    List<Long> findIdsByStatus(TenantStatus status);
}
