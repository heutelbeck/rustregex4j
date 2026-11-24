package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link Regex#compile(String)}: the compiled {@link Regex} or the
 * {@link RegexError} that explains why the pattern was rejected.
 *
 * <pre>{@code
 * switch (Regex.compile("[a-z]{3}-[0-9]{6}")) {
 *     case Regex regex      -> use(regex);
 *     case RegexError error -> report(error.kind(), error.message());
 * }
 * }</pre>
 */
public sealed interface RegexCompilation permits Regex, RegexError {
}
