//! A compiled pattern and the searches on it.

use regex::{Regex, RegexBuilder, RegexSet, RegexSetBuilder};
use regex_syntax::ParserBuilder;
use regex_syntax::ast;
use regex_syntax::hir::{Hir, Look};

use crate::error::{ErrorKind, RegexError};
use crate::limits::Limits;
use crate::positions::{Span, resolve_spans};

const ERROR_INVALID_START: &str =
    "The start {value} lies outside the text or inside a UTF-16 surrogate pair.";
const ERROR_MALFORMED_PATTERN: &str = "The pattern contains an unpaired UTF-16 surrogate.";
const ERROR_MALFORMED_TEXT: &str = "The text contains an unpaired UTF-16 surrogate.";
const ERROR_NESTING_TOO_DEEP: &str =
    "The pattern nests groups, classes or repetitions deeper than {value} levels.";
const ERROR_PATTERN_TOO_LARGE: &str =
    "The compiled pattern exceeds the size limit of {value} bytes.";
const ERROR_PATTERN_TOO_LONG: &str = "The pattern is longer than {value} UTF-8 bytes.";
const ERROR_RESULT_TOO_LONG: &str =
    "The result of the replacement is longer than {value} UTF-8 bytes.";
const ERROR_SET_TOO_LARGE: &str =
    "The compiled set of patterns exceeds the size limit of {value} bytes.";
const ERROR_TEXT_TOO_LONG: &str = "The text is longer than {value} UTF-8 bytes.";

/// A compiled regular expression.
///
/// The engine is configured with the defaults of the `regex` crate: Unicode mode, case
/// sensitive, `^` and `$` match at the start and end of the text, `.` does not match `\n`,
/// leftmost-first match semantics. Patterns change this only with inline flags such as
/// `(?i)`, `(?m)`, `(?s)`, `(?x)`, `(?U)` and `(?-u)`.
///
/// A compiled regex is immutable and can be searched from many threads at the same time.
#[derive(Debug, Clone)]
pub struct CompiledRegex {
    regex: Regex,
    max_text_bytes: usize,
    group_names: Vec<Option<String>>,
}

/// The groups of a match, group 0 first. A group that did not participate is `None`.
pub type Captures = Vec<Option<Span>>;

/// Where a pattern must match.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Anchoring {
    /// A match may lie anywhere in the text.
    Anywhere,
    /// A match must span the whole text.
    WholeText,
}

impl CompiledRegex {
    /// Compiles a pattern whose matches may lie anywhere in a text.
    ///
    /// # Errors
    ///
    /// The checks run in this order: pattern length, syntax and nesting depth, size of the
    /// compiled pattern.
    pub fn compile(pattern: &str, limits: &Limits) -> Result<CompiledRegex, RegexError> {
        CompiledRegex::compile_anchored(pattern, limits, Anchoring::Anywhere)
    }

    /// Compiles a pattern that must match where `anchoring` says.
    ///
    /// For [`Anchoring::WholeText`] the pattern is checked as written, then its syntax tree
    /// is enclosed in the assertions `\A` and `\z` and compiled from its canonical text.
    /// Enclosing the text of the pattern instead would let the pattern escape the
    /// enclosure, for example `a)(b`, or a comment in `(?x)` mode that swallows the
    /// closing parenthesis.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::compile`].
    pub fn compile_anchored(
        pattern: &str,
        limits: &Limits,
        anchoring: Anchoring,
    ) -> Result<CompiledRegex, RegexError> {
        let (_, regex) = prepare(pattern, limits, anchoring)?;
        let group_names = regex
            .capture_names()
            .map(|name| name.map(str::to_owned))
            .collect();
        Ok(CompiledRegex {
            regex,
            max_text_bytes: limits.max_text_bytes,
            group_names,
        })
    }

