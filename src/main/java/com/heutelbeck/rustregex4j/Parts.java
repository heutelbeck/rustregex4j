package com.heutelbeck.rustregex4j;

import java.util.List;

/**
 * The parts of a text between the matches, in text order.
 *
 * @param parts the parts; the whole text as the only part if the pattern does not match
 */
public record Parts(List<String> parts) implements SplitResult {

    /**
     * Creates the result with an immutable copy of the parts.
     *
     * @throws NullPointerException if {@code parts} or one of its entries is null
     */
    public Parts {
        parts = List.copyOf(parts);
    }
}
