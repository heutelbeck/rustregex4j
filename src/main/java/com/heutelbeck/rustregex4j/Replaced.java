package com.heutelbeck.rustregex4j;

import static java.util.Objects.requireNonNull;

/**
 * A text in which every match is replaced; the text unchanged if the pattern does not match.
 *
 * @param text the text after the replacement
 */
public record Replaced(String text) implements ReplaceResult {

    private static final String ERROR_TEXT_NULL = "The text must not be null.";

    /**
     * Creates the result.
     *
     * @throws NullPointerException if {@code text} is null
     */
    public Replaced {
        requireNonNull(text, ERROR_TEXT_NULL);
    }
}
