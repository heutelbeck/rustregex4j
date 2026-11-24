use rustregex4j::{Anchoring, CompiledRegexSet, ErrorKind, Limits};

fn set(patterns: &[&str], anchoring: Anchoring) -> CompiledRegexSet {
    let patterns: Vec<String> = patterns.iter().map(|&pattern| pattern.to_owned()).collect();
    CompiledRegexSet::compile(&patterns, &Limits::DEFAULT, anchoring)
        .unwrap_or_else(|error| panic!("{patterns:?}: {error}"))
}

fn refusal(patterns: &[&str], limits: &Limits) -> Option<ErrorKind> {
    let patterns: Vec<String> = patterns.iter().map(|&pattern| pattern.to_owned()).collect();
    CompiledRegexSet::compile(&patterns, limits, Anchoring::WholeText)
        .err()
        .map(|error| error.kind)
}

#[test]
fn when_a_set_is_matched_then_every_matching_pattern_is_reported_in_order() {
    let anywhere = set(&["a", "b", "ab", "x*"], Anchoring::Anywhere);
    let whole_text = set(&["a|ab", "ab|a", "b", "[a-z]+", ""], Anchoring::WholeText);
    assert_eq!(
        (
            anywhere.matches("ab").unwrap(),
            anywhere.matches("zzz").unwrap(),
            whole_text.matches("ab").unwrap(),
            whole_text.matches("").unwrap(),
            whole_text.matches("a\nb").unwrap(),
        ),
        (vec![0, 1, 2, 3], vec![3], vec![0, 1, 3], vec![4], vec![])
    );
}

#[test]
fn when_a_set_has_many_patterns_then_one_search_finds_the_one_that_matches() {
    let patterns: Vec<String> = (0..1000)
        .map(|tenant| format!("/api/v1/tenant-{tenant}/users/[0-9]+"))
        .collect();
    let set = CompiledRegexSet::compile(&patterns, &Limits::DEFAULT, Anchoring::WholeText).unwrap();
    assert_eq!(
        (
            set.len(),
            set.matches("/api/v1/tenant-500/users/42").unwrap(),
            set.matches("/api/v1/tenant-5000/users/42").unwrap(),
        ),
        (1000, vec![500], vec![])
    );
}

#[test]
fn when_a_pattern_of_a_set_is_refused_alone_then_the_set_is_refused_for_the_same_reason() {
    let short = Limits {
        max_pattern_bytes: 4,
        ..Limits::DEFAULT
    };
    assert_eq!(
        (
            refusal(&["a", "(b"], &Limits::DEFAULT),
            refusal(&["a", "(?=b)"], &Limits::DEFAULT),
            refusal(&["a", r"\w{1000}"], &Limits::DEFAULT),
            refusal(&["a", "abcde"], &short),
            refusal(&["a)(b"], &Limits::DEFAULT),
            refusal(&[], &Limits::DEFAULT),
        ),
        (
            Some(ErrorKind::Syntax),
            Some(ErrorKind::Syntax),
            Some(ErrorKind::PatternTooLarge),
            Some(ErrorKind::PatternTooLong),
            Some(ErrorKind::Syntax),
            None,
        )
    );
}

#[test]
fn when_a_text_exceeds_the_text_limit_then_the_set_rejects_it() {
    let limits = Limits {
        max_text_bytes: 4,
        ..Limits::DEFAULT
    };
    let set = CompiledRegexSet::compile(&["a".to_owned()], &limits, Anchoring::Anywhere).unwrap();
    assert_eq!(
        (
            set.matches("aaaa").ok(),
            set.matches("aaaaa").err().map(|error| error.kind),
            set.is_empty(),
        ),
        (Some(vec![0]), Some(ErrorKind::TextTooLong), false)
    );
}
