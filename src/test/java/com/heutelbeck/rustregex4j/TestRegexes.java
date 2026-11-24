package com.heutelbeck.rustregex4j;

import static org.assertj.core.api.Assertions.fail;

import java.util.List;

import lombok.experimental.UtilityClass;

/** Unwraps the sealed results of the API for tests that expect a particular outcome. */
@UtilityClass
class TestRegexes {

    static Regex compiled(String pattern) {
        return compiled(pattern, RegexLimits.DEFAULT);
    }

    static Regex compiled(String pattern, RegexLimits limits) {
        return switch (Regex.compile(pattern, limits)) {
        case Regex regex      -> regex;
        case RegexError error -> fail("Expected %s to compile, but: %s", pattern, error);
        };
    }

    static Regex compiledWholeText(String pattern) {
        return switch (Regex.compileWholeText(pattern)) {
        case Regex regex      -> regex;
        case RegexError error -> fail("Expected %s to compile, but: %s", pattern, error);
        };
    }

    static RegexError rejected(String pattern, RegexLimits limits) {
        return switch (Regex.compile(pattern, limits)) {
        case Regex regex      -> fail("Expected %s to be rejected.", pattern);
        case RegexError error -> error;
        };
    }

    static List<Match> matches(Regex regex, String text) {
        return switch (regex.findAll(text)) {
        case Matches(var matches) -> matches;
        case RegexError error     -> fail("Expected %s to be searched, but: %s", text, error);
        };
    }
}
