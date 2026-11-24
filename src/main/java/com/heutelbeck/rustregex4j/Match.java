package com.heutelbeck.rustregex4j;

/**
 * The half-open range {@code [start, end)} of a match or a capture group.
 * <p>
 * {@code start} and {@code end} are UTF-16 indexes, usable with
 * {@link String#substring(int, int)}. The code point offsets count Unicode characters; they
 * are the same in every language and thus the unit to compare results across languages.
 *
 * @param start the inclusive start as UTF-16 index
 * @param end the exclusive end as UTF-16 index
 * @param startCodepoint the inclusive start in Unicode code points
 * @param endCodepoint the exclusive end in Unicode code points
 */
public record Match(int start, int end, int startCodepoint, int endCodepoint) {

    /**
     * Returns the matched part of a text.
     *
     * @param text the text that was searched
     * @return the substring from {@code start} to {@code end}
     * @throws IndexOutOfBoundsException if the range does not lie in {@code text}
     */
    public String of(String text) {
        return text.substring(start, end);
    }

    /**
     * Returns the length of the match in UTF-16 units.
     *
     * @return {@code end - start}; 0 for an empty match
     */
    public int length() {
        return end - start;
    }
}
