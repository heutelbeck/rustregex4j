package com.heutelbeck.rustregex4j;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Why a pattern or a text was rejected.
 * <p>
 * Compare kinds, not messages: the kinds are stable, the messages are for humans and may
 * change.
 */
@Getter
@RequiredArgsConstructor
public enum RegexErrorKind {
    /** The pattern is not valid syntax. */
    SYNTAX(1),
    /** The pattern nests groups, classes or repetitions deeper than {@link RegexLimits#nestLimit()}. */
    NESTING_TOO_DEEP(2),
    /** The pattern is longer than {@link RegexLimits#maxPatternBytes()}. */
    PATTERN_TOO_LONG(3),
    /** The compiled pattern would exceed {@link RegexLimits#sizeLimit()}. */
    PATTERN_TOO_LARGE(4),
    /** The pattern or the text contains an unpaired UTF-16 surrogate. */
    MALFORMED_UNICODE(5),
    /** The text is longer than {@link RegexLimits#maxTextBytes()}. */
    TEXT_TOO_LONG(6),
    /** The regex engine failed unexpectedly. */
    INTERNAL(7),
    /** A start position lies outside the text or inside a surrogate pair, or a limit is negative. */
    INVALID_ARGUMENT(8),
    /** The result of a replacement would be longer than {@link RegexLimits#maxTextBytes()}. */
    RESULT_TOO_LONG(9);

    private static final String ERROR_UNKNOWN_CODE = "Unknown error kind code %d.";

    /** The code shared with the regex engine. */
    private final int code;

    /**
     * Returns the kind with the given code.
     *
     * @param code the code reported by the regex engine
     * @return the kind
     * @throws IllegalArgumentException if no kind has this code
     */
    static RegexErrorKind fromCode(int code) {
        for (val kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException(ERROR_UNKNOWN_CODE.formatted(code));
    }
}
