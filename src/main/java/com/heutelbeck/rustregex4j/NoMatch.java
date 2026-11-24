package com.heutelbeck.rustregex4j;

/** The pattern does not match anywhere in the text. */
public record NoMatch() implements FindResult, CapturesResult {

    /** The single instance used by the library. */
    public static final NoMatch INSTANCE = new NoMatch();
}
