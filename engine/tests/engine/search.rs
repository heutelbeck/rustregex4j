use rustregex4j::{CompiledRegex, ErrorKind, Limits, Span};

use crate::{compile, utf16};

/// A span as (UTF-16 start, UTF-16 end, code point start, code point end).
type Offsets = (u32, u32, u32, u32);

fn offsets(span: &Span) -> Offsets {
    (
        span.start.utf16,
        span.end.utf16,
        span.start.codepoint,
        span.end.codepoint,
    )
}

#[test]
fn when_matching_then_spans_carry_utf16_and_code_point_offsets() {
    let cases: &[(&str, &str, &[Offsets])] = &[
        ("b", "\u{1f600}b", &[(2, 3, 1, 2)]),
        (
            "",
            "\u{1f600}\u{e9}",
            &[(0, 0, 0, 0), (2, 2, 1, 1), (3, 3, 2, 2)],
        ),
        ("x*", "\u{1f600}x", &[(0, 0, 0, 0), (2, 3, 1, 2)]),
        (
            r"\w+",
            "Gr\u{fc}\u{df}e \u{4e16}",
            &[(0, 5, 0, 5), (6, 7, 6, 7)],
        ),
        ("$", "\u{1f600}", &[(2, 2, 1, 1)]),
    ];
    for &(pattern, text, expected) in cases {
        let spans = compile(pattern).find_all(text).unwrap();
        assert_eq!(
            spans.iter().map(offsets).collect::<Vec<_>>(),
            expected,
            "pattern {pattern:?} on {text:?}"
        );
    }
}

#[test]
fn when_capturing_then_absent_groups_are_none() {
    let groups = compile(r"(\d)|(\p{So})(x)")
        .captures("id \u{1f600}x")
        .unwrap()
        .unwrap();
    let observed: Vec<Option<Offsets>> = groups
        .iter()
        .map(|group| group.as_ref().map(offsets))
        .collect();
    assert_eq!(
        observed,
        [
            Some((3, 6, 3, 5)),
            None,
            Some((3, 5, 3, 4)),
            Some((5, 6, 4, 5))
        ]
    );
}

#[test]
fn when_texts_are_invalid_or_too_long_then_they_are_rejected() {
    let regex = CompiledRegex::compile(
        "a",
        &Limits {
            max_text_bytes: 4,
            ..Limits::DEFAULT
        },
    )
    .unwrap();
    let kinds = (
        CompiledRegex::decode_text(&[0x61, 0xD83D])
            .err()
            .map(|error| error.kind),
        regex.is_match("aaaa").ok(),
        regex.is_match("aaa\u{e9}").err().map(|error| error.kind),
        CompiledRegex::decode_text(&utf16("\u{1f600}")).ok(),
    );
    assert_eq!(
        kinds,
        (
            Some(ErrorKind::MalformedUnicode),
            Some(true),
            Some(ErrorKind::TextTooLong),
            Some("\u{1f600}".to_owned())
        )
    );
}
