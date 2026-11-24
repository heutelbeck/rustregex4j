package com.heutelbeck.rustregex4j;

/**
 * A searched text: whether the pattern matches anywhere in it.
 *
 * @param matches true if there is at least one match
 */
public record IsMatch(boolean matches) implements IsMatchResult {

    /** The text contains a match. */
    public static final IsMatch YES = new IsMatch(true);

    /** The text contains no match. */
    public static final IsMatch NO = new IsMatch(false);

    static IsMatch of(boolean matches) {
        return matches ? YES : NO;
    }
}
