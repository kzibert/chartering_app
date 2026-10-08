package com.chartering.service;

import com.chartering.model.AppSetting;
import com.chartering.model.UserRole;
import com.chartering.repository.AppSettingRepository;
import com.chartering.security.AuthenticatedUser;
import com.chartering.service.feed.FeedSettings;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where every setting is read and written, and the one place that knows whose it is.
 *
 * <p><b>Two scopes.</b> Most settings are how a desk works - match weights, mail pacing, its
 * own addresses, its prompts - and belong to the desk on the thread. A few describe the
 * installation: the model servers every desk shares, how often the one parser sweep and the
 * one board fetch run, and the context window the one served model has. Those cannot differ
 * per desk because there is only one of each thing they describe, so they are stored once,
 * with no desk, and {@link #PLATFORM_KEYS} is the list.
 *
 * <p>The settings classes ask by key and do not know which scope a key is in. That is the
 * point of routing here: a group of keys read together (the Feed card reads the context
 * window beside its own prompts) can straddle the two, and each class keeps reading its group
 * in one call.
 *
 * <p><b>Who may change what.</b> A desk's settings are changed by its administrators, the
 * installation's by a platform administrator; work with no logged-in caller (a timer) is not
 * asked. Saving a value equal to the stored one is not a change, so a desk administrator
 * saving a whole card that happens to carry an installation value is not refused for it - and
 * a reset by a desk administrator resets the desk's keys and leaves the installation's alone.
 */
@Component
@RequiredArgsConstructor
public class SettingsStore {

    /** The installation's settings. Every other key is a desk's. */
    static final Set<String> PLATFORM_KEYS = Set.of(
            ParserSettings.SWEEP_INTERVAL_MINUTES,
            ParserSettings.SWEEP_BATCH_SIZE,
            ParserSettings.SWEEP_MAX_AGE_DAYS,
            ParserSettings.MODEL_URL,
            ParserSettings.MODEL_NAME,
            FeedSettings.MODEL_URL,
            FeedSettings.MODEL_NAME,
            FeedSettings.FETCH_INTERVAL_MINUTES,
            FeedSettings.CONTEXT_WINDOW_TOKENS);

    private final AppSettingRepository repository;

    public static boolean isPlatform(String key) {
        return PLATFORM_KEYS.contains(key);
    }

    /** The stored rows among {@code keys}, each from its own scope. Absent keys are simply missing. */
    public List<AppSetting> findByKeyIn(Collection<String> keys) {
        List<String> platform = keys.stream().filter(SettingsStore::isPlatform).toList();
        List<String> desk = keys.stream().filter(k -> !isPlatform(k)).toList();
        List<AppSetting> rows = new ArrayList<>();
        if (!platform.isEmpty()) rows.addAll(repository.findForPlatform(platform));
        if (!desk.isEmpty()) rows.addAll(repository.findForTenant(TenantContext.require(), desk));
        return rows;
    }

    public Optional<AppSetting> findById(String key) {
        return findByKeyIn(List.of(key)).stream().findFirst();
    }

    /**
     * Insert or update. A new row is put in its key's scope here, whatever the caller set,
     * so a settings class cannot file a desk's value as the installation's or the reverse.
     */
    public AppSetting save(AppSetting setting) {
        boolean platform = isPlatform(setting.getKey());
        Optional<AppSetting> stored = setting.getId() == null ? findById(setting.getKey()) : Optional.empty();
        if (stored.isPresent()) {
            if (Objects.equals(stored.get().getValue(), setting.getValue())) {
                return stored.get();
            }
            stored.get().setValue(setting.getValue());
            setting = stored.get();
        }
        requireMayChange(setting.getKey(), platform);
        setting.setTenantId(platform ? null : TenantContext.require());
        return repository.save(setting);
    }

    /** Remove overrides, so the keys fall back to their defaults. See the class note on resets. */
    public void deleteByKeyIn(Collection<String> keys) {
        List<String> platform = keys.stream().filter(SettingsStore::isPlatform).toList();
        List<String> desk = keys.stream().filter(k -> !isPlatform(k)).toList();
        if (!desk.isEmpty()) {
            requireMayChange(desk.get(0), false);
            repository.deleteForTenant(TenantContext.require(), desk);
        }
        if (!platform.isEmpty() && mayChange(true)) {
            repository.deleteForPlatform(platform);
        }
    }

    private static void requireMayChange(String key, boolean platform) {
        if (!mayChange(platform)) {
            throw new IllegalArgumentException(platform
                    ? "Only a platform administrator can change '" + key + "' — it applies to every desk."
                    : "Only a desk administrator can change the desk's settings.");
        }
    }

    private static boolean mayChange(boolean platform) {
        return AuthenticatedUser.current()
                .map(u -> u.role().atLeast(platform ? UserRole.PLATFORM_ADMIN : UserRole.TENANT_ADMIN))
                .orElse(true);
    }
}
