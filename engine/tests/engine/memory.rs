use rustregex4j::{CompiledRegex, Limits};

use crate::allocation::balance;

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