    /// Compiles a pattern given as UTF-16 code units.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::MalformedUnicode`] for an unpaired surrogate, otherwise as
    /// [`CompiledRegex::compile`].
    pub fn compile_utf16(
        pattern: &[u16],
        limits: &Limits,
        anchoring: Anchoring,
    ) -> Result<CompiledRegex, RegexError> {
        let pattern = decode_utf16(pattern, ERROR_MALFORMED_PATTERN)?;
        CompiledRegex::compile_anchored(&pattern, limits, anchoring)
    }

    /// Returns the number of capture groups including the implicit group 0.
    #[must_use]
    pub fn group_count(&self) -> usize {
        self.regex.captures_len()
    }

    /// Returns the name of every group in group order; `None` for group 0 and for unnamed
    /// groups.
    #[must_use]
    pub fn group_names(&self) -> &[Option<String>] {
        &self.group_names
    }

    /// Reports whether the pattern matches.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::TextTooLong`] if the text exceeds the text length limit.
    pub fn is_match(&self, text: &str) -> Result<bool, RegexError> {
        self.check_text(text)?;
        Ok(self.regex.is_match(text))
    }

    /// Returns the leftmost-first match.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::is_match`].
    pub fn find(&self, text: &str) -> Result<Option<Span>, RegexError> {
        self.find_at(text, 0)
    }

    /// Returns the leftmost-first match that starts at or after the byte offset `start`.
    ///
    /// The search sees the whole text, so anchors and word boundaries behave as in a search
    /// from the start of the text: `^` does not match at `start` unless `start` is 0.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::TextTooLong`], or [`ErrorKind::InvalidArgument`] if `start` is not a
    /// character boundary of the text.
    pub fn find_at(&self, text: &str, start: usize) -> Result<Option<Span>, RegexError> {
        self.check_text(text)?;
        check_start(text, start)?;
        let range = self
            .regex
            .find_at(text, start)
            .map(|found| (found.start(), found.end()));
        Ok(resolve_spans(text, &[range]).into_iter().flatten().next())
    }

    /// Returns all successive non-overlapping matches.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::is_match`].
    pub fn find_all(&self, text: &str) -> Result<Vec<Span>, RegexError> {
        self.find_all_limited(text, usize::MAX)
    }

    /// Returns the first `limit` successive non-overlapping matches; the search stops after
    /// the last of them.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::is_match`].
    pub fn find_all_limited(&self, text: &str, limit: usize) -> Result<Vec<Span>, RegexError> {
        self.check_text(text)?;
        let ranges: Vec<Option<(usize, usize)>> = self
            .regex
            .find_iter(text)
            .take(limit)
            .map(|found| Some((found.start(), found.end())))
            .collect();
        Ok(resolve_spans(text, &ranges).into_iter().flatten().collect())
    }

    /// Returns the groups of the leftmost-first match.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::is_match`].
    pub fn captures(&self, text: &str) -> Result<Option<Captures>, RegexError> {
        self.captures_at(text, 0)
    }

