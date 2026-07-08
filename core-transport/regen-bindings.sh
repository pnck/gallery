#!/usr/bin/env bash
# Regenerate the UniFFI Kotlin bindings for the transport core.
#
# UniFFI reads its interface metadata out of a COMPILED library, and our release
# profile strips symbols — so we build the crate in debug (metadata intact) for
# the host target and run the bundled uniffi-bindgen against that .so. The result
# is pure Kotlin, checked into src/main/uniffi and compiled like any other source.
#
# CI runs this and `git diff --exit-code` to guarantee the checked-in bindings
# never drift from the Rust FFI surface. In the arm64 dev container, source the
# toolchain first:  . /home/node/.claude/toolchains/env.sh
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
rust_dir="$repo_root/rust"
out_dir="$repo_root/core-transport/src/main/uniffi"

cd "$rust_dir"

# Debug build keeps the uniffi metadata symbols that `strip = true` removes in release.
cargo build

lib="$rust_dir/target/debug/libgallery_transport.so"
[ -f "$lib" ] || lib="$rust_dir/target/debug/libgallery_transport.dylib"

rm -rf "$out_dir"
mkdir -p "$out_dir"
cargo run --bin uniffi-bindgen -- generate \
    --library "$lib" \
    --language kotlin \
    --out-dir "$out_dir"

echo "UniFFI bindings regenerated into $out_dir"
