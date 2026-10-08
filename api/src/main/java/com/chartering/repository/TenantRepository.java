package com.chartering.repository;

import com.chartering.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    @Query("select t from Tenant t where lower(t.name) = lower(:name)")
    Optional<Tenant> findByNameIgnoreCase(String name);
}
