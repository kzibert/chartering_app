package com.chartering.service;

import com.chartering.config.BrevoProperties;
import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CirculationSettingsRequest;
import com.chartering.model.AppSetting;
import com.chartering.repository.AppSettingRepository;
import com.chartering.service.mail.CircularProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Settings the user can change at runtime, layered over the configured defaults.
 *
 * <p>Only values actually changed are stored; an absent key falls through to
 * application.yml and therefore to the environment variables. That keeps `.env` meaningful
 * as the baseline for a fresh deployment, makes "reset to defaults" a delete, and means
 * nothing has to be seeded for the app to work.
 *
 * <p><b>Credentials are not settings.</b> MAIL_USERNAME / MAIL_PASSWORD and BREVO_API_KEY
 * stay in the environment: this table is served to the browser, which is the wrong place
 * for a mailbox password or an API key with full send rights. The From identity is
 * adjustable because it is not a secret — though a provider will still reject a From that
 * is not the authenticated mailbox or a verified sender.
 *
 * <h2>Pacing is stored per provider</h2>
 * <p>The Settings screen shows one set of pacing knobs, but what those knobs mean depends
 * on which provider is sending: three seconds between messages is prudent through a personal
 * mailbox and pointless through Brevo. So each provider keeps its own stored values under
 * its own keys, and its own defaults to fall back to. Switching provider therefore swaps the
 * pacing to something appropriate straight away, and switching back restores whatever the
 * mailbox flow had been tuned to rather than leaving it wearing Brevo's numbers.
 *
 * <p>The mailbox flow keeps the original unprefixed keys, so an installation that was
 * already tuned before Brevo existed carries its settings forward untouched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {

    /** Which flow sends circulars. Absent means the mailbox flow, which is what predates it. */
    public static final String PROVIDER = "mail.provider";

    /** The greeting prefilled into a wa.me link when checking or opening a WhatsApp chat. */
    public static final String WHATSAPP_MESSAGE = "whatsapp.message";

    /**
     * The built-in default greeting. A constant rather than a configured property: unlike the
     * mail settings there is no environment behind it — nothing is sent from the server, the
     * text only ends up in a link the browser opens.
     */
    public static final String DEFAULT_WHATSAPP_MESSAGE = "Good day, {{greeting}}";

    public static final String FROM_ADDRESS = "mail.fromAddress";
    public static final String FROM_NAME = "mail.fromName";
    public static final String SMTP_HOST = "mail.smtp.host";
    public static final String SMTP_PORT = "mail.smtp.port";

    /** Settings that mean the same thing whichever provider is in force. */
    private static final List<String> SHARED_KEYS = List.of(FROM_ADDRESS, FROM_NAME, SMTP_HOST, SMTP_PORT);

    /**
     * Pacing keys, unqualified. Each provider stores its own values under
     * {@link #pacingKey(CircularProvider, String)}.
     */
    private static final String MIN_DELAY_MS = "minDelayMs";
    private static final String MAX_DELAY_MS = "maxDelayMs";
    private static final String MAX_RECIPIENTS = "maxRecipientsPerCampaign";
    private static final String BATCH_PAUSE_MS = "batchPauseMs";

    private static final List<String> PACING_KEYS =
            List.of(MIN_DELAY_MS, MAX_DELAY_MS, MAX_RECIPIENTS, BATCH_PAUSE_MS);

    /** Good enough to catch a typo; the mail server is the real authority on an address. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /** A day between two messages is already absurd; beyond that it is a typo. */
    private static final long MAX_DELAY_ALLOWED_MS = 86_400_000L;

    private final AppSettingRepository repository;
    private final MailCampaignProperties props;
    private final BrevoProperties brevo;
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

    /**
     * The mailbox flow's pacing keeps the names it always had; Brevo's are namespaced. Not
     * cosmetic: it is what lets an existing installation keep its tuning when this feature
     * lands, with no migration to run and nothing to re-enter.
     */
    private static String pacingKey(CircularProvider provider, String name) {
        return provider == CircularProvider.BREVO ? "mail.brevo." + name : "mail." + name;
    }

    private static List<String> pacingKeys(CircularProvider provider) {
        return PACING_KEYS.stream().map(k -> pacingKey(provider, k)).toList();
    }

    private static List<String> keysFor(CircularProvider provider) {
        List<String> keys = new ArrayList<>(SHARED_KEYS);
        keys.addAll(pacingKeys(provider));
        return keys;
    }

    // ---------------------------------------------------------------- reading

    /** Which flow circulars go out through right now. */
    @Transactional(readOnly = true)
    public CircularProvider provider() {
        return repository.findById(PROVIDER)
                .map(AppSetting::getValue)
                .map(CircularProvider::parse)
                .orElse(CircularProvider.SMTP);
    }

    /** Effective circulation settings: stored overrides on top of the configured defaults. */
    @Transactional(readOnly = true)
    public CirculationSettings circulation() {
        CircularProvider provider = provider();
        Map<String, String> stored = repository.findByKeyIn(keysFor(provider)).stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        CirculationSettings defaults = circulationDefaults(provider);
        return new CirculationSettings(
                provider,
                stored.getOrDefault(FROM_ADDRESS, defaults.fromAddress()),
                stored.getOrDefault(FROM_NAME, defaults.fromName()),
                stored.getOrDefault(SMTP_HOST, defaults.smtpHost()),
                parse(stored, SMTP_PORT, defaults.smtpPort(), Integer::parseInt),
                parse(stored, pacingKey(provider, MIN_DELAY_MS), defaults.minDelayMs(), Long::parseLong),
                parse(stored, pacingKey(provider, MAX_DELAY_MS), defaults.maxDelayMs(), Long::parseLong),
                parse(stored, pacingKey(provider, MAX_RECIPIENTS), defaults.maxRecipientsPerCampaign(), Integer::parseInt),
                parse(stored, pacingKey(provider, BATCH_PAUSE_MS), defaults.batchPauseMs(), Long::parseLong));
    }

    /** What the settings screen offers as "reset to", for whichever provider is in force. */
    public CirculationSettings circulationDefaults() {
        return circulationDefaults(provider());
    }

    /**
     * The configured baseline for one provider. The From identity and SMTP endpoint come
     * from the mail environment either way; only the pacing differs, and it differs because
     * the two providers are protecting different things — see {@link BrevoProperties}.
     */
    public CirculationSettings circulationDefaults(CircularProvider provider) {
        boolean viaBrevo = provider == CircularProvider.BREVO;
        return new CirculationSettings(provider,
                props.getFromAddress(), props.getFromName(), defaultHost, defaultPort,
                viaBrevo ? brevo.getMinDelayMs() : props.getMinDelayMs(),
                viaBrevo ? brevo.getMaxDelayMs() : props.getMaxDelayMs(),
                viaBrevo ? brevo.getMaxRecipientsPerCampaign() : props.getMaxRecipientsPerCampaign(),
                viaBrevo ? brevo.getBatchPauseMs() : props.getBatchPauseMs());
    }

    /**
     * The greeting prefilled into wa.me links, and the default behind it.
     *
     * <p>Rendered in the browser, not here: the substitution needs the person on the row the
     * user clicked, which the browser already has, and no request to the server would tell it
     * anything it does not know. The server's job is only to remember the text.
     */
    @Transactional(readOnly = true)
    public String whatsappMessage() {
        return repository.findById(WHATSAPP_MESSAGE)
                .map(AppSetting::getValue)
                .filter(v -> !v.isBlank())
                .orElse(DEFAULT_WHATSAPP_MESSAGE);
    }

    // ---------------------------------------------------------------- writing

    @Transactional
    public String updateWhatsappMessage(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("A message is required.");
        }
        put(WHATSAPP_MESSAGE, trimmed);
        log.info("WhatsApp greeting updated to \"{}\"", trimmed);
        return whatsappMessage();
    }

    /** Delete the override so {@link #DEFAULT_WHATSAPP_MESSAGE} applies again. */
    @Transactional
    public String resetWhatsappMessage() {
        repository.deleteByKeyIn(List.of(WHATSAPP_MESSAGE));
        log.info("WhatsApp greeting reset to the built-in default");
        return whatsappMessage();
    }

    /**
     * Switch which flow sends circulars.
     *
     * <p>Only the one key is written. The pacing that comes with the new provider arrives on
     * its own, because each provider reads its own stored values and its own defaults — so
     * ticking Brevo immediately shows Brevo's pacing, and unticking it hands the mailbox
     * flow back exactly the numbers it had before.
     *
     * <p>A campaign already in flight is unaffected: it bound its provider when it started,
     * which is what stops a switch mid-run from sending half a circular one way and half
     * the other.
     */
    @Transactional
    public CirculationSettings updateProvider(CircularProvider provider) {
        put(PROVIDER, provider.name());
        log.info("Circulars will now be sent via {}", provider.label());
        return circulation();
    }

    @Transactional
    public CirculationSettings updateCirculation(CirculationSettingsRequest req) {
        validate(req);
        CircularProvider provider = provider();
        put(FROM_ADDRESS, req.getFromAddress().trim());
        put(FROM_NAME, req.getFromName() == null ? "" : req.getFromName().trim());
        put(SMTP_HOST, req.getSmtpHost().trim());
        put(SMTP_PORT, String.valueOf(req.getSmtpPort()));
        put(pacingKey(provider, MIN_DELAY_MS), String.valueOf(req.getMinDelayMs()));
        put(pacingKey(provider, MAX_DELAY_MS), String.valueOf(req.getMaxDelayMs()));
        put(pacingKey(provider, MAX_RECIPIENTS), String.valueOf(req.getMaxRecipientsPerCampaign()));
        put(pacingKey(provider, BATCH_PAUSE_MS), String.valueOf(req.getBatchPauseMs()));
        log.info("Circulation settings updated ({}): from {} <{}> via {}:{} pacing {}-{}ms cap {} pause {}ms",
                provider.name(), req.getFromName(), req.getFromAddress(), req.getSmtpHost(),
                req.getSmtpPort(), req.getMinDelayMs(), req.getMaxDelayMs(),
                req.getMaxRecipientsPerCampaign(), req.getBatchPauseMs());
        return circulation();
    }

    /**
     * Drop every override the screen can set, so the configured defaults apply again.
     *
     * <p>Scoped to the provider in force: the other provider's pacing is not on screen and
     * resetting what the user cannot see would be a surprise. The provider choice itself
     * survives too — it is which flow you use, not a value you tuned.
     */
    @Transactional
    public CirculationSettings resetCirculation() {
        CircularProvider provider = provider();
        repository.deleteByKeyIn(keysFor(provider));
        log.info("Circulation settings reset to the configured defaults for {}", provider.label());
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
        if (req.getFromAddress() == null || req.getFromAddress().isBlank()) {
            throw new IllegalArgumentException("A From address is required.");
        }
        if (!EMAIL.matcher(req.getFromAddress().trim()).matches()) {
            throw new IllegalArgumentException(
                    "\"" + req.getFromAddress().trim() + "\" is not a valid email address.");
        }
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
        if (req.getBatchPauseMs() < 0) {
            throw new IllegalArgumentException("The pause between runs cannot be negative.");
        }
        if (req.getBatchPauseMs() > MAX_DELAY_ALLOWED_MS) {
            throw new IllegalArgumentException("A pause of over 24 hours between runs is not sensible.");
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
     * The circulation knobs in force for a run, provider included. Resolved once when a
     * campaign starts, so editing the settings mid-send cannot change the pacing half way
     * through — the same rule the footer follows, and the same rule that stops a provider
     * switch from splitting one circular across two transports.
     */
    public record CirculationSettings(CircularProvider provider, String fromAddress, String fromName,
                                      String smtpHost, int smtpPort, long minDelayMs, long maxDelayMs,
                                      int maxRecipientsPerCampaign, long batchPauseMs) {

        /** Mean gap, used for the "this will take about N minutes" estimate. */
        public long averageDelayMs() {
            long min = Math.max(0, minDelayMs);
            return (min + Math.max(min, maxDelayMs)) / 2;
        }

        /** How many runs a campaign of this size is sent as. Never fewer than one. */
        public int batchCount(int recipients) {
            int size = Math.max(1, maxRecipientsPerCampaign);
            return Math.max(1, (recipients + size - 1) / size);
        }
    }
}
