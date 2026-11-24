package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link RegexSet#matches(String)}.
 *
 * <pre>{@code
 * switch (set.matches(text)) {
 *     case SetMatches(var patterns) -> patterns;
 *     case RegexError error         -> fail(error);
 * }
 * }</pre>
 */
public sealed interface SetMatchesResult permits SetMatches, RegexError {
}
