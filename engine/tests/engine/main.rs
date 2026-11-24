//! Behavior of the engine through its Rust API: limits, error kinds, strict decoding and
//! offsets. The Java binding passes through the same functions.

// Test programs fail fast: a panic is the failure report of a test.
#![allow(clippy::panic, clippy::indexing_slicing)]

mod allocation;
mod compilation;
mod memory;
mod search;
mod whole_text_and_iteration;

use rustregex4j::{CompiledRegex, Limits};

fn compile(pattern: &str) -> CompiledRegex {
    CompiledRegex::compile(pattern, &Limits::DEFAULT)
        .unwrap_or_else(|error| panic!("{pattern}: {error}"))
}

fn utf16(text: &str) -> Vec<u16> {
    text.encode_utf16().collect()
}
