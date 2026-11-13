# STATUS (2026-11-13, updated after Open with + Save a copy)

## Built and verified (spike, feasibility steps 1-2)
- Rust facade `amipro-mobile`: open `.SAM` / native `.amipro` (password-protected SAM too), paragraphs with resolved style look and spans, replace text, split (Enter) and join (Backspace) paragraphs, undo/redo, save in the format opened (protected stays protected), SAM loss list. 7 host tests pass.
- Android app: open through the system picker, edit in place, Save (warns first if SAM would lose something), first save keeps the untouched original in app-private `files/originals/`, discard prompt on close, password prompt, dark/light.
- On the Pixel 4a (synthetic file only), debug AND release (minified) builds: opened, typed (fast `adb input` typing too), Enter split, Backspace-at-start join, Save. The saved SAM has the edit in the body and the same frame/table/layout sections as the original; extra sections are defaults the writer always adds.
- Measured: arm64 library 3.9 MB; release APK 6.4 MB.
- Found and fixed: a typing race (the field adopted the core's echo of an older keystroke). Fields are now the source of truth while typing and reload from the core only after split/join/undo/redo (`EditorViewModel.epoch`).

## Added: Open with and Save a copy (verified on the 4a, debug and release)
- **Open with**: VIEW/EDIT filters for `.sam`/`.SAM`/`.Sam`/`.amipro` names and for the types `application/x-amipro-document` (the Linux app's type) and `application/octet-stream` (what providers report for unknown extensions). A path pattern needs `<data android:host="*"/>` or Android ignores it and the app is offered for EVERY file (caught on the phone). Tapping a file in Files by Google opens it in AmiPro.
- **Read-only opens**: if the system gave no write grant, the banner says so, the Save icon becomes "Save a copy", and nothing can overwrite the source.
- **Save a copy**: More menu or the Save icon (read-only opens): system "create file" screen with `<name> copy.sam`, loss list first for SAM, then the open document becomes the copy and the source is never touched. Plain Save on that copy works.
- **Unsaved-edit guard**: opening another file (picker or Open with) while edited asks "Discard your changes?" (Keep editing / Discard and open).
- **State is process-wide** (`AmiProApp.editor`), not per activity: file managers can start the app in a second window, which used to see an empty document and skip the guard.
- **Note banner**: only when the document really has objects or non-header/footer frames (header and footer are frames in the model; section names such as `lay` are on nearly every SAM).
- Tested with COPIES of reference samples (`sample A`, `sample B`, `sample C`, `sample D` 97 KB, `sample E`, `sample F` protected: password prompt and wrong-password retry work) in `Download/AmiProTest/ref/` on the 4a. The reference set on the owner's drive (`~/AmiPro-Reference/Charlie-Sample`) was only read: same hash before and after, files mode 400. Copies on the phone were checked unchanged; edited copies made by Save a copy sit in the phone's `Download/` (`sample C copy.sam`, `sample D copy.sam`). A saved copy differs from its original only by the page cache `[pg]` (dropped by the writer) and, for some files, a default `[fldnames]` it adds.
- Not tested: an EDIT intent with a write grant from another app (the shell cannot grant URIs); a plain Save over a file opened from Files (VIEW is read-only by design).

## Added: formatting controls (verified on the 4a, debug)
- A bar above the keyboard (`FormatBar.kt`): **B I U S**, **A-** size **A+** (1 pt steps, 6-72), and the paragraph **style** menu (the document's own styles). Buttons show the state of the selection (on only if on for every character) or of the character before the caret.
- Over a selection the change is made at once; **at a caret** the choice is pending and applies to the next text typed (Ami Pro's behaviour) until the caret is moved. A pending choice and the typing it formats are two undo steps.
- The rules are the Linux core's (`Command::SetCharacterAttribute`, `SetFontParts`, `apply_style`; style-aware: switching off something the style has writes an explicit off, switching on what the style has clears the override).
- Limits: a selection stays inside one paragraph (each paragraph is its own field); no font face or colour picker yet; no alignment, indents or spacing.
- Checked with the Linux version: the phone-saved file (bold, italic, underline, 13 pt, Heading style) was run through AmiPro-Linux's own `sam_round_trip` and `loss_check` examples: sections equal 103 of 103, read back equal, "Save As would list nothing". The saved text uses Ami Pro's markup (`<+!>` bold, `<+">` italic, `<+#>` underline, `<:f260,,>` size, `@Heading@`). The VM is not used any more (owner's decision, 2026-11-13).
- The Linux GUI cannot be given a file from here: it takes no file argument and File > Open goes through the desktop's file portal, which belongs to the real session, not a private display. The project's own headless render (`page_surface_screenshots`, `AMIPRO_PERF_SAM`) is used for a picture.

## Not done
- No New document.
- Objects (U+FFFC) show as a box and can be deleted by typing over them (nothing protects them yet).
- Unchecked: real soft-keyboard Backspace at the start of a paragraph (adb sends a hardware key); selection/IME edge cases; very large documents.
- Phone-saved files are checked with the Linux version, not the Ami Pro 3.1 VM (owner's decision).
- Save a copy always writes the format that was opened (no SAM -> native conversion choice).
- Key Vault backup of `~/APK-Keys/amipro`: made by the owner 2026-11-13.

## Next (from FEASIBILITY.md)
Step 3 (formatting) is built; left: protect objects, New document, face/colour, alignment, cross-paragraph selection. Then the VM check of phone-saved files and the owner's own use.
