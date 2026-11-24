package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

@DisplayName("Replacing and splitting")
class ReplaceAndSplitTests {

    private static final String GRINNING_FACE = "😀";

    @Nested
    @DisplayName("Replacing")
    class Replacing {

        static Stream<Arguments> replacements() {
            return Stream.of(arguments("groups by number", "(\\w+)@(\\w+)", "alice@example", "$2 at $1", "example at alice"),
                    arguments("a group by name", "(?<user>\\w+)@(\\w+)", "alice@example", "${user}!", "alice!"),
                    arguments("braces end a group number", "(\\d)", "a1b2", "${1}x", "a1xb2x"),
                    arguments("a name runs to the end of the word", "(\\d)", "a1b2", "$1x", "ab"),
                    arguments("$$ is a dollar sign", "(\\d)", "a1", "$$1", "a$1"),
                    arguments("a backslash stands for itself", "(\\d)", "a1", "\\$1", "a\\1"),
                    arguments("a group that does not exist inserts nothing", "(\\d)", "a1", "<$9>", "a<>"),
                    arguments("a group that did not participate inserts nothing", "(x)?(\\d)", "a1", "<$1$2>", "a<1>"),
                    arguments("empty matches are replaced between all characters", "", "ab", "-", "-a-b-"),
                    arguments("characters outside the BMP stay whole", "b", GRINNING_FACE + 'b', "c", GRINNING_FACE + 'c'),
                    arguments("a text without match stays unchanged", "x", "abc", "y", "abc"));
        }

        @MethodSource("replacements")
        @ParameterizedTest(name = "{0}")
        @DisplayName("follow the replacement syntax of the Rust crate")
        void whenReplacingThenTheReplacementSyntaxOfTheCrateApplies(String description, String pattern, String text,
                String replacement, String expected) {
            assertThat(compiled(pattern).replaceAll(text, replacement)).isEqualTo(new Replaced(expected));
        }

        @Test
        @DisplayName("reject a result longer than the text limit")
        void whenResultExceedsTheTextLimitThenReplacementIsRejected() {
            val regex = compiled("a", RegexLimits.DEFAULT.withMaxTextBytes(8));
            assertThat(List.of(regex.replaceAll("aa", "xxxx"), regex.replaceAll("aaa", "xxx"), regex.replaceAll("a", "123456789")))
                    .satisfiesExactly(result -> assertThat(result).isEqualTo(new Replaced("xxxxxxxx")),
                            result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                                    error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.RESULT_TOO_LONG)),
                            result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                                    error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.TEXT_TOO_LONG)));
        }

        static Stream<Arguments> boundedReplacements() {
            return Stream.of(
                    arguments("repeated capture at the limit", "(.+)", "a".repeat(64), "$0$0", 128, new Replaced("a".repeat(128))),
                    arguments("repeated capture beyond the limit", "(.+)", "a".repeat(64), "$0$0", 127, resultTooLong(127)),
                    arguments("capture expansion beyond the limit", "(.+)", "a".repeat(64), "$0".repeat(64), 256, resultTooLong(256)),
                    arguments("nested captures", "(a(b))", "ab", "$1$2$1", 6, new Replaced("abbab")),
                    arguments("unmatched and unknown names", "(?<a>x)?(?<b>y)", "y", "${a}$b${missing}", 16, new Replaced("y")),
                    arguments("named and whole match", "(?<x_1>a)", "a", "$x_1$0", 6, new Replaced("aa")),
                    arguments("a greedy unknown name", "(?<x_1>a)", "a", "$x_1z", 6, new Replaced("")),
                    arguments("a Unicode capture name", "(?<\u03b1>a)", "a", "${\u03b1}", 8, new Replaced("a")),
                    arguments("an escaped dollar", "(a)", "a", "$$1", 3, new Replaced("$1")),
                    arguments("a bare dollar", "(a)", "a", "$", 1, new Replaced("$")),
                    arguments("an unclosed reference", "(a)", "a", "${missing", 9, new Replaced("${missing")),
                    arguments("an overflowing group number", "(a)", "a", "$999999999999999999999999", 28, new Replaced("")),
                    arguments("successive replacements", "(b)", "abba", "$1$1$1", 6, resultTooLong(6)),
                    arguments("the unchanged tail", "(a)", "ab", "xxxx", 4, resultTooLong(4)),
                    arguments("UTF-8 capture bytes", "(.+)", "\u00e4", "$0$0", 4, new Replaced("\u00e4\u00e4")),
                    arguments("zero width at the limit", "", "\u00e4", "-", 4, new Replaced("-\u00e4-")),
                    arguments("zero width beyond the limit", "", "\u00e4", "-", 3, resultTooLong(3)),
                    arguments("no match", "x", "abc", "yyyy", 4, new Replaced("abc")));
        }

        @MethodSource("boundedReplacements")
        @ParameterizedTest(name = "{0}")
        @DisplayName("preserve capture expansion and UTF-8 result limits")
        void whenCaptureReplacementsApproachTheLimitThenTheResultRemainsBounded(String description, String pattern,
                String text, String replacement, int limit, ReplaceResult expected) {
            assertThat(compiled(pattern, RegexLimits.DEFAULT.withMaxTextBytes(limit)).replaceAll(text, replacement))
                    .isEqualTo(expected);
        }

        private static RegexError resultTooLong(int limit) {
            return new RegexError(RegexErrorKind.RESULT_TOO_LONG,
                    "The result of the replacement is longer than %d UTF-8 bytes.".formatted(limit));
        }
    }

    @Nested
    @DisplayName("Splitting")
    class Splitting {

        static Stream<Arguments> splits() {
            return Stream.of(arguments("at every match, keeping empty parts", ",", "a,b,,c", 10, List.of("a", "b", "", "c")),
                    arguments("with empty parts at both ends", ",", ",a,", 10, List.of("", "a", "")),
                    arguments("the rest of the text in the last part", ",", "a,b,c", 2, List.of("a", "b,c")),
                    arguments("into no part for the limit 0", ",", "a,b", 0, List.of()),
                    arguments("between all characters for an empty pattern", "", "ab", 10, List.of("", "a", "b", "")),
                    arguments("keeping characters outside the BMP whole", "\\s+", GRINNING_FACE + " x", 10,
                            List.of(GRINNING_FACE, "x")),
                    arguments("an empty text into one empty part", "x", "", 10, List.of("")));
        }

        @MethodSource("splits")
        @ParameterizedTest(name = "{0}")
        @DisplayName("split as the Rust crate does")
        void whenSplittingThenThePartsAreThoseOfTheCrate(String description, String pattern, String text, int limit,
                List<String> expected) {
            assertThat(compiled(pattern).split(text, limit)).isEqualTo(new Parts(expected));
        }

        @Test
        @DisplayName("reject a negative limit")
        void whenLimitIsNegativeThenSplitIsRejected() {
            assertThat(compiled(",").split("a,b", -1)).isInstanceOfSatisfying(RegexError.class,
                    error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.INVALID_ARGUMENT));
        }
    }
}
