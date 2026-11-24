package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#isMatch(String)}.
 *
 * <pre>{@code
 * switch (regex.isMatch(text)) {
 *     case IsMatch(var matches) -> matches;
 *     case RegexError error     -> fail(error);
 * }
 * }</pre>
 */
public sealed interface IsMatchResult permits IsMatch, RegexError {
}
