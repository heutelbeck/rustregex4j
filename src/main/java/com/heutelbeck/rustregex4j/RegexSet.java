package com.heutelbeck.rustregex4j;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

import lombok.val;

/**
 * A set of compiled regular expressions that one search matches against a text all at once,
 * as {@code RegexSet} of the Rust {@code regex} crate does.
 * <p>
 * Use a set where many patterns are tested against the same text, as for routing or for
 * selecting the rules that apply to a request: one search reports every pattern that
 * matches, in time linear in the length of the text, instead of one search per pattern.
 * <pre>{@code
 * RegexSet routes = (RegexSet) RegexSet.compileWholeText(List.of("/users/[0-9]+", "/orders/[0-9]+"));
 * List<Integer> matching = switch (routes.matches("/orders/42")) {
 *     case SetMatches(var patterns) -> patterns;
 *     case RegexError error         -> List.of();
 * };
 * }</pre>
 * A set reports which patterns match, not where: it has no positions and no capture groups.
 * <p>
 * Every pattern is compiled on its own first, with the same limits as a {@link Regex}, so a
 * set holds no pattern that {@link Regex#compile(String, RegexLimits)} rejects. The patterns
 * then share one compiled program, whose size may reach {@link RegexLimits#sizeLimit()}
 * times the number of patterns. Like a {@code Regex}, a set is immutable, thread-safe, and
 * freed automatically when it becomes unreachable.
 */
public final class RegexSet implements RegexSetCompilation {

    private static final String ERROR_INTERNAL         = "The regex engine failed unexpectedly.";
    private static final String ERROR_LIMITS_NULL      = "The limits must not be null.";
    private static final String ERROR_MALFORMED_TEXT   = "The text contains an unpaired UTF-16 surrogate.";
    private static final String ERROR_PATTERN_TOO_LONG = "The pattern %d is longer than %d UTF-8 bytes.";
    private static final String ERROR_PATTERNS_NULL    = "The patterns must not be null.";
    private static final String ERROR_TEXT_NULL        = "The text must not be null.";
    private static final String ERROR_TEXT_TOO_LONG    = "The text is longer than %d UTF-8 bytes.";

    private final List<String> patterns;
    private final RegexLimits  limits;
    private final boolean      wholeText;
    private final long         pointer;

    private RegexSet(List<String> patterns, RegexLimits limits, boolean wholeText, long pointer) {
        this.patterns  = patterns;
        this.limits    = limits;
        this.wholeText = wholeText;
        this.pointer   = pointer;
        Regex.CLEANER.register(this, () -> RegexEngine.freeSet(pointer));
    }

    /**
     * Compiles patterns whose matches may lie anywhere in a text, with the
     * {@linkplain RegexLimits#DEFAULT default limits}.
     *
     * @param patterns the patterns in the syntax of the Rust {@code regex} crate
     * @return the compiled {@code RegexSet}, or a {@link RegexError} whose kind tells why a
     *         pattern or the set was rejected
     * @throws NullPointerException if {@code patterns} or one of its entries is null
     */
    public static RegexSetCompilation compile(List<String> patterns) {
        return compile(patterns, RegexLimits.DEFAULT, false);
    }

    /**
     * Compiles patterns whose matches may lie anywhere in a text.
     *
     * @param patterns the patterns in the syntax of the Rust {@code regex} crate
     * @param limits the limits for every pattern and for every search with the set
     * @return the compiled {@code RegexSet}, or a {@link RegexError} whose kind tells why a
     *         pattern or the set was rejected
     * @throws NullPointerException if an argument or one of the patterns is null
     */
    public static RegexSetCompilation compile(List<String> patterns, RegexLimits limits) {
        return compile(patterns, limits, false);
    }

    /**
     * Compiles patterns that must each match the whole text, with the
     * {@linkplain RegexLimits#DEFAULT default limits}.
     *
     * @param patterns the patterns in the syntax of the Rust {@code regex} crate
     * @return the compiled {@code RegexSet}, or a {@link RegexError} whose kind tells why a
     *         pattern or the set was rejected
     * @throws NullPointerException if {@code patterns} or one of its entries is null
     */
    public static RegexSetCompilation compileWholeText(List<String> patterns) {
        return compile(patterns, RegexLimits.DEFAULT, true);
    }

