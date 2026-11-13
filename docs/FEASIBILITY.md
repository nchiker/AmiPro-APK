# AmiPro-APK feasibility (2026-11-13)

Question: can there be a simple Android app that reads and writes simple Ami Pro files?

**Verdict: yes, and the risky part is already done.** The file format work lives in `~/AmiPro-Linux` as plain Rust. The phone app is mostly a new user interface on top of it.

## What exists (checked today)
- `~/AmiPro-Linux` is a Rust workspace. `document`, `ami-formats`, `editing` and `layout` have **no third-party dependencies**. Only `exchange` (DOCX/ODT/RTF/Markdown) pulls in `pulldown-cmark`.
- `ami-formats` reads SAM and STY, including password-protected files (`import_sam`, `import_sam_path_with_password`) and writes SAM (`sam_document`, `SamOptions`).
- Recorded evidence (`docs/COMPATIBILITY.md`): 417 of 417 of the owner's documents read back equal after a write; Ami Pro 3.1 opens the app's output in the VM. Anything the model cannot hold is kept raw and written back, so editing text in a complex document does not destroy the rest.
- **Cross-compile check:** `cargo +nightly-2026-02-20 check --target aarch64-linux-android -p amipro-document -p amipro-formats -p amipro-editing -p amipro-layout` finished clean in 4.6 s with no source changes. Only the nightly toolchain has the Android targets installed; the `stable` and `1.99.0` toolchains do not (`rustup target add aarch64-linux-android` fixes that).
- Android tooling is already on this machine from Filen and Financial: JDK 21, Android SDK, NDK 29, `cargo-ndk` (`source ~/Projects/Filen/env.sh`). Filen already uses the Rust + UniFFI + Kotlin/Compose pattern.

## Proposed shape
- A small Rust facade crate (for example `amipro-mobile`) over `amipro-document` + `amipro-formats` + `amipro-editing`, exposed to Kotlin through UniFFI as Filen does. It would offer: open (bytes, optional password), a paragraph list (text, style name, character runs), apply an edit, save to SAM bytes, and the loss list from `sam_losses`.
- Kotlin + Compose + Material 3 app: system file picker (SAF) in and out, a reflowed text editor, a few formatting buttons. Release-signed with a key from `~/APK-Keys`.
- Use the editing crate's commands for edits rather than changing the document directly, so runs and marks stay consistent.

## Scope that stays simple
In: open and save `.SAM` (and the lossless native `.amipro`); paragraphs, styles by name, bold/italic/underline, face and size, plain tables as read-only or simple cell text.
Shown as placeholders, kept and written back untouched: frames, pictures, charts, equations, OLE objects, fields, notes.
Out for now: page-faithful layout, printing and PDF, spell check, DOCX/ODT/RTF export, macros.

## Real risks, in order
1. **The editor surface.** Compose has no rich-text editor. `BasicTextField` with `AnnotatedString` can show runs; mapping edits back to paragraphs and runs is the main new code. Largest piece of work.
2. **Not page-faithful on a phone.** The `layout` crate wants system fonts; a phone view would be reflowed text, not Ami Pro pages. Fonts such as Times New Roman or Arial would map to Android's serif and sans.
3. **Editing the model's text safely.** Whether the editing crate's API suits a touch editor (selection, IME composition) has not been tried.
4. **Verification.** Reuse the core's tests and the Ami Pro 3.1 VM in `~/AmiPro-Reference/vm-lab`; the corpus is private, so any shared test data must be synthetic.
5. **Repo wiring.** The app needs the Linux crates by path or git dependency, or a shared workspace. Decide before starting.

## Not measured
APK size (expect a few MB with the Rust library per ABI, but not built), real-device behaviour, Rust-to-Kotlin call overhead on large documents, and whether the editing API is easy to drive from a touch editor.

## Suggested steps
1. Spike: facade crate, `cargo-ndk` build, a Compose screen that opens a sample SAM and shows its text on the Pixel 4a (synthetic file only).
2. Edit text and save; round-trip check on synthetic documents and in the VM.
3. Run-level formatting, style picker, loss warning on Save, `.bak` on first save as the Linux app does.
4. Release build, signed APK, release-build check on the 4a before the owner's phone.
