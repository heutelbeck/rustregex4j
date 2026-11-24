package com.heutelbeck.rustregex4j;

import static java.util.Objects.requireNonNull;

/**
 * A rejected pattern or text.
 * <p>
 * {@code RegexError} is a possible outcome of every operation: of compiling a pattern and of
 * every search. Operations never throw for invalid input; they return this record instead,
 * and the sealed result types make the caller handle it.
 *
 * @param kind why the pattern or text was rejected; stable, use it for decisions
 * @param message a description for humans; for syntax errors the message of the Rust parser,
 *        which points at the offending part of the pattern
 */
public record RegexError(RegexErrorKind kind, String message)
        implements RegexCompilation, IsMatchResult, FindResult, FindAllResult, CapturesResult, CapturesAllResult,
        ReplaceResult, SplitResult, RegexSetCompilation, SetMatchesResult {

    private static final String ERROR_KIND_NULL    = "The error kind must not be null.";
    private static final String ERROR_MESSAGE_NULL = "The error message must not be null.";

    /**
     * Creates an error.
     *
     * @throws NullPointerException if an argument is null
     */
    public RegexError {
        requireNonNull(kind, ERROR_KIND_NULL);
        requireNonNull(message, ERROR_MESSAGE_NULL);
    }
}
