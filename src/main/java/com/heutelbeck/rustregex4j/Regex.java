package com.heutelbeck.rustregex4j;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import lombok.val;

/**
 * A compiled regular expression, matched by the Rust {@code regex} crate.
 * <p>
 * The pattern syntax and the matching semantics are those of the Rust
 * <a href="https://docs.rs/regex/1.13.2/regex/">regex</a> crate 1.13.2: every search runs in
 * time linear in the length of the text, there are no backreferences and no look-around, and
 * matching is Unicode-aware. Because the Rust code does the matching, a Rust program that uses
 * the same crate version with the same configuration finds exactly the same matches.
 *
 * <h2>Compiling</h2>
 * {@link #compile(String)} returns either the compiled {@code Regex} or a {@link RegexError}.
 * It never throws for an invalid pattern, so all validation happens when the pattern is
 * compiled, typically once when a configuration or policy is loaded:
 * <pre>{@code
 * switch (Regex.compile("[a-z]{3}-[0-9]{6}")) {
 *     case Regex regex      -> identifiers = regex;
 *     case RegexError error -> report(error.kind(), error.message());
 * }
 * }</pre>
 * {@link #compileWholeText(String)} compiles a pattern that must match the whole text, as
 * a full-match operator needs it.
 *
 * <h2>Searching</h2>
 * Every search returns a sealed result. Searches reject only texts that are longer than
 * {@link RegexLimits#maxTextBytes()} or that contain an unpaired UTF-16 surrogate, and
 * arguments out of range; such a search yields a {@link RegexError}, never an exception:
 * <pre>{@code
 * boolean valid = switch (identifiers.isMatch(input)) {
 *     case IsMatch(var matches) -> matches;
 *     case RegexError error     -> false;
 * };
 * }</pre>
 * {@link #replaceAll(String, String)} and {@link #split(String, int)} replace and split as
 * the Rust crate does, with its syntax of replacements.
 *
 * <h2>Engine configuration</h2>
 * The defaults of the {@code regex} crate: Unicode mode, case sensitive, {@code ^} and
 * {@code $} match at the start and end of the text only, {@code .} does not match
 * {@code \n}, leftmost-first match semantics. Patterns change this with inline flags such as
 * {@code (?i)}, {@code (?m)}, {@code (?s)}, {@code (?x)}, {@code (?U)} and {@code (?-u)}.
 *
 * <h2>Threads</h2>
 * A {@code Regex} is immutable and thread-safe. Any number of threads may search with the
 * same instance at the same time, without locking; each thread uses its own matching cache
 * inside the Rust crate.
 *
 * <h2>Resources</h2>
 * The compiled pattern lives in the memory of the Rust engine, outside the Java heap. It is
 * freed automatically when the {@code Regex} becomes unreachable; there is nothing to close.
 */
public final class Regex implements RegexCompilation {

    private static final String ERROR_INTERNAL         = "The regex engine failed unexpectedly.";
    private static final String ERROR_INVALID_START    = "The start %d lies outside the text or inside a UTF-16 surrogate pair.";
    private static final String ERROR_LIMITS_NULL      = "The limits must not be null.";
    private static final String ERROR_MALFORMED_TEXT   = "The text contains an unpaired UTF-16 surrogate.";
    private static final String ERROR_NAME_NULL        = "The group name must not be null.";
    private static final String ERROR_NEGATIVE_LIMIT   = "The limit %d must not be negative.";
    private static final String ERROR_PATTERN_TOO_LONG = "The pattern is longer than %d UTF-8 bytes.";
    private static final String ERROR_PATTERN_NULL     = "The pattern must not be null.";
    private static final String ERROR_REPLACEMENT_NULL = "The replacement must not be null.";
    private static final String ERROR_RESULT_TOO_LONG  = "The result of the replacement is longer than %d UTF-8 bytes.";
    private static final String ERROR_TEXT_NULL        = "The text must not be null.";
    private static final String ERROR_TEXT_TOO_LONG    = "The text is longer than %d UTF-8 bytes.";

    private static final Cleaner CLEANER = Cleaner.create();

    private static final int    FOUND          = 1;
    private static final int    SPAN_VALUES    = 4;
    private static final int    GROUP_VALUES   = SPAN_VALUES + 1;
    private static final String NAME_SEPARATOR = "\u0000";

    private final String                 pattern;
    private final RegexLimits            limits;
    private final boolean                wholeText;
    private final long                   pointer;
    private final int                    groupCount;
    private final List<Optional<String>> groupNames;

