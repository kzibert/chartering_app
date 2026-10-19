package com.chartering.service;

import com.chartering.config.ParserProperties;
import com.chartering.model.AppSetting;
import com.chartering.security.CredentialCipher;
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
 * The hosted model's API key: stored encrypted, read back only as a decrypted value the parser
 * uses, and shown to the screen as its last four characters at most.
 */
class ParserSettingsCloudKeyTest {

    private static final String SECRET = "test-credentials-key-for-unit-tests-only";
    private static final String KEY = "gsk_0123456789abcdefSECRETTAIL1234";

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
        settings = new ParserSettings(fakeRepository(), props, new CredentialCipher(SECRET));
    }

    @Test
    void storingAKeyEncryptsItAndTheParserReadsItBack() {
        settings.updateCloud(null, null, KEY, null);

        assertThat(table.get(ParserSettings.CLOUD_API_KEY)).isNotEqualTo(KEY).doesNotContain("SECRETTAIL");
        assertThat(table.get(ParserSettings.CLOUD_API_KEY)).startsWith("v1:");
        assertThat(settings.cloudApiKey()).hasValue(KEY);
        assertThat(settings.cloudApiKeySet()).isTrue();
    }

    @Test
    void theHintIsTheLastFourCharactersOnly() {
        settings.updateCloud(null, null, KEY, null);

        assertThat(settings.cloudApiKeyHint()).isEqualTo("…1234");
    }

    @Test
    void aShortKeyGetsNoTailAtAll() {
        settings.updateCloud(null, null, "abc", null);

        assertThat(settings.cloudApiKeyHint()).isEqualTo("…");
    }

    @Test
    void withNoKeyStoredThereIsNoHintAndNoKey() {
        assertThat(settings.cloudApiKey()).isEmpty();
        assertThat(settings.cloudApiKeySet()).isFalse();
        assertThat(settings.cloudApiKeyHint()).isNull();
    }

    @Test
    void resetCloudLeavesTheKeyAndRestoresTheAddresses() {
        settings.updateCloud("https://example.test/v1/chat/completions", "other-model", KEY, null);

        settings.resetCloud();

        assertThat(table).doesNotContainKeys(ParserSettings.CLOUD_URL, ParserSettings.CLOUD_MODEL);
        assertThat(settings.cloudApiKey()).hasValue(KEY);
    }

    @Test
    void clearingTheKeyRemovesIt() {
        settings.updateCloud(null, null, KEY, null);

        settings.updateCloud(null, null, null, true);

        assertThat(table).doesNotContainKey(ParserSettings.CLOUD_API_KEY);
        assertThat(settings.cloudApiKey()).isEmpty();
    }

    @Test
    void aBlankKeyLeavesTheStoredOneAlone() {
        settings.updateCloud(null, null, KEY, null);

        settings.updateCloud(null, null, "   ", null);

        assertThat(settings.cloudApiKey()).hasValue(KEY);
    }

    @Test
    void withoutCredentialsKeyTheKeyIsRefusedAndNothingIsStored() {
        ParserSettings unkeyed = new ParserSettings(fakeRepository(), props, new CredentialCipher(""));

        assertThat(unkeyed.cloudApiKeyStorable()).isFalse();
        assertThatThrownBy(() -> unkeyed.updateCloud(null, null, KEY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREDENTIALS_KEY is not set");
        assertThat(table).doesNotContainKey(ParserSettings.CLOUD_API_KEY);
    }

    @Test
    void aKeyThatCannotBeDecryptedReadsAsNotSet() {
        settings.updateCloud(null, null, KEY, null);
        ParserSettings otherKey = new ParserSettings(fakeRepository(), props,
                new CredentialCipher("a-different-credentials-key"));

        assertThat(otherKey.cloudApiKey()).isEmpty();
        assertThat(otherKey.cloudApiKeySet()).isFalse();
        assertThat(otherKey.cloudApiKeyHint()).isNull();
    }

    @Test
    void theCloudAddressIsCheckedAndDefaultsToTheConfiguredOne() {
        assertThat(settings.cloudEndpoint().url()).isEqualTo(props.getCloudUrl());
        assertThat(settings.cloudEndpoint().urlCustomised()).isFalse();

        assertThatThrownBy(() -> settings.updateCloud("not a url", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