    /// Returns the groups of the leftmost-first match that starts at or after the byte
    /// offset `start`; anchors behave as in [`CompiledRegex::find_at`].
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::find_at`].
    pub fn captures_at(&self, text: &str, start: usize) -> Result<Option<Captures>, RegexError> {
        self.check_text(text)?;
        check_start(text, start)?;
        let Some(groups) = self.regex.captures_at(text, start) else {
            return Ok(None);
        };
        Ok(Some(resolve_spans(text, &group_ranges(&groups))))
    }

    /// Returns the groups of the first `limit` successive non-overlapping matches; the
    /// search stops after the last of them.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::is_match`].
    pub fn captures_all(&self, text: &str, limit: usize) -> Result<Vec<Captures>, RegexError> {
        self.check_text(text)?;
        let ranges: Vec<Option<(usize, usize)>> = self
            .regex
            .captures_iter(text)
            .take(limit)
            .flat_map(|groups| group_ranges(&groups))
            .collect();
        let spans = resolve_spans(text, &ranges);
        Ok(spans
            .chunks(self.group_count().max(1))
            .map(<[Option<Span>]>::to_vec)
            .collect())
    }

    /// Replaces every successive non-overlapping match with `replacement`, as
    /// [`Regex::replace_all`] does: `$1` and `${1}` insert a group by number, `$name` and
    /// `${name}` by name, and `$$` inserts `$`. A group that does not exist or did not
    /// participate inserts nothing. `$name` takes the longest name of letters, digits and
    /// `_`, so `${1}a` is group 1 followed by `a`, while `$1a` is the group named `1a`.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::TextTooLong`] if the text or the replacement exceeds the text length
    /// limit, [`ErrorKind::ResultTooLong`] if the result would.
    pub fn replace_all(&self, text: &str, replacement: &str) -> Result<String, RegexError> {
        self.check_text(text)?;
        self.check_text(replacement)?;
        let mut result = String::new();
        let mut replaced = 0;
        let mut replacement_lengths = None;
        for groups in self.regex.captures_iter(text) {
            let Some(found) = groups.get(0) else {
                continue;
            };
            let unchanged = text.get(replaced..found.start()).unwrap_or_default();
            let (literal_bytes, occurrences) =
                replacement_lengths.get_or_insert_with(|| self.replacement_lengths(replacement));
            let expanded = occurrences
                .iter()
                .try_fold(*literal_bytes, |size, (index, count)| {
                    let captured = groups.get(*index).map_or(0, |group| group.len());
                    captured
                        .checked_mul(*count)
                        .and_then(|bytes| size.checked_add(bytes))
                });
            self.check_result_size(
                result
                    .len()
                    .checked_add(unchanged.len())
                    .and_then(|size| expanded.and_then(|bytes| size.checked_add(bytes))),
            )?;
            result.push_str(unchanged);
            groups.expand(replacement, &mut result);
            replaced = found.end();
        }
        let tail = text.get(replaced..).unwrap_or_default();
        self.check_result_size(result.len().checked_add(tail.len()))?;
        result.push_str(tail);
        Ok(result)
    }

    /// Splits the text at the matches into at most `limit` parts, as [`Regex::splitn`]
    /// does: the text before the first match is the first part, also if it is empty, and
    /// the last part holds the rest of the text.
    ///
    /// # Errors
    ///
    /// As [`CompiledRegex::is_match`].
    pub fn split(&self, text: &str, limit: usize) -> Result<Vec<Span>, RegexError> {
        self.check_text(text)?;
        let start_of_text = text.as_ptr().addr();
        let ranges: Vec<Option<(usize, usize)>> = self
            .regex
            .splitn(text, limit)
            .map(|part| {
                let start = part.as_ptr().addr() - start_of_text;
                Some((start, start + part.len()))
            })
            .collect();
        Ok(resolve_spans(text, &ranges).into_iter().flatten().collect())
    }

    /// Decodes a pattern given as UTF-16 code units.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::MalformedUnicode`] for an unpaired surrogate.
    pub fn decode_pattern(pattern: &[u16]) -> Result<String, RegexError> {
        decode_utf16(pattern, ERROR_MALFORMED_PATTERN)
    }

    /// Decodes a text given as UTF-16 code units for searching.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::MalformedUnicode`] for an unpaired surrogate.
    pub fn decode_text(text: &[u16]) -> Result<String, RegexError> {
        decode_utf16(text, ERROR_MALFORMED_TEXT)
    }

    fn replacement_lengths(&self, replacement: &str) -> (usize, Vec<(usize, usize)>) {
        let mut occurrences = vec![0; self.regex.captures_len()];
        let mut literals = String::with_capacity(replacement.len());
        // Use the same interpolation grammar as Captures::expand, without copying captures.
        regex_automata::util::interpolate::string(
            replacement,
            |index, _| {
                if let Some(count) = occurrences.get_mut(index) {
                    *count += 1;
                }
            },
            |name| {
                self.regex
                    .capture_names()
                    .position(|capture| capture == Some(name))
            },
            &mut literals,
        );
        let referenced = occurrences
            .into_iter()
            .enumerate()
            .filter(|(_, count)| *count != 0)
            .collect();
        (literals.len(), referenced)
    }

    fn check_result_size(&self, size: Option<usize>) -> Result<(), RegexError> {
        if size.is_none_or(|size| size > self.max_text_bytes) {
            return Err(RegexError::new(
                ErrorKind::ResultTooLong,
                with_value(ERROR_RESULT_TOO_LONG, self.max_text_bytes),
            ));
        }
        Ok(())
    }

    fn check_text(&self, text: &str) -> Result<(), RegexError> {
        check_text_length(text, self.max_text_bytes)
    }
}

