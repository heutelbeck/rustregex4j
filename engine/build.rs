//! Bundles the unwinder of the Rust toolchain into the static engine library for musl.
//!
//! The standard library calls `_Unwind_*`, but a static library does not carry an unwinder.
//! Linked into a static native image, the linker would then take whatever unwinder the C
//! toolchain offers. With the `musl-gcc` wrapper of glibc distributions, that is the libgcc
//! of glibc, which needs `_dl_find_object` and fails to link against musl. The toolchain
//! ships LLVM libunwind built for musl; bundling it makes the static library independent of
//! the C toolchain of the image build.

use std::env;
use std::path::PathBuf;
use std::process::Command;

const MUSL_TARGET: &str = "x86_64-unknown-linux-musl";

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if env::var("TARGET").ok().as_deref() != Some(MUSL_TARGET) {
        return;
    }
    let Some(directory) = self_contained_directory() else {
        println!(
            "cargo:warning=The Rust toolchain has no self-contained libunwind for {MUSL_TARGET}; the static engine relies on the unwinder of the C toolchain."
        );
        return;
    };
    println!("cargo:rustc-link-search=native={}", directory.display());
    println!("cargo:rustc-link-lib=static=unwind");
}

/// The directory of the toolchain that holds the C runtime and libunwind for the target.
fn self_contained_directory() -> Option<PathBuf> {
    let rustc = env::var("RUSTC").unwrap_or_else(|_| "rustc".to_owned());
    let output = Command::new(rustc)
        .args(["--print", "sysroot"])
        .output()
        .ok()?;
    let sysroot = String::from_utf8(output.stdout).ok()?;
    let directory = PathBuf::from(sysroot.trim())
        .join("lib/rustlib")
        .join(MUSL_TARGET)
        .join("lib/self-contained");
    directory.join("libunwind.a").is_file().then_some(directory)
}
