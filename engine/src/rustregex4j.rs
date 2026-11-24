//! The regex engine of rustregex4j: the Rust `regex` crate behind a JNI interface.
//!
//! The Java class `com.heutelbeck.rustregex4j.Regex` compiles a pattern once into a
//! [`CompiledRegex`] and matches it from any number of threads at the same time. A compiled
//! regex is immutable and `Sync`; the matching state lives in per-thread caches of the
//! `regex` crate. Everything that must behave the same for every caller lives in this crate:
//! the engine configuration, the [`Limits`], the classification of errors into
//! [`ErrorKind`], the strict decoding of UTF-16 text, and the conversion of byte offsets into
//! UTF-16 and code point offsets.

mod compiled;
mod error;
#[allow(unsafe_code)]
mod jni;
mod limits;
mod positions;

pub use compiled::{Anchoring, Captures, CompiledRegex, byte_offset_of_utf16};
pub use error::{ErrorKind, RegexError};
pub use limits::Limits;
pub use positions::{Position, Span};
