package com.heutelbeck.rustregex4j;

/**
 * The JNI interface of the regex engine, the Rust library behind {@link Regex}.
 * <p>
 * Contract: arguments are never null; a pointer comes from {@link #compile} and goes to
 * {@link #free} exactly once; no method receives a pointer after {@link #free}. The array
 * results start with a status: {@code 1} found, {@code 0} not found, or a negated
 * {@link RegexErrorKind} code. A span is four values: UTF-16 start, UTF-16 end, code point
 * start, code point end.
 */
final class RegexEngine {

    static {
        EngineLoader.load();
    }

    private RegexEngine() {
    }

    /**
     * Compiles a pattern.
     *
     * @return the pointer to the compiled regex, or a negated error kind code with the message
     *         stored in {@code message[0]}
     */
    static native long compile(String pattern, int maxPatternBytes, int nestLimit, long sizeLimit, long dfaSizeLimit,
            int maxTextBytes, boolean wholeText, String[] message);

    static native void free(long pointer);

    static native int groupCount(long pointer);

    /** Stores the group names in {@code names[0]}, separated by NUL; unnamed groups are empty. */
    static native void groupNames(long pointer, String[] names);

    /** @return {@code 1}, {@code 0}, or a negated error kind code */
    static native int isMatch(long pointer, String text);

    /** @return {@code [status, span]} of the first match at or after the UTF-16 index {@code start} */
    static native int[] find(long pointer, String text, int start);

    /** @return {@code [count, span...]} of at most {@code limit} matches, or {@code [negated error kind code]} */
    static native int[] findAll(long pointer, String text, int limit);

    /** @return {@code [status, group count, (present, span)...]} of the first match at or after {@code start} */
    static native int[] captures(long pointer, String text, int start);

    /** @return {@code [match count, group count, (present, span)... per match]} of at most {@code limit} matches */
    static native int[] capturesAll(long pointer, String text, int limit);

    /** Stores the result in {@code result[0]}; @return {@code 1} or a negated error kind code */
    static native int replaceAll(long pointer, String text, String replacement, String[] result);

    /** @return {@code [count, span...]} of at most {@code limit} parts, or {@code [negated error kind code]} */
    static native int[] split(long pointer, String text, int limit);

    /** @return the number of compiled regexes not yet freed */
    static native long liveRegexes();
}
