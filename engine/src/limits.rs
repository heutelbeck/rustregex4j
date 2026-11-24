//! Limits that bound the cost of compiling a pattern and of searching a text.

/// Limits for compiling a pattern and searching texts with it.
///
/// A search runs in time proportional to the size of the compiled pattern times the length
/// of the text. Both factors are bounded here, so every search has a fixed worst case.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Limits {
    /// Maximum pattern length in UTF-8 bytes.
    pub max_pattern_bytes: usize,
    /// Maximum nesting depth of groups, classes and repetitions.
    pub nest_limit: u32,
    /// Maximum heap size of the compiled pattern in bytes (`size_limit` of the engine).
    pub size_limit: usize,
    /// Maximum size of the lazy DFA cache per thread in bytes (`dfa_size_limit` of the
    /// engine). Only affects speed, never results.
    pub dfa_size_limit: usize,
    /// Maximum text length in UTF-8 bytes.
    pub max_text_bytes: usize,
}

impl Limits {
    /// The default limits.
    pub const DEFAULT: Limits = Limits {
        max_pattern_bytes: 4096,
        nest_limit: 64,
        size_limit: 1024 * 1024,
        dfa_size_limit: 2 * 1024 * 1024,
        max_text_bytes: 64 * 1024,
    };
}

impl Default for Limits {
    fn default() -> Limits {
        Limits::DEFAULT
    }
}
