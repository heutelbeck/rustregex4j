//! JNI entry points of the Java class `com.heutelbeck.rustregex4j.RegexEngine`.
//!
//! The Java side guarantees the contract of every function: strings and arrays are never
//! null, a pointer comes from `compile` and is passed to `free` exactly once, and no call
//! uses a pointer after `free`. Results travel as `int` arrays whose first element is a
//! status: `1` found, `0` not found, or a negated [`ErrorKind`] code. A span is four
//! values: UTF-16 start, UTF-16 end, code point start, code point end.
//!
//! Every function catches panics, so a defect in the engine reports
//! [`ErrorKind::Internal`] instead of unwinding into the JVM.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicI64, Ordering};

use jni_sys::{
    JNI_VERSION_1_8, JNIEnv, JavaVM, jboolean, jclass, jint, jintArray, jlong, jobjectArray, jsize,
    jstring,
};

use crate::compiled::{
    Anchoring, Captures, CompiledRegex, CompiledRegexSet, byte_offset_of_utf16, invalid_start,
};
use crate::error::{ErrorKind, RegexError};
use crate::limits::Limits;
use crate::positions::Span;

const ERROR_PANIC: &str = "The regex engine failed unexpectedly.";

/// The values of a span: UTF-16 start, UTF-16 end, code point start, code point end.
const SPAN_VALUES: usize = 4;

/// The values of a group: whether it participated, then its span.
const GROUP_VALUES: usize = SPAN_VALUES + 1;

/// Separates group names in the result of `groupNames`; group names cannot contain it.
const NAME_SEPARATOR: char = '\u{0}';

/// The number of compiled regexes and sets not yet freed. It changes only on compile and free, never
/// during a search, so it costs searches nothing; tests use it to prove that every compiled
/// regex is freed.
static LIVE_REGEXES: AtomicI64 = AtomicI64::new(0);

fn status_of(kind: ErrorKind) -> jint {
    -kind.code()
}

fn to_jint(value: usize) -> jint {
    jint::try_from(value).unwrap_or(jint::MAX)
}

/// Converts a UTF-16 start offset from Java into the byte offset of the decoded text.
fn byte_start(text: &str, start: jint) -> Result<usize, RegexError> {
    usize::try_from(start)
        .ok()
        .and_then(|start| byte_offset_of_utf16(text, start))
        .ok_or_else(|| invalid_start(start))
}

/// Encodes groups as `(present, span)` per group.
fn group_values(groups: &Captures, values: &mut Vec<jint>) {
    for group in groups {
        match group {
            Some(span) => {
                values.push(1);
                values.extend(span_values(span));
            }
            None => values.extend([0; GROUP_VALUES]),
        }
    }
}

fn span_values(span: &Span) -> [jint; SPAN_VALUES] {
    [
        to_jint(span.start.utf16 as usize),
        to_jint(span.end.utf16 as usize),
        to_jint(span.start.codepoint as usize),
        to_jint(span.end.codepoint as usize),
    ]
}

/// Copies a Java string as UTF-16 code units.
///
/// # Safety
///
/// `env` is the current JNI environment and `string` a live, non-null `java.lang.String`.
unsafe fn read_string(env: *mut JNIEnv, string: jstring) -> Vec<u16> {
    // SAFETY: guaranteed by the caller.
    unsafe {
        let functions = &(**env).v1_2;
        let length = (functions.GetStringLength)(env, string);
        let mut units = vec![0u16; usize::try_from(length).unwrap_or(0)];
        (functions.GetStringRegion)(env, string, 0, length, units.as_mut_ptr());
        units
    }
}

