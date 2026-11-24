package com.heutelbeck.rustregex4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

@DisplayName("Sets of patterns")
class RegexSetTests {

    private static RegexSet compiled(RegexSetCompilation compilation) {
        return switch (compilation) {
        case RegexSet set     -> set;
        case RegexError error -> fail("Expected the set to compile, but: %s", error);
        };
    }

    private static List<Integer> matching(RegexSet set, String text) {
        return switch (set.matches(text)) {
        case SetMatches(var patterns) -> patterns;
        case RegexError error         -> fail("Expected %s to be searched, but: %s", text, error);
        };
    }

    static Stream<Arguments> matches() {
        val anywhere  = List.of("a", "b", "ab", "x*");
        val wholeText = List.of("a|ab", "ab|a", "b", "[a-z]+", "");
        return Stream.of(arguments("every pattern found anywhere", anywhere, false, "ab", List.of(0, 1, 2, 3)),
                arguments("only the pattern that matches the empty string", anywhere, false, "zzz", List.of(3)),
                arguments("every alternative that spans the whole text", wholeText, true, "ab", List.of(0, 1, 3)),
                arguments("the empty pattern for the empty text", wholeText, true, "", List.of(4)),
                arguments("no pattern", wholeText, true, "a\nb", List.of()),
                arguments("no pattern of an empty set", List.of(), true, "a", List.of()));
    }

    @MethodSource("matches")
    @ParameterizedTest(name = "{0}")
    @DisplayName("one search reports")
    void whenSetIsMatchedThenEveryMatchingPatternIsReported(String description, List<String> patterns, boolean wholeText,
            String text, List<Integer> expected) {
        val set = compiled(wholeText ? RegexSet.compileWholeText(patterns) : RegexSet.compile(patterns));
        assertThat(matching(set, text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a set agrees with its patterns compiled one by one")
    void whenSetIsMatchedThenItAgreesWithSingleRegexes() {
        val patterns = List.of("[a-z]{3}-[0-9]{6}", "(?i)admin.*", "\\w+", "a|ab", "\\p{Greek}+", "/api/v[0-9]+/.*");
        val texts    = List.of("abc-123456", "ADMINISTRATOR", "Grüße", "ab", "αβ", "/api/v2/users", "", "!");
        val set      = compiled(RegexSet.compileWholeText(patterns));
        val singles  = patterns.stream().map(pattern -> (Regex) Regex.compileWholeText(pattern)).toList();
        assertThat(texts).allSatisfy(text -> assertThat(matching(set, text)).isEqualTo(IntStream.range(0, singles.size())
                .filter(index -> singles.get(index).isMatch(text) == IsMatch.YES).boxed().toList()));
    }

    @Test
    @DisplayName("one search finds the pattern that matches among a thousand")
    void whenSetHasManyPatternsThenOneSearchFindsTheMatch() {
        val patterns = IntStream.range(0, 1000).mapToObj(tenant -> "/api/v1/tenant-" + tenant + "/users/[0-9]+").toList();
        val set      = compiled(RegexSet.compileWholeText(patterns));
        assertThat(List.of(matching(set, "/api/v1/tenant-500/users/42"), matching(set, "/api/v1/tenant-5000/users/42")))
                .containsExactly(List.of(500), List.of());
    }

    static Stream<Arguments> rejections() {
        return Stream.of(arguments("an unclosed group", List.of("a", "(b"), RegexErrorKind.SYNTAX),
                arguments("a look-ahead", List.of("a", "(?=b)"), RegexErrorKind.SYNTAX),
                arguments("a pattern too large alone", List.of("a", "\\w{1000}"), RegexErrorKind.PATTERN_TOO_LARGE),
                arguments("a pattern too long alone", List.of("a", "a".repeat(4097)), RegexErrorKind.PATTERN_TOO_LONG),
                arguments("a pattern that would escape an enclosure written as text", List.of("a)(b"), RegexErrorKind.SYNTAX),
                arguments("an unpaired surrogate", List.of("a\uD800"), RegexErrorKind.MALFORMED_UNICODE));
    }

    @MethodSource("rejections")
    @ParameterizedTest(name = "{0}")
    @DisplayName("a set is rejected for the reason its pattern is rejected alone")
    void whenPatternIsRejectedAloneThenSetIsRejected(String description, List<String> patterns, RegexErrorKind kind) {
        assertThat(RegexSet.compileWholeText(patterns)).isInstanceOfSatisfying(RegexError.class,
                error -> assertThat(error.kind()).isEqualTo(kind));
    }

    @Test
    @DisplayName("a text the engine cannot search yields an error, not an empty result")
    void whenTextCannotBeSearchedThenSearchYieldsError() {
        val set = compiled(RegexSet.compile(List.of("a"), RegexLimits.DEFAULT.withMaxTextBytes(4)));
        assertThat(List.of(set.matches("aaaa"), set.matches("aaaaa"), set.matches("ééa"), set.matches("a\uD800")))
                .satisfiesExactly(result -> assertThat(result).isEqualTo(new SetMatches(List.of(0))),
                        result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                                error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.TEXT_TOO_LONG)),
                        result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                                error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.TEXT_TOO_LONG)),
                        result -> assertThat(result).isInstanceOfSatisfying(RegexError.class,
                                error -> assertThat(error.kind()).isEqualTo(RegexErrorKind.MALFORMED_UNICODE)));
    }

    @Test
    @DisplayName("a set keeps its patterns, limits and mode")
    void whenSetIsCompiledThenItKeepsWhatItWasCompiledFrom() {
        val limits = RegexLimits.DEFAULT.withMaxTextBytes(100);
        val set    = compiled(RegexSet.compileWholeText(List.of("a", "b"), limits));
        assertThat(List.of(set.patterns(), set.limits(), set.wholeText(), set.toString())).containsExactly(List.of("a", "b"),
                limits, true, "[a, b]");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("many threads searching one set get the results of a single thread")
    void whenManyThreadsSearchOneSetThenResultsEqualSequentialResults() throws Exception {
        val set      = compiled(RegexSet.compileWholeText(List.of("user-[0-9]+", "user-.*7", "admin-[0-9]+")));
        val texts    = IntStream.range(0, 64).mapToObj(index -> (index % 5 == 0 ? "admin-" : "user-") + index).toList();
        val expected = texts.stream().map(text -> matching(set, text)).toList();
        try (val executor = Executors.newVirtualThreadPerTaskExecutor()) {
            val results = IntStream.range(0, 20_000)
                    .<Future<Boolean>>mapToObj(task -> executor.submit(() -> matching(set, texts.get(task % texts.size()))
                            .equals(expected.get(task % texts.size()))))
                    .toList();
            for (val result : results) {
                assertThat(result.get()).isTrue();
            }
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("unreachable sets free their engine memory")
    void whenSetsBecomeUnreachableThenEngineMemoryIsFreed() throws InterruptedException {
        val baseline = settledLiveRegexes();
        for (var index = 0; index < 5_000; index++) {
            compiled(RegexSet.compile(List.of("user-" + index, "admin-" + index))).matches("user-" + index);
        }
        assertThat(settledLiveRegexes()).isLessThanOrEqualTo(baseline);
    }

    private static long settledLiveRegexes() throws InterruptedException {
        var previous = Long.MAX_VALUE;
        var live     = Regex.liveRegexes();
        for (var round = 0; round < 20 && live < previous; round++) {
            System.gc();
            Thread.sleep(100);
            previous = live;
            live     = Regex.liveRegexes();
        }
        return live;
    }
}
