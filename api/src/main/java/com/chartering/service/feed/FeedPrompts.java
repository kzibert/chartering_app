package com.chartering.service.feed;

import java.time.LocalDate;
import java.util.List;

/**
 * The prompts the Feed starts from, before anybody edits them.
 *
 * <p>Two, because a map-reduce run asks the model two different things: take notes on one batch
 * without losing a figure, then write the summary from the notes. The editable copies live in
 * {@code app_settings}; these are what "reset to default" restores.
 *
 * <p>Both are placeholders rather than finished text, so one prompt serves every topic. The
 * placeholders are replaced before sending and the result is what a summary stores as its own
 * prompt — so an old summary shows the words it was actually written under.
 *
 * <p>The model these were checked against is the email parser's: a Qwen3-4B finetuned on
 * extraction to JSON. Asked plainly for prose it writes prose, and "plain text only, no JSON" is
 * in both prompts because that is the habit it was trained into.
 */
public final class FeedPrompts {

    private FeedPrompts() {
    }

    public static final List<String> PLACEHOLDERS = List.of("{topic}", "{keywords}", "{today}", "{period}");

    public static final String DEFAULT_SYSTEM = """
            You are a dry bulk chartering analyst writing for a shipbroking desk that works the \
            Mediterranean and the Black Sea.

            Topic: {topic}
            Keywords: {keywords}
            Today is {today}. The material covers {period}.

            From the source material the user sends, write a market summary for this topic only.
            - Open with two or three sentences on the overall direction.
            - Then up to eight bullet points, each starting with "- ".
            - Keep every figure exactly as written, with its unit and route ($/t, $/day, DWT, laycan).
            - After each figure name its source and date in brackets, copied from the header above \
            the item, for example (Ukrainian Shipping Magazine, 14 Sep 2026).
            - Where sources disagree, say so. Where the material says nothing about the topic, \
            say that in one sentence.
            - Plain text only: no JSON, no markdown headings, no figures that are not in the material.""";

    public static final String DEFAULT_NOTES = """
            You are taking notes for a later market summary on: {topic}
            Keywords: {keywords}
            Today is {today}.

            From the source material the user sends, list only the facts relevant to that topic: \
            freight rates with their units and routes, vessel sizes, cargoes and quantities, ports, \
            laycans and dates, market direction and the reasons given for it.
            - One fact per line, starting with "- " and ending with its source and date in brackets, \
            copied from the header above the item, for example (ship.gr open cargoes, 11 Sep 2026).
            - Copy figures exactly as written.
            - Leave out greetings, signatures, phone numbers, addresses and anything off the topic.
            - If nothing is relevant, answer with the single line: - nothing relevant
            Plain text only.""";

    public static String render(String template, String topic, List<String> keywords,
                                LocalDate today, String period) {
        return template
                .replace("{topic}", topic == null ? "" : topic)
                .replace("{keywords}", keywords == null || keywords.isEmpty() ? "(none)" : String.join(", ", keywords))
                .replace("{today}", today.toString())
                .replace("{period}", period == null ? "the last few days" : period);
    }
}
