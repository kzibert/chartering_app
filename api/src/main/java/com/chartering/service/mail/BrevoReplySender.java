package com.chartering.service.mail;

import com.chartering.config.BrevoProperties;
import com.chartering.exception.MailSendFailedException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posting one reply to Brevo's transactional API, for deployments that cannot open an SMTP
 * connection at all.
 *
 * <h2>Why this exists, given that replies are supposed to go through the mailbox</h2>
 * <p>{@link MailReplyService} argues at length that a reply belongs in the mailbox flow, and
 * that argument has not changed. What changed is that some hosts do not offer the mailbox
 * flow. Render blocks outbound traffic to ports 25, 465 and 587 on its free instances, and a
 * reply from there does not fail with a refusal that explains itself — it fails with
 * "Connect timed out" fifteen seconds later, because the SYN is dropped rather than refused.
 * The mailbox is still perfectly reachable for <em>reading</em> on IMAP 993, so the Mailbox
 * tab looks entirely healthy right up until somebody tries to answer something.
 *
 * <p>So the choice this class offers is not "Brevo or the mailbox". It is "Brevo or nothing",
 * and only on a deployment that has already been established to be in that position. See
 * {@code MailCampaignProperties#replyProvider} for why that is an environment variable.
 *
 * <h2>What is given up, and what is not</h2>
 * <p><b>Not the From address.</b> A reply must come from the address the correspondent wrote
 * to or it is not a reply, and Brevo will happily send as one — but only if Brevo has been
 * told the domain is ours. A single verified <em>sender</em> is not enough: that authorises
 * one address, and the mailbox is usually not the address circulars go out as. Authenticating
 * the <em>domain</em> (Brevo's Senders &rarr; Domains screen, DKIM and SPF records in DNS)
 * authorises every address on it, which is what makes this viable at all. Without it Brevo
 * answers 400 "Sender not valid", and {@link #describeSenderRefusal} turns that into the
 * sentence that says so rather than leaving a bare status code on screen.
 *
 * <p><b>The Sent folder, outright.</b> The message never touches the mailbox, so the
 * provider files no copy and the "your mailbox today" figure will not count it. The app's own
 * {@code mail_replies} row is then the <em>only</em> record the reply happened, which is
 * precisely the case that table was built for.
 *
 * <p><b>Threading, possibly.</b> {@code In-Reply-To} and {@code References} are sent in
 * Brevo's {@code headers} map, which its documentation describes as being for custom
 * (non-standard) headers and says standard ones are not supported. They are passed anyway —
 * they cost nothing, they are the only way threading can work, and a documented restriction
 * that turns out to be enforced leaves the recipient with a message titled "Re: …" that
 * starts a new thread rather than joining one. That is a real loss and it is not repairable
 * from this side; it is the strongest remaining argument for putting the api on a host that
 * allows SMTP.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrevoReplySender {

    private final BrevoProperties brevo;

    /** What is stopping a reply going out this way, in the words the user has to act on. */
    public List<String> missingSettings(String fromAddress) {
        List<String> missing = new ArrayList<>();
        if (!isSet(brevo.getApiKey())) {
            missing.add("BREVO_API_KEY");
        }
        if (!isSet(fromAddress)) {
            missing.add("MAIL_USERNAME, or a From address (Settings, or MAIL_FROM)");
        }
        return missing;
    }

    /**
     * Send one reply and return the id Brevo assigned it.
     *
     * <p>Brevo's id is kept rather than the one this app would have minted for the SMTP path.
     * There, our own Message-ID is what lets the provider's Sent-folder copy be recognised as
     * this reply when it syncs back; here there is no such copy to recognise, and Brevo
     * stamps its own Message-ID on the message regardless, so inventing one would store an
     * identifier that appears in no mail anywhere. What Brevo returns is the id the message
     * actually carries, and the one their dashboard can be searched by.
     *
     * @param inReplyTo the answered message's Message-ID, or null if the sync never stored one
     */
    public String send(String fromAddress, String fromName, String to, String subject,
                       String html, String text, String inReplyTo) {
        SendRequest body = new SendRequest(
                new Contact(fromAddress, blankToNull(fromName)),
                List.of(new Contact(to, null)),
                subject,
                html,
                text,
                threadHeaders(inReplyTo));
        try {
            SendResponse res = newClient().post()
                    .uri("/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw new MailSendFailedException(
                                "Brevo would not send this reply. " + describeSenderRefusal(
                                        response.getStatusCode().value(),
                                        BrevoCircularSender.describe(response),
                                        fromAddress),
                                null);
                    })
                    .body(SendResponse.class);
            return res == null ? null : res.messageId();
        } catch (MailSendFailedException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Genuinely ambiguous — Brevo may have accepted the message we are about to call
            // failed. Said plainly, because the person reading this is deciding whether to
            // press Send again, and a second copy of a reply is a real cost.
            throw new MailSendFailedException(
                    "Brevo did not answer in time, so it is not certain whether this reply went "
                            + "out. Check the mailbox before sending it again. " + brevo.getBaseUrl()
                            + " said: " + CircularSendException.rootMessage(e), e);
        } catch (RuntimeException e) {
            throw new MailSendFailedException(
                    "Brevo would not send this reply: " + CircularSendException.rootMessage(e), e);
        }
    }

    /**
     * The two headers that make a reply a reply rather than a new message with a familiar
     * subject. Only the answered message is referenced: the chain above it lives in headers
     * the sync does not store, and a short true chain threads where a guessed one does not —
     * the same reasoning the SMTP path applies.
     */
    private static Map<String, String> threadHeaders(String inReplyTo) {
        if (!isSet(inReplyTo)) {
            return null;
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("In-Reply-To", inReplyTo.trim());
        h.put("References", inReplyTo.trim());
        return h;
    }

    /**
     * Brevo's own words, with the one failure worth naming outright spelled out.
     *
     * <p>An unverified sender is the mistake this route invites — the From is the mailbox
     * address, which is exactly the address nobody thinks to authorise, and Brevo reports it
     * as a 400 saying "Sender not valid" with no hint as to the remedy. Everything else is
     * passed through as Brevo phrased it.
     */
    static String describeSenderRefusal(int status, String detail, String fromAddress) {
        String said = "Brevo %d — %s".formatted(status, detail);
        if (status == 400 && detail != null && detail.toLowerCase().contains("sender")) {
            return said + ". Replies go out as the mailbox address (" + fromAddress
                    + "), so Brevo has to be told that address is yours: authenticate the whole"
                    + " domain under Senders → Domains, which covers every address on it."
                    + " Verifying only the circulars From is not enough.";
        }
        return said;
    }

    /**
     * A client per send, matching {@code BrevoCircularSender} and {@code BrevoStatsService}:
     * the timeouts and base URL a call starts with are the ones it keeps, and nothing shared
     * can be reconfigured underneath it.
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

    private static String blankToNull(String s) {
        return isSet(s) ? s.trim() : null;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    // ---------------------------------------------------------------- wire types

    /** A sender or a recipient. Brevo wants the name separate from the address. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Contact(String email, String name) {
    }

    /**
     * Body of POST /v3/smtp/email. Deliberately narrower than the circular sender's: no
     * replyTo, because this goes out as the mailbox and that is already where an answer
     * should land, and no List-Unsubscribe, because offering to unsubscribe somebody from a
     * conversation they started is not a courtesy.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SendRequest(Contact sender,
                               List<Contact> to,
                               String subject,
                               String htmlContent,
                               String textContent,
                               Map<String, String> headers) {
    }

    /** Brevo answers an accepted send with the id it gave the message. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SendResponse(String messageId) {
    }
}
