# rustregex4j

Regular expressions for Java, matched by the Rust [`regex`](https://docs.rs/regex/1.13.2/regex/) crate.

- **Linear time.** Every search runs in time linear in the length of the text. There is no backtracking, so patterns like `(a|aa)*b` or `a*a*a*b` cannot hang a thread.
- **Bounded cost.** Limits on the size of a compiled pattern and on the length of a text give every search a fixed worst case, even when an attacker writes both.
- **Thread-safe without locks.** A compiled `Regex` is immutable. Any number of threads can search with it at the same time.
- **Identical to Rust.** The matching is done by the Rust crate itself, so a Rust program that uses the same crate version with the same configuration accepts the same patterns and finds the same matches.
- **No exceptions for bad input.** Compiling and searching return sealed results, and the compiler makes the caller handle the error case.
- **No runtime dependencies.** The jar carries the engine for each supported platform. It also works in GraalVM native images, including fully static ones.

```xml
<dependency>
    <groupId>com.heutelbeck</groupId>
    <artifactId>rustregex4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

Releases are published to Maven Central and attached to the GitHub release of their tag. The jars of the latest build of `main` are attached to the [GitHub release `snapshot`](https://github.com/heutelbeck/rustregex4j/releases/tag/snapshot). The library needs Java 21 or newer.

## Usage

### Compiling

`Regex.compile` returns a `RegexCompilation`, a sealed interface. The result is either the compiled `Regex` or a `RegexError`. An invalid pattern never throws, so you can validate patterns once, when a configuration or a policy is loaded:

```java
import com.heutelbeck.rustregex4j.*;

Regex identifier = switch (Regex.compile("^[a-z]{3}-[0-9]{6}$")) {
    case Regex regex      -> regex;
    case RegexError error -> throw new ConfigurationException(error.kind() + ": " + error.message());
};
```

`RegexError.kind()` is a stable `RegexErrorKind`; use it for decisions. `RegexError.message()` is for humans. For syntax errors it is the message of the Rust parser, which points at the offending part of the pattern:

```
regex parse error:
    abc(def
       ^
error: unclosed group
```

### Searching

Every search also returns a sealed result. A search rejects a text only if the text is longer than the text limit or contains an unpaired UTF-16 surrogate. Such a text yields a `RegexError`, never an exception.

```java
// Is there a match anywhere in the text? The cheapest search.
boolean valid = switch (identifier.isMatch(input)) {
    case IsMatch(var matches) -> matches;
    case RegexError error     -> false;
};

// The leftmost-first match.
String firstId = switch (identifier.find(input)) {
    case Found(var match) -> match.of(input);
    case NoMatch noMatch  -> "";
    case RegexError error -> "";
};

// All successive non-overlapping matches.
List<Match> all = switch (identifier.findAll(input)) {
    case Matches(var matches) -> matches;
    case RegexError error     -> List.of();
};

// The capture groups of the leftmost-first match; group 0 is the whole match.
// A pattern known to be valid can be cast; a cast fails loudly if it is not.
Regex mail = (Regex) Regex.compile("(?<user>[\\w.]+)@(?<host>[\\w.-]+)");
String user = switch (mail.captures(input)) {
    case Captured(var captures) -> captures.group(1).map(group -> group.of(input)).orElse("");
    case NoMatch noMatch        -> "";
    case RegexError error       -> "";
};
```

`Match.start()` and `Match.end()` are UTF-16 indexes, so they work with `String.substring`. `Match.startCodepoint()` and `Match.endCodepoint()` count Unicode characters. Those are the same in every language, so they are the unit to compare positions with a Rust program.

### Matching the whole text

`Regex.compileWholeText` compiles a pattern that must match the whole text, as a full-match operator needs it:

```java
Regex identifier = (Regex) Regex.compileWholeText("[a-z]{3}-[0-9]{6}");
identifier.isMatch("abc-123456");   // IsMatch[matches=true]
identifier.isMatch("xabc-123456");  // IsMatch[matches=false]
```

This is not the same as searching anywhere and comparing the match with the text. With leftmost-first semantics `a|ab` finds `a` in `ab`; compiled for the whole text, it matches `ab`. Nor is it the same as writing `^(?:` and `)$` around the pattern text: a pattern such as `a)(b` would escape such an enclosure, and so would a `(?x)` comment that swallows the closing parenthesis. The engine checks the pattern as written, encloses its syntax tree in `\A` and `\z`, and keeps group numbers and names as written.

### Continuing, bounding and naming

```java
// Continue a search at a position. The search sees the whole text, so ^ and \b keep their meaning.
FindResult next = regex.find(text, previous.end());

// Stop after a number of matches.
FindAllResult firstTen = regex.findAll(text, 10);
CapturesAllResult groupsOfFirstTen = regex.capturesAll(text, 10);

// Map group names to numbers.
OptionalInt year = regex.groupIndex("year");
List<Optional<String>> names = regex.groupNames();
```

Positions are UTF-16 indexes. A start outside the text or inside a surrogate pair, and a negative limit, yield a `RegexError` of kind `INVALID_ARGUMENT`.

### Replacing and splitting

```java
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
```

Both behave as `replace_all` and `splitn` of the crate. In a replacement, `$1` and `${1}` insert a group by number, `$name` and `${name}` by name, and `$$` inserts `$`; every other character, the backslash included, stands for itself. A group that does not exist or did not participate inserts nothing. `$name` takes the longest name of letters, digits and `_`, so write `${1}a` for group 1 followed by `a`. A split keeps empty parts, also at the start and the end, and the last part holds the rest of the text once the limit is reached. A result of `replaceAll` longer than `maxTextBytes` is rejected as `RESULT_TOO_LONG`.

### Matching many patterns at once

```java
RegexSet routes = (RegexSet) RegexSet.compileWholeText(List.of("/users/[0-9]+", "/orders/[0-9]+", "/orders/.*"));
List<Integer> matching = switch (routes.matches("/orders/42")) {
    case SetMatches(var patterns) -> patterns;
    case RegexError error         -> List.of();
};
```

A `RegexSet` is the `RegexSet` of the crate: one search reports the indexes of all patterns that match, here `[1, 2]`, in time linear in the length of the text instead of one search per pattern. Use it where many patterns are tested against the same text. A set reports which patterns match, not where; it has no positions and no capture groups. Every pattern is compiled on its own first, so a set is rejected for the reason its pattern would be rejected alone. The patterns then share one compiled program, whose size may reach `sizeLimit` times the number of patterns.

### Searching from many threads

A `Regex` is immutable and thread-safe. Compile it once and share it. Searches do not lock; each thread uses its own matching cache inside the Rust crate.

```java
Regex email = (Regex) Regex.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (String text : texts) {
        executor.submit(() -> email.isMatch(text));
    }
}
```

Measured with one shared regex on a machine with 16 cores, the throughput of `isMatch` on a short text grows linearly up to 8 threads (5.5 million searches per second on one thread, 38.7 million on eight). Beyond 8 threads it falls again (22.7 million on 32 threads): the `regex` crate keeps the matching caches of the threads in 8 stacks, and more threads contend for them.

The compiled pattern lives in the memory of the engine, outside the Java heap. It is freed automatically when the `Regex` becomes unreachable, so there is nothing to close.

### Limits

`RegexLimits` bounds the cost of compiling a pattern and of every search with it. `Regex.compile(pattern)` uses `RegexLimits.DEFAULT`. To use other limits, derive them from the defaults:

```java
RegexLimits generous = RegexLimits.DEFAULT
        .withMaxTextBytes(1024 * 1024)
        .withSizeLimit(4L * 1024 * 1024);

RegexCompilation compiled = Regex.compile(pattern, generous);
```

| Limit | Default | Rejection |
|---|---|---|
| `maxPatternBytes`: pattern length in UTF-8 bytes | 4096 | `PATTERN_TOO_LONG` |
| `nestLimit`: nesting of groups, classes and repetitions | 64 | `NESTING_TOO_DEEP` |
| `sizeLimit`: heap size of the compiled pattern in bytes | 1 MiB | `PATTERN_TOO_LARGE` |
| `dfaSizeLimit`: lazy DFA cache per thread in bytes; affects speed only | 2 MiB | none |
| `maxTextBytes`: text length in UTF-8 bytes | 64 KiB | `TEXT_TOO_LONG` |

A search takes time proportional to the size of the compiled pattern times the length of the text. That is why both factors are bounded.

The defaults are chosen for patterns and texts of authorization policies: identifiers, paths, e-mail addresses and similar short values. Measured on the development machine with the most expensive patterns the defaults still admit and a text at the length limit, the worst case was:

- Compiling: about 10 ms.
- `isMatch`, `find` and `captures`: about 0.1 ms.
- `findAll`: about 6 ms, for a pattern that matches the empty string 65,537 times.

With a size limit of 4 MiB, a search of such a pattern already took 64 ms on a 4 KB text. The test `WorstCaseTests` checks the bound of the defaults.

Lengths are measured in UTF-8 bytes, so a limit means the same in Java and in Rust. A Java string never has more UTF-16 units than UTF-8 bytes, so a string with more units than the limit is rejected without being encoded.

### Error kinds

| Kind | Cause |
|---|---|
| `SYNTAX` | Invalid syntax, including backreferences and look-around, which the crate does not support |
| `NESTING_TOO_DEEP` | Nesting deeper than `nestLimit` |
| `PATTERN_TOO_LONG` | Pattern longer than `maxPatternBytes` |
| `PATTERN_TOO_LARGE` | Compiled pattern larger than `sizeLimit` |
| `MALFORMED_UNICODE` | Pattern or text with an unpaired UTF-16 surrogate |
| `TEXT_TOO_LONG` | Text longer than `maxTextBytes` |
| `INVALID_ARGUMENT` | Start position outside the text or inside a surrogate pair, or negative limit |
| `RESULT_TOO_LONG` | Result of `replaceAll` longer than `maxTextBytes` |
| `INTERNAL` | Unexpected failure of the engine |

Compiling checks the pattern length first, then well-formed UTF-16, then syntax and nesting, then the compiled size.

## Semantics

Patterns use the [syntax of the `regex` crate](https://docs.rs/regex/1.13.2/regex/#syntax). The engine runs with the defaults of the crate:

- Unicode mode: `\w`, `\d`, `\s` and `\b` are Unicode-aware, and `.` matches a Unicode scalar value.
- Case sensitive.
- `^` and `$` match at the start and end of the text only.
- `.` does not match `\n`.
- Leftmost-first match semantics: `a|ab` matches `a` in `ab`.

Inline flags change this per pattern: `(?i)` case-insensitive with simple case folding, `(?m)` multi-line anchors, `(?s)` dot matches `\n`, `(?x)` verbose, `(?U)` swap greedy and lazy, `(?-u)` ASCII classes.

All searches look for a match anywhere in the text. Anchor the pattern with `^...$` to match whole texts. `findAll` skips an empty match directly after the previous match, as the crate does.

### The same results in Rust

A Rust program gets the same acceptance and the same matches with the same crate versions and this configuration:

```toml
regex = "=1.13.2"
regex-syntax = "=0.8.12"
```

```rust
use regex::RegexBuilder;
use regex_syntax::ParserBuilder;

// Classify nesting errors the same way: parse with the same nest limit first.
ParserBuilder::new().nest_limit(64).build().parse(pattern)?;
let regex = RegexBuilder::new(pattern)
    .nest_limit(64)
    .size_limit(1024 * 1024)
    .dfa_size_limit(2 * 1024 * 1024)
    .build()?;
// Reject patterns longer than 4096 bytes before compiling, and texts longer than 65536 bytes before searching.

// A pattern for the whole text: check the pattern as written, then enclose its syntax tree.
let hir = ParserBuilder::new().nest_limit(64).build().parse(pattern)?;
let whole = Hir::concat(vec![Hir::look(Look::Start), hir, Hir::look(Look::End)]).to_string();
let regex = RegexBuilder::new(&whole)
    .nest_limit(130)
    .size_limit(1024 * 1024)
    .dfa_size_limit(2 * 1024 * 1024)
    .build()?;
```

The nest limit of the enclosed text is twice the limit plus 2, because the canonical text of a syntax tree can add non-capturing groups; the depth is already bounded by the check of the pattern as written.

Keep both sides on the same versions: the Unicode tables come with `regex-syntax`, so an update of the crate can change which characters a class contains. The size limit counts heap bytes of the compiled program. It is the same on all 64-bit platforms, which are the only platforms the engine supports.

## Platforms

| Platform | Engine library | Tested native image |
|---|---|---|
| Linux x86_64, glibc 2.17 or newer | `librustregex4j.so` | fully static with musl, engine linked in |
| Linux x86_64, musl (Alpine) | `librustregex4j.so` built against musl | none, JVM only |
| Linux aarch64, glibc 2.17 or newer | `librustregex4j.so` | mostly static (`--static-nolibc`) |
| Linux aarch64, musl (Alpine) | `librustregex4j.so` built against musl | none, JVM only |
| macOS aarch64 | `librustregex4j.dylib` | dynamic |
| macOS x86_64 | `librustregex4j.dylib` | none, JVM only (no GraalVM 25 for Intel Macs) |
| Windows x86_64 | `rustregex4j.dll` | dynamic |

CI builds the engine on each platform and runs all tests there, on the JVM and as the native image of the table. On Linux it also runs the Java tests on the JVM of Alpine, with the musl engine, and of Amazon Linux 2, with glibc 2.26.

On the JVM the engine library of the current platform is copied from the jar to a temporary file and loaded from there. On Linux the loader takes the musl engine when the dynamic loader of musl runs the process. The system property `rustregex4j.library` names a library file to load instead, for other platforms or for systems that do not allow executing files from the temporary directory. The library needs nothing but the C library of the system.

Java 22 and newer warn about the native access of the library. Add `--enable-native-access=ALL-UNNAMED` to the JVM options to silence the warning.

## GraalVM native images

The jar carries the native image metadata it needs, so a native image works without configuration: the engine library is bundled and loaded as on the JVM.

A fully static native image (`--static --libc=musl`) cannot load shared libraries. For such images the jar carries the engine for Linux x86_64 as a static library built against musl, and the feature `StaticEngineFeature` links it into the executable:

```
native-image --static --libc=musl --features=com.heutelbeck.rustregex4j.StaticEngineFeature -cp app.jar:rustregex4j.jar com.example.Main
```

The result is one executable without any library files that extracts nothing at run time. The feature uses internal API of the GraalVM image builder; the jar adds the required `--add-exports` options itself. It is tested with GraalVM for JDK 25.

## Building

Requirements: JDK 21 or newer, Maven, and Rust (the version in `engine/rust-toolchain.toml`). On Linux x86_64 the build also needs the musl target for the static engine (`rustup target add x86_64-unknown-linux-musl`).

```
mvn verify
```

The build compiles the Rust engine with cargo, bundles it into the jar under `com/heutelbeck/rustregex4j/engine/<platform>/`, and runs the Rust and the Java tests. Other ways to run the tests:

- `mvn -Pnative test` runs the Java tests as a GraalVM native image.
- `mvn -Pnative-mostly-static test` runs them as a mostly static image.
- `mvn -Pnative-static test` runs them as a fully static musl image; this needs GraalVM with musl support.
- `mvn -Pportable-linux-engines verify` builds the Linux engines as CI does: against glibc 2.17 instead of the glibc of the build machine, and in addition against musl. This needs [zig](https://ziglang.org), [cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild) and the Rust targets `<arch>-unknown-linux-gnu` and `<arch>-unknown-linux-musl`.

CI assembles the engine libraries of all platforms into one jar with `-Pprebuilt-engines -Dengine.libraries=<directory>` and publishes the snapshot jars as assets of the GitHub release `snapshot`.

Layout:

```
engine/                  the Rust engine: limits, error kinds, offsets, JNI functions
src/main/java/...        the Java API and the loader of the engine library
src/main/resources/...   native image metadata, third-party notices
src/test/java/...        the Java tests
```

## License

MIT, see [LICENSE](LICENSE). The engine is compiled from Rust crates under MIT, Apache-2.0, Unlicense and Unicode-3.0 licenses; their notices are in `META-INF/THIRD-PARTY-NOTICES.txt` of the jar.
