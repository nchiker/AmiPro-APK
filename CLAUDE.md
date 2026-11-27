# AmiPro-APK notes for Claude

Read `docs/STATUS.md` first. This folder is its OWN git repo (branch `main`), not part of `~/Projects`.

- Core logic lives in `~/AmiPro-Linux` (reached through the git-ignored link `external/AmiPro-Linux`). Do not edit it from here; add what the phone needs to `crates/amipro-mobile`.
- `external/AmiPro-Linux` is under this workspace, so `Cargo.toml` here defines `[workspace.package]` (edition, license, rust-version) that its crates inherit. Keep it equal to AmiPro-Linux's.
- Signing key: `~/APK-Keys/amipro/` (owner's rule: all keys go in `~/APK-Keys/`). Never commit it. It is in the Key Vault backups since 2026-11-13 (see docs/STATUS.md; `keyvault status` lists it). Re-run `~/Projects/APK-Keys/keyvault pack` + verify only when the key changes (owner at the keyboard).
- Test phone: Pixel 4a `<test-phone-serial>` (always `adb -s`). The owner's real phone (Pixel 10a) is hands off. Test files must be synthetic (`crates/amipro-mobile/tests/fixtures/`); the owner's corpus is private.
- Generated Kotlin enum constants are UPPER_CASE (`FileKind.SAM`); error fields avoid the name `message`.
- Shell heredocs here turn `\uXXXX` into literal characters: write such constants as `Char(0xFFFC)`.
- Checking phone-saved files: use the Linux version, not the VM (owner's rule). `cargo run --release --example sam_round_trip -p amipro-formats -- DIR` and `--example loss_check` from `external/AmiPro-Linux` (set `CARGO_TARGET_DIR` to a scratch dir), and the headless page render `page_surface_screenshots` with `AMIPRO_SCREENSHOT_DIR` + `AMIPRO_PERF_SAM`. Never run `amipro-linux` with `--help` (it opens a window) and never `pkill` by name; stop only processes you started, by PID.
