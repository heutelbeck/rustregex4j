package com.heutelbeck.rustregex4j;

import java.util.List;

/**
 * The patterns of a {@link RegexSet} that match a text.
 *
 * @param patterns the indexes of the matching patterns in the order the set was compiled
 *        from, ascending; empty if no pattern matches
 */
public record SetMatches(List<Integer> patterns) implements SetMatchesResult {

    /** No pattern of the set matches. */
    public static final SetMatches NONE = new SetMatches(List.of());

    /**
     * Creates the result with an immutable copy of the indexes.
     *
     * @throws NullPointerException if {@code patterns} or one of its entries is null
     */
    public SetMatches {
        patterns = List.copyOf(patterns);
    }
}
