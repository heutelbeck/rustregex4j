use rustregex4j::{CompiledRegex, ErrorKind, Limits};

use crate::allocation::{balance, largest_request};

#[test]
fn when_a_capture_expansion_exceeds_the_limit_then_it_is_not_materialized() {
    let limits = Limits {
        max_text_bytes: 256,
        ..Limits::DEFAULT
    };
    let regex = CompiledRegex::compile("(.+)", &limits).unwrap();
    let text = "a".repeat(64);
    let replacement = "$0".repeat(64);
    assert_eq!(regex.replace_all(&text, "$0"), Ok(text.clone()));
    let (result, largest) = largest_request(|| regex.replace_all(&text, &replacement));
    assert_eq!(
        (
            result.err().map(|error| error.kind),
            largest <= limits.max_text_bytes
        ),
        (Some(ErrorKind::ResultTooLong), true),
        "A rejected capture expansion must not be materialized; largest request: {largest}."
    );
}

#[test]
fn when_regexes_are_compiled_searched_and_dropped_then_no_heap_memory_remains() {
    let text = "USER-000042@example.org and more text";
    let warm_up = CompiledRegex::compile("(?i)warm", &Limits::DEFAULT).unwrap();
    drop(warm_up.captures(text));
    drop(warm_up);
    let baseline = balance();
    for index in 0..200 {
        let regex = CompiledRegex::compile(
            &format!(r"(?i)user-{index:06}@[a-z]+\.(org|com)"),
            &Limits::DEFAULT,
        )
        .unwrap();
        drop((
            regex.is_match(text),
            regex.find(text),
            regex.find_all(text),
            regex.captures(text),
        ));
    }
    for index in 0..200 {
        drop(CompiledRegex::compile(
            &format!("(unclosed-{index}"),
            &Limits::DEFAULT,
        ));
        drop(CompiledRegex::compile(
            &format!(r"\w{{{}}}", 100 + index % 900),
            &Limits::DEFAULT,
        ));
    }
    assert_eq!(balance(), baseline);
}
