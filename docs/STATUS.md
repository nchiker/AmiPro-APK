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
- **Known SAM limit (the Linux core's own, reported by its loss check):** switching bold OFF inside a style that is bold (e.g. over a Heading) cannot be written to SAM and read back. The phone then shows "This format cannot keep: Character formatting in 1 paragraph" before saving, exactly as the Linux app's Save As would; Cancel keeps the file untouched. Italic, underline, strikethrough, size, bold on, and style changes are all exact (test `only_bold_off_over_a_bold_style_is_reported_as_a_loss`).
- Limits: a selection stays inside one paragraph (each paragraph is its own field); no font face or colour picker yet; no alignment, indents or spacing.
- Checked with the Linux version: the phone-saved file (bold, italic, underline, 13 pt, Heading style) was run through AmiPro-Linux's own `sam_round_trip` and `loss_check` examples: sections equal 103 of 103, read back equal, "Save As would list nothing". The saved text uses Ami Pro's markup (`<+!>` bold, `<+">` italic, `<+#>` underline, `<:f260,,>` size, `@Heading@`). The VM is not used any more (owner's decision, 2026-11-13).
- The Linux GUI cannot be given a file from here: it takes no file argument and File > Open goes through the desktop's file portal, which belongs to the real session, not a private display. The project's own headless render (`page_surface_screenshots`, `AMIPRO_PERF_SAM`; first build ~5.5 min, then seconds) is used for a picture: a phone-saved file with "Pages" bold, italic, underlined at 18 pt rendered correctly in the Linux page surface with its frames, table and picture frame intact; `sam_round_trip` on it: read back equal, sections 103 of 103, Save As would list nothing.

## Added: New document (verified on the 4a, debug and release)
- **New document** on the start screen and in the More menu. It is the Linux app's own starting document (`Document::default()`: Body Text, Heading 1, Heading 2, standard page layout, Doc Info stamped now), saved as SAM. It opens with "Untitled.sam", a "Start typing" hint and the keyboard up.
- It has no file yet, so Save asks where (system create-file screen, suggested `Untitled.sam`); after that the document is that file and Save writes straight over it (checked: a second Save changed the file).
- New document with unsaved edits asks "Discard your changes?" first (Keep editing / Discard and start new).
- Enter at the end of a heading now starts Body Text in phone-made documents (the core's Next style, which its default headings had off; turned on for Heading styles in `new_document`). Enter in the middle keeps the heading. Opened documents keep their own styles, so a document whose heading has no next style still repeats it. The setting is written to the SAM and survives save and reopen (Linux audit: read back equal, 51 of 51 sections, no losses).
- Checked with the Linux version on a phone-created file (Heading 1 title + body with a bold phrase): `sam_round_trip` read back equal, sections 51 of 51, Save As would list nothing; the Linux page render shows Heading 1 in Arial 20 and Times body with the bold phrase, page 1 of 1. Tests: `a_new_document_saves_as_sam_and_reads_back`, `an_empty_new_document_saves_and_reopens`.

## Added: objects are protected (verified on the 4a, debug and release)
- An edit that would delete or overwrite an object (a picture, frame anchor or table anchor, shown as a box) is refused: the text snaps back, the caret stays, and a message says "That is a picture, frame or table AmiPro cannot show. It is kept; edit the text around it." Refused: Backspace over a box, typing over a selection that covers one (including select all), cut, paste over. Allowed: typing beside a box, deleting other text, Enter, joining paragraphs. The rule is enforced in the Rust wrapper (`AmiError::ProtectedObject`), not only in the screen.
- The format bar skips object characters when it reads the format at a caret.
- Audit over the owner's whole reference set (read only; `reference_audit`, opt-in with `AMIPRO_REF_DIR`; counts only): 424 files, 417 opened, 1 password-protected; 222 had objects; all 222 refused the delete, accepted typing beside the box, and kept every object through edit, save and reopen; 0 mismatches. The reference folder's hash was identical afterwards.
- Cost: an object cannot be deleted from the phone at all (a paragraph that is only a box stays). A deliberate "delete object" action is a possible later addition.

## Added: recent files on the start screen (builds; NOT yet run on the phone)
- Under Open and New the start screen lists up to 8 recent documents (name, "5 minutes ago", a remove button). Tapping one opens it (password prompt and the unsaved-edit guard work as for any open). `RecentFiles.kt` keeps the list in app-private preferences and holds a lasting SAF grant for each file.
- A file is listed after a successful open or Save a copy / first save of a new document. A file another app only handed over ("Open with") is listed only if Android lets us keep its grant; otherwise it is skipped rather than shown and failing later. A listed file that was deleted or lost its grant is dropped from the list when tapped.
- Debug build compiles; the test phone was not connected, so on-device checks are still to do.
- (done, see the Print section) ~~RESUME HERE when the 4a is connected~~ (`adb -s <serial>`, synthetic/copied files only): install `android/app/build/outputs/apk/debug/app-debug.apk`, then check (1) open a file, force-stop the app, relaunch: it is listed and reopens; (2) the remove button; (3) delete a listed file in Files, tap it: message and row dropped; (4) a protected file from the list asks for the password; (5) Save a copy and a new document's first save appear in the list; (6) "Open with" from Files: note whether it is listed. Then try the RELEASE build (R8), update this section, and commit.

## Added: Print and Check for updates (verified on the 4a, debug; the install step is not)
- **Print...** (More menu): `Printing.kt`, a `PrintDocumentAdapter` that lays the paragraphs out with `StaticLayout` on the paper the system print dialog picks (0.75 in margins, line-by-line pagination, portrait/landscape; the dialog also gives "Save as PDF"). It prints what the phone shows: character formatting, paragraph look, alignment; objects print as the same box the screen shows (pictures, frames and tables are not rendered, as on screen). Page layout from the SAM (margins, columns, headers) is not used yet.
- **Check for updates** (start screen and More menu): `Updates.kt`. Manual only. Asks GitHub `releases/latest` of `nchiker/AmiPro-APK`, shows the notes, **Download** (HTTPS, streamed to `cache/update/`, SHA-256 checked against GitHub's asset digest when given), checks the file is this package, a higher versionCode and signed with the same certificate as the installed app, then **Install** hands it to `PackageInstaller` (Android's own confirmation; `InstallResultReceiver` reports the outcome). First install asks the person to allow "install unknown apps" for AmiPro (opens that Settings page). A debug build (`.debug` id) cannot take a release update; the dialog says why.
- **Manifest change (decision to confirm):** the app now declares `INTERNET` and `REQUEST_INSTALL_PACKAGES`; previously it had no permissions and the release notes said "no network access". The network is used only after a tap on Check for updates / Download. README and release notes need that sentence changed before the next release. A new release needs a higher `versionCode` (now 1) and a signed APK on GitHub.
- Model for this: Filen's `UpdateChecker` (GitHub check) and Recipe-Stack's `PackageInstallerApk` (install session).
- **Checked on the 4a (2026-11-17, debug):** recent files: listed after an open, still there after force-stop, reopens, remove works, a deleted file gives "That file is no longer available" and the row is dropped. Print: NOTEBOOK.SAM (97 KB) previews as 4 Letter pages with bold headings and serif body, same text as the screen. Check for updates: reaches GitHub and says "You have the newest version (0.1.0)".
- **Not checked:** Download and Install (needs a published release with a higher versionCode, signed with the release key; the debug build could only test the refusal), the unknown-sources page, Save as PDF to a file, landscape, the protected-file-from-recents prompt, and the release (R8) build. Print has no gap between paragraphs yet (the screen has a small one).

## Added: version 0.2.0, start screen, theme, print spacing (verified on the 4a, debug)
- Version 0.2.0 (versionCode 2) in `android/app/build.gradle.kts`. Not built as a release or published; the release notes and "no network access" line in `dist/RELEASE_NOTES.md` need rewriting for it (network only on the update check).
- **Start screen** restyled after the Stack app: Open and New as action rows with a tinted icon disc, Recent list (empty-state line), Appearance and Check for updates rows at the bottom. Fixed Stack-like blue palette in light and dark (no wallpaper colours any more).
- **Appearance** (start screen and More menu): Follow the phone (default) / Light / Dark, kept in app preferences (`AmiProApp.themeChoice`). Window and status/navigation bars follow the choice (a Light override on a dark phone first showed invisible status icons; fixed). Checked: Light override on the dark phone, dark and light start screens, editor in light; default reset to Follow the phone.
- **Print** puts a 5 pt gap between paragraphs (checked in the preview: still 4 pages of NOTEBOOK.SAM).

## Not done
- Unchecked: real soft-keyboard Backspace at the start of a paragraph (adb sends a hardware key); selection/IME edge cases; very large documents.
- Phone-saved files are checked with the Linux version, not the Ami Pro 3.1 VM (owner's decision).
- Save a copy always writes the format that was opened (no SAM -> native conversion choice).
- Key Vault backup of `~/APK-Keys/amipro`: made by the owner 2026-11-13.

## Next (from FEASIBILITY.md)
Steps 3 (formatting) and New document are built; left: face/colour, alignment, cross-paragraph selection, SAM -> native choice on Save a copy. Then the VM check of phone-saved files and the owner's own use.
## Not done
- Unchecked: real soft-keyboard Backspace at the start of a paragraph (adb sends a hardware key); selection/IME edge cases; very large documents.
- Phone-saved files are checked with the Linux version, not the Ami Pro 3.1 VM (owner's decision).
- Save a copy always writes the format that was opened (no SAM -> native conversion choice).
- Key Vault backup of `~/APK-Keys/amipro`: made by the owner 2026-11-13.

## Next (from FEASIBILITY.md)
Steps 3 (formatting) and New document are built; left: face/colour, alignment, cross-paragraph selection, SAM -> native choice on Save a copy. Then the VM check of phone-saved files and the owner's own use.