/// A set of compiled patterns that one search matches against a text all at once.
///
/// Every pattern is checked and compiled on its own first, so a set holds no pattern that
/// [`CompiledRegex`] refuses. The patterns then share one program, whose size may reach the
/// size limit of a single pattern times the number of patterns.
#[derive(Debug, Clone)]
pub struct CompiledRegexSet {
    set: RegexSet,
    max_text_bytes: usize,
}

impl CompiledRegexSet {
    /// Compiles the patterns into one set.
    ///
    /// # Errors
    ///
    /// The error of the first pattern that [`CompiledRegex::compile_anchored`] refuses, or
    /// [`ErrorKind::PatternTooLarge`] if the set as a whole is too large.
    pub fn compile(
        patterns: &[String],
        limits: &Limits,
        anchoring: Anchoring,
    ) -> Result<CompiledRegexSet, RegexError> {
        let mut prepared = Vec::with_capacity(patterns.len());
        for pattern in patterns {
            prepared.push(prepare(pattern, limits, anchoring)?.0);
        }
        let size_limit = limits.size_limit.saturating_mul(patterns.len().max(1));
        let set = RegexSetBuilder::new(&prepared)
            .nest_limit(limits.nest_limit.saturating_mul(2).saturating_add(2))
            .size_limit(size_limit)
            .dfa_size_limit(limits.dfa_size_limit)
            .build()
            .map_err(|error| match error {
                regex::Error::CompiledTooBig(limit) => RegexError::new(
                    ErrorKind::PatternTooLarge,
                    with_value(ERROR_SET_TOO_LARGE, limit),
                ),
                other => RegexError::new(ErrorKind::Syntax, other.to_string()),
            })?;
        Ok(CompiledRegexSet {
            set,
            max_text_bytes: limits.max_text_bytes,
        })
    }

    /// Returns the number of patterns in the set.
    #[must_use]
    pub fn len(&self) -> usize {
        self.set.len()
    }

    /// Reports whether the set holds no pattern.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.set.is_empty()
    }

    /// Returns the indexes of the patterns that match the text, in ascending order.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::TextTooLong`] if the text exceeds the text length limit.
    pub fn matches(&self, text: &str) -> Result<Vec<usize>, RegexError> {
        check_text_length(text, self.max_text_bytes)?;
        Ok(self.set.matches(text).into_iter().collect())
    }
}

