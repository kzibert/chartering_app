package com.chartering.repository;

import com.chartering.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/**
 * Raw access to {@code app_settings}. Nothing but {@link com.chartering.service.SettingsStore}
 * should use it: every query here names its scope, and choosing the scope is that class's job.
 */
public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

    @Query("select s from AppSetting s where s.tenantId = :tenantId and s.key in :keys")
    List<AppSetting> findForTenant(Long tenantId, Collection<String> keys);

    @Query("select s from AppSetting s where s.tenantId is null and s.key in :keys")
    List<AppSetting> findForPlatform(Collection<String> keys);

    @Modifying
    @Query("delete from AppSetting s where s.tenantId = :tenantId and s.key in :keys")
    void deleteForTenant(Long tenantId, Collection<String> keys);

    @Modifying
    @Query("delete from AppSetting s where s.tenantId is null and s.key in :keys")
    void deleteForPlatform(Collection<String> keys);
}
