# AmiPro-APK notes for Claude

Read `docs/STATUS.md` first. This folder is its OWN git repo (branch `main`), not part of `~/Projects`.

- Core logic lives in `~/AmiPro-Linux` (reached through the git-ignored link `external/AmiPro-Linux`). Do not edit it from here; add what the phone needs to `crates/amipro-mobile`.
- `external/AmiPro-Linux` is under this workspace, so `Cargo.toml` here defines `[workspace.package]` (edition, license, rust-version) that its crates inherit. Keep it equal to AmiPro-Linux's.
- Signing key: `~/APK-Keys/amipro/` (owner's rule: all keys go in `~/APK-Keys/`). Never commit it. It is not yet in a Key Vault backup (`~/Projects/APK-Keys/keyvault pack`, owner at the keyboard).
- Test phone: Pixel 4a `<test-phone-serial>` (always `adb -s`). The owner's real phone (Pixel 10a) is hands off. Test files must be synthetic (`crates/amipro-mobile/tests/fixtures/`); the owner's corpus is private.
- Generated Kotlin enum constants are UPPER_CASE (`FileKind.SAM`); error fields avoid the name `message`.
- Shell heredocs here turn `\uXXXX` into literal characters: write such constants as `Char(0xFFFC)`.
