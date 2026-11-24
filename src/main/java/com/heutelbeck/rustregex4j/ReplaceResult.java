package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#replaceAll(String, String)}.
 *
 * <pre>{@code
 * switch (regex.replaceAll(text, "${user} at ${host}")) {
 *     case Replaced(var result) -> result;
 *     case RegexError error     -> fail(error);
 * }
 * }</pre>
 */
public sealed interface ReplaceResult permits Replaced, RegexError {
}
