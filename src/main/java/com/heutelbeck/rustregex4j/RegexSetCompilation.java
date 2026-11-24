package com.heutelbeck.rustregex4j;

/**
 * The outcome of {@link RegexSet#compile(java.util.List)}: the compiled {@link RegexSet} or
 * the {@link RegexError} that explains why a pattern or the set was rejected.
 *
 * <pre>{@code
 * switch (RegexSet.compileWholeText(List.of("/users/[0-9]+", "/orders/[0-9]+"))) {
 *     case RegexSet set     -> use(set);
 *     case RegexError error -> report(error.kind(), error.message());
 * }
 * }</pre>
 */
public sealed interface RegexSetCompilation permits RegexSet, RegexError {
}
