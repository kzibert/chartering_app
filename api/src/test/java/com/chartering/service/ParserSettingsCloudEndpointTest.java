package com.chartering.service;

import com.chartering.config.ParserProperties;
import com.chartering.model.AppSetting;
import com.chartering.service.parser.ParserRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The hosted model's address and name: installation settings beside the local ones, with no key
 * among them - each person's key lives in their browser and arrives per request
 * ({@link ParserRoute}).
 */
class ParserSettingsCloudEndpointTest {

    private Map<String, String> table;
    private ParserProperties props;
    private ParserSettings settings;

    /** The settings store as a map, which is all the class uses it as. */
    private SettingsStore fakeRepository() {
        SettingsStore repository = mock(SettingsStore.class);
        when(repository.findByKeyIn(anyCollection())).thenAnswer(call -> {
            Collection<String> keys = call.getArgument(0);
            List<AppSetting> rows = new ArrayList<>();
            keys.forEach(key -> {
                if (table.containsKey(key)) rows.add(row(key, table.get(key)));
            });
            return rows;
        });
        when(repository.findById(any())).thenAnswer(call -> {
            String key = call.getArgument(0);
            return table.containsKey(key)
                    ? java.util.Optional.of(row(key, table.get(key)))
                    : java.util.Optional.empty();
        });
        when(repository.save(any(AppSetting.class))).thenAnswer(call -> {
            AppSetting saved = call.getArgument(0);
            table.put(saved.getKey(), saved.getValue());
            return saved;
        });
        doAnswer(call -> {
            ((Collection<String>) call.getArgument(0)).forEach(table::remove);
            return null;
        }).when(repository).deleteByKeyIn(anyCollection());
        return repository;
    }

    private static AppSetting row(String key, String value) {
        AppSetting setting = new AppSetting();
        setting.setKey(key);
        setting.setValue(value);
        return setting;
    }

    @BeforeEach
    void setUp() {
        table = new HashMap<>();
        props = new ParserProperties();
        settings = new ParserSettings(fakeRepository(), props);
    }

    @Test
    void theCloudAddressIsCheckedAndDefaultsToTheConfiguredOne() {
        assertThat(settings.cloudEndpoint().url()).isEqualTo(props.getCloudUrl());
        assertThat(settings.cloudEndpoint().urlCustomised()).isFalse();

        assertThatThrownBy(() -> settings.updateCloud("not a url", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savingTheCloudModelStoresOnlyItsAddressAndName() {
        settings.updateCloud("https://example.test/v1/chat/completions", "other-model");

        assertThat(table).containsOnlyKeys(ParserSettings.CLOUD_URL, ParserSettings.CLOUD_MODEL);
        assertThat(settings.cloudEndpoint().model()).isEqualTo("other-model");
    }

    @Test
    void resetRestoresTheConfiguredAddresses() {
        settings.updateCloud("https://example.test/v1/chat/completions", "other-model");

        settings.resetCloud();

        assertThat(table).isEmpty();
        assertThat(settings.cloudEndpoint().url()).isEqualTo(props.getCloudUrl());
    }

    @Test
    void theActiveEndpointFollowsTheRouteOnTheThread() {
        settings.updateCloud("https://example.test/v1/chat/completions", null);

        assertThat(settings.activeEndpoint().url()).isEqualTo(settings.endpoint().url());
        assertThat(ParserRoute.callWith(ParserRoute.cloud("gsk_test"), () -> settings.activeEndpoint().url()))
                .isEqualTo("https://example.test/v1/chat/completions");
    }
}
