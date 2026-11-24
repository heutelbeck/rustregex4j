package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.SearchTiming.fastestNanos;
import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import lombok.val;

@DisplayName("Linear search time")
class LinearityTests {

    private static final int[]  SIZES         = { 4_096, 8_192, 16_384, 32_768, 65_000 };
    private static final double MAX_EXPONENT  = 1.7;
    private static final int    WARM_UP_RUNS  = 50;
    private static final int    MEASURED_RUNS = 15;

    /**
     * Patterns that take exponential or polynomial time on backtracking engines, with the
     * unit their text repeats and the suffix that ends it. The suffix holds the literal the
     * pattern requires, so literal prefilters cannot skip the search, while the character
     * before it prevents the match.
     */
    static Stream<Arguments> catastrophicPatterns() {
        return Stream.of(arguments("(a|aa)*b", "a", "!b"), arguments("(x+x+)+y", "x", "!y"), arguments("(a*)*b", "a", "!b"),
                arguments("a*a*a*b", "a", "!b"), arguments("(\\w+\\s?)+$", "a", "!"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("catastrophicPatterns")
    @DisplayName("grows linearly with the text for patterns that are catastrophic for backtracking")
    void whenSearchingCatastrophicPatternThenTimeGrowsLinearly(String pattern, String unit, String suffix) {
        val regex           = compiled(pattern);
        val sizeLogarithms  = new double[SIZES.length];
        val timeLogarithms  = new double[SIZES.length];
        for (var index = 0; index < SIZES.length; index++) {
            val text = unit.repeat(SIZES[index]) + suffix;
            sizeLogarithms[index] = Math.log(SIZES[index]);
            timeLogarithms[index] = Math.log(fastestNanos(() -> regex.find(text), WARM_UP_RUNS, MEASURED_RUNS));
        }
        assertThat(slope(sizeLogarithms, timeLogarithms)).as("power law exponent of %s", pattern).isLessThan(MAX_EXPONENT);
    }

    /** The slope of the least-squares line through the points, the exponent of the power law. */
    private static double slope(double[] abscissas, double[] ordinates) {
        val meanAbscissa = Arrays.stream(abscissas).average().orElse(0);
        val meanOrdinate = Arrays.stream(ordinates).average().orElse(0);
        var covariance   = 0.0;
        var variance     = 0.0;
        for (var index = 0; index < abscissas.length; index++) {
            covariance += (abscissas[index] - meanAbscissa) * (ordinates[index] - meanOrdinate);
            variance   += (abscissas[index] - meanAbscissa) * (abscissas[index] - meanAbscissa);
        }
        return covariance / variance;
    }
}
