package com.chartering.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same guard {@link MailPropertiesAreWiredTest} exists for, applied to the parser — and
 * this feature is a worse place for the bug it catches.
 *
 * <p>A field on {@link ParserProperties} is half a setting; the other half is a line in
 * application.yml naming the environment variable that fills it, and nothing but that line
 * joins the two. {@code PARSER_URL} and {@code PARSER_ENABLED} are names of our own
 * invention: Spring's relaxed binding would match {@code CHARTERING_PARSER_URL} on its own,
 * so a missing line leaves the field on its default with no error anywhere.
 *
 * <p>What makes it worse here than for the mail settings is where the default points. An
 * unwired {@code PARSER_URL} does not read as "not configured" — it reads as
 * {@code host.docker.internal:8090}, which is a perfectly plausible address that simply is
 * not the one the deployment was told to use. The symptom is a feature that works on the one
 * machine where the default happens to be right.
 */
class ParserPropertiesAreWiredTest {

    private static final String PREFIX = "chartering.parser.";

    private static Map<String, Object> applicationYml() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        Map<String, Object> all = new LinkedHashMap<>();
        for (PropertySource<?> document : documents) {
            for (String name : ((EnumerablePropertySource<?>) document).getPropertyNames()) {
                all.putIfAbsent(name, document.getProperty(name));
            }
        }
        return all;
    }

    /** {@code readTimeoutMs} -> {@code read-timeout-ms}. */
    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    @Test
    void everyParserPropertyIsFilledFromTheEnvironment() throws IOException {
        Map<String, Object> yml = applicationYml();

        var unwired = new TreeSet<String>();
        for (var field : ParserProperties.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            String key = PREFIX + kebab(field.getName());
            if (!yml.containsKey(key)) {
                unwired.add(key + "  (field " + field.getName() + ")");
            }
        }

        assertThat(unwired)
                .as("fields on ParserProperties with no line in application.yml — each one is"
                        + " a setting nothing can ever set, and it silently keeps a default"
                        + " that looks like a real answer")
                .isEmpty();
    }

    @Test
    void everyParserPropertyNamesAnEnvironmentVariable() throws IOException {
        var literals = new TreeSet<String>();
        applicationYml().forEach((key, value) -> {
            if (key.startsWith(PREFIX) && !String.valueOf(value).startsWith("${")) {
                literals.add(key + " = " + value);
            }
        });

        assertThat(literals)
                .as("hard-coded values under " + PREFIX + " — a deployment cannot change these")
                .isEmpty();
    }

    /**
     * The switch render.yaml pins is the switch the app reads.
     *
     * <p>Spelled out because the hosted deployment depends on it being off: there is no GPU
     * there and no route to one, so an unwired flag defaulting the wrong way would mean every
     * sweep spending a request proving it and every status call waiting out a connect timeout.
     */
    @Test
    void theSwitchReadsTheVariableRenderPins() throws IOException {
        assertThat(applicationYml().get(PREFIX + "enabled")).isEqualTo("${PARSER_ENABLED:false}");
    }
}