/// Creates a Java `int[]` holding `values`; null if the JVM is out of memory.
///
/// # Safety
///
/// `env` is the current JNI environment.
unsafe fn new_int_array(env: *mut JNIEnv, values: &[jint]) -> jintArray {
    // SAFETY: guaranteed by the caller; the region lies within the new array.
    unsafe {
        let functions = &(**env).v1_2;
        let length = jsize::try_from(values.len()).unwrap_or(jsize::MAX);
        let array = (functions.NewIntArray)(env, length);
        if !array.is_null() {
            (functions.SetIntArrayRegion)(env, array, 0, length, values.as_ptr());
        }
        array
    }
}

/// Stores `message` in the first element of a Java `String[]`.
///
/// # Safety
///
/// `env` is the current JNI environment and `target` a live `String[]` of length at least 1.
unsafe fn store_message(env: *mut JNIEnv, target: jobjectArray, message: &str) {
    let units: Vec<u16> = message.encode_utf16().collect();
    // SAFETY: guaranteed by the caller.
    unsafe {
        let functions = &(**env).v1_2;
        let length = jsize::try_from(units.len()).unwrap_or(jsize::MAX);
        let string = (functions.NewString)(env, units.as_ptr(), length);
        if !string.is_null() {
            (functions.SetObjectArrayElement)(env, target, 0, string);
        }
    }
}

/// Converts the limits as Java passes them; a value out of range becomes 0 and refuses
/// every pattern.
fn limits_of(
    max_pattern_bytes: jint,
    nest_limit: jint,
    size_limit: jlong,
    dfa_size_limit: jlong,
    max_text_bytes: jint,
) -> Limits {
    Limits {
        max_pattern_bytes: usize::try_from(max_pattern_bytes).unwrap_or(0),
        nest_limit: u32::try_from(nest_limit).unwrap_or(0),
        size_limit: usize::try_from(size_limit).unwrap_or(0),
        dfa_size_limit: usize::try_from(dfa_size_limit).unwrap_or(0),
        max_text_bytes: usize::try_from(max_text_bytes).unwrap_or(0),
    }
}

fn anchoring_of(whole_text: jboolean) -> Anchoring {
    if whole_text {
        Anchoring::WholeText
    } else {
        Anchoring::Anywhere
    }
}

/// Copies the strings of a Java `String[]` as UTF-16 code units.
///
/// # Safety
///
/// `env` is the current JNI environment and `strings` a live `String[]` without null
/// elements.
unsafe fn read_strings(env: *mut JNIEnv, strings: jobjectArray) -> Vec<Vec<u16>> {
    // SAFETY: guaranteed by the caller; every local reference is deleted after use, so a
    // long array does not exhaust the local reference table.
    unsafe {
        let functions = &(**env).v1_2;
        let length = (functions.GetArrayLength)(env, strings);
        let mut result = Vec::with_capacity(usize::try_from(length).unwrap_or(0));
        for index in 0..length {
            let element = (functions.GetObjectArrayElement)(env, strings, index);
            result.push(read_string(env, element));
            (functions.DeleteLocalRef)(env, element);
        }
        result
    }
}

// Pointers travel to Java as `long`. The library supports 64-bit targets only, where a
// pointer and a `long` have the same width, so the conversions below are lossless.
#[cfg(not(target_pointer_width = "64"))]
compile_error!("rustregex4j supports 64-bit targets only.");

#[allow(clippy::cast_possible_wrap)]
fn to_handle(regex: Box<CompiledRegex>) -> jlong {
    Box::into_raw(regex) as usize as jlong
}

#[allow(clippy::cast_sign_loss, clippy::cast_possible_truncation)]
fn to_pointer(handle: jlong) -> *mut CompiledRegex {
    handle as usize as *mut CompiledRegex
}

#[allow(clippy::cast_possible_wrap)]
fn to_set_handle(set: Box<CompiledRegexSet>) -> jlong {
    Box::into_raw(set) as usize as jlong
}

#[allow(clippy::cast_sign_loss, clippy::cast_possible_truncation)]
fn to_set_pointer(handle: jlong) -> *mut CompiledRegexSet {
    handle as usize as *mut CompiledRegexSet
}