    private Regex(String pattern, RegexLimits limits, boolean wholeText, long pointer) {
        this.pattern    = pattern;
        this.limits     = limits;
        this.wholeText  = wholeText;
        this.pointer    = pointer;
        this.groupCount = RegexEngine.groupCount(pointer);
        this.groupNames = namesOf(pointer, groupCount);
        CLEANER.register(this, () -> RegexEngine.free(pointer));
    }

    /**
     * Compiles a pattern whose matches may lie anywhere in a text, with the
     * {@linkplain RegexLimits#DEFAULT default limits}.
     *
     * @param pattern the pattern in the syntax of the Rust {@code regex} crate
     * @return the compiled {@code Regex}, or a {@link RegexError} whose kind tells why the
     *         pattern was rejected
     * @throws NullPointerException if {@code pattern} is null
     */
    public static RegexCompilation compile(String pattern) {
        return compile(pattern, RegexLimits.DEFAULT);
    }

    /**
     * Compiles a pattern whose matches may lie anywhere in a text.
     * <p>
     * The checks run in this order: pattern length ({@link RegexErrorKind#PATTERN_TOO_LONG}),
     * well-formed UTF-16 ({@link RegexErrorKind#MALFORMED_UNICODE}), syntax
     * ({@link RegexErrorKind#SYNTAX}) and nesting depth
     * ({@link RegexErrorKind#NESTING_TOO_DEEP}), size of the compiled pattern
     * ({@link RegexErrorKind#PATTERN_TOO_LARGE}).
     *
     * @param pattern the pattern in the syntax of the Rust {@code regex} crate
     * @param limits the limits for compiling the pattern and for every search with it
     * @return the compiled {@code Regex}, or a {@link RegexError} whose kind tells why the
     *         pattern was rejected
     * @throws NullPointerException if an argument is null
     */
    public static RegexCompilation compile(String pattern, RegexLimits limits) {
        return compile(pattern, limits, false);
    }

    /**
     * Compiles a pattern that must match the whole text, with the
     * {@linkplain RegexLimits#DEFAULT default limits}.
     *
     * @param pattern the pattern in the syntax of the Rust {@code regex} crate
     * @return the compiled {@code Regex}, or a {@link RegexError} whose kind tells why the
     *         pattern was rejected
     * @throws NullPointerException if {@code pattern} is null
     * @see #compileWholeText(String, RegexLimits)
     */
    public static RegexCompilation compileWholeText(String pattern) {
        return compileWholeText(pattern, RegexLimits.DEFAULT);
    }

    /**
     * Compiles a pattern that must match the whole text.
     * <p>
     * Every search of the resulting regex finds only a match that spans the whole text. That
     * differs from searching anywhere and comparing the match with the text: with
     * leftmost-first semantics {@code a|ab} finds {@code a} in {@code ab}, but compiled for
     * the whole text it matches {@code ab}.
     * <p>
     * The pattern is checked as written, as by {@link #compile(String, RegexLimits)}. Its
     * syntax tree is then enclosed in the assertions {@code \A} and {@code \z}. Enclosing the
     * text of the pattern instead would let a pattern such as {@code a)(b} escape the
     * enclosure. Group numbers and names stay as written.
     *
     * @param pattern the pattern in the syntax of the Rust {@code regex} crate
     * @param limits the limits for compiling the pattern and for every search with it
     * @return the compiled {@code Regex}, or a {@link RegexError} whose kind tells why the
     *         pattern was rejected
     * @throws NullPointerException if an argument is null
     */
    public static RegexCompilation compileWholeText(String pattern, RegexLimits limits) {
        return compile(pattern, limits, true);
    }

    private static RegexCompilation compile(String pattern, RegexLimits limits, boolean wholeText) {
        requireNonNull(pattern, ERROR_PATTERN_NULL);
        requireNonNull(limits, ERROR_LIMITS_NULL);
        if (pattern.length() > limits.maxPatternBytes()) {
            return new RegexError(RegexErrorKind.PATTERN_TOO_LONG, ERROR_PATTERN_TOO_LONG.formatted(limits.maxPatternBytes()));
        }
        val message = new String[1];
        val result  = RegexEngine.compile(pattern, limits.maxPatternBytes(), limits.nestLimit(), limits.sizeLimit(),
                limits.dfaSizeLimit(), limits.maxTextBytes(), wholeText, message);
        if (result < 0) {
            val kind = RegexErrorKind.fromCode((int) -result);
            return new RegexError(kind, requireNonNullElse(message[0], ERROR_INTERNAL));
        }
        return new Regex(pattern, limits, wholeText, result);
    }

