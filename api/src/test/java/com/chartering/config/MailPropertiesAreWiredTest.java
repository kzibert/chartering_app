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
 * A field on {@link MailCampaignProperties} is only half of a setting. The other half is a
 * line in application.yml naming the environment variable that fills it, and the two are
 * joined by nothing but that line.
 *
 * <p>This exists because the missing half is invisible. {@code MAIL_REPLY_PROVIDER} was
 * added to {@code render.yaml} and to {@code .env.example}, and the field was added here,
 * and the deployment still replied over SMTP for days: Spring's relaxed binding would have
 * matched {@code CHARTERING_MAIL_REPLY_PROVIDER}, but {@code MAIL_REPLY_PROVIDER} is a name
 * of our own invention and only application.yml connects it to anything. Unwired, the field
 * stayed null — which every reader of it treats as "not configured", the same answer it
 * gives for a deployment that genuinely said nothing. There was no error to find, on a host
 * where the fallback route is the one that cannot work.
 *
 * <p>Unit tests do not catch this, and the ones for the reply route did not: they set the
 * property on the object directly, which is the half that was never broken.
 */
class MailPropertiesAreWiredTest {

    private static final String PREFIX = "chartering.mail.";

    /** Every property application.yml defines, across all of its profile documents. */
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

    /** {@code maxRecipientsPerCampaign} -> {@code max-recipients-per-campaign}. */
    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    @Test
    void everyCampaignPropertyIsFilledFromTheEnvironment() throws IOException {
        Map<String, Object> yml = applicationYml();

        var unwired = new TreeSet<String>();
        for (var field : MailCampaignProperties.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            String key = PREFIX + kebab(field.getName());
            if (!yml.containsKey(key)) {
                unwired.add(key + "  (field " + field.getName() + ")");
            }
        }

        assertThat(unwired)
                .as("fields on MailCampaignProperties with no line in application.yml — each"
                        + " one is a setting nothing can ever set, and it will read as"
                        + " 'not configured' rather than as a mistake")
                .isEmpty();
    }

    @Test
    void everyCampaignPropertyNamesAnEnvironmentVariable() throws IOException {
        Map<String, Object> yml = applicationYml();

        var literals = new TreeSet<String>();
        yml.forEach((key, value) -> {
            if (key.startsWith(PREFIX) && !String.valueOf(value).startsWith("${")) {
                literals.add(key + " = " + value);
            }
        });

        // log-file is overridden with a literal by the docker profile, which is the point of
        // that profile; the base document still reads MAIL_LOG_FILE.
        literals.removeIf(entry -> entry.startsWith(PREFIX + "log-file"));

        assertThat(literals)
                .as("hard-coded values under " + PREFIX + " — a deployment cannot change these")
                .isEmpty();
    }

    /**
     * The specific regression, spelled out: the variable {@code render.yaml} sets is the
     * variable the app reads. Renaming one without the other silently reverts the hosted
     * deployment to SMTP, which is the transport its host blocks.
     */
    @Test
    void theReplyRouteReadsTheVariableRenderSets() throws IOException {
        assertThat(applicationYml().get(PREFIX + "reply-provider"))
                .isEqualTo("${MAIL_REPLY_PROVIDER:}");
    }
}
