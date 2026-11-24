package com.heutelbeck.rustregex4j;

import static java.util.Objects.requireNonNull;

/**
 * The leftmost-first match in a text.
 *
 * @param match the match
 */
public record Found(Match match) implements FindResult {

    private static final String ERROR_MATCH_NULL = "The match must not be null.";

    /**
     * Creates the result.
     *
     * @throws NullPointerException if {@code match} is null
     */
    public Found {
        requireNonNull(match, ERROR_MATCH_NULL);
    }
}