    /**
     * Returns the pattern this regex was compiled from, as written.
     *
     * @return the pattern
     */
    public String pattern() {
        return pattern;
    }

    /**
     * Returns the limits this regex was compiled with; they also bound every search.
     *
     * @return the limits
     */
    public RegexLimits limits() {
        return limits;
    }

    /**
     * Reports whether this regex was compiled to match the whole text.
     *
     * @return true for {@link #compileWholeText(String, RegexLimits)}, false for
     *         {@link #compile(String, RegexLimits)}
     */
    public boolean wholeText() {
        return wholeText;
    }

    /**
     * Returns the number of capture groups including the implicit group 0.
     *
     * @return the group count, at least 1
     */
    public int groupCount() {
        return groupCount;
    }

    /**
     * Returns the names of all groups in group order.
     *
     * @return one entry per group; empty for group 0 and for unnamed groups
     */
    public List<Optional<String>> groupNames() {
        return groupNames;
    }

    /**
     * Returns the number of a named group.
     *
     * @param name the group name, as in {@code (?<name>...)} or {@code (?P<name>...)}
     * @return the group number, or empty if the pattern has no group of this name
     * @throws NullPointerException if {@code name} is null
     */
    public OptionalInt groupIndex(String name) {
        requireNonNull(name, ERROR_NAME_NULL);
        for (var index = 0; index < groupNames.size(); index++) {
            if (groupNames.get(index).filter(name::equals).isPresent()) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Reports whether the pattern matches.
     * <p>
     * This is the cheapest search: it stops at the first match and does not compute positions.
     *
     * @param text the text to search
     * @return {@link IsMatch}, or a {@link RegexError} if the text is rejected
     * @throws NullPointerException if {@code text} is null
     */
    public IsMatchResult isMatch(String text) {
        val rejection = rejectionOf(text);
        if (rejection != null) {
            return rejection;
        }
        try {
            val status = RegexEngine.isMatch(pointer, text);
            if (status < 0) {
                return errorOf(status, 0);
            }
            return IsMatch.of(status == FOUND);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Finds the leftmost-first match.
     *
     * @param text the text to search
     * @return {@link Found} with the match, {@link NoMatch}, or a {@link RegexError} if the
     *         text is rejected
     * @throws NullPointerException if {@code text} is null
     */
    public FindResult find(String text) {
        return find(text, 0);
    }

    /**
     * Finds the leftmost-first match that starts at or after a position.
     * <p>
     * The search sees the whole text, so anchors and word boundaries behave as in a search
     * from the start of the text: {@code ^} does not match at {@code start} unless
     * {@code start} is 0, and {@code \b} looks at the character before {@code start}. To
     * find successive matches, continue at the end of the previous match, or after it for an
     * empty match.
     *
     * @param text the text to search
     * @param start the UTF-16 index to start at, from 0 to {@code text.length()}
     * @return {@link Found} with the match, {@link NoMatch}, or a {@link RegexError} if the
     *         text is rejected or {@code start} lies outside the text or inside a surrogate pair
     *         ({@link RegexErrorKind#INVALID_ARGUMENT})
     * @throws NullPointerException if {@code text} is null
     */
    public FindResult find(String text, int start) {
        val rejection = rejectionOf(text, start);
        if (rejection != null) {
            return rejection;
        }
        try {
            val values = RegexEngine.find(pointer, text, start);
            val status = values[0];
            if (status < 0) {
                return errorOf(status, start);
            }
            if (status != FOUND) {
                return NoMatch.INSTANCE;
            }
            return new Found(matchAt(values, 1));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Finds all successive non-overlapping matches.
     * <p>
     * An empty match directly after the previous match is skipped, as in the Rust crate.
     *
     * @param text the text to search
     * @return {@link Matches} in text order, empty if there is no match, or a
     *         {@link RegexError} if the text is rejected
     * @throws NullPointerException if {@code text} is null
     */
    public FindAllResult findAll(String text) {
        return findAll(text, Integer.MAX_VALUE);
    }

    /**
     * Finds at most {@code limit} successive non-overlapping matches; the search stops after
     * the last of them.
     *
     * @param text the text to search
     * @param limit the maximum number of matches
     * @return {@link Matches} in text order, or a {@link RegexError} if the text is rejected
     *         or {@code limit} is negative ({@link RegexErrorKind#INVALID_ARGUMENT})
     * @throws NullPointerException if {@code text} is null
     */
    public FindAllResult findAll(String text, int limit) {
        val rejection = rejectionOf(text, 0, limit);
        if (rejection != null) {
            return rejection;
        }
        try {
            val values = RegexEngine.findAll(pointer, text, limit);
            val count  = values[0];
            if (count < 0) {
                return errorOf(count, 0);
            }
            val matches = new ArrayList<Match>(count);
            for (var index = 0; index < count; index++) {
                matches.add(matchAt(values, 1 + index * SPAN_VALUES));
            }
            return new Matches(matches);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Finds the leftmost-first match and returns its capture groups.
     *
     * @param text the text to search
     * @return {@link Captured} with the groups, {@link NoMatch}, or a {@link RegexError} if
     *         the text is rejected
     * @throws NullPointerException if {@code text} is null
     */
    public CapturesResult captures(String text) {
        return captures(text, 0);
    }

    /**
     * Finds the leftmost-first match that starts at or after a position and returns its
     * capture groups. Anchors and word boundaries behave as in {@link #find(String, int)}.
     *
     * @param text the text to search
     * @param start the UTF-16 index to start at, from 0 to {@code text.length()}
     * @return {@link Captured} with the groups, {@link NoMatch}, or a {@link RegexError} if
     *         the text is rejected or {@code start} is invalid
     * @throws NullPointerException if {@code text} is null
     */
    public CapturesResult captures(String text, int start) {
        val rejection = rejectionOf(text, start);
        if (rejection != null) {
            return rejection;
        }
        try {
            val values = RegexEngine.captures(pointer, text, start);
            val status = values[0];
            if (status < 0) {
                return errorOf(status, start);
            }
            if (status != FOUND) {
                return NoMatch.INSTANCE;
            }
            return new Captured(capturesAt(values, 2, values[1]));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Finds at most {@code limit} successive non-overlapping matches and returns the capture
     * groups of each; the search stops after the last of them.
     *
     * @param text the text to search
     * @param limit the maximum number of matches
     * @return {@link AllCaptures} in text order, or a {@link RegexError} if the text is
     *         rejected or {@code limit} is negative
     * @throws NullPointerException if {@code text} is null
     */
    public CapturesAllResult capturesAll(String text, int limit) {
        val rejection = rejectionOf(text, 0, limit);
        if (rejection != null) {
            return rejection;
        }
        try {
            val values = RegexEngine.capturesAll(pointer, text, limit);
            val count  = values[0];
            if (count < 0) {
                return errorOf(count, 0);
            }
            val groups  = values[1];
            val matches = new ArrayList<Captures>(count);
            for (var index = 0; index < count; index++) {
                matches.add(capturesAt(values, 2 + index * groups * GROUP_VALUES, groups));
            }
            return new AllCaptures(matches);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Replaces every successive non-overlapping match, as {@code Regex::replace_all} of the
     * Rust crate does.
     * <p>
     * In the replacement, {@code $1} and {@code ${1}} insert a group by number,
     * {@code $name} and {@code ${name}} insert a group by name, and {@code $$} inserts
     * {@code $}. A group that does not exist or did not participate in the match inserts
     * nothing. {@code $name} takes the longest name of letters, digits and {@code _}, so
     * {@code ${1}a} is group 1 followed by {@code a}, while {@code $1a} is the group named
     * {@code 1a}. Every other character, the backslash included, stands for itself.
     *
     * @param text the text to search
     * @param replacement the replacement of each match
     * @return {@link Replaced} with the result, or a {@link RegexError} if the text or the
     *         replacement is rejected, or the result would be longer than
     *         {@link RegexLimits#maxTextBytes()} ({@link RegexErrorKind#RESULT_TOO_LONG})
     * @throws NullPointerException if an argument is null
     */
    public ReplaceResult replaceAll(String text, String replacement) {
        requireNonNull(replacement, ERROR_REPLACEMENT_NULL);
        val textRejection = rejectionOf(text);
        val rejection     = textRejection != null ? textRejection : rejectionOf(replacement);
        if (rejection != null) {
            return rejection;
        }
        try {
            val result = new String[1];
            val status = RegexEngine.replaceAll(pointer, text, replacement, result);
            if (status < 0) {
                return errorOf(status, 0);
            }
            return new Replaced(requireNonNullElse(result[0], ""));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Splits the text at the successive non-overlapping matches into at most {@code limit}
     * parts, as {@code Regex::splitn} of the Rust crate does.
     * <p>
     * The text before the first match is the first part and the text after the last match
     * the last part, also if they are empty, so that {@code ,} splits {@code ,a,} into
     * {@code ""}, {@code "a"} and {@code ""}. The last part holds the rest of the text once
     * the limit is reached.
     *
     * @param text the text to split
     * @param limit the maximum number of parts
     * @return {@link Parts} in text order, or a {@link RegexError} if the text is rejected
     *         or {@code limit} is negative ({@link RegexErrorKind#INVALID_ARGUMENT})
     * @throws NullPointerException if {@code text} is null
     */
    public SplitResult split(String text, int limit) {
        val rejection = rejectionOf(text, 0, limit);
        if (rejection != null) {
            return rejection;
        }
        try {
            val values = RegexEngine.split(pointer, text, limit);
            val count  = values[0];
            if (count < 0) {
                return errorOf(count, 0);
            }
            val parts = new ArrayList<String>(count);
            for (var index = 0; index < count; index++) {
                parts.add(matchAt(values, 1 + index * SPAN_VALUES).of(text));
            }
            return new Parts(parts);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Returns the pattern.
     *
     * @return the pattern this regex was compiled from
     */
    @Override
    public String toString() {
        return pattern;
    }

    /**
     * Returns the number of compiled regexes whose engine memory is not yet freed, for tests
     * that prove that unreachable regexes are freed.
     *
     * @return the number of live compiled regexes in the engine
     */
    static long liveRegexes() {
        return RegexEngine.liveRegexes();
    }

    /**
     * Rejects a text that is certainly too long without encoding it: a string never has more
     * UTF-16 units than UTF-8 bytes.
     */
    private RegexError rejectionOf(String text) {
        requireNonNull(text, ERROR_TEXT_NULL);
        if (text.length() > limits.maxTextBytes()) {
            return new RegexError(RegexErrorKind.TEXT_TOO_LONG, ERROR_TEXT_TOO_LONG.formatted(limits.maxTextBytes()));
        }
        return null;
    }

    private RegexError rejectionOf(String text, int start) {
        val rejection = rejectionOf(text);
        if (rejection == null && (start < 0 || start > text.length())) {
            return new RegexError(RegexErrorKind.INVALID_ARGUMENT, ERROR_INVALID_START.formatted(start));
        }
        return rejection;
    }

    private RegexError rejectionOf(String text, int start, int limit) {
        val rejection = rejectionOf(text, start);
        if (rejection == null && limit < 0) {
            return new RegexError(RegexErrorKind.INVALID_ARGUMENT, ERROR_NEGATIVE_LIMIT.formatted(limit));
        }
        return rejection;
    }

    private RegexError errorOf(int status, int start) {
        val kind = RegexErrorKind.fromCode(-status);
        return switch (kind) {
        case TEXT_TOO_LONG     -> new RegexError(kind, ERROR_TEXT_TOO_LONG.formatted(limits.maxTextBytes()));
        case MALFORMED_UNICODE -> new RegexError(kind, ERROR_MALFORMED_TEXT);
        case INVALID_ARGUMENT  -> new RegexError(kind, ERROR_INVALID_START.formatted(start));
        case RESULT_TOO_LONG   -> new RegexError(kind, ERROR_RESULT_TOO_LONG.formatted(limits.maxTextBytes()));
        default                -> new RegexError(kind, ERROR_INTERNAL);
        };
    }

    private static Captures capturesAt(int[] values, int offset, int count) {
        val groups = new ArrayList<Optional<Match>>(count);
        for (var index = 0; index < count; index++) {
            val group = offset + index * GROUP_VALUES;
            groups.add(values[group] == FOUND ? Optional.of(matchAt(values, group + 1)) : Optional.empty());
        }
        return new Captures(groups);
    }

    private static Match matchAt(int[] values, int offset) {
        return new Match(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
    }

    private static List<Optional<String>> namesOf(long pointer, int groupCount) {
        val joined = new String[1];
        RegexEngine.groupNames(pointer, joined);
        val names = requireNonNullElse(joined[0], "").split(NAME_SEPARATOR, -1);
        val result = new ArrayList<Optional<String>>(groupCount);
        for (var index = 0; index < groupCount; index++) {
            val name = index < names.length ? names[index] : "";
            result.add(name.isEmpty() ? Optional.empty() : Optional.of(name));
        }
        return List.copyOf(result);
    }
}
