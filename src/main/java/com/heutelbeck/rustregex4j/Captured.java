package com.heutelbeck.rustregex4j;

import static java.util.Objects.requireNonNull;

/**
 * The capture groups of the leftmost-first match in a text.
 *
 * @param captures the groups
 */
public record Captured(Captures captures) implements CapturesResult {

    private static final String ERROR_CAPTURES_NULL = "The captures must not be null.";

    /**
     * Creates the result.
     *
     * @throws NullPointerException if {@code captures} is null
     */
    public Captured {
        requireNonNull(captures, ERROR_CAPTURES_NULL);
    }
}
