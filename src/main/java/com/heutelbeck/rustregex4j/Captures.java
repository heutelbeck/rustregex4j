package com.heutelbeck.rustregex4j;

import java.util.List;
import java.util.Optional;

/**
 * The capture groups of a match, in the order of their opening parentheses. Group 0 is the
 * whole match.
 *
 * @param groups one entry per group; empty for a group that did not participate in the match
 */
public record Captures(List<Optional<Match>> groups) {

    /**
     * Creates captures with an immutable copy of the groups.
     *
     * @throws NullPointerException if {@code groups} or one of its entries is null
     */
    public Captures {
        groups = List.copyOf(groups);
    }

    /**
     * Returns the number of groups including group 0.
     *
     * @return the group count, at least 1
     */
    public int groupCount() {
        return groups.size();
    }

    /**
     * Returns a group.
     *
     * @param index the group number; 0 is the whole match
     * @return the group, or empty if it did not participate in the match
     * @throws IndexOutOfBoundsException if the pattern has no such group
     */
    public Optional<Match> group(int index) {
        return groups.get(index);
    }

    /**
     * Returns the whole match, group 0.
     *
     * @return the match
     */
    public Match match() {
        return groups.getFirst().orElseThrow();
    }
}
