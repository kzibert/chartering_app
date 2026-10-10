package com.chartering.service;

import com.chartering.config.ParserProperties;
import com.chartering.model.AppSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The few-shot experiment's settings: what is off by default, what is refused, and whose it is.
 *
 * <p>The installation scope is the one that matters most. The examples change what the single served
 * model is asked, so if a desk could switch them on the parser would change for every desk at once,
 * with nothing on the screen of the others to say why.
 */
class ParserSettingsFewShotTest {

    private Map<String, String> table;
    private ParserSettings settings;

    @BeforeEach
    void setUp() {
        table = new HashMap<>();
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
            return table.containsKey(key) ? Optional.of(row(key, table.get(key))) : Optional.empty();
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
        settings = new ParserSettings(repository, new ParserProperties());
    }

    private static AppSetting row(String key, String value) {
        AppSetting setting = new AppSetting();
        setting.setKey(key);
        setting.setValue(value);
        return setting;
    }

    @Test
    void onWithTheMeasuredConfiguration() {
        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(2, 6_000));
        assertThat(ParserSettings.fewShotDefaults()).isEqualTo(settings.fewShot());
    }

    @Test
    void aSavedValueIsRead() {
        settings.updateFewShot(3, 20_000);

        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(3, 20_000));
    }

    @Test
    void aNullLeavesTheOtherKnobAlone() {
        settings.updateFewShot(2, 5_000);

        settings.updateFewShot(null, 8_000);

        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(2, 8_000));
    }

    @Test
    void outOfRangeValuesAreRefusedNotClamped() {
        assertThatThrownBy(() -> settings.updateFewShot(9, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.updateFewShot(-1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.updateFewShot(null, 999))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.updateFewShot(null, 60_001))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(table).isEmpty();
        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(2, 6_000));
    }

    @Test
    void theBoundsAreInclusive() {
        settings.updateFewShot(8, 60_000);
        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(8, 60_000));

        settings.updateFewShot(0, 1_000);
        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(0, 1_000));
    }

    @Test
    void aHandEditedRowOutOfRangeReadsAsTheDefault() {
        table.put(ParserSettings.FEW_SHOT_EXAMPLES, "42");
        table.put(ParserSettings.FEW_SHOT_MAX_CHARS, "garbage");

        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(2, 6_000));
    }

    @Test
    void resetRemovesBothOverrides() {
        settings.updateFewShot(4, 30_000);

        settings.resetFewShot();

        assertThat(table).doesNotContainKeys(ParserSettings.FEW_SHOT_EXAMPLES, ParserSettings.FEW_SHOT_MAX_CHARS);
        assertThat(settings.fewShot()).isEqualTo(new ParserSettings.FewShot(2, 6_000));
    }

    @Test
    void bothKnobsAreInstallationWideNotADesks() {
        assertThat(SettingsStore.isPlatform(ParserSettings.FEW_SHOT_EXAMPLES)).isTrue();
        assertThat(SettingsStore.isPlatform(ParserSettings.FEW_SHOT_MAX_CHARS)).isTrue();
    }
}
