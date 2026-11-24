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