/// Returns the compiled regex behind a pointer from `compile`.
///
/// # Safety
///
/// `pointer` was returned by `compile` and not yet freed.
unsafe fn regex<'a>(pointer: jlong) -> &'a CompiledRegex {
    // SAFETY: guaranteed by the caller.
    unsafe { &*to_pointer(pointer) }
}

/// Runs a search on a decoded text and encodes its result.
///
/// # Safety
///
/// As [`read_string`] and [`regex`].
unsafe fn search(
    env: *mut JNIEnv,
    pointer: jlong,
    text: jstring,
    operation: impl FnOnce(&CompiledRegex, &str) -> Result<Vec<jint>, RegexError>,
) -> jintArray {
    let values = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: guaranteed by the caller.
        let (regex, units) = unsafe { (regex(pointer), read_string(env, text)) };
        CompiledRegex::decode_text(&units)
            .and_then(|decoded| operation(regex, &decoded))
            .unwrap_or_else(|error| vec![status_of(error.kind)])
    }))
    .unwrap_or_else(|_| vec![status_of(ErrorKind::Internal)]);
    // SAFETY: guaranteed by the caller.
    unsafe { new_int_array(env, &values) }
}

/// Compiles a pattern. Returns the pointer to the compiled regex, or a negated
/// [`ErrorKind`] code with the message stored in `message[0]`.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_compile(
    env: *mut JNIEnv,
    _class: jclass,
    pattern: jstring,
    max_pattern_bytes: jint,
    nest_limit: jint,
    size_limit: jlong,
    dfa_size_limit: jlong,
    max_text_bytes: jint,
    whole_text: jboolean,
    message: jobjectArray,
) -> jlong {
    let limits = limits_of(
        max_pattern_bytes,
        nest_limit,
        size_limit,
        dfa_size_limit,
        max_text_bytes,
    );
    let anchoring = anchoring_of(whole_text);
    let compiled = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the Java side passes a live, non-null string.
        let units = unsafe { read_string(env, pattern) };
        CompiledRegex::compile_utf16(&units, &limits, anchoring)
    }))
    .unwrap_or_else(|_| Err(RegexError::new(ErrorKind::Internal, ERROR_PANIC)));
    match compiled {
        Ok(regex) => {
            LIVE_REGEXES.fetch_add(1, Ordering::Relaxed);
            to_handle(Box::new(regex))
        }
        Err(error) => {
            // SAFETY: the Java side passes a live String[1].
            unsafe { store_message(env, message, &error.message) };
            jlong::from(status_of(error.kind))
        }
    }
}

/// Frees a compiled regex.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_free(
    _env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
) {
    // SAFETY: the Java side frees every pointer from `compile` exactly once.
    drop(unsafe { Box::from_raw(to_pointer(pointer)) });
    LIVE_REGEXES.fetch_sub(1, Ordering::Relaxed);
}

/// Returns the number of capture groups including group 0.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_groupCount(
    _env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
) -> jint {
    // SAFETY: the Java side passes a live pointer.
    to_jint(unsafe { regex(pointer) }.group_count())
}

/// Stores the group names in `names[0]`, one per group separated by NUL; an unnamed group
/// has the empty name.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_groupNames(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    names: jobjectArray,
) {
    // SAFETY: the Java side passes a live pointer.
    let joined = unsafe { regex(pointer) }
        .group_names()
        .iter()
        .map(|name| name.as_deref().unwrap_or_default())
        .collect::<Vec<_>>()
        .join(&NAME_SEPARATOR.to_string());
    // SAFETY: the Java side passes a live String[1].
    unsafe { store_message(env, names, &joined) };
}

