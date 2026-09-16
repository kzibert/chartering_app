package com.chartering.service.feed;

import com.chartering.model.FeedSource;

import java.util.function.Predicate;

/**
 * A reader written for one website.
 *
 * <p>A site that is not a feed has no general rule — one is a page of pasted emails between
 * horizontal rules, another a list of links to offer pages — so each gets a class, registered by
 * {@link #key()}, and a source names the one that reads it. Adding a site is one implementation;
 * the Sources form lists whatever is registered.
 */
public interface WebsiteParser {

    /** Stored in {@code feed_sources.parser_key}. Never rename one that is in use. */
    String key();

    /** What the source form shows. */
    String label();

    /** An address that works with this parser, offered as the form's placeholder. */
    String exampleUrl();

    FeedReader.ReadResult read(FeedSource source, Predicate<String> alreadySeen);
}