/// Checks a pattern against the limits and compiles it. Returns the text the engine
/// compiled, which for [`Anchoring::WholeText`] is the canonical text of the enclosed
/// syntax tree, together with the compiled regex.
fn prepare(
    pattern: &str,
    limits: &Limits,
    anchoring: Anchoring,
) -> Result<(String, Regex), RegexError> {
    if pattern.len() > limits.max_pattern_bytes {
        return Err(RegexError::new(
            ErrorKind::PatternTooLong,
            with_value(ERROR_PATTERN_TOO_LONG, limits.max_pattern_bytes),
        ));
    }
    let hir = ParserBuilder::new()
        .nest_limit(limits.nest_limit)
        .build()
        .parse(pattern)
        .map_err(|error| classify_syntax_error(&error, limits))?;
    let compiled_pattern = match anchoring {
        Anchoring::Anywhere => pattern.to_owned(),
        Anchoring::WholeText => {
            Hir::concat(vec![Hir::look(Look::Start), hir, Hir::look(Look::End)]).to_string()
        }
    };
    // The parser above already enforced the nest limit on the pattern as written. The
    // canonical text that the builder parses again may nest deeper, for example by the
    // group that encloses the anchors, so the builder gets headroom and never rejects
    // what the parser admitted.
    let regex = RegexBuilder::new(&compiled_pattern)
        .nest_limit(limits.nest_limit.saturating_mul(2).saturating_add(2))
        .size_limit(limits.size_limit)
        .dfa_size_limit(limits.dfa_size_limit)
        .build()
        .map_err(|error| match error {
            regex::Error::CompiledTooBig(limit) => RegexError::new(
                ErrorKind::PatternTooLarge,
                with_value(ERROR_PATTERN_TOO_LARGE, limit),
            ),
            other => RegexError::new(ErrorKind::Syntax, other.to_string()),
        })?;
    Ok((compiled_pattern, regex))
}

/// Converts a UTF-16 offset into the byte offset of the same position in `text`; `None` if
/// it lies beyond the text or inside a surrogate pair.
#[must_use]
pub fn byte_offset_of_utf16(text: &str, utf16_offset: usize) -> Option<usize> {
    let mut units = 0usize;
    for (byte, character) in text.char_indices() {
        if units == utf16_offset {
            return Some(byte);
        }
        units += character.len_utf16();
        if units > utf16_offset {
            return None;
        }
    }
    (units == utf16_offset).then_some(text.len())
}

fn check_text_length(text: &str, max_text_bytes: usize) -> Result<(), RegexError> {
    if text.len() > max_text_bytes {
        return Err(RegexError::new(
            ErrorKind::TextTooLong,
            with_value(ERROR_TEXT_TOO_LONG, max_text_bytes),
        ));
    }
    Ok(())
}

fn group_ranges(groups: &regex::Captures<'_>) -> Vec<Option<(usize, usize)>> {
    groups
        .iter()
        .map(|group| group.map(|found| (found.start(), found.end())))
        .collect()
}

fn check_start(text: &str, start: usize) -> Result<(), RegexError> {
    if text.is_char_boundary(start) {
        return Ok(());
    }
    Err(invalid_start(start))
}

/// The error for a start offset outside the text or inside a character.
pub(crate) fn invalid_start(start: impl std::fmt::Display) -> RegexError {
    RegexError::new(
        ErrorKind::InvalidArgument,
        with_value(ERROR_INVALID_START, start),
    )
}

/// Decodes UTF-16 strictly: an unpaired surrogate is an error, never replaced.
fn decode_utf16(units: &[u16], message: &str) -> Result<String, RegexError> {
    let mut decoded = String::with_capacity(units.len());
    for character in char::decode_utf16(units.iter().copied()) {
        match character {
            Ok(character) => decoded.push(character),
            Err(_) => return Err(RegexError::new(ErrorKind::MalformedUnicode, message)),
        }
    }
    Ok(decoded)
}

fn classify_syntax_error(error: &regex_syntax::Error, limits: &Limits) -> RegexError {
    if let regex_syntax::Error::Parse(parse) = error
        && let ast::ErrorKind::NestLimitExceeded(_) = parse.kind()
    {
        return RegexError::new(
            ErrorKind::NestingTooDeep,
            with_value(ERROR_NESTING_TOO_DEEP, limits.nest_limit),
        );
    }
    RegexError::new(ErrorKind::Syntax, error.to_string())
}

/// Fills a limit or an offset into a message.
fn with_value(message: &str, value: impl std::fmt::Display) -> String {
    message.replace("{value}", &value.to_string())
}
