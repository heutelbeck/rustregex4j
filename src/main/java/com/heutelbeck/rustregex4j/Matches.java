package com.heutelbeck.rustregex4j;

import java.util.List;

/**
 * All successive non-overlapping matches in a text, in text order. Empty if the pattern does
 * not match.
 *
 * @param matches the matches
 */
public record Matches(List<Match> matches) implements FindAllResult {

    /**
     * Creates the result with an immutable copy of the matches.
     *
     * @throws NullPointerException if {@code matches} or one of its entries is null
     */
    public Matches {
        matches = List.copyOf(matches);
    }
}
