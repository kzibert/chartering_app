package com.chartering.model;

/**
 * How a feed source is read.
 *
 * <p>Two general rules and one escape hatch. A Telegram channel's public preview and an RSS or
 * Atom feed have one shape each whoever publishes them, so one reader serves every channel and
 * every feed. A website has no such shape — ship.gr is a page of pasted emails between rules,
 * ShipOffer is a list of links to offer pages — so {@link #WEBSITE} names a parser written for
 * that site, by key.
 */
public enum FeedSourceKind {
    TELEGRAM,
    RSS,
    WEBSITE
}
