package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

@DisplayName("Rejecting texts")
class TextRejectionTests {

    private static final int MAX_TEXT_BYTES = RegexLimits.DEFAULT.maxTextBytes();

    static Stream<Arguments> rejectedTexts() {
        return Stream.of(arguments("more UTF-16 units than the limit", "a".repeat(MAX_TEXT_BYTES + 1), RegexErrorKind.TEXT_TOO_LONG),
                arguments("more UTF-8 bytes than the limit", "\u00e9".repeat(MAX_TEXT_BYTES / 2) + 'a', RegexErrorKind.TEXT_TOO_LONG),
                arguments("unpaired high surrogate at the end", "ab\uD83D", RegexErrorKind.MALFORMED_UNICODE),
                arguments("unpaired low surrogate at the start", "\uDE00ab", RegexErrorKind.MALFORMED_UNICODE),
                arguments("two high surrogates", "\uD83D\uD83D\uDE00", RegexErrorKind.MALFORMED_UNICODE));
    }

    @MethodSource("rejectedTexts")
    @ParameterizedTest(name = "{0}")
    @DisplayName("every search reports the same error instead of throwing")
    void whenTextIsRejectedThenEverySearchYieldsTheError(String description, String text, RegexErrorKind kind) {
        val regex = compiled("b");
        assertThat(new Object[] { regex.isMatch(text), regex.find(text), regex.findAll(text), regex.captures(text),
                regex.capturesAll(text, 1), regex.replaceAll(text, "c"), regex.split(text, 2) })
                .allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                        error -> assertThat(error.kind()).isEqualTo(kind)));
    }

    @Test
    @DisplayName("a text exactly at the limit is searched")
    void whenTextIsExactlyAtLimitThenItIsSearched() {
        val text = "\u00e9".repeat(MAX_TEXT_BYTES / 2 - 1) + "ab";
        assertThat(compiled("b$").find(text)).isEqualTo(new Found(new Match(text.length() - 1, text.length(),
                text.length() - 1, text.length())));
    }

    @Test
    @DisplayName("the text limit is the one the regex was compiled with")
    void whenRegexHasCustomTextLimitThenSearchesUseIt() {
        val regex = compiled("a", RegexLimits.DEFAULT.withMaxTextBytes(3));
        assertThat(new Object[] { regex.isMatch("aaa"), regex.isMatch("aaaa") }).satisfies(
                results -> assertThat(results[0]).isEqualTo(IsMatch.YES),
                results -> assertThat(results[1]).isInstanceOfSatisfying(RegexError.class,
                        error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.TEXT_TOO_LONG)));
    }
}
