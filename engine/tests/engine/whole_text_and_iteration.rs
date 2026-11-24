use rustregex4j::{Anchoring, CompiledRegex, ErrorKind, Limits, Span, byte_offset_of_utf16};

use crate::compile;

/// (pattern, text, start, expected match in code points)
type StartCase = (&'static str, &'static str, usize, Option<(u32, u32)>);

fn whole_text(pattern: &str) -> Result<CompiledRegex, ErrorKind> {
    CompiledRegex::compile_anchored(pattern, &Limits::DEFAULT, Anchoring::WholeText)
        .map_err(|error| error.kind)
}

fn codepoints(span: &Span) -> (u32, u32) {
    (span.start.codepoint, span.end.codepoint)
}

#[test]
fn when_a_pattern_must_match_the_whole_text_then_any_alternative_that_spans_it_matches() {
    let cases: &[(&str, &str, bool)] = &[
        ("a|ab", "ab", true),
        ("a|ab", "abc", false),
        ("ab|a", "a", true),
        ("[a-z]{3}-[0-9]{6}", "abc-123456", true),
        ("[a-z]{3}-[0-9]{6}", "xabc-123456", false),
        ("(?m)^a$", "a\nb", false),
        ("(?x) a b  # spaces and comments", "ab", true),
        ("", "", true),
        ("", "a", false),
    ];
    for &(pattern, text, expected) in cases {
        let regex = whole_text(pattern).unwrap_or_else(|kind| panic!("{pattern}: {kind:?}"));
        assert_eq!(
            regex.is_match(text).unwrap(),
            expected,
            "pattern {pattern:?} on {text:?}"
        );
    }
}

#[test]
fn when_a_pattern_tries_to_escape_the_whole_text_enclosure_then_it_is_rejected_as_written() {
    assert_eq!(
        (
            whole_text("a)(b").err(),
            whole_text("(a").err(),
            whole_text(r"a\").err()
        ),
        (
            Some(ErrorKind::Syntax),
            Some(ErrorKind::Syntax),
            Some(ErrorKind::Syntax)
        )
    );
}

#[test]
fn when_a_whole_text_pattern_has_groups_then_numbers_and_names_are_kept() {
    let regex = whole_text(r"(?P<user>\w+)@(\w+)").unwrap();
    let groups = regex.captures("alice@example").unwrap().unwrap();
    assert_eq!(
        (
            regex.group_names().to_vec(),
            groups
                .iter()
                .map(|group| group.as_ref().map(codepoints))
                .collect::<Vec<_>>(),
        ),
        (
            vec![None, Some("user".to_owned()), None],
            vec![Some((0, 13)), Some((0, 5)), Some((6, 13))],
        )
    );
}

#[test]
fn when_searching_from_a_start_offset_then_anchors_and_boundaries_see_the_whole_text() {
    let cases: &[StartCase] = &[
        ("b", "abab", 2, Some((3, 4))),
        ("^b", "ab", 1, None),
        (r"\bb", "ab b", 1, Some((3, 4))),
        ("a", "abc", 3, None),
        ("", "abc", 3, Some((3, 3))),
    ];
    for &(pattern, text, start, expected) in cases {
        let found = compile(pattern).find_at(text, start).unwrap();
        assert_eq!(
            found.as_ref().map(codepoints),
            expected,
            "pattern {pattern:?} on {text:?} from {start}"
        );
    }
}

#[test]
fn when_a_start_offset_is_not_a_character_boundary_then_the_search_is_rejected() {
    let regex = compile("a");
    assert_eq!(
        (
            regex.find_at("\u{e9}a", 1).err().map(|error| error.kind),
            regex.captures_at("ab", 3).err().map(|error| error.kind),
        ),
        (
            Some(ErrorKind::InvalidArgument),
            Some(ErrorKind::InvalidArgument)
        )
    );
}

#[test]
fn when_utf16_offsets_are_converted_then_surrogate_pairs_and_the_end_are_respected() {
    let text = "a\u{1f600}b";
    let converted: Vec<Option<usize>> = (0..=5)
        .map(|offset| byte_offset_of_utf16(text, offset))
        .collect();
    assert_eq!(converted, [Some(0), Some(1), None, Some(5), Some(6), None]);
}

#[test]
fn when_searches_are_limited_then_they_stop_after_the_limit() {
    let regex = compile(r"(\d)(\d)?");
    let text = "1 23 4 56";
    let all_captures = regex.captures_all(text, 3).unwrap();
    let groups: Vec<Vec<Option<(u32, u32)>>> = all_captures
        .iter()
        .map(|groups| {
            groups
                .iter()
                .map(|group| group.as_ref().map(codepoints))
                .collect()
        })
        .collect();
    assert_eq!(
        (
            regex
                .find_all_limited(text, 2)
                .unwrap()
                .iter()
                .map(codepoints)
                .collect::<Vec<_>>(),
            regex.find_all_limited(text, 0).unwrap().len(),
            groups,
        ),
        (
            vec![(0, 1), (2, 4)],
            0,
            vec![
                vec![Some((0, 1)), Some((0, 1)), None],
                vec![Some((2, 4)), Some((2, 3)), Some((3, 4))],
                vec![Some((5, 6)), Some((5, 6)), None],
            ],
        )
    );
}
