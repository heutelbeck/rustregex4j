use rustregex4j::{Anchoring, CompiledRegex, ErrorKind, Limits};

use crate::utf16;

fn kind_of(pattern: &[u16], limits: &Limits) -> Option<ErrorKind> {
    CompiledRegex::compile_utf16(pattern, limits, Anchoring::Anywhere)
        .err()
        .map(|error| error.kind)
}

#[test]
fn when_patterns_violate_a_rule_then_compilation_reports_the_kind_of_violation() {
    let nested = |depth: usize| format!("{}a{}", "(".repeat(depth), ")".repeat(depth));
    let cases: Vec<(&str, Vec<u16>, Option<ErrorKind>)> = vec![
        ("valid", utf16("^[a-z]{3}-[0-9]{6}$"), None),
        ("unclosed group", utf16("(abc"), Some(ErrorKind::Syntax)),
        ("backreference", utf16(r"(a)\1"), Some(ErrorKind::Syntax)),
        ("look-around", utf16("a(?=b)"), Some(ErrorKind::Syntax)),
        ("nesting at the limit", utf16(&nested(63)), None),
        (
            "nesting beyond the limit",
            utf16(&nested(65)),
            Some(ErrorKind::NestingTooDeep),
        ),
        (
            "size beyond the limit",
            utf16(r"\w{1000}"),
            Some(ErrorKind::PatternTooLarge),
        ),
        (
            "length at the limit in bytes",
            utf16(&"\u{e9}".repeat(2048)),
            None,
        ),
        (
            "length beyond the limit in bytes",
            utf16(&"\u{e9}".repeat(2049)),
            Some(ErrorKind::PatternTooLong),
        ),
        (
            "unpaired high surrogate",
            vec![0x61, 0xD800],
            Some(ErrorKind::MalformedUnicode),
        ),
        (
            "unpaired low surrogate",
            vec![0xDC00, 0x61],
            Some(ErrorKind::MalformedUnicode),
        ),
    ];
    for (name, pattern, expected) in cases {
        assert_eq!(kind_of(&pattern, &Limits::DEFAULT), expected, "case {name}");
    }
}

#[test]
fn when_limits_are_changed_then_admission_follows_them() {
    let raised = Limits {
        size_limit: 64 * 1024 * 1024,
        ..Limits::DEFAULT
    };
    let lowered = Limits {
        max_pattern_bytes: 8,
        nest_limit: 2,
        size_limit: 1000,
        ..Limits::DEFAULT
    };
    assert_eq!(
        (
            kind_of(&utf16(r"\w{1000}"), &raised),
            kind_of(&utf16("abcdefghi"), &lowered),
            kind_of(&utf16("(((a)))"), &lowered),
            kind_of(&utf16(r"\w{5}"), &lowered),
        ),
        (
            None,
            Some(ErrorKind::PatternTooLong),
            Some(ErrorKind::NestingTooDeep),
            Some(ErrorKind::PatternTooLarge),
        )
    );
}

#[test]
fn when_a_pattern_is_rejected_then_the_message_is_a_sentence_with_the_limit() {
    let error = CompiledRegex::compile(&"a".repeat(4097), &Limits::DEFAULT).unwrap_err();
    assert_eq!(
        error.message,
        "The pattern is longer than 4096 UTF-8 bytes."
    );
}