/// Reports whether the regex matches: `1`, `0`, or a negated [`ErrorKind`] code.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_isMatch(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the Java side passes a live pointer and a live, non-null string.
        let (regex, units) = unsafe { (regex(pointer), read_string(env, text)) };
        CompiledRegex::decode_text(&units)
            .and_then(|decoded| regex.is_match(&decoded))
            .map_or_else(|error| status_of(error.kind), jint::from)
    }))
    .unwrap_or(status_of(ErrorKind::Internal))
}

/// Finds the first match at or after the UTF-16 offset `start`: `[status, span]`.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_find(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
    start: jint,
) -> jintArray {
    // SAFETY: the Java side passes a live pointer and a live, non-null string.
    unsafe {
        search(env, pointer, text, |regex, text| {
            Ok(match regex.find_at(text, byte_start(text, start)?)? {
                Some(span) => [&[1][..], &span_values(&span)].concat(),
                None => vec![0],
            })
        })
    }
}

/// Finds at most `limit` matches: `[count, span, span, ...]` or `[negated error code]`.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_findAll(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
    limit: jint,
) -> jintArray {
    // SAFETY: the Java side passes a live pointer and a live, non-null string.
    unsafe {
        search(env, pointer, text, |regex, text| {
            let spans = regex.find_all_limited(text, usize::try_from(limit).unwrap_or(0))?;
            let mut values = Vec::with_capacity(1 + SPAN_VALUES * spans.len());
            values.push(to_jint(spans.len()));
            values.extend(spans.iter().flat_map(span_values));
            Ok(values)
        })
    }
}

/// Captures the groups of the first match at or after the UTF-16 offset `start`:
/// `[status, group count, (present, span) per group]`.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_captures(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
    start: jint,
) -> jintArray {
    // SAFETY: the Java side passes a live pointer and a live, non-null string.
    unsafe {
        search(env, pointer, text, |regex, text| {
            let Some(groups) = regex.captures_at(text, byte_start(text, start)?)? else {
                return Ok(vec![0]);
            };
            let mut values = Vec::with_capacity(2 + GROUP_VALUES * groups.len());
            values.extend([1, to_jint(groups.len())]);
            group_values(&groups, &mut values);
            Ok(values)
        })
    }
}

/// Captures the groups of at most `limit` matches:
/// `[match count, group count, (present, span) per group of every match]`.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_capturesAll(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
    limit: jint,
) -> jintArray {
    // SAFETY: the Java side passes a live pointer and a live, non-null string.
    unsafe {
        search(env, pointer, text, |regex, text| {
            let matches = regex.captures_all(text, usize::try_from(limit).unwrap_or(0))?;
            let mut values = vec![to_jint(matches.len()), to_jint(regex.group_count())];
            for groups in &matches {
                group_values(groups, &mut values);
            }
            Ok(values)
        })
    }
}

/// Replaces all matches and stores the result in `result[0]`: `1`, or a negated
/// [`ErrorKind`] code.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_replaceAll(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
    replacement: jstring,
    result: jobjectArray,
) -> jint {
    let replaced = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the Java side passes a live pointer and live, non-null strings.
        let (regex, text_units, replacement_units) = unsafe {
            (
                regex(pointer),
                read_string(env, text),
                read_string(env, replacement),
            )
        };
        let text = CompiledRegex::decode_text(&text_units)?;
        let replacement = CompiledRegex::decode_text(&replacement_units)?;
        regex.replace_all(&text, &replacement)
    }))
    .unwrap_or_else(|_| Err(RegexError::new(ErrorKind::Internal, ERROR_PANIC)));
    match replaced {
        Ok(replaced) => {
            // SAFETY: the Java side passes a live String[1].
            unsafe { store_message(env, result, &replaced) };
            1
        }
        Err(error) => status_of(error.kind),
    }
}

