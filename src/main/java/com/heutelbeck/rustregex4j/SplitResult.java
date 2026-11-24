package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#split(String, int)}.
 *
 * <pre>{@code
 * switch (regex.split(text, 100)) {
 *     case Parts(var parts) -> parts;
 *     case RegexError error -> fail(error);
 * }
 * }</pre>
 */
public sealed interface SplitResult permits Parts, RegexError {
}
