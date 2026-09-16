package com.chartering.service.feed;

/**
 * How many tokens a text is. An interface so the planner can be tested with a fake that counts
 * characters, without a model server.
 */
@FunctionalInterface
public interface TokenCounter {
    int count(String text);
}
