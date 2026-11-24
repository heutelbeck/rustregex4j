package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static com.heutelbeck.rustregex4j.TestRegexes.compiledWholeText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import lombok.val;

@DisplayName("Whole-text matching, continued and bounded searches, group names")
class WholeTextAndIterationTests {

    private static final String GRINNING_FACE = "\uD83D\uDE00";

    @Nested
    @DisplayName("Whole-text matching")
    class WholeText {

        static Stream<Arguments> decisions() {
            return Stream.of(arguments("a|ab", "ab", true), arguments("a|ab", "abc", false), arguments("ab|a", "a", true),
                    arguments("[a-z]{3}-[0-9]{6}", "abc-123456", true), arguments("[a-z]{3}-[0-9]{6}", "xabc-123456", false),
                    arguments("(?m)^a$", "a\nb", false), arguments("(?x) a b  # comment", "ab", true),
                    arguments("", "", true), arguments("", "a", false));
        }

        @MethodSource("decisions")
        @ParameterizedTest(name = "{0} on {1}")
        @DisplayName("matches only if an alternative spans the whole text")
        void whenPatternMustMatchWholeTextThenAnySpanningAlternativeMatches(String pattern, String text, boolean expected) {
            assertThat(compiledWholeText(pattern).isMatch(text)).isEqualTo(IsMatch.of(expected));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = { "a)(b", "(a", "a\\" })
        @DisplayName("rejects patterns that would escape an enclosure written as text")
        void whenPatternTriesToEscapeTheEnclosureThenItIsRejected(String pattern) {
            assertThat(Regex.compileWholeText(pattern)).isInstanceOfSatisfying(RegexError.class,
                    error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.SYNTAX));
        }

        @Test
        @DisplayName("keeps the pattern, group numbers and names as written")
        void whenWholeTextPatternHasGroupsThenNumbersAndNamesAreKept() {
            val regex = compiledWholeText("(?<user>\\w+)@(\\w+)");
            assertThat(List.of(regex.pattern(), regex.wholeText(), regex.groupNames(), regex.captures("alice@example")))
                    .containsExactly("(?<user>\\w+)@(\\w+)", true, List.of(Optional.empty(), Optional.of("user"), Optional.empty()),
                            new Captured(new Captures(List.of(Optional.of(new Match(0, 13, 0, 13)),
                                    Optional.of(new Match(0, 5, 0, 5)), Optional.of(new Match(6, 13, 6, 13))))));
        }
    }

    @Nested
    @DisplayName("Searches from a start position")
    class StartPositions {

        static Stream<Arguments> starts() {
            return Stream.of(arguments("b", "abab", 2, new Found(new Match(3, 4, 3, 4))),
                    arguments("^b", "ab", 1, NoMatch.INSTANCE),
                    arguments("\\bb", "ab b", 1, new Found(new Match(3, 4, 3, 4))),
                    arguments("b", GRINNING_FACE + 'b', 2, new Found(new Match(2, 3, 1, 2))),
                    arguments("", "abc", 3, new Found(new Match(3, 3, 3, 3))));
        }

        @MethodSource("starts")
        @ParameterizedTest(name = "{0} on {1} from {2}")
        @DisplayName("see the whole text, so anchors and word boundaries keep their meaning")
        void whenSearchingFromStartThenAnchorsSeeTheWholeText(String pattern, String text, int start, FindResult expected) {
            assertThat(compiled(pattern).find(text, start)).isEqualTo(expected);
        }

        @ValueSource(ints = { -1, 1, 4 })
        @ParameterizedTest(name = "start {0}")
        @DisplayName("reject a start outside the text or inside a surrogate pair")
        void whenStartIsInvalidThenSearchYieldsError(int start) {
            val regex = compiled("a");
            assertThat(List.of(regex.find(GRINNING_FACE + "a", start), regex.captures(GRINNING_FACE + "a", start)))
                    .allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                            error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.INVALID_ARGUMENT)));
        }

        @Test
        @DisplayName("continue a search after the previous match")
        void whenContinuingAfterEachMatchThenAllMatchesAreFound() {
            val regex = compiled("\\d+");
            val text  = "a1 b22 c333";
            val found = new ArrayList<String>();
            var start = 0;
            while (regex.find(text, start) instanceof Found(var match)) {
                found.add(match.of(text));
                start = match.length() == 0 ? match.end() + 1 : match.end();
            }
            assertThat(found).containsExactly("1", "22", "333");
        }
    }

    @Nested
    @DisplayName("Bounded searches")
    class BoundedSearches {

        @Test
        @DisplayName("stop after the limit")
        void whenSearchIsLimitedThenItStopsAfterTheLimit() {
            val regex = compiled("(\\d)(\\d)?");
            val text  = "1 23 4 56";
            assertThat(List.of(regex.findAll(text, 2), regex.findAll(text, 0), regex.capturesAll(text, 2)))
                    .containsExactly(new Matches(List.of(new Match(0, 1, 0, 1), new Match(2, 4, 2, 4))), new Matches(List.of()),
                            new AllCaptures(List.of(
                                    new Captures(List.of(Optional.of(new Match(0, 1, 0, 1)), Optional.of(new Match(0, 1, 0, 1)),
                                            Optional.empty())),
                                    new Captures(List.of(Optional.of(new Match(2, 4, 2, 4)), Optional.of(new Match(2, 3, 2, 3)),
                                            Optional.of(new Match(3, 4, 3, 4)))))));
        }

        @Test
        @DisplayName("reject a negative limit")
        void whenLimitIsNegativeThenSearchYieldsError() {
            val regex = compiled("a");
            assertThat(List.<Object>of(regex.findAll("a", -1), regex.capturesAll("a", -1)))
                    .allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                            error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.INVALID_ARGUMENT)));
        }
    }

    @Nested
    @DisplayName("Group names")
    class GroupNames {

        @Test
        @DisplayName("map names to group numbers")
        void whenPatternHasNamedGroupsThenNamesMapToNumbers() {
            val regex = compiled("(?P<year>\\d{4})-(\\d{2})-(?<day>\\d{2})");
            assertThat(List.of(regex.groupIndex("year"), regex.groupIndex("day"), regex.groupIndex("month")))
                    .containsExactly(OptionalInt.of(1), OptionalInt.of(3), OptionalInt.empty());
        }
    }
}