/// Splits the text into at most `limit` parts: `[count, span, span, ...]` or
/// `[negated error code]`.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_split(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
    limit: jint,
) -> jintArray {
    // SAFETY: the Java side passes a live pointer and a live, non-null string.
    unsafe {
        search(env, pointer, text, |regex, text| {
            let parts = regex.split(text, usize::try_from(limit).unwrap_or(0))?;
            let mut values = Vec::with_capacity(1 + SPAN_VALUES * parts.len());
            values.push(to_jint(parts.len()));
            values.extend(parts.iter().flat_map(span_values));
            Ok(values)
        })
    }
}

/// Compiles a set of patterns. Returns the pointer to the compiled set, or a negated
/// [`ErrorKind`] code with the message stored in `message[0]`.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_compileSet(
    env: *mut JNIEnv,
    _class: jclass,
    patterns: jobjectArray,
    max_pattern_bytes: jint,
    nest_limit: jint,
    size_limit: jlong,
    dfa_size_limit: jlong,
    max_text_bytes: jint,
    whole_text: jboolean,
    message: jobjectArray,
) -> jlong {
    let limits = limits_of(
        max_pattern_bytes,
        nest_limit,
        size_limit,
        dfa_size_limit,
        max_text_bytes,
    );
    let anchoring = anchoring_of(whole_text);
    let compiled = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the Java side passes a live String[] without null elements.
        let units = unsafe { read_strings(env, patterns) };
        let mut decoded = Vec::with_capacity(units.len());
        for pattern in &units {
            decoded.push(CompiledRegex::decode_pattern(pattern)?);
        }
        CompiledRegexSet::compile(&decoded, &limits, anchoring)
    }))
    .unwrap_or_else(|_| Err(RegexError::new(ErrorKind::Internal, ERROR_PANIC)));
    match compiled {
        Ok(set) => {
            LIVE_REGEXES.fetch_add(1, Ordering::Relaxed);
            to_set_handle(Box::new(set))
        }
        Err(error) => {
            // SAFETY: the Java side passes a live String[1].
            unsafe { store_message(env, message, &error.message) };
            jlong::from(status_of(error.kind))
        }
    }
}

/// Frees a compiled set.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_freeSet(
    _env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
) {
    // SAFETY: the Java side frees every pointer from `compileSet` exactly once.
    drop(unsafe { Box::from_raw(to_set_pointer(pointer)) });
    LIVE_REGEXES.fetch_sub(1, Ordering::Relaxed);
}

/// Matches all patterns of a set: `[count, pattern index, ...]` or `[negated error code]`.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_setMatches(
    env: *mut JNIEnv,
    _class: jclass,
    pointer: jlong,
    text: jstring,
) -> jintArray {
    let values = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the Java side passes a pointer from `compileSet` that is not yet freed,
        // and a live, non-null string.
        let (set, units) = unsafe { (&*to_set_pointer(pointer), read_string(env, text)) };
        CompiledRegex::decode_text(&units)
            .and_then(|decoded| set.matches(&decoded))
            .map_or_else(
                |error| vec![status_of(error.kind)],
                |matched| {
                    let mut values = Vec::with_capacity(1 + matched.len());
                    values.push(to_jint(matched.len()));
                    values.extend(matched.into_iter().map(to_jint));
                    values
                },
            )
    }))
    .unwrap_or_else(|_| vec![status_of(ErrorKind::Internal)]);
    // SAFETY: `env` is the current JNI environment.
    unsafe { new_int_array(env, &values) }
}

/// Returns the number of compiled regexes not yet freed.
#[unsafe(no_mangle)]
extern "system" fn Java_com_heutelbeck_rustregex4j_RegexEngine_liveRegexes(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    LIVE_REGEXES.load(Ordering::Relaxed)
}

/// Entry point of the engine when it is linked statically into an executable, such as a
/// static native image of `GraalVM`. The JVM calls it for the built-in library
/// `rustregex4j`.
#[unsafe(no_mangle)]
extern "system" fn JNI_OnLoad_rustregex4j(
    _vm: *mut JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jint {
    JNI_VERSION_1_8
}
