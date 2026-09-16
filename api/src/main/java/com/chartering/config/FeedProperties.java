package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The Feed tab's deployment facts: whether this instance fetches and summarises, and how it
 * behaves on other people's servers.
 *
 * <p><b>The tab itself is on everywhere; only the analysis is switched.</b> Sources, topics,
 * the prompt and every summary already written are rows in the one database both instances
 * share, so the hosted instance shows and edits them like any other data. Fetching and
 * summarising are what this switch covers, and for two reasons that point the same way: the
 * model is on a GPU in the office, and two instances fetching the same sources would do the
 * same work twice against somebody else's server.
 *
 * <p>How often to fetch, how big the model's window is and what the prompt says are runtime
 * settings ({@code FeedSettings}), for the reason the parser's sweep interval is one: they are
 * knobs turned while reading the output, and an environment variable is a redeploy. The model's
 * address is not repeated here — it is {@code PARSER_URL}, so there is one server to point at.
 */
@Component
@ConfigurationProperties(prefix = "chartering.feed")
@Data
public class FeedProperties {

    /** Fetching and summarising. Off means those endpoints answer 404 on this deployment. */
    private boolean analysisEnabled = false;

    /**
     * The chat-completions endpoint that writes summaries. Blank uses the parser's.
     *
     * <p>Its own setting because the parser's model is the wrong tool for this. It is a finetune
     * trained only on email-to-JSON, and run against real feed items it reported "no vessel
     * openings" for a position list full of them and wrote eight Danube–Med rates that no item
     * contained. Summaries want a general instruct model; extraction wants the finetune; the two
     * are served apart.
     */
    private String llmUrl = "";

    /** Sent only when set, as the parser's is. */
    private String llmModel = "";

    /**
     * An agent that names this application.
     *
     * <p>Unlike the vessel lookup's, this is not a browser string, and it does not need to be:
     * every source this was built against answers it. A source that refuses it has said it does
     * not want to be read by a program, and the answer is to drop the source rather than to
     * disguise the program — which is why Hellenic Shipping News is not one.
     */
    private String userAgent = "CharteringApp-Feed/1.0 (market feed reader)";

    private int connectTimeoutMs = 10_000;

    private int readTimeoutMs = 20_000;

    /**
     * A pause between two requests to the same site within one fetch — the pages a Telegram
     * channel is paged back through, the offer pages ShipOffer links to. One request at a time
     * is the whole courtesy this can offer, and a burst is what gets an address blocked.
     */
    private int requestGapMs = 1_500;

    /** How far back a Telegram channel is paged on one fetch, at twenty posts a page. */
    private int telegramMaxPages = 5;

    /** How many unseen offer pages one fetch of a site parser may open. */
    private int maxDetailPagesPerFetch = 15;

    /**
     * The longest text kept for one item. An RSS article can carry a whole report; past this it
     * is appendix, and the summariser would split it into parts anyway.
     */
    private int maxItemChars = 40_000;
}
