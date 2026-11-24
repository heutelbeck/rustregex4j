package com.heutelbeck.rustregex4j;

import static com.heutelbeck.rustregex4j.TestRegexes.compiled;
import static com.heutelbeck.rustregex4j.TestRegexes.matches;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import lombok.val;

@DisplayName("Concurrent use")
class ConcurrencyTests {

    private static final int THREADS    = 32;
    private static final int ITERATIONS = 2_000;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("many threads searching one regex get the results of a single thread")
    void whenManyThreadsSearchOneRegexThenResultsEqualSequentialResults() throws Exception {
        val regex    = compiled("(?<user>[\\w.]+)@(?<host>[\\w-]+\\.[a-z]{2,})");
        val texts    = IntStream.range(0, 64).mapToObj(index -> "contact user%d.name@host-%d.org or nobody".formatted(index, index)).toList();
        val expected = texts.stream().map(text -> matches(regex, text)).toList();
        try (val executor = Executors.newFixedThreadPool(THREADS)) {
            val tasks = new ArrayList<Callable<List<List<Match>>>>();
            for (var thread = 0; thread < THREADS; thread++) {
                tasks.add(() -> {
                    val observed = new ArrayList<List<Match>>();
                    for (var iteration = 0; iteration < ITERATIONS; iteration++) {
                        observed.add(matches(regex, texts.get(iteration % texts.size())));
                    }
                    return observed;
                });
            }
            for (val result : executor.invokeAll(tasks)) {
                val observed = result.get();
                assertThat(observed).hasSize(ITERATIONS)
                        .allSatisfy(matches -> assertThat(expected).contains(matches));
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("virtual threads compile, search and drop regexes concurrently")
    void whenVirtualThreadsCompileAndDropRegexesThenSearchesStayCorrect() throws Exception {
        try (val executor = Executors.newVirtualThreadPerTaskExecutor()) {
            val results = new ArrayList<Future<Boolean>>();
            for (var task = 0; task < 10_000; task++) {
                val number = task;
                results.add(executor.submit(() -> compiled("^item-%d$".formatted(number)).isMatch("item-" + number) == IsMatch.YES));
            }
            for (val result : results) {
                assertThat(result.get()).isTrue();
            }
        }
    }
}
