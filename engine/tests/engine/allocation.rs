//! A global allocator for this test program that keeps a balance of heap bytes per thread.
//! Each test runs on its own thread, so a test sees only its own allocations.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

thread_local! {
    static BALANCE: Cell<isize> = const { Cell::new(0) };
    static LARGEST_REQUEST: Cell<usize> = const { Cell::new(0) };
}

struct CountingAllocator;

fn record(change: isize) {
    // `try_with` fails while a thread shuts down; those allocations belong to no test.
    let _ = BALANCE.try_with(|balance| balance.set(balance.get() + change));
}

fn record_request(size: usize) {
    let _ = LARGEST_REQUEST.try_with(|largest| largest.set(largest.get().max(size)));
}

// SAFETY: every call is delegated unchanged to the system allocator.
#[allow(unsafe_code)]
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        // SAFETY: same contract as the caller of this method.
        let pointer = unsafe { System.alloc(layout) };
        if !pointer.is_null() {
            record(layout.size().cast_signed());
            record_request(layout.size());
        }
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        // SAFETY: same contract as the caller of this method.
        unsafe { System.dealloc(pointer, layout) };
        record(-layout.size().cast_signed());
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        // SAFETY: same contract as the caller of this method.
        let moved = unsafe { System.realloc(pointer, layout, new_size) };
        if !moved.is_null() {
            record(new_size.cast_signed() - layout.size().cast_signed());
            record_request(new_size);
        }
        moved
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

/// Returns the heap bytes the current thread allocated and has not freed.
pub(crate) fn balance() -> isize {
    BALANCE.with(Cell::get)
}

/// Runs an operation and returns its largest heap allocation or reallocation request.
pub(crate) fn largest_request<T>(operation: impl FnOnce() -> T) -> (T, usize) {
    LARGEST_REQUEST.with(|largest| largest.set(0));
    let result = operation();
    (result, LARGEST_REQUEST.with(Cell::get))
}
