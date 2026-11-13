# STATUS (2026-11-13)

## Built and verified (spike, feasibility steps 1-2)
- Rust facade `amipro-mobile`: open `.SAM` / native `.amipro` (password-protected SAM too), paragraphs with resolved style look and spans, replace text, split (Enter) and join (Backspace) paragraphs, undo/redo, save in the format opened (protected stays protected), SAM loss list. 7 host tests pass.
- Android app: open through the system picker, edit in place, Save (warns first if SAM would lose something), first save keeps the untouched original in app-private `files/originals/`, discard prompt on close, password prompt, dark/light.
- On the Pixel 4a (synthetic file only), debug AND release (minified) builds: opened, typed (fast `adb input` typing too), Enter split, Backspace-at-start join, Save. The saved SAM has the edit in the body and the same frame/table/layout sections as the original; extra sections are defaults the writer always adds.
- Measured: arm64 library 3.9 MB; release APK 6.4 MB.
- Found and fixed: a typing race (the field adopted the core's echo of an older keystroke). Fields are now the source of truth while typing and reload from the core only after split/join/undo/redo (`EditorViewModel.epoch`).

## Not done
- No New document; no way to open from other apps (Open with) or Save a copy elsewhere.
- No formatting controls (bold/italic/size/style picker); spans and styles are shown, not edited.
- Objects (U+FFFC) show as a box and can be deleted by typing over them (nothing protects them yet).
- Unchecked: real soft-keyboard Backspace at the start of a paragraph (adb sends a hardware key); selection/IME edge cases; very large documents.
- Not yet checked in the Ami Pro 3.1 VM (`~/AmiPro-Reference/vm-lab`): a phone-saved file opening there.
- Key Vault backup of `~/APK-Keys/amipro` not made.

## Next (from FEASIBILITY.md)
Step 3: run-level formatting and style picker, `Open with` / Save a copy, protect objects. Then the VM check of phone-saved files and the owner's own use.
