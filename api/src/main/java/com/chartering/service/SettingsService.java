package com.chartering.service;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CirculationSettingsRequest;
import com.chartering.model.AppSetting;
import com.chartering.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Settings the user can change at runtime, layered over the configured defaults.
 *
 * <p>Only values actually changed are stored; an absent key falls through to
 * application.yml and therefore to the environment variables. That keeps `.env` meaningful
 * as the baseline for a fresh deployment, makes "reset to defaults" a delete, and means
 * nothing has to be seeded for the app to work.
 *
 * <p><b>Credentials are not settings.</b> MAIL_USERNAME / MAIL_PASSWORD stay in the
 * environment: this table is served to the browser, which is the wrong place for a mailbox
 * password. Only the host and port are adjustable here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {

    public static final String SMTP_HOST = "mail.smtp.host";
    public static final String SMTP_PORT = "mail.smtp.port";
    public static final String MIN_DELAY_MS = "mail.minDelayMs";
    public static final String MAX_DELAY_MS = "mail.maxDelayMs";
    public static final String MAX_RECIPIENTS = "mail.maxRecipientsPerCampaign";

    private static final List<String> CIRCULATION_KEYS =
            List.of(SMTP_HOST, SMTP_PORT, MIN_DELAY_MS, MAX_DELAY_MS, MAX_RECIPIENTS);

    /** A day between two messages is already absurd; beyond that it is a typo. */
    private static final long MAX_DELAY_ALLOWED_MS = 86_400_000L;

    private final AppSettingRepository repository;
    private final MailCampaignProperties props;
    private final JavaMailSender mailSender;

    /**
     * The configured SMTP host/port, captured once at startup.
     *
     * <p>Snapshotted rather than read from the sender on demand because the sender is the
     * thing a changed setting reconfigures — reading it back later would return the
     * override and quietly redefine what "default" means.
     */
    private String defaultHost;
    private int defaultPort;

    @PostConstruct
    void captureConfiguredDefaults() {
        if (mailSender instanceof JavaMailSenderImpl impl) {
            defaultHost = impl.getHost();
            defaultPort = impl.getPort();
        }
    }

    // ---------------------------------------------------------------- reading

    /** Effective circulation settings: stored overrides on top of the configured defaults. */
    @Transactional(readOnly = true)
    public CirculationSettings circulation() {
        Map<String, String> stored = repository.findByKeyIn(CIRCULATION_KEYS).stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        return new CirculationSettings(
                stored.getOrDefault(SMTP_HOST, defaultHost),
                parse(stored, SMTP_PORT, defaultPort, Integer::parseInt),
                parse(stored, MIN_DELAY_MS, props.getMinDelayMs(), Long::parseLong),
                parse(stored, MAX_DELAY_MS, props.getMaxDelayMs(), Long::parseLong),
                parse(stored, MAX_RECIPIENTS, props.getMaxRecipientsPerCampaign(), Integer::parseInt));
    }

    /** What the settings screen offers as "reset to" — the configured baseline. */
    public CirculationSettings circulationDefaults() {
        return new CirculationSettings(defaultHost, defaultPort, props.getMinDelayMs(),
                props.getMaxDelayMs(), props.getMaxRecipientsPerCampaign());
    }

    // ---------------------------------------------------------------- writing

    @Transactional
    public CirculationSettings updateCirculation(CirculationSettingsRequest req) {
        validate(req);
        put(SMTP_HOST, req.getSmtpHost().trim());
        put(SMTP_PORT, String.valueOf(req.getSmtpPort()));
        put(MIN_DELAY_MS, String.valueOf(req.getMinDelayMs()));
        put(MAX_DELAY_MS, String.valueOf(req.getMaxDelayMs()));
        put(MAX_RECIPIENTS, String.valueOf(req.getMaxRecipientsPerCampaign()));
        log.info("Circulation settings updated: {}:{} pacing {}-{}ms cap {}",
                req.getSmtpHost(), req.getSmtpPort(), req.getMinDelayMs(), req.getMaxDelayMs(),
                req.getMaxRecipientsPerCampaign());
        return circulation();
    }

    /** Drop every override, so the configured defaults apply again. */
    @Transactional
    public CirculationSettings resetCirculation() {
        repository.deleteByKeyIn(CIRCULATION_KEYS);
        log.info("Circulation settings reset to the configured defaults");
        return circulation();
    }

    // ---------------------------------------------------------------- internals

    private void put(String key, String value) {
        AppSetting s = repository.findById(key).orElseGet(() -> {
            AppSetting fresh = new AppSetting();
            fresh.setKey(key);
            return fresh;
        });
        s.setValue(value);
        repository.save(s);
    }

    /**
     * Validated here rather than only by bean validation, because the interesting rule is
     * the relationship between two fields: a max below the min would make the random gap
     * meaningless, and the sender would silently clamp it instead of telling anyone.
     */
    private void validate(CirculationSettingsRequest req) {
        if (req.getSmtpHost() == null || req.getSmtpHost().isBlank()) {
            throw new IllegalArgumentException("SMTP host is required.");
        }
        if (req.getSmtpPort() < 1 || req.getSmtpPort() > 65535) {
            throw new IllegalArgumentException("SMTP port must be between 1 and 65535.");
        }
        if (req.getMinDelayMs() < 0 || req.getMaxDelayMs() < 0) {
            throw new IllegalArgumentException("Delays cannot be negative.");
        }
        if (req.getMaxDelayMs() < req.getMinDelayMs()) {
            throw new IllegalArgumentException(
                    "The longest gap must be at least the shortest gap — got %d-%dms."
                            .formatted(req.getMinDelayMs(), req.getMaxDelayMs()));
        }
        if (req.getMaxDelayMs() > MAX_DELAY_ALLOWED_MS) {
            throw new IllegalArgumentException("A gap of over 24 hours between messages is not sensible.");
        }
        if (req.getMaxRecipientsPerCampaign() < 1) {
            throw new IllegalArgumentException("The per-run cap must be at least 1.");
        }
    }

    private static <T> T parse(Map<String, String> stored, String key, T fallback,
                               Function<String, T> parser) {
        String raw = stored.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return parser.apply(raw.trim());
        } catch (RuntimeException e) {
            // A malformed row must not take sending down with it — fall back and say so.
            log.warn("Setting {} holds an unreadable value '{}', using the default", key, raw);
            return fallback;
        }
    }

    /**
     * The circulation knobs in force for a run. Resolved once when a campaign starts, so
     * editing the settings mid-send cannot change the pacing half way through — the same
     * rule the footer follows.
     */
    public record CirculationSettings(String smtpHost, int smtpPort, long minDelayMs,
                                      long maxDelayMs, int maxRecipientsPerCampaign) {

        /** Mean gap, used for the "this will take about N minutes" estimate. */
        public long averageDelayMs() {
            long min = Math.max(0, minDelayMs);
            return (min + Math.max(min, maxDelayMs)) / 2;
        }
    }
}
