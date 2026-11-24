package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static com.heutelbeck.rustregex4j.TestRegexes.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import lombok.val;

@DisplayName("Compiling patterns")
class CompilationTests {

    @Nested
    @DisplayName("Valid patterns")
    class ValidPatterns {

        @ParameterizedTest(name = "{0}")
        @DisplayName("compile and keep their pattern and limits")
        @ValueSource(strings = { "", "a", "^[a-z]{3}-[0-9]{6}$", "(?i)administrator", "/(?:api|admin)/v[0-9]+/.*",
                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "\\p{Greek}+", "(?P<user>\\w+)@(?<host>\\w+)",
                "\uD83D\uDE00+" })
        void whenPatternIsValidThenItCompiles(String pattern) {
            val regex = compiled(pattern);
            assertThat(regex).satisfies(compiled -> assertThat(compiled.pattern()).isEqualTo(pattern),
                    compiled -> assertThat(compiled.limits()).isEqualTo(RegexLimits.DEFAULT),
                    compiled -> assertThat(compiled).hasToString(pattern));
        }

        @Test
        @DisplayName("count capture groups including the whole match")
        void whenPatternHasGroupsThenGroupCountIncludesGroupZero() {
            assertThat(new int[] { compiled("abc").groupCount(), compiled("(a)(?:b)(?P<c>c)").groupCount() })
                    .containsExactly(1, 3);
        }
    }

    @Nested
    @DisplayName("Rejected patterns")
    class RejectedPatterns {

        static Stream<Arguments> rejections() {
            return Stream.of(arguments("unclosed group", "(abc", RegexErrorKind.SYNTAX),
                    arguments("unopened group", "abc)", RegexErrorKind.SYNTAX),
                    arguments("unclosed class", "[a-z", RegexErrorKind.SYNTAX),
                    arguments("backreference", "(a)\\1", RegexErrorKind.SYNTAX),
                    arguments("look-ahead", "a(?=b)", RegexErrorKind.SYNTAX),
                    arguments("invalid repetition range", "a{3,1}", RegexErrorKind.SYNTAX),
                    arguments("unknown Unicode class", "\\p{Klingon}", RegexErrorKind.SYNTAX),
                    arguments("nesting beyond the limit", "(".repeat(65) + 'a' + ")".repeat(65), RegexErrorKind.NESTING_TOO_DEEP),
                    arguments("compiled size beyond the limit", "\\w{1000}", RegexErrorKind.PATTERN_TOO_LARGE),
                    arguments("unpaired high surrogate", "a\uD800", RegexErrorKind.MALFORMED_UNICODE),
                    arguments("unpaired low surrogate", "\uDC00a", RegexErrorKind.MALFORMED_UNICODE),
                    arguments("more UTF-16 units than the limit", "a".repeat(4097), RegexErrorKind.PATTERN_TOO_LONG),
                    arguments("more UTF-8 bytes than the limit", "\u00e9".repeat(2049), RegexErrorKind.PATTERN_TOO_LONG));
        }

        @MethodSource("rejections")
        @ParameterizedTest(name = "{0}")
        @DisplayName("yield an error with a stable kind and a message")
        void whenPatternIsInvalidThenCompilationYieldsError(String description, String pattern, RegexErrorKind kind) {
            assertThat(rejected(pattern, RegexLimits.DEFAULT)).satisfies(error -> assertThat(error.kind()).isEqualTo(kind),
                    error -> assertThat(error.message()).isNotBlank());
        }

        @Test
        @DisplayName("report the offending part of the pattern in syntax errors")
        void whenSyntaxIsInvalidThenMessagePointsAtTheError() {
            assertThat(rejected("abc(def", RegexLimits.DEFAULT).message()).contains("abc(def").contains("unclosed group");
        }

        @Test
        @DisplayName("accept nesting exactly at the limit and a pattern exactly at the length limit")
        void whenPatternIsExactlyAtLimitsThenItCompiles() {
            val nested = "(".repeat(63) + 'a' + ")".repeat(63);
            assertThat(new Object[] { compiled(nested), compiled("\u00e9".repeat(2048)) }).doesNotContainNull();
        }
    }

    @Nested
    @DisplayName("Custom limits")
    class CustomLimits {

        @Test
        @DisplayName("admit patterns the defaults reject")
        void whenLimitsAreRaisedThenLargerPatternsCompile() {
            val limits = RegexLimits.DEFAULT.withSizeLimit(64L * 1024 * 1024).withMaxPatternBytes(20_000).withNestLimit(200);
            assertThat(new Object[] { compiled("\\w{1000}", limits), compiled("a".repeat(10_000), limits),
                    compiled("(".repeat(150) + 'a' + ")".repeat(150), limits) }).doesNotContainNull();
        }

        @Test
        @DisplayName("reject patterns the defaults admit")
        void whenLimitsAreLoweredThenSmallerPatternsAreRejected() {
            val limits = RegexLimits.DEFAULT.withMaxPatternBytes(8).withSizeLimit(1000).withNestLimit(2);
            assertThat(new RegexErrorKind[] { rejected("abcdefghi", limits).kind(), rejected("\\w{5}", limits).kind(),
                    rejected("(((a)))", limits).kind() })
                    .containsExactly(RegexErrorKind.PATTERN_TOO_LONG, RegexErrorKind.PATTERN_TOO_LARGE,
                            RegexErrorKind.NESTING_TOO_DEEP);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("must be positive")
        @ValueSource(strings = { "maxPatternBytes", "nestLimit", "sizeLimit", "dfaSizeLimit", "maxTextBytes" })
        void whenLimitIsNotPositiveThenItIsRefused(String limit) {
            assertThatIllegalArgumentException().isThrownBy(() -> withZero(limit)).withMessageContaining(limit);
        }

        private static RegexLimits withZero(String limit) {
            val defaults = RegexLimits.DEFAULT;
            return switch (limit) {
            case "maxPatternBytes" -> defaults.withMaxPatternBytes(0);
            case "nestLimit"       -> defaults.withNestLimit(0);
            case "sizeLimit"       -> defaults.withSizeLimit(0);
            case "dfaSizeLimit"    -> defaults.withDfaSizeLimit(0);
            default                -> defaults.withMaxTextBytes(0);
            };
        }
    }
}
