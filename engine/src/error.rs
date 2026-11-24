//! Classification of rejected patterns and texts.

use std::fmt;

/// Why a pattern or a text was rejected.
///
/// The numeric codes are shared with the Java binding and never change.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum ErrorKind {
    /// The pattern is not valid syntax.
    Syntax = 1,
    /// The pattern nests groups, classes or repetitions deeper than the nest limit.
    NestingTooDeep = 2,
    /// The pattern is longer than the pattern length limit.
    PatternTooLong = 3,
    /// The compiled pattern would exceed the size limit.
    PatternTooLarge = 4,
    /// The pattern or the text is not well-formed Unicode (an unpaired UTF-16 surrogate).
    MalformedUnicode = 5,
    /// The text is longer than the text length limit.
    TextTooLong = 6,
    /// The engine failed unexpectedly.
    Internal = 7,
    /// A start offset lies outside the text or inside a UTF-16 surrogate pair.
    InvalidArgument = 8,
}

impl ErrorKind {
    /// Returns the stable numeric code.
    #[must_use]
    pub fn code(self) -> i32 {
        self as i32
    }
}

/// A rejected pattern or text: the kind and a message for humans.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct RegexError {
    /// Why the pattern or text was rejected.
    pub kind: ErrorKind,
    /// A description for humans; for syntax errors the message of the parser.
    pub message: String,
}

impl RegexError {
    pub(crate) fn new(kind: ErrorKind, message: impl Into<String>) -> RegexError {
        RegexError {
            kind,
            message: message.into(),
        }
    }
}

impl fmt::Display for RegexError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for RegexError {}
