use rustregex4j::{CompiledRegex, ErrorKind, Limits, Span};

use crate::compile;

fn parts(text: &str, spans: &[Span]) -> Vec<String> {
    let units: Vec<u16> = text.encode_utf16().collect();
    spans
        .iter()
        .map(|span| {
            String::from_utf16(&units[span.start.utf16 as usize..span.end.utf16 as usize]).unwrap()
        })
        .collect()
}

#[test]
fn when_replacing_then_the_replacement_syntax_of_the_crate_applies() {
    let cases: &[(&str, &str, &str, &str)] = &[
        (
            r"(\w+)@(\w+)",
            "alice@example",
            "$2 at $1",
            "example at alice",
        ),
        (r"(?<user>\w+)@(\w+)", "alice@example", "${user}!", "alice!"),
        (r"(\d)", "a1b2", "${1}x", "a1xb2x"),
        (r"(\d)", "a1b2", "$1x", "ab"),
        (r"(\d)", "a1", "$$1", "a$1"),
        (r"(\d)", "a1", r"\$1", r"a\1"),
        (r"(\d)", "a1", "<$9>", "a<>"),
        (r"(x)?(\d)", "a1", "<$1$2>", "a<1>"),
        ("", "ab", "-", "-a-b-"),
        ("b", "\u{1f600}b", "c", "\u{1f600}c"),
    ];
    for &(pattern, text, replacement, expected) in cases {
        assert_eq!(
            compile(pattern).replace_all(text, replacement).unwrap(),
            expected,
            "pattern {pattern:?} on {text:?} with {replacement:?}"
        );
    }
}

#[test]
fn when_a_replacement_grows_beyond_the_text_limit_then_it_is_rejected() {
    let regex = CompiledRegex::compile(
        "a",
        &Limits {
            max_text_bytes: 8,
            ..Limits::DEFAULT
        },
    )
    .unwrap();
    assert_eq!(
        (
            regex.replace_all("aa", "xxxx").ok(),
            regex
                .replace_all("aaa", "xxx")
                .err()
                .map(|error| error.kind),
            regex
                .replace_all("a", "123456789")
                .err()
                .map(|error| error.kind),
        ),
        (
            Some("xxxxxxxx".to_owned()),
            Some(ErrorKind::ResultTooLong),
            Some(ErrorKind::TextTooLong),
        )
    );
}

#[test]
fn when_splitting_then_the_parts_are_those_of_the_crate() {
    let cases: &[(&str, &str, usize, &[&str])] = &[
        (",", "a,b,,c", 10, &["a", "b", "", "c"]),
        (",", ",a,", 10, &["", "a", ""]),
        (",", "a,b,c", 2, &["a", "b,c"]),
        (",", "a,b", 0, &[]),
        ("", "ab", 10, &["", "a", "b", ""]),
        (r"\s+", "\u{1f600} x", 10, &["\u{1f600}", "x"]),
        ("x", "", 10, &[""]),
    ];
    for &(pattern, text, limit, expected) in cases {
        let spans = compile(pattern).split(text, limit).unwrap();
        assert_eq!(
            parts(text, &spans),
            expected,
            "pattern {pattern:?} on {text:?} with limit {limit}"
        );
    }
}

#[test]
fn when_capture_replacements_approach_the_limit_then_the_upstream_expansion_is_preserved() {
    for (pattern, text, replacement, limit) in [
        ("(.+)", "a".repeat(64), "$0$0", 128),
        ("(.+)", "a".repeat(64), "$0$0", 127),
        ("(a(b))", "ab".to_owned(), "$1$2$1", 6),
        ("(?<a>x)?(?<b>y)", "y".to_owned(), "${a}$b${missing}", 16),
        ("(?<x_1>a)", "a".to_owned(), "$x_1$0", 6),
        ("(?<x_1>a)", "a".to_owned(), "$x_1z", 6),
        ("(?<\u{3b1}>a)", "a".to_owned(), "${\u{3b1}}", 8),
        ("(a)", "a".to_owned(), "$$1", 3),
        ("(a)", "a".to_owned(), "$", 1),
        ("(a)", "a".to_owned(), "${missing", 9),
        ("(a)", "a".to_owned(), "$999999999999999999999999", 28),
        ("(b)", "abba".to_owned(), "$1$1$1", 6),
        ("(a)", "ab".to_owned(), "xxxx", 4),
        ("(.+)", "\u{e4}".to_owned(), "$0$0", 4),
        ("", "\u{e4}".to_owned(), "-", 4),
        ("", "\u{e4}".to_owned(), "-", 3),
        ("", String::new(), "", 0),
        ("x", "abc".to_owned(), "yyyy", 4),
    ] {
        let limits = Limits {
            max_text_bytes: limit,
            ..Limits::DEFAULT
        };
        let reference = regex::Regex::new(pattern)
            .unwrap()
            .replace_all(&text, replacement)
            .into_owned();
        let expected = if reference.len() > limit {
            Err(ErrorKind::ResultTooLong)
        } else {
            Ok(reference)
        };
        assert_eq!(
            CompiledRegex::compile(pattern, &limits)
                .unwrap()
                .replace_all(&text, replacement)
                .map_err(|error| error.kind),
            expected,
            "{pattern:?}, {replacement:?}, limit {limit}"
        );
    }
}
