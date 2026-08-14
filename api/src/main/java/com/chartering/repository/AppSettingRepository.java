package com.chartering.repository;

import com.chartering.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

    /** All overrides for one group of keys, fetched in a single round trip. */
    List<AppSetting> findByKeyIn(Collection<String> keys);

    void deleteByKeyIn(Collection<String> keys);
}
