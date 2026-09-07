package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Looking a hull up on an outside source when the IMO is missing on both sides.
 *
 * <p><b>Off by default, and the reasons to leave it off are worth reading before turning it
 * on.</b> The only implementation reads a public ship-database search page. That is not what
 * the site's terms invite, it can stop working the day somebody changes a stylesheet, and it
 * puts this desk's traffic on somebody else's server. It was chosen over the paid APIs
 * knowingly — those run from about £100 to £700 a month — and the settings below exist to
 * keep it a good citizen: one request at a time, a gap between them, a small cap per pass,
 * and nothing at all unless a person is actually waiting on a review item.
 *
 * <p>Everything it produces is a proposal. Nothing here can write to a vessel; a person
 * accepts field by field, and {@code vessel_lookups} keeps the page it came off.
 */
@Component
@ConfigurationProperties(prefix = "chartering.lookup")
@Data
public class VesselLookupProperties {

    /** Master switch. Off means no outside request is ever made. */
    private boolean enabled = false;

    /**
     * Which source answers. The only one implemented is {@code vesselfinder}; the name is a
     * setting rather than a constant so that swapping in a paid API is configuration.
     */
    private String provider = "vesselfinder";

    /** Where its search lives. {@code {name}} is replaced by the URL-encoded ship name. */
    private String searchUrl = "https://www.vesselfinder.com/vessels?name={name}";

    /** Prefix for the page a person opens to check a candidate; the IMO is appended. */
    private String detailUrlPrefix = "https://www.vesselfinder.com/vessels/details/";

    /**
     * How this identifies itself.
     *
     * <p>A browser string, and it is worth being straight about why: a custom agent naming
     * this application is the honest thing to send and is refused. That is the compromise the
     * decision to read a public page carries with it, and it is configurable so a deployment
     * with an agreement in place can say who it really is.
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Unused by the scraper: Jsoup has one timeout covering both halves of a request, and
     * {@link #readTimeoutMs} is what is passed to it. Kept because it is the setting a paid
     * API client would want, and this is the port's configuration rather than one
     * implementation's.
     */
    private int connectTimeoutMs = 8_000;

    private int readTimeoutMs = 20_000;

    /**
     * The smallest gap between two outside requests, enforced across the whole application.
     *
     * <p>Politeness, and self-interest: a burst is what gets an address blocked, and a
     * blocked address ends the feature for everyone here. Three seconds makes a pass of ten
     * lookups take half a minute, which nobody is watching anyway — they run behind the
     * queue, not in front of a user.
     */
    private long minRequestIntervalMs = 3_000;

    /**
     * How many hulls one enrichment pass will look up.
     *
     * <p>Small on purpose. The pass runs on a timer and the queue is not usually long; a cap
     * of ten keeps a morning's circulars from turning into a hundred requests at somebody
     * else's expense, and the next pass picks up what this one left.
     */
    private int maxPerPass = 10;

    /**
     * Candidates kept from one search, <b>after they are ranked</b>. Beyond a handful nobody
     * reads them.
     *
     * <p>The order matters and used to be the other way round. The page arrives in the
     * source's own order, which knows only the name it was asked for; cutting it here meant a
     * search for a common name kept the first eight of twenty and threw away the hull whose
     * deadweight and build year agreed, because she was twelfth. The scraper now reads the
     * page and {@code VesselLookupService} keeps the best few once the matcher has scored
     * them.
     */
    private int maxCandidates = 8;

    /**
     * How sure the matcher must be before a candidate is offered at all.
     *
     * <p>Out of 100, scored on the name, the build year, the deadweight and the flag. Below
     * this the lookup is recorded as {@code NO_MATCH} rather than putting a plausible wrong
     * ship in front of somebody — which is the failure that matters here, because a wrong IMO
     * is not a wrong number, it is a different vessel wearing this one's history.
     */
    private int minConfidence = 55;
}
