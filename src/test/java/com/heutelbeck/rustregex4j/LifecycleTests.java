package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import lombok.val;

/**
 * The engine counts its compiled regexes. That the engine frees all memory of a freed regex
 * is proven by the Rust tests of the engine; these tests prove that every regex is freed.
 */
@DisplayName("Memory of compiled regexes")
class LifecycleTests {

    private static final int REGEXES    = 20_000;
    private static final int REJECTIONS = 2_000;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("unreachable regexes free their engine memory")
    void whenRegexesBecomeUnreachableThenEngineMemoryIsFreed() throws InterruptedException {
        val baseline = settledLiveRegexes();
        for (var index = 0; index < REGEXES; index++) {
            val regex = compiled("(?i)user-%06d@[a-z]+\\.(org|com)".formatted(index));
            regex.captures("USER-%06d@example.org".formatted(index));
        }
        assertThat(settledLiveRegexes()).isLessThanOrEqualTo(baseline);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("rejected patterns leave no compiled regex behind")
    void whenPatternsAreRejectedThenNoCompiledRegexRemains() {
        val baseline = Regex.liveRegexes();
        for (var index = 0; index < REJECTIONS; index++) {
            Regex.compile(index % 2 == 0 ? "(unclosed-" + index : "\\w{" + (100 + index % 900) + '}');
        }
        assertThat(Regex.liveRegexes()).as("no rejected pattern leaves a regex; the cleaner may free earlier ones meanwhile")
                .isLessThanOrEqualTo(baseline);
    }

    @Test
    @DisplayName("a reachable regex keeps its engine memory")
    void whenRegexIsReachableThenItStaysUsableAfterGarbageCollection() throws InterruptedException {
        val regex = compiled("^kept-[0-9]+$");
        settledLiveRegexes();
        assertThat(regex.isMatch("kept-42")).isEqualTo(IsMatch.YES);
    }

    /** Collects garbage until the number of live regexes stops falling, at most twenty rounds. */
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
