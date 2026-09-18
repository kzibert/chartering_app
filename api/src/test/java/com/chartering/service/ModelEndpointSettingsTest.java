package com.chartering.service;

import com.chartering.config.FeedProperties;
import com.chartering.config.ParserProperties;
import com.chartering.model.AppSetting;
import com.chartering.repository.AppSettingRepository;
import com.chartering.service.feed.FeedSettings;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which model server each half of the intake talks to.
 *
 * <p>The behaviour worth pinning is not that a setting can be saved — it is the two ways the
 * fallback can go wrong quietly. A feed left inheriting an address the parser has since moved off
 * would summarise against the extraction finetune with nothing on screen saying so, and a model
 * name inherited alongside an address that was <i>not</i> inherited would be sent to a server that
 * has never heard of it. Both are silent, both look like the model being bad at its job.
 */
class ModelEndpointSettingsTest {

    private static final String PARSER_ENV = "http://host.docker.internal:8090/v1/chat/completions";
    private static final String FEED_ENV = "http://host.docker.internal:8091/v1/chat/completions";

    private Map<String, String> table;
    private ParserProperties parserProps;
    private FeedProperties feedProps;
    private ParserSettings parser;
    private FeedSettings feed;

    /** {@code app_settings} as a map, which is all either class uses it as. */
    private AppSettingRepository fakeRepository() {
        AppSettingRepository repository = mock(AppSettingRepository.class);
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
        org.mockito.Mockito.doAnswer(call -> {
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
        parserProps = new ParserProperties();
        parserProps.setUrl(PARSER_ENV);
        feedProps = new FeedProperties();
        AppSettingRepository repository = fakeRepository();
        parser = new ParserSettings(repository, parserProps);
        feed = new FeedSettings(repository, feedProps, parser);
    }

    @Test
    void withNothingStoredEachSideUsesItsOwnEnvironmentVariable() {
        feedProps.setLlmUrl(FEED_ENV);

        assertThat(parser.endpoint().url()).isEqualTo(PARSER_ENV);
        assertThat(parser.endpoint().urlCustomised()).isFalse();
        assertThat(feed.endpoint().url()).isEqualTo(FEED_ENV);
        assertThat(feed.endpoint().urlCustomised()).isFalse();
    }

    @Test
    void aStoredAddressWinsAndReadsAsSetHere() {
        parser.updateEndpoint("http://10.0.0.5:8090/v1/chat/completions", null);

        assertThat(parser.endpoint().url()).isEqualTo("http://10.0.0.5:8090/v1/chat/completions");
        assertThat(parser.endpoint().urlCustomised()).isTrue();
        assertThat(parser.endpoint().configuredUrl()).isEqualTo(PARSER_ENV);
    }

    @Test
    void anAddressEqualToTheConfiguredOneIsNotStored() {
        // Or a later change to PARSER_URL would never reach an instance whose user had once
        // typed the address that was already in force.
        parser.updateEndpoint(PARSER_ENV, null);

        assertThat(table).doesNotContainKey(ParserSettings.MODEL_URL);
        assertThat(parser.endpoint().urlCustomised()).isFalse();
    }

    @Test
    void clearingTheFieldRestoresTheConfiguredAddress() {
        parser.updateEndpoint("http://10.0.0.5:8090/v1/chat/completions", null);

        assertThat(parser.updateEndpoint("", null).url()).isEqualTo(PARSER_ENV);
        assertThat(table).doesNotContainKey(ParserSettings.MODEL_URL);
    }

    @Test
    void theFeedFollowsTheParsersOwnSettingWhereItHasNoAddressOfItsOwn() {
        // The single-server shape. Pointed at a new box, the feed must follow it rather than go
        // on talking to the address PARSER_URL happened to hold at boot.
        parser.updateEndpoint("http://10.0.0.5:8090/v1/chat/completions", null);

        assertThat(feed.endpoint().url()).isEqualTo("http://10.0.0.5:8090/v1/chat/completions");
    }

    @Test
    void theFeedsOwnAddressBeatsTheParsers() {
        parser.updateEndpoint("http://10.0.0.5:8090/v1/chat/completions", null);
        feed.updateEndpoint(FEED_ENV, null);

        assertThat(feed.endpoint().url()).isEqualTo(FEED_ENV);
        assertThat(feed.endpoint().urlCustomised()).isTrue();
    }

    @Test
    void theParsersModelNameIsInheritedOnlyWithItsAddress() {
        parser.updateEndpoint(null, "qwen3-4b-chartering");

        // Same server, so the name means something there.
        assertThat(feed.endpoint().model()).isEqualTo("qwen3-4b-chartering");

        // A second server has never heard of it, and sending it would be refused by an Ollama
        // and ignored by nothing.
        feed.updateEndpoint(FEED_ENV, null);
        assertThat(feed.endpoint().model()).isEmpty();
    }

    @Test
    void aSummaryRunSharesTheParsersServerOnlyWhileBothPointAtIt() {
        assertThat(feed.endpoint().url()).isEqualTo(parser.endpoint().url());

        feed.updateEndpoint(FEED_ENV, null);
        assertThat(feed.endpoint().url()).isNotEqualTo(parser.endpoint().url());
    }

    @Test
    void anAddressThatIsNotOneIsRefusedByName() {
        // Stored, this is a sweep that fails from inside the HTTP client on every message after.
        assertThatThrownBy(() -> parser.updateEndpoint("localhost:8090", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http://");
        assertThatThrownBy(() -> feed.updateEndpoint("http:///v1/chat/completions", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no host");
        assertThat(table).isEmpty();
    }

    @Test
    void theServerRootIsDerivedFromTheCompletionsAddress() {
        assertThat(parser.endpoint().base()).isEqualTo("http://host.docker.internal:8090");
    }
}
