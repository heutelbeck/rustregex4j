package com.heutelbeck.rustregex4j;

import lombok.experimental.UtilityClass;
import lombok.val;

/** Measures searches for tests that bound or compare their running time. */
@UtilityClass
class SearchTiming {

    /**
     * Runs a search a number of times to warm up the JIT, then returns the fastest of the
     * measured runs. The fastest run is the one least disturbed by garbage collection and
     * other load of the machine.
     *
     * @return the nanoseconds of the fastest measured run, at least 1
     */
    static long fastestNanos(Runnable search, int warmUpRuns, int measuredRuns) {
        for (var run = 0; run < warmUpRuns; run++) {
            search.run();
        }
        var fastest = Long.MAX_VALUE;
        for (var run = 0; run < measuredRuns; run++) {
            val start = System.nanoTime();
            search.run();
            fastest = Math.min(fastest, Math.max(1, System.nanoTime() - start));
        }
        return fastest;
    }
}
