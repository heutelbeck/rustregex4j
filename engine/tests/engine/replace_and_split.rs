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
