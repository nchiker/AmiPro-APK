# AmiPro-APK

A simple Android app that reads and writes Ami Pro documents (`.SAM`, and the lossless native `.amipro`). It reuses the file-format core of [AmiPro-Linux](https://github.com/nchiker/AmiPro-Linux) (plain Rust, no third-party dependencies), wrapped for Kotlin with UniFFI. Independent project. It is not affiliated with, endorsed by, or sponsored by IBM, HCL, or Lotus. Lotus and Ami Pro are trademarks of their owners; they are used here only to name the file formats and the program this software is compatible with.

Licence: MPL-2.0 (see `LICENSE`), the same as AmiPro-Linux. The test file in `crates/amipro-mobile/tests/fixtures/` is hand-written and synthetic; no Ami Pro software or private documents are in this repository.

- `crates/amipro-mobile/` the Rust facade: bytes in, paragraphs and character spans out (UTF-16 offsets), edits in, bytes out. `cargo test` runs on the host.
- `android/` Kotlin + Compose app (`app.amipro.editor`, debug build `app.amipro.editor.debug` labelled "AmiPro (test)").
- Open with (VIEW/EDIT for .sam), Save a copy, password prompt, unsaved-edit guard: see `docs/STATUS.md`.
- `docs/FEASIBILITY.md` why this works; `docs/STATUS.md` where it stands and what is next.

## Build
```sh
ln -s ~/AmiPro-Linux external/AmiPro-Linux   # or: git clone https://github.com/nchiker/AmiPro-Linux external/AmiPro-Linux
source env.sh                                 # JDK 21 + Android SDK/NDK shared with the Filen project
cargo test                                    # facade tests on the host
./build-rust.sh                               # arm64 .so into android/app/src/main/jniLibs + Kotlin bindings
cd android && ./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```
`./gradlew :app:assembleRelease` signs with `~/APK-Keys/amipro/keystore.properties` (outside the repo; without it the debug key is used). Always try the RELEASE build on the test phone: R8 only affects release builds.
