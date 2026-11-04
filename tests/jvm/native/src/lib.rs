// jni.rs takes its names from the crate root. brotli-decompressor is there for older versions of
// jni.rs.
#[allow(unused_imports)]
pub use ina::*;

#[path = "../../../../ina/ina/src/jni.rs"]
#[allow(dead_code, clippy::all)]
mod jni;
