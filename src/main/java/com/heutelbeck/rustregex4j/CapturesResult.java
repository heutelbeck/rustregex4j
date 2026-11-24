package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#captures(String)}.
 *
 * <pre>{@code
 * switch (regex.captures(text)) {
 *     case Captured(var captures) -> captures.group(1).map(group -> group.of(text));
 *     case NoMatch noMatch        -> Optional.empty();
 *     case RegexError error       -> fail(error);
 * }
 * }</pre>
 */
public sealed interface CapturesResult permits Captured, NoMatch, RegexError {
}
