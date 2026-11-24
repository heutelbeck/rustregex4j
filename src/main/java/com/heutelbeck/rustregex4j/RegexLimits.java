package com.heutelbeck.rustregex4j;

import lombok.With;

/**
 * Limits that bound the cost of compiling a pattern and of searching a text with it.
 * <p>
 * A search runs in time proportional to the size of the compiled pattern times the length
 * of the text. Bounding both factors gives every search a fixed worst case, independent of
 * what an attacker puts into the pattern or the text. The defaults in {@link #DEFAULT} are
 * chosen for patterns and texts of authorization policies: identifiers, paths, e-mail
 * addresses and similar short values.
 * <p>
 * Lengths are measured in UTF-8 bytes, so the limits mean the same in every language that
 * uses the Rust {@code regex} crate with the same configuration. A Java string never has
 * more UTF-16 units than UTF-8 bytes, so a string with more units than a limit is rejected
 * without being encoded.
 * <p>
 * Derive custom limits from the defaults:
 * <pre>{@code
 * var limits = RegexLimits.DEFAULT.withMaxTextBytes(1024 * 1024);
 * }</pre>
 *
 * @param maxPatternBytes the maximum pattern length in UTF-8 bytes
 * @param nestLimit the maximum nesting depth of groups, classes and repetitions
 * @param sizeLimit the maximum heap size of a compiled pattern in bytes
 * @param dfaSizeLimit the maximum size of the lazy DFA cache per thread in bytes; it only
 *        affects speed, never results
 * @param maxTextBytes the maximum text length in UTF-8 bytes
 */
@With
public record RegexLimits(int maxPatternBytes, int nestLimit, long sizeLimit, long dfaSizeLimit, int maxTextBytes) {

    private static final String ERROR_NOT_POSITIVE = "The limit %s must be positive, but is %d.";

    /** The default limits. */
    public static final RegexLimits DEFAULT = new RegexLimits(4096, 64, 1024 * 1024, 2 * 1024 * 1024, 64 * 1024);

    /**
     * Creates limits.
     *
     * @throws IllegalArgumentException if a limit is not positive
     */
    public RegexLimits {
        requirePositive("maxPatternBytes", maxPatternBytes);
        requirePositive("nestLimit", nestLimit);
        requirePositive("sizeLimit", sizeLimit);
        requirePositive("dfaSizeLimit", dfaSizeLimit);
        requirePositive("maxTextBytes", maxTextBytes);
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(ERROR_NOT_POSITIVE.formatted(name, value));
        }
    }
}
