package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static com.heutelbeck.rustregex4j.TestRegexes.matches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

@DisplayName("Searching texts")
class SearchTests {

    private static final String GRINNING_FACE = "\uD83D\uDE00";

    @Nested
    @DisplayName("Semantics of the Rust regex crate")
    class Semantics {

        static Stream<Arguments> decisions() {
            return Stream.of(arguments("unanchored pattern matches anywhere", "b", "abc", true),
                    arguments("anchors bind to the whole text", "^b$", "abc", false),
                    arguments("anchored pattern matches the whole text", "^[a-z]{3}-[0-9]{6}$", "abc-123456", true),
                    arguments("dot excludes the line break", "a.b", "a\nb", false),
                    arguments("flag s lets dot match the line break", "(?s)a.b", "a\nb", true),
                    arguments("dollar ignores a final line break without flag m", "a$", "a\n", false),
                    arguments("flag m anchors at lines", "(?m)^b$", "a\nb\nc", true),
                    arguments("case-insensitive matching uses simple case folding", "(?i)\u03a3", "\u03c2", true),
                    arguments("case-insensitive matching has no multi-character folding", "(?i)stra\u00dfe", "STRASSE", false),
                    arguments("word characters are Unicode", "^\\w+$", "Gr\u00fc\u00dfe", true),
                    arguments("digits are Unicode", "^\\d+$", "\u0661\u0662", true),
                    arguments("ASCII mode restricts classes", "^(?-u:\\d)+$", "\u0661\u0662", false),
                    arguments("empty pattern matches every text", "", "", true));
        }

        @MethodSource("decisions")
        @ParameterizedTest(name = "{0}")
        @DisplayName("decide matches as the Rust crate does")
        void whenTestingTextThenDecisionFollowsRustSemantics(String description, String pattern, String text, boolean expected) {
            assertThat(compiled(pattern).isMatch(text)).isEqualTo(IsMatch.of(expected));
        }

        @Test
        @DisplayName("prefer the first alternative that matches, not the longest")
        void whenAlternativesOverlapThenLeftmostFirstWins() {
            assertThat(compiled("a|ab").find("ab")).isEqualTo(new Found(new Match(0, 1, 0, 1)));
        }

        @Test
        @DisplayName("report no match as NoMatch")
        void whenPatternDoesNotMatchThenFindAndCapturesReportNoMatch() {
            val regex = compiled("x(y)");
            assertThat(new Object[] { regex.find("abc"), regex.captures("abc"), regex.findAll("abc") })
                    .containsExactly(NoMatch.INSTANCE, NoMatch.INSTANCE, new Matches(List.of()));
        }
    }

    @Nested
    @DisplayName("Positions")
    class Positions {

        static Stream<Arguments> positions() {
            return Stream.of(arguments("ASCII", "b+", "abbc", List.of(new Match(1, 3, 1, 3))),
                    arguments("after a character outside the BMP", "b", GRINNING_FACE + 'b', List.of(new Match(2, 3, 1, 2))),
                    arguments("of characters outside the BMP", GRINNING_FACE + '+', 'a' + GRINNING_FACE + GRINNING_FACE,
                            List.of(new Match(1, 5, 1, 3))),
                    arguments("of empty matches around surrogate pairs", "", GRINNING_FACE + '\u00e9',
                            List.of(new Match(0, 0, 0, 0), new Match(2, 2, 1, 1), new Match(3, 3, 2, 2))),
                    arguments("of empty and non-empty matches", "x*", GRINNING_FACE + 'x',
                            List.of(new Match(0, 0, 0, 0), new Match(2, 3, 1, 2))),
                    arguments("of Unicode words", "\\w+", "Gr\u00fc\u00dfe \u4e16", List.of(new Match(0, 5, 0, 5), new Match(6, 7, 6, 7))),
                    arguments("at the end of the text", "$", GRINNING_FACE, List.of(new Match(2, 2, 1, 1))));
        }

        @MethodSource("positions")
        @ParameterizedTest(name = "{0}")
        @DisplayName("are UTF-16 indexes and code point offsets")
        void whenFindingAllThenPositionsAreUtf16IndexesAndCodepoints(String description, String pattern, String text,
                List<Match> expected) {
            assertThat(matches(compiled(pattern), text)).isEqualTo(expected);
        }

        @Test
        @DisplayName("slice the searched text")
        void whenMatchIsFoundThenItSlicesTheText() {
            val text = "mail: alice@example.org";
            assertThat(compiled("\\w+@[\\w.]+").find(text)).isInstanceOfSatisfying(Found.class,
                    found -> assertThat(found.match()).satisfies(match -> assertThat(match.of(text)).isEqualTo("alice@example.org"),
                            match -> assertThat(match.length()).isEqualTo(17)));
        }
    }

    @Nested
    @DisplayName("Capture groups")
    class CaptureGroups {

        @Test
        @DisplayName("report present and absent groups, group 0 first")
        void whenCapturingThenAbsentGroupsAreEmpty() {
            val text = "id " + GRINNING_FACE + 'x';
            assertThat(compiled("(\\d)|(\\p{So})(x)").captures(text)).isInstanceOfSatisfying(Captured.class,
                    captured -> assertThat(captured.captures()).satisfies(captures -> assertThat(captures.groupCount()).isEqualTo(4),
                            captures -> assertThat(captures.match().of(text)).isEqualTo(GRINNING_FACE + 'x'),
                            captures -> assertThat(captures.group(1)).isEmpty(),
                            captures -> assertThat(captures.group(2).map(group -> group.of(text))).contains(GRINNING_FACE),
                            captures -> assertThat(captures.group(3)).contains(new Match(5, 6, 4, 5))));
        }

        @Test
        @DisplayName("capture the leftmost-first match only")
        void whenTextHasSeveralMatchesThenCapturesDescribeTheFirst() {
            assertThat(compiled("(\\w)(\\d)").captures("a1 b2")).isEqualTo(new Captured(new Captures(List.of(
                    Optional.of(new Match(0, 2, 0, 2)), Optional.of(new Match(0, 1, 0, 1)), Optional.of(new Match(1, 2, 1, 2))))));
        }
    }
}
