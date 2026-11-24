//! Conversion of UTF-8 byte offsets into UTF-16 and code point offsets.

/// A position in a text in all supported units.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Position {
    /// Offset in UTF-8 bytes.
    pub byte: u32,
    /// Offset in Unicode code points.
    pub codepoint: u32,
    /// Offset in UTF-16 code units.
    pub utf16: u32,
}

/// The half-open range `start..end` of a match or capture group.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Span {
    /// Inclusive start.
    pub start: Position,
    /// Exclusive end.
    pub end: Position,
}

/// Resolves byte offsets into positions with one pass over the text.
///
/// The offsets may come in any order; every offset must lie on a character boundary of
/// `text`, which holds for all offsets reported by the engine. Offsets beyond the text are
/// clamped to its end.
fn resolve(text: &str, byte_offsets: &[usize]) -> Vec<Position> {
    let mut order: Vec<usize> = (0..byte_offsets.len()).collect();
    order.sort_by_key(|&index| byte_offsets.get(index).copied().unwrap_or(0));
    let bytes = text.as_bytes();
    let mut resolved = vec![Position::default(); byte_offsets.len()];
    let mut current = Position::default();
    let mut scanned = 0usize;
    for index in order {
        let target = byte_offsets
            .get(index)
            .copied()
            .unwrap_or(0)
            .min(bytes.len());
        for &byte in bytes.get(scanned..target).unwrap_or_default() {
            if !is_continuation_byte(byte) {
                current.codepoint += 1;
                current.utf16 += utf16_units_of_lead_byte(byte);
            }
        }
        scanned = scanned.max(target);
        current.byte = u32::try_from(scanned).unwrap_or(u32::MAX);
        if let Some(slot) = resolved.get_mut(index) {
            *slot = current;
        }
    }
    resolved
}

/// Resolves the byte ranges of spans, keeping `None` entries.
pub(crate) fn resolve_spans(text: &str, ranges: &[Option<(usize, usize)>]) -> Vec<Option<Span>> {
    let offsets: Vec<usize> = ranges
        .iter()
        .flatten()
        .flat_map(|&(start, end)| [start, end])
        .collect();
    let mut positions = resolve(text, &offsets).into_iter();
    ranges
        .iter()
        .map(|range| {
            range.and_then(|_| {
                let start = positions.next()?;
                let end = positions.next()?;
                Some(Span { start, end })
            })
        })
        .collect()
}

fn is_continuation_byte(byte: u8) -> bool {
    byte & 0xC0 == 0x80
}

/// Characters encoded with four UTF-8 bytes lie outside the Basic Multilingual Plane and
/// take a surrogate pair in UTF-16.
fn utf16_units_of_lead_byte(byte: u8) -> u32 {
    if byte >= 0xF0 { 2 } else { 1 }
}
