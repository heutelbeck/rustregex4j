package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#findAll(String)}.
 *
 * <pre>{@code
 * switch (regex.findAll(text)) {
 *     case Matches(var matches) -> matches.size();
 *     case RegexError error     -> fail(error);
 * }
 * }</pre>
 */
public sealed interface FindAllResult permits Matches, RegexError {
}
