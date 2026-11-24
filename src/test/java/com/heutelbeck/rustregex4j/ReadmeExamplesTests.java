package com.heutelbeck.rustregex4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The examples of the README, so that they compile and do what the README says. */
@DisplayName("Examples of the README")
class ReadmeExamplesTests {

    private static final String INPUT = "order abc-123456 by alice@example.org, abc-654321";

    @Test
    @DisplayName("compiling and searching with sealed results")
    void whenFollowingTheUsageExamplesThenResultsAreAsDescribed() {
        Regex identifier = switch (Regex.compile("[a-z]{3}-[0-9]{6}")) {
        case Regex regex      -> regex;
        case RegexError error -> throw new IllegalStateException(error.kind() + ": " + error.message());
        };
        boolean valid = switch (identifier.isMatch(INPUT)) {
        case IsMatch(var matches) -> matches;
        case RegexError error     -> false;
        };
        String firstId = switch (identifier.find(INPUT)) {
        case Found(var match) -> match.of(INPUT);
        case NoMatch noMatch  -> "";
        case RegexError error -> "";
        };
        List<Match> all = switch (identifier.findAll(INPUT)) {
        case Matches(var matches) -> matches;
        case RegexError error     -> List.of();
        };
        Regex mail = (Regex) Regex.compile("(?<user>[\\w.]+)@(?<host>[\\w.-]+)");
        String user = switch (mail.captures(INPUT)) {
        case Captured(var captures) -> captures.group(1).map(group -> group.of(INPUT)).orElse("");
        case NoMatch noMatch        -> "";
        case RegexError error       -> "";
        };
        assertThat(List.of(valid, firstId, all.size(), user)).containsExactly(true, "abc-123456", 2, "alice");
    }

    @Test
    @DisplayName("searching from many threads")
    void whenSearchingFromVirtualThreadsThenEverySearchSucceeds() {
        Regex email = (Regex) Regex.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        var texts   = new ArrayList<String>();
        for (var index = 0; index < 1_000; index++) {
            texts.add("user" + index + "@example.org");
        }
        var results = new ConcurrentLinkedQueue<IsMatchResult>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String text : texts) {
                executor.submit(() -> results.add(email.isMatch(text)));
            }
        }
        assertThat(results).hasSize(1_000).containsOnly(IsMatch.YES);
    }

    @Test
    @DisplayName("replacing and splitting")
    void whenReplacingAndSplittingThenResultsAreAsDescribed() {
        Regex date = (Regex) Regex.compile("(?<day>[0-9]{2})\\.(?<month>[0-9]{2})\\.(?<year>[0-9]{4})");
        String iso = switch (date.replaceAll("due 24.11.2026", "${year}-${month}-${day}")) {
        case Replaced(var result) -> result;
        case RegexError error     -> "";
        };
        Regex comma = (Regex) Regex.compile(",");
        List<String> fields = switch (comma.split("a,b,,c", 100)) {
        case Parts(var parts) -> parts;
        case RegexError error -> List.of();
        };
        assertThat(List.of(iso, fields)).containsExactly("due 2026-11-24", List.of("a", "b", "", "c"));
    }

    @Test
    @DisplayName("matching the whole text")
    void whenCompilingForTheWholeTextThenOnlySpanningMatchesCount() {
        Regex identifier = (Regex) Regex.compileWholeText("[a-z]{3}-[0-9]{6}");
        assertThat(List.of(identifier.isMatch("abc-123456"), identifier.isMatch("xabc-123456")))
                .containsExactly(IsMatch.YES, IsMatch.NO);
    }

    @Test
    @DisplayName("deriving limits from the defaults")
    void whenDerivingLimitsThenOnlyTheChangedLimitsDiffer() {
        RegexLimits generous = RegexLimits.DEFAULT.withMaxTextBytes(1024 * 1024).withSizeLimit(4L * 1024 * 1024);
        assertThat(generous).isEqualTo(new RegexLimits(4096, 64, 4L * 1024 * 1024, 2 * 1024 * 1024, 1024 * 1024));
    }
}
