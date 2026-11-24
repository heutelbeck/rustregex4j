package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#find(String)}.
 *
 * <pre>{@code
 * switch (regex.find(text)) {
 *     case Found(var match) -> match.of(text);
 *     case NoMatch noMatch  -> "";
 *     case RegexError error -> fail(error);
 * }
 * }</pre>
 */
public sealed interface FindResult permits Found, NoMatch, RegexError {
}
