package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.SearchTiming.fastestNanos;
import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

/**
 * The default limits bound the cost of every search. On an x86_64 workstation the largest
 * patterns the defaults admit take at most 0.1 ms per search and 6 ms for finding all matches
 * of a text at the length limit; the bound here leaves a wide margin for slower machines.
 */
@DisplayName("Worst case under the default limits")
class WorstCaseTests {

    private static final long   BOUND_NANOS   = 100_000_000L;
    private static final int    MAX_REPEAT    = 1_000;
    private static final String TEXT_UNIT     = "xa1 aaaa b9 ";
    private static final int    TEXT_LENGTH   = RegexLimits.DEFAULT.maxTextBytes();
    private static final int    WARM_UP_RUNS  = 3;
    private static final int    MEASURED_RUNS = 5;

    /** Pattern families whose compiled size grows with the number of repetitions. */
    static Stream<Arguments> families() {
        return Stream.of(arguments("blocks of any character", (IntFunction<String>) repetitions -> "(?s:.{8}){" + repetitions + '}'),
                arguments("word or space pairs", (IntFunction<String>) repetitions -> "(?:(?:\\w|\\s){2}){" + repetitions + '}'),
                arguments("Unicode word characters", (IntFunction<String>) repetitions -> "\\w{" + repetitions + '}'),
                arguments("optional prefix", (IntFunction<String>) repetitions -> "(?:a?){" + repetitions + "}a{" + repetitions + '}'),
                arguments("bounded repetition with empty matches", (IntFunction<String>) repetitions -> "(?:x\\d){0," + repetitions + '}'),
                arguments("alternation", (IntFunction<String>) repetitions -> "(?:alpha|beta|\\d+|\\w){" + repetitions + '}'),
                arguments("case folding", (IntFunction<String>) repetitions -> "(?i)[a-z\\u00e0-\\u017f]{" + repetitions + '}'));
    }

    @MethodSource("families")
    @ParameterizedTest(name = "{0}")
    @DisplayName("the largest admitted pattern searches a text at the length limit within the bound")
    void whenPatternIsLargestAdmittedThenEverySearchStaysWithinBound(String family, IntFunction<String> pattern) {
        val regex = compiled(pattern.apply(largestAdmitted(pattern)));
        val text  = TEXT_UNIT.repeat(TEXT_LENGTH / TEXT_UNIT.length() + 1).substring(0, TEXT_LENGTH);
        assertThat(List.of(fastest(() -> regex.isMatch(text)), fastest(() -> regex.find(text)),
                fastest(() -> regex.findAll(text)), fastest(() -> regex.captures(text)),
                fastest(() -> regex.capturesAll(text, Integer.MAX_VALUE))))
                .as("nanoseconds of isMatch, find, findAll, captures, capturesAll for %s", regex)
                .allSatisfy(nanos -> assertThat(nanos).isLessThan(BOUND_NANOS));
    }

    private static int largestAdmitted(IntFunction<String> pattern) {
        var repetitions = 1;
        while (repetitions < MAX_REPEAT && Regex.compile(pattern.apply(repetitions + 1)) instanceof Regex) {
            repetitions++;
        }
        return repetitions;
    }

    private static long fastest(Runnable search) {
        return fastestNanos(search, WARM_UP_RUNS, MEASURED_RUNS);
    }
}
