#!/usr/bin/env bash
# Builds the Rust facade for Android and generates the Kotlin bindings.
# Usage: ./build-rust.sh [arm64-v8a] [x86_64]    (default: arm64-v8a)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/filen-android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
export PATH="$HOME/.claude/.cargo/bin:$HOME/.cargo/bin:$PATH"
[ -e "$ROOT/external/AmiPro-Linux/Cargo.toml" ] || {
  echo "external/AmiPro-Linux is missing: ln -s ~/AmiPro-Linux external/AmiPro-Linux (or git clone https://github.com/nchiker/AmiPro-Linux external/AmiPro-Linux)" >&2; exit 1; }
ABIS=("${@:-arm64-v8a}")
cd "$ROOT"
ARGS=(); for a in "${ABIS[@]}"; do ARGS+=(-t "$a"); done
JNI="$ROOT/android/app/src/main/jniLibs"
cargo ndk "${ARGS[@]}" -o "$JNI" build --release -p amipro-mobile
# Kotlin bindings, generated from the freshly built library (host tool, any ABI's .so works for metadata)
LIB="$(ls target/aarch64-linux-android/release/libamipro_mobile.so 2>/dev/null || ls target/*/release/libamipro_mobile.so | head -1)"
rm -rf "$ROOT/build-out/kotlin"
cargo run --release --bin uniffi-bindgen generate --library "$LIB" --language kotlin \
  --out-dir "$ROOT/build-out/kotlin" --no-format
OUT="$ROOT/android/app/src/main/java/generated"
rm -rf "$OUT"; mkdir -p "$OUT"
cp -r "$ROOT/build-out/kotlin/uniffi" "$OUT/"
echo "Rust build + bindings OK"
