package com.chartering.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The address check is the one place a typo in the settings table is caught before it fails every
 * sweep. Both schemes and a bare host are refused with the wording a person can act on.
 *
 * <p>A missing scheme and a non-http scheme share one message today; the tests assert each case's
 * full text so a future split is a deliberate change rather than a silent one.
 */
class ModelEndpointTest {

    @Test
    void anHttpAddressIsAcceptedAndComesBackStripped() {
        assertThat(ModelEndpoint.requireCompletionsUrl("  http://host:8090/v1/chat/completions \n"))
                .isEqualTo("http://host:8090/v1/chat/completions");
    }

    @Test
    void anHttpsAddressIsAccepted() {
        assertThat(ModelEndpoint.requireCompletionsUrl("https://models.example.org/v1/chat/completions"))
                .isEqualTo("https://models.example.org/v1/chat/completions");
    }

    @Test
    void anAddressWithoutASchemeIsRefused() {
        assertThatThrownBy(() -> ModelEndpoint.requireCompletionsUrl("example.org/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The model's address must start with http:// or https:// — got "
                        + "'example.org/v1/chat/completions'.");
    }

    @Test
    void aNonHttpSchemeIsRefusedWithTheSameWording() {
        assertThatThrownBy(() -> ModelEndpoint.requireCompletionsUrl("ftp://example.org/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The model's address must start with http:// or https:// — got "
                        + "'ftp://example.org/v1'.");
    }

    @Test
    void anAddressWithNoHostIsRefused() {
        assertThatThrownBy(() -> ModelEndpoint.requireCompletionsUrl("http:///v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The model's address names no host: 'http:///v1/chat/completions'.");
    }

    @Test
    void anAddressThatIsNotAUriIsRefused() {
        assertThatThrownBy(() -> ModelEndpoint.requireCompletionsUrl("http://exa mple.org"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'http://exa mple.org' is not a URL.");
    }

    @Test
    void baseIsTheServerRootBeforeTheV1Path() {
        assertThat(new ModelEndpoint("http://localhost:8090/v1/chat/completions", "m", "", "").base())
                .isEqualTo("http://localhost:8090");
    }

    @Test
    void baseIsTheUrlUnchangedWhenItHasNoV1Path() {
        assertThat(new ModelEndpoint("http://localhost:11434/api/chat", "m", "", "").base())
                .isEqualTo("http://localhost:11434/api/chat");
    }

    @Test
    void urlIsCustomisedOnlyWhenItDiffersFromTheConfiguredOne() {
        assertThat(new ModelEndpoint("http://a/v1/chat/completions", "m", "http://a/v1/chat/completions", "m")
                .urlCustomised()).isFalse();
        assertThat(new ModelEndpoint("http://b/v1/chat/completions", "m", "http://a/v1/chat/completions", "m")
                .urlCustomised()).isTrue();
    }

    @Test
    void modelIsCustomisedOnlyWhenItDiffersFromTheConfiguredOne() {
        assertThat(new ModelEndpoint("http://a/v1/chat/completions", "qwen", "http://a/v1/chat/completions", "qwen")
                .modelCustomised()).isFalse();
        assertThat(new ModelEndpoint("http://a/v1/chat/completions", "other", "http://a/v1/chat/completions", "qwen")
                .modelCustomised()).isTrue();
    }
}