    /**
     * Compiles patterns that must each match the whole text, as
     * {@link Regex#compileWholeText(String, RegexLimits)} does for a single pattern.
     *
     * @param patterns the patterns in the syntax of the Rust {@code regex} crate
     * @param limits the limits for every pattern and for every search with the set
     * @return the compiled {@code RegexSet}, or a {@link RegexError} whose kind tells why a
     *         pattern or the set was rejected
     * @throws NullPointerException if an argument or one of the patterns is null
     */
    public static RegexSetCompilation compileWholeText(List<String> patterns, RegexLimits limits) {
        return compile(patterns, limits, true);
    }

    private static RegexSetCompilation compile(List<String> patterns, RegexLimits limits, boolean wholeText) {
        val copy = List.copyOf(requireNonNull(patterns, ERROR_PATTERNS_NULL));
        requireNonNull(limits, ERROR_LIMITS_NULL);
        for (var index = 0; index < copy.size(); index++) {
            if (copy.get(index).length() > limits.maxPatternBytes()) {
                return new RegexError(RegexErrorKind.PATTERN_TOO_LONG,
                        ERROR_PATTERN_TOO_LONG.formatted(index, limits.maxPatternBytes()));
            }
        }
        val message = new String[1];
        val result  = RegexEngine.compileSet(copy.toArray(String[]::new), limits.maxPatternBytes(), limits.nestLimit(),
                limits.sizeLimit(), limits.dfaSizeLimit(), limits.maxTextBytes(), wholeText, message);
        if (result < 0) {
            return new RegexError(RegexErrorKind.fromCode((int) -result), requireNonNullElse(message[0], ERROR_INTERNAL));
        }
        return new RegexSet(copy, limits, wholeText, result);
    }

    /**
     * Returns the patterns this set was compiled from, as written.
     *
     * @return the patterns; a match reports the index of its pattern in this list
     */
    public List<String> patterns() {
        return patterns;
    }

    /**
     * Returns the limits this set was compiled with; they also bound every search.
     *
     * @return the limits
     */
    public RegexLimits limits() {
        return limits;
    }

    /**
     * Reports whether the patterns of this set must match the whole text.
     *
     * @return true for {@link #compileWholeText(List, RegexLimits)}, false for
     *         {@link #compile(List, RegexLimits)}
     */
    public boolean wholeText() {
        return wholeText;
    }

    /**
     * Finds the patterns that match a text, with one search for all patterns.
     *
     * @param text the text to search
     * @return {@link SetMatches} with the indexes of the matching patterns, or a
     *         {@link RegexError} if the text is rejected
     * @throws NullPointerException if {@code text} is null
     */
    public SetMatchesResult matches(String text) {
        requireNonNull(text, ERROR_TEXT_NULL);
        if (text.length() > limits.maxTextBytes()) {
            return textTooLong();
        }
        try {
            val values = RegexEngine.setMatches(pointer, text);
            val count  = values[0];
            if (count < 0) {
                return errorOf(count);
            }
            if (count == 0) {
                return SetMatches.NONE;
            }
            val matching = new ArrayList<Integer>(count);
            for (var index = 1; index <= count; index++) {
                matching.add(values[index]);
            }
            return new SetMatches(matching);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Returns the patterns.
     *
     * @return the patterns this set was compiled from
     */
    @Override
    public String toString() {
        return patterns.toString();
    }

    private RegexError textTooLong() {
        return new RegexError(RegexErrorKind.TEXT_TOO_LONG, ERROR_TEXT_TOO_LONG.formatted(limits.maxTextBytes()));
    }

    private RegexError errorOf(int status) {
        val kind = RegexErrorKind.fromCode(-status);
        return switch (kind) {
        case TEXT_TOO_LONG     -> textTooLong();
        case MALFORMED_UNICODE -> new RegexError(kind, ERROR_MALFORMED_TEXT);
        default                -> new RegexError(kind, ERROR_INTERNAL);
        };
    }
}
