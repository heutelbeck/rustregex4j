package com.heutelbeck.rustregex4j;

import java.util.List;

/**
 * The capture groups of successive non-overlapping matches, in text order. Empty if the
 * pattern does not match.
 *
 * @param matches the groups of each match
 */
public record AllCaptures(List<Captures> matches) implements CapturesAllResult {

    /**
     * Creates the result with an immutable copy of the matches.
     *
     * @throws NullPointerException if {@code matches} or one of its entries is null
     */
    public AllCaptures {
        matches = List.copyOf(matches);
    }
}
