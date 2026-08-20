package com.chartering.service.mail;

import com.chartering.config.BrevoProperties;
import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CampaignRecipientRequest;
import com.chartering.exception.MailNotConfiguredException;
import com.chartering.service.MailTemplateService;
import com.chartering.service.SettingsService.CirculationSettings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The second flow: each circular is POSTed to Brevo's transactional API, one request per
 * recipient, and Brevo does the delivering.
 *
 * <p>Everything the campaign does around a send is unchanged — same pacing, same retries,
 * same circuit breaker, same history — so a circulation sent this way is the same
 * circulation, recorded the same way, and resumable across a provider switch. What changes
 * is whose reputation is on the line: a bounce here costs a bounce record on the Brevo
 * account rather than a strike against the user's own mailbox, and the throughput ceiling
 * is a plan allowance rather than a personal mailbox's hourly cap.
 *
 * <p><b>One request per recipient, not Brevo's batch endpoint.</b> The batch form would be
 * fewer round trips, but it returns one outcome for the whole batch, and the circulation
 * history is per address — "who received this?" has to keep having an exact answer. Per
 * recipient also means a single refused address is a single failed row rather than a
 * batch that half-succeeded in a way nothing can reconstruct afterwards.
 *
 * <p><b>The sender identity comes from Settings</b>, the same From the mailbox flow uses.
 * Brevo will refuse anything else: the address has to be a verified sender on the account,
 * which is a one-off step in Brevo's Senders screen and shows up here as a 400 naming the
 * address if it was skipped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrevoCircularSender implements CircularSender {

    private final BrevoProperties brevo;
    private final MailCampaignProperties props;
    private final MailTemplateService templates;

    @Override
    public CircularProvider provider() {
        return CircularProvider.BREVO;
    }

    @Override
    public List<String> missingSettings(CirculationSettings cfg) {
        List<String> missing = new ArrayList<>();
        if (!isSet(brevo.getApiKey())) {
            missing.add("BREVO_API_KEY");
        }
        if (!isSet(cfg.fromAddress())) {
            missing.add("From address (Settings, or MAIL_FROM)");
        }
        return missing;
    }

    @Override
    public Bound bind(CirculationSettings cfg) {
        return new BoundBrevo(newClient(), cfg);
    }

    /**
     * A client of this run's own. Built here rather than injected as a shared bean so the
     * timeouts and base URL a run starts with are the ones it keeps, matching how every
     * other setting behaves once a campaign is under way.
     */
    private RestClient newClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(brevo.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(brevo.getReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(brevo.getBaseUrl())
                .defaultHeader("api-key", brevo.getApiKey() == null ? "" : brevo.getApiKey().trim())
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /** One run's Brevo client, frozen against the settings the campaign started with. */
    private final class BoundBrevo implements Bound {

        private final RestClient client;
        private final CirculationSettings cfg;

        private BoundBrevo(RestClient client, CirculationSettings cfg) {
            this.client = client;
            this.cfg = cfg;
        }

        @Override
        public CircularProvider provider() {
            return CircularProvider.BREVO;
        }

        /**
         * Ask Brevo who we are. The account endpoint is the cheapest call that exercises the
         * whole path — DNS, TLS, and the key's validity — without sending anything, which is
         * exactly what a preflight wants: a rejected key becomes a message on screen instead
         * of 200 identical failures and an account that has now seen us fail to authenticate
         * 200 times.
         */
        @Override
        public void verify() {
            if (!isSet(brevo.getApiKey())) {
                throw new MailNotConfiguredException(
                        "No Brevo API key. Set BREVO_API_KEY in .env and restart the api container.");
            }
            try {
                Account account = client.get()
                        .uri("/account")
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new MailNotConfiguredException(
                                    "Brevo refused the API key — " + describe(res));
                        })
                        .body(Account.class);
                log.debug("Brevo preflight ok for {}", account == null ? "(unknown account)" : account.email());
            } catch (MailNotConfiguredException e) {
                throw e;
            } catch (ResourceAccessException e) {
                throw new MailNotConfiguredException("Could not reach the Brevo API at %s — %s"
                        .formatted(brevo.getBaseUrl(), CircularSendException.rootMessage(e)));
            } catch (RuntimeException e) {
                throw new MailNotConfiguredException(
                        "Brevo preflight failed — " + CircularSendException.rootMessage(e));
            }
        }

        @Override
        public void send(CampaignRecipientRequest r, String subjectTemplate, String htmlTemplate) {
            String html = templates.renderHtml(htmlTemplate, r);
            SendRequest body = new SendRequest(
                    new Contact(cfg.fromAddress(), blankToNull(cfg.fromName())),
                    List.of(new Contact(r.getEmail().trim(), blankToNull(r.getPersonName()))),
                    isSet(props.getReplyTo()) ? new Contact(props.getReplyTo().trim(), null) : null,
                    templates.renderText(subjectTemplate, r),
                    html,
                    // The plain-text alternative is built here rather than left to Brevo:
                    // it is the same htmlToText the mailbox flow uses, so the two providers
                    // produce the same message rather than merely similar ones.
                    templates.htmlToText(html),
                    headers());

            try {
                client.post()
                        .uri("/smtp/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw classify(res);
                        })
                        .toBodilessEntity();
            } catch (CircularSendException e) {
                throw e;
            } catch (ResourceAccessException e) {
                // A timeout or a dropped connection is genuinely ambiguous: Brevo may have
                // accepted the message we are about to call failed. Transient, so the retry
                // gets a chance to turn the ambiguity into a definite answer.
                throw CircularSendException.transientFailure(CircularSendException.rootMessage(e), e);
            } catch (RuntimeException e) {
                throw CircularSendException.transientFailure(CircularSendException.rootMessage(e), e);
            }
        }

        /**
         * Headers Brevo passes through untouched. Only List-Unsubscribe today: bulk senders
         * that omit it are penalised by most inbox providers, and a one-click opt-out is
         * what a recipient reaches for instead of the spam button.
         */
        private Map<String, String> headers() {
            if (!isSet(props.getUnsubscribeMailto())) {
                return null;
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("List-Unsubscribe", "<mailto:%s>".formatted(props.getUnsubscribeMailto().trim()));
            return h;
        }
    }

    /**
     * Turn an HTTP failure into the campaign's three-way decision.
     *
     * <ul>
     *   <li><b>401/403</b> — the key is wrong, revoked, or the account is suspended. Every
     *       remaining message would fail identically, so this aborts the run rather than
     *       burning the list one refusal at a time.</li>
     *   <li><b>429</b> — the plan's rate limit. Exactly what the backoff is for.</li>
     *   <li><b>5xx</b> — Brevo's problem, and usually brief.</li>
     *   <li><b>any other 4xx</b> — this message is malformed or this address is refused
     *       (an unverified sender lands here, as does a blocklisted contact). Retrying
     *       re-sends the identical request for the identical answer.</li>
     * </ul>
     */
    private static CircularSendException classify(ClientHttpResponse res) {
        int status;
        try {
            status = res.getStatusCode().value();
        } catch (IOException e) {
            return CircularSendException.transientFailure(CircularSendException.rootMessage(e), e);
        }
        String detail = describe(res);
        if (status == 401 || status == 403) {
            return CircularSendException.auth("Brevo rejected the API key — " + detail, null);
        }
        if (status == 429 || status >= 500) {
            return CircularSendException.transientFailure("Brevo %d — %s".formatted(status, detail), null);
        }
        return CircularSendException.permanent("Brevo %d — %s".formatted(status, detail), null);
    }

    /**
     * The most useful sentence available about a failed response. Brevo answers errors with
     * {@code {"code": "...", "message": "..."}}; that message names the actual problem
     * ("Sender not valid", "Contact is blocklisted"), so it is worth digging out of the body
     * rather than logging a bare status code.
     */
    private static String describe(ClientHttpResponse res) {
        String body;
        try {
            body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            body = "";
        }
        if (body.isEmpty()) {
            try {
                return "HTTP " + res.getStatusCode().value();
            } catch (IOException e) {
                return "no response body";
            }
        }
        // Parsed by hand rather than through Jackson: this runs on a path that is already
        // failing, and a body that is not the JSON we expected must still produce a readable
        // line instead of a second exception.
        int at = body.indexOf("\"message\"");
        if (at >= 0) {
            int open = body.indexOf('"', body.indexOf(':', at) + 1);
            int close = open < 0 ? -1 : body.indexOf('"', open + 1);
            if (open >= 0 && close > open) {
                return body.substring(open + 1, close);
            }
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static String blankToNull(String s) {
        return isSet(s) ? s.trim() : null;
    }

    // ---------------------------------------------------------------- wire types

    /** A sender or a recipient. Brevo wants the name separate from the address. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Contact(String email, String name) {
    }

    /** Body of POST /v3/smtp/email. Null members are omitted rather than sent as null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SendRequest(Contact sender,
                               List<Contact> to,
                               Contact replyTo,
                               String subject,
                               String htmlContent,
                               String textContent,
                               Map<String, String> headers) {
    }

    /** Only the identifying part of GET /v3/account — the rest is plan detail we don't use. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Account(String email, String companyName) {
    }
}
