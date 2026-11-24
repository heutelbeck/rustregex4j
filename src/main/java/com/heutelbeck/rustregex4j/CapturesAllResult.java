package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#capturesAll(String, int)}.
 *
 * <pre>{@code
 * switch (regex.capturesAll(text, 100)) {
 *     case AllCaptures(var matches) -> matches.size();
 *     case RegexError error         -> fail(error);
 * }
 * }</pre>
 */
public sealed interface CapturesAllResult permits AllCaptures, RegexError {
}
