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
- (Superseded: selections now cross paragraphs and there are face, colour, alignment, indent and spacing controls; see the section on editing across paragraphs.)
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

## Released: 0.2.0, and the in-app update tested end to end (2026-11-17)
- **v0.2.0 is published** on GitHub (`AmiPro-0.2.0.apk` + `SHA256SUMS`, notes in `dist/RELEASE_NOTES_0.2.0.md`), same signing certificate as 0.1.0 (`9a387c70...`), SHA-256 `2abf612c...`. `main` and tag `v0.2.0` pushed.
- **Update test on the 4a (release build):** installed 0.2.0 by adb, published a throwaway v0.2.1 (versionCode 3), then in the app: Check for updates found 0.2.1 with its notes; Download passed the checksum and signature checks; Install opened Android's "allow from this source" page for AmiPro, then Android's "Do you want to update this app?"; Cancel returned to Install; Update installed 0.2.1 over 0.2.0 with data kept (recents still there). Google Play Protect asked for a scan on the update; "Install without scanning" was used (nothing was uploaded to Google). Expect that prompt on real phones too.
- The throwaway v0.2.1 release and tag were deleted afterwards; the 4a now runs 0.2.1 (versionCode 3, which cannot be installed under: reinstalling 0.2.0 on it needs an uninstall first). The "install unknown apps" permission for AmiPro on the 4a was reset. Not tested: an update that fails the signature check, a dropped connection mid-download.

## Added: editing across paragraphs, layout, fonts, find, objects, better print (verified on the 4a, debug; 2026-11-17)
- **One text field for the whole document** (paragraphs joined by a newline), so selection handles, select all, copy, cut, paste and typing over a selection work across paragraphs. Every change becomes one `replace_range` between two (paragraph, offset) positions in the core (newline = split, a removed newline = join). The field is the source of truth while typing and is re-read from the core only after undo/redo, a refused edit or Replace all (`EditorViewModel.epoch`). A pasted object marker is stripped. A selection that holds a picture/frame/table anchor is still refused (`ProtectedObject`). Paragraph look (face, size, alignment, indents, line spacing, spans) is drawn by `DocumentTransformation` over the one text; paragraph spacing above/below (a style property) is shown only as a small gap.
- **Formatting across paragraphs:** B I U S, size, face and colour apply over any selection (facade `set_attribute_between`, `set_font_between`, `format_state_between`); at a caret they are pending for the next typed text as before.
- **Paragraph layout controls** (second row of the format bar): alignment (left, centre, right, justify), indent -/+ (quarter inch), line spacing (single, 1.5, double, as the style). Paragraph alignment and indents set directly on a paragraph are now shown (they used to be ignored; only the style's was read). A paragraph's own 1.5/double spacing is stored by SAM as the kind only (height 0 = the style's), so the phone writes it that way. Space above/below is a style property and has no control.
- **Font and colour:** face menu (the document's faces, then Times New Roman, Arial, Courier New, Helvetica, Georgia, Verdana) and an 11-colour palette. The phone draws unknown faces as serif, sans or mono.
- **Find and replace** (More menu): highlights every match, step with the arrows, Match case, Whole word, Replace, Replace all (one undo step). Checked on NOTEBOOK.SAM: 37 matches, replace all 37, one Undo restores them.
- **Pictures, frames and tables:** More > Pictures and tables lists them; View shows a bitmap picture (BMP/DIB, PNG, JPEG, GIF, TIFF, or Ami Pro's own screen image of it), a table's text as a grid, or a text frame's text; Delete removes the object and what it owns (one undo step, confirmed first). A bar with the same View/Delete appears when the caret touches an object. Not drawn: Windows metafiles, Ami Pro drawings, charts, equations (the box stays and prints as a labelled box). A picture/table/frame stays where it is anchored, not at its page position.
- **Print now uses the page layout:** the document's own paper (offered as the dialog's starting size), margins, header and footer with page numbers (and "from second page"), page breaks, per-paragraph face/size/alignment/indents/line spacing, pictures, tables and text frames after the paragraph that anchors them. Checked in the preview: NOTEBOOK.SAM prints as 8 pages with its cover page, header "Innovative Computer Solutions" and page numbers; PICPASTE.SAM prints its three bitmaps. Not done: columns, frames placed at fixed spots on a page, footnotes.
- **Checked with the Linux version:** a phone-made file (3 paragraphs, bold, centred, 0.25 in indent, Courier New) went through `sam_round_trip` (main text read back equal, sections 51 of 51) and `loss_check` (no losses). Facade tests: 26 (cross-paragraph replace/copy/format, layout and spacing survive save, face and colour survive save, find/replace one undo step, objects list/delete/undo, picture decoding, page setup). Synthetic picture documents from the Linux repo's test scenarios (not the owner's corpus) were used for the picture checks: PICPASTE.SAM (4 bitmaps, all drawn); charts, drawings and equations are not.
- **Release build (R8) checked on the 4a:** installed (8.7 MB) after uninstalling the test copy of 0.2.1, opened NOTEBOOK.SAM (97 KB) and typed: median frame 17 ms, 90th percentile 34 ms (the debug build is 70 / 150 ms, so judge typing speed on release). Backspace at a paragraph start joins the paragraphs (adb hardware key).
- **Not checked on the phone:** Save as PDF to a file, landscape, a real soft-keyboard Backspace, picking a colour from the palette (covered by a facade test), documents much larger than NOTEBOOK.SAM.
- Released as 0.3.0 (versionCode 3) on 2026-11-17; SHA-256 `747a4bb8...`; same signing certificate.

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

## Stack link, AmiPro side of slice 2 (built 2026-11-21, version 0.4.0)
Built: `AmiDoc.plain_text()` (body + table cells + frame text), preview PDF via `DocumentPrintAdapter.writePdf`, More > Share with Stack
(`StackLink.kt`, `EditorViewModel.shareWithStack`), `LinkMap` (uri to UUID, app-private), re-publish after each save of a shared file,
provider `<applicationId>.stacklink` with `open` (`StackLinkProvider.kt`, Stack release certificate only), `<queries>` for Stack.
Protected files ask before sharing and set `protected: true`. Stack's package is `-PstackPackage=` (default `org.stackdocs.app`); the
checks build on the 4a is `org.stackdocs.app.checks.linkcontract` and is signed with the same release key.
Checked on the Pixel 4a (release build, synthetic file): table/frame words found by Stack search; edit+save changed Stack's text, preview
(valid PDF) and hash; Stack's Open in Ami Pro opened the file. Also checked on the 4a: the password warning (Cancel shares nothing; Share anyway sets `protected`, Stack labels it), and Stack's Open after a rename (MediaStore rename keeps the saf uri). Fixture `tests/fixtures/synthetic-protected.sam` (password `test`). NOT device-checked: Stack not installed (one early `installed()` check), a large file.
Real-package check DONE (2026-11-21): default-package AmiPro 0.4.0 (release cert 9a387c70...868a) on the 4a against real Stack 0.17.0: share accepted, listed as "Linked · Ami Pro · protected", Open in Ami Pro launched AmiPro. The 4a now has the default build installed. Remaining before publishing: the owner's go-ahead. Not done: share from recent-file rows; `stat`/`refresh`/`delete` (slice 4). A shipping APK must be built
WITHOUT `-PstackPackage` (the one on the 4a points at the checks build). Code review (2026-11-21) fixed: auto-publish now snapshots the saved document; a failed first share no longer marks the file shared; unique preview temp file; cancellation and busy handling. Left as known nits: hash/text can differ if the file changes outside the app while open; `open` trusts the uri (a file re-created at the same uri opens as the old link); title/text limits count UTF-16 units (Stack may count code points). 0.4.0 published as v0.4.0.
Medicine-list stand-in (synthetic, `tests/fixtures/synthetic-medicine-list.sam`) checked on the 4a against real Stack 0.17.0: search for a word only in the table finds it, the preview shows the table. The table cell text is laid out as the print code does (the fixture's table geometry is odd: first cell sits on its own row). A real list of the owner's has NOT been tried (kept private by choice).
Original plan below.

## Planned: Stack link (agreed direction 2026-11-21)
Stack's side of slice 2 is built and tested. **Start from `docs/STACK_LINK_HANDOFF.md`** (protocol, record format, provider, rules, checks).
Stack becomes the one place to find every document; Ami Pro keeps the editable files and tells Stack about them.
Contract draft: `~/Projects/Stack/docs/STACK_LINK_CONTRACT.md` (read it first). Work that lands in THIS project:
1. **Text for search.** Facade function returning plain text of the main text plus every table cell and text frame
   (and later headers, footers, notes). Audit (`reference_object_audit`, 424 reference files, counts only): 34% of
   documents have a table, 14% have more text in tables than in the body, 0 tables unreadable. A body-only index
   is not acceptable.
2. **Preview PDF.** Reuse the print layout (`Printing.kt`) to write a PDF without the print dialog. Decided 2026-11-21:
   drawings, charts, equations and metafiles (15% of reference files) are only labelled in the preview and not
   searched (the owner says they hold nothing Stack needs). Columns, frames fixed on the page and footnotes wait
   for a real document that needs them.
3. **Share with Stack.** An action in the More menu and on the recent-files rows. Builds the record (title, mime,
   locators, modified, revision, text, preview), mints and keeps a `link_id` map in app-private storage, sends it
   to Stack's provider, and re-sends after every save.
4. **Provider `<package>.stacklink`** with `stat`, `open`, `refresh`, `delete`; callers checked by package and
   pinned certificate. Opening a file from Stack goes through the existing Open with path.
5. **Lifecycle.** Emit `moved` and `removed` for files it changes; rescan known folders by hash when `stat` fails.
6. **Manifest and release.** New provider and a package query for Stack; a decision for the owner on each permission;
   version bump, signed release, release notes updated.
Order and dependencies are the slices table in the contract. Do none of this before the contract is agreed.

## Stack link slice 4, AmiPro side (code written 2026-11-21, NOT yet tested on a device, not released)
Built to the contract Stack confirmed: provider `stat` (`exists`, `hash`, `modified`, `locators`; `exists=false` only when the
system says the document is gone, otherwise `error=unreachable`), `refresh` (headless re-publish; `protected`, `unreachable`,
`not_found`), and `removed` sent to Stack (`reason` "deleted") by a sweep at app start and when opening a file fails with
file-not-found; sent once per link until the file is published again. Code: `StackSync` in `StackLink.kt`.
Device check (2026-11-21, against Stack's TEST build): stat, refresh and the protected refusal passed. Deleting a Downloads file
revokes AmiPro's access (SecurityException, and the persisted grant is gone too), so a deletion is not detectable there: the record
stays "unreachable" and the user removes it by hand in Stack. The owner chose to stay cautious (no guessing from lost access).
`removed` fires only when the provider says file-not-found with no row left. Not done: reason "trashed", `moved`, `unshared`, `delete`. Waiting for Stack's slice 4 build on the test phone to check end to end.

## Slice 4 end-to-end against Stack HEAD (2026-11-22, test phone 0A121JEC203530 only)
Release-signed AmiPro 0.5.0 built with `-PstackPackage=org.stackdocs.app.checks.linkcontract` (needs a JDK with javac, e.g. `JAVA_HOME=~/.local/share/filen-jdk21`) against Stack's checks build (schema 15). Synthetic document made in the app (`e2es4.sam`, saved through the create-file screen).
- **Share**: listed in Stack as "Linked document - Ami Pro", text searchable.
- **stat**: "Check linked documents" left it live; after the file was replaced from outside (adb push, new hash) Stack showed "This document changed in Ami Pro since Stack saved it" + "Update saved copy".
- **refresh**: "Update saved copy" re-published; modified date and preview updated (the first preview had been "could not be drawn", the refreshed one drew).
- **removed**: file deleted (adb rm), AmiPro restarted (sweep), Stack check: "Can't reach Ami Pro right now. Nothing was removed" (unreachable, not removed). Same as the 2026-11-21 finding: a deleted Downloads file revokes our grant, so a deletion is not detectable; the owner chose to stay cautious. `removed` is only sent when the provider says file-not-found. Not exercised on device.
- Left on the phone: the "e2es4" link in the Stack checks build (unreachable), AmiPro release 0.5.0 (checks-package build) installed.


## Slice 5, AmiPro side (2026-11-22, test phone, Filen Files test build 0.4.7 with the pending-id fix)
Built (`27ce68c`): for a document whose URI is Filen Files' (`app.filenfiles.documents`), Ami Pro calls `locate` on `app.filenfiles.stacklink` and puts `{filen, uuid, path}` first in the published locators, then the `saf` one; `stat` answers the same list. The sweep at app start asks `locate` again and sends `moved` if the path differs from the one last told to Stack. Manifest: one `<queries>` entry for `app.filenfiles`; no permission added.
- **Checked on the phone:** a synthetic document saved into Filen (through Filen's provider), reopened, shared: Stack's record holds `[filen{uuid 0ff77489..., path}, saf]`. The pinned release `app.amipro.editor` is accepted by `locate`.
- **Filen bug found and fixed on Filen's side:** a file created through the provider got a `filen-pending:` id that never resolved ("This file was already written"). Fixed in Filen's build; the pending URI still sits in our `saf` locator and in the link map (it resolves).
- **`moved` NOT independently proven:** after Filen renamed the file, Filen's own `moved` event had already updated Stack's path before Ami Pro's start-up sweep ran, so Stack's data was identical before and after; Ami Pro's own `moved` could not be told apart. Needs a rename made while Filen is not sending its event, or a log line, to prove it. No unit test (org.json is not available offline in JVM tests).
- Version 0.6.0 (versionCode 6) and notes are prepared in `dist/RELEASE_NOTES.md`; not built as a release, not published.

- **0.6.0 published (2026-11-22, on the owner's go-ahead):** tag `v0.6.0`, https://github.com/nchiker/AmiPro-APK/releases/tag/v0.6.0 (APK + SHA256SUMS), signed with the release key (cert 9a387c70...868a, checked with apksigner), default Stack package. Needs Filen Files 0.4.7+. The `moved` caveat above still stands.

## After 0.6.0 (local commits, NOT released, NOT yet run on a device)
- **Explanation before Android's install-unknown-apps page** (`Updates.kt`, `MainActivity.kt`): tapping Install without the permission first shows a short explanation and a Continue button; only Continue opens the Settings page. No network change.
- **Automatic restore of the link map** (`StackSync.restoreLinks`, runs at app start, once, only when the link map is empty): asks Stack's provider `list_owned` (`{contract:1, owner}` -> `records` JSON of `{link_id, title, hash, modified, state, locators[filen only]}`, agreed with the Stack session), confirms each Filen file with Filen's `lookup(uuid)` and rebuilds uri -> link_id with Stack's own ids. Filen files only; others stay for Stack's "Set up again". Needs a Stack build that has `list_owned` (Stack will say when it is on the test phone).
- **Daily update check: not built** (owner/orchestrator: skip; Stack's Companion-apps screen covers updates). **Stack certificate pin** is already a set (`StackLinkProvider.STACK_CERTIFICATES`): rotation = add one string. **Share all my documents button: dropped** in favour of the automatic restore.

## Restore test, 2026-11-22 (test phone; PARTIAL, not finished)
Stack's checks build with `list_owned` + this Ami Pro (checks package). Baseline: after `pm clear app.amipro.editor` and a Stack "Check linked documents" (Ami Pro not started), all 5 Ami Pro records went `missing`. Then Ami Pro started: `list_owned` answered with 1 record that has a filen locator (the Filen file `ami-s5b copy`, uuid 0ff77489...), the Filen `lookup` of it answered exists=false (the file was renamed and the test folder cleaned up by Filen), so nothing was restored and the records stayed `missing`. The call path (Ami Pro -> Stack `list_owned` -> Filen `lookup`) works; the rebuild of the map is NOT yet seen working.
- **Next step:** save a fresh synthetic .sam into Filen, share it, `pm clear` Ami Pro, start it, then Stack "Check linked documents": expect that record `live` again. Also prove Ami Pro's own `moved` (rename the file from Ami Pro while Filen sends no event). Log lines: `adb logcat -s StackLink`.
- Local commits since 0.6.0 (all unreleased, nothing pushed): install explanation, automatic restore, shorter UI text, Get Stack action (releases/latest), restore diagnostics. Release (0.6.1) needs the owner's go-ahead.
- Test phone state: Stack checks build links for Ami Pro are `missing`; Ami Pro data cleared.

## Restore re-test: steps (written 2026-11-23; to run on the TEST phone `0A121JEC203530` only, `adb -s`)
Needs installed: Stack's checks build `org.stackdocs.app.checks.linkcontract` WITH `list_owned` (versionCode 36 reported; confirm it answers `list_owned`), Filen Files `app.filenfiles` 0.4.7+ (code 14 reported), and Ami Pro built from this tree with `-PstackPackage=org.stackdocs.app.checks.linkcontract`, **release-signed** (Stack pins cert 9a387c70...; a debug build is refused). Release signing needs the owner's approval each time. `pm clear` only `app.amipro.editor`; do not clear or sign out of Stack or Filen.
1. In Ami Pro: New document, type a line, Save into a Filen folder (name it `restore-t1.sam`). Share with Stack (More > Share with Stack). Stack lists it "Linked document - Ami Pro".
2. `adb logcat -c`; `pm clear app.amipro.editor`; start Ami Pro (do NOT open or save anything).
3. `adb logcat -d -s StackLink`: expect `restore: N record(s)...`, `lookup <uuid> -> true`.
4. In Stack: "Check linked documents". Expect `restore-t1` **live** (not `missing`). Then Open in Ami Pro from Stack works, and a save re-publishes (same link id, no duplicate).
5. Also check the new retry rule: with Filen force-stopped/unavailable at start the log shows no `markRestoreDone`; next start restores.
6. Own `moved`: with the link live, rename `restore-t1.sam` inside Filen at a moment Filen sends no `moved` event (or read the Stack record's path before and after), restart Ami Pro; log should show Ami Pro's `moved` call, Stack's path changes. Proves the sweep, which 2026-11-22 could not.
7. Cleanup: delete only `restore-t1*` files created for the test; note the phone state here.

## Restore re-test RESULT (2026-11-23, test phone 0A121JEC203530)
Builds: Stack checks build `org.stackdocs.app.checks.linkcontract` 0.19.0 (code 36), Filen Files 0.4.7 (code 14), Ami Pro = this tree plus two log lines, **release-signed (cert 9a387c70...868a) with `-PstackPackage=org.stackdocs.app.checks.linkcontract`, installed over 0.6.0 with `adb install -r`** (owner approved the signing for this test only).
- **Restore works, with one extra step.** Fresh `restore-t1.sam` saved in Filen ("Filen Files link test (safe to delete)"), shared (Stack: "Linked document - Ami Pro"). `pm clear app.amipro.editor`, start: log `restore: 2 record(s) with a filen locator`, `lookup 838e3092... -> true` (the new file), `lookup 0ff77489... -> false` (old `ami-s5b copy`, gone). The map was rebuilt with Stack's own link id, but Stack's "Check linked documents" said **"can't reach"**: `pm clear` also drops Android's saved access to the Filen file ("Permission Denial: opening provider FilenDocumentsProvider ... requires ACTION_OPEN_DOCUMENT"), so `stat` cannot read it. After the file was **opened once through Ami Pro's picker** (regrants access) the check showed the record live, with no duplicate. The same will happen on a real new phone: a restored link is live only after that file is opened once. Possible improvement, not built (needs a contract decision with Stack/Filen): answer `stat` from Filen's `lookup` when there is no grant.
- **Ami Pro's own `moved`: sent; Stack's acceptance NOT isolated.** Renamed `restore-t1.sam` to `restore-t1-renamed.sam` from the system Files app with Ami Pro stopped; at the next start the log shows `moved: d93236b2-... '/Filen Files link test (safe to delete)/restore-t1.sam' -> '.../restore-t1-renamed.sam': sent`. Stack's record stayed live. Filen's installed test build also sends `moved` for a rename made through the system Files app, so Stack's record staying live could come from Filen's event; the log proves only that Ami Pro sent its call. To isolate it, rename while Filen's event is off, or read Stack's side log.
- **Retry rule** (restore not marked done when Filen gives no answer): now `StackSync.planRestore`, covered by `RestorePlanTest` (3 tests: confirmed restored and done; Filen null keeps it open; empty path falls back to Stack's). NOT exercised on the phone.
- **Nested file for Filen's trash test:** `nested-t2.sam` saved through Filen in `nested-trash-A/level-B/level-C` and shared; Stack shows it live. Filen item UUID (from `locate`) `b86073f5-1148-47d8-a709-2598687edfcf`, path `/nested-trash-A/level-B/level-C/nested-t2.sam`, **link_id `87438c99-3089-45e2-9e10-a098336f0990`**. Left in place for Filen.
- Cleanup: `restore-t1-renamed.sam` deleted from Filen (mine). Not deleted: Stack's `restore-t1` record (now unreachable; remove by hand in Stack) and the Stack records of other sessions.
- Code added: log lines in `StackSync.publish` ("publish: link ... filen uuid path -> done") and in `sendMoveIfAny` ("moved: ...").
- **Signing record:** three release-signed builds were made for this test, all with `-PstackPackage=...checks.linkcontract`: (1) from `b8cf8ef`, (2) the same plus the `moved` log line (uncommitted source), (3) plus the `publish` log line (= `0675a91`; the one on the phone). The owner's yes in the Ami Pro window was "build it with the release key for the test phone"; the two rebuilds were made under that sentence without asking again. No more signed builds without a fresh yes.
- **Logging (after `0675a91`):** names, paths, UUIDs, link ids and Stack's error text go through `StackLink.testLog`, which logs only when `STACK_PACKAGE` is not the real `org.stackdocs.app` (a checks build); exceptions log the class name only in a real build (their message can hold a document URI). The build now on the phone is from `0675a91` and still logs them; a default release logs none.
- **PHONE STATE: the flagged checks-package, release-signed build with these two log lines stays on the phone. It must NOT be used as the 0.6.1 release** (wrong Stack package; build the release without `-PstackPackage`).

## 0.6.1 readiness (2026-11-23, computer side; nothing bumped, signed, tagged or pushed)
- **Dropped from 0.6.1: answering `stat` from Filen's lookup when there is no grant.** Stack's LinkChecker treats `exists=true` without a valid hash as unreachable (no "live, unverified" state), so it cannot be expressed. Stack will only reword restored records ("Open it once in Ami Pro"). A contract change is an after-weekend owner decision. Until then a restored link is live only after its file is opened once in Ami Pro.
- **Commits since 0.6.0 and what a device has seen** (phone ran the code of `0675a91`, checks-package, release-signed):
  - `50472f6` install explanation + automatic restore: restore and the "open once" behaviour DEVICE-CHECKED (2026-11-23, at 0675a91). The install-explanation dialog: NOT checked on a device.
  - `0fff65c`, `11253dd`, `3c3843d`, `d270714` shorter text, Get Stack action: NOT checked on a device.
  - `d98b010` restore diagnostics: logs seen on device (at 0675a91).
  - `b8cf8ef` retry-restore fix: unit-tested only (`RestorePlanTest`), not on a device.
  - `0675a91` publish/moved log lines: `moved` sent, seen on device. Stack's acceptance of it not isolated.
  - `7f04682`, `6b43c92` test-only logging, restore planner, trashed handling, share-failure log: unit tests + debug build only, NEVER on a device.
- **Final-build device plan** (needs the owner's go-ahead and a signed build; test phone `0A121JEC203530`, `adb -s`, no uninstall, no `pm clear` of Stack or Filen):
  1. Bump `versionCode` 7 / `versionName` 0.6.1, build WITHOUT `-PstackPackage` (default `org.stackdocs.app`), sign with the release key, check cert 9a387c70...868a with apksigner. The phone now has versionCode 6 (the flagged checks build), so 7 installs over it with `adb install -r` (same key, higher code). Note it points at the real Stack package, so the checks-build links stop working until the checks Stack is selected again; check against the real Stack the phone has (0.19.0, `org.stackdocs.app`), or leave the install-over test to a throwaway and keep a checks build for Stack-link checks.
  2. Upgrade keeps data: recent files and link map still there after `install -r`; `logcat -s StackLink` shows no ids or paths.
  3. Install-explanation dialog: Check for updates > Install without the permission shows the explanation, Continue opens the Settings page, back returns. Reset the permission after.
  4. Get Stack: with Stack absent (or from the dialog path that offers it) opens Stack's releases/latest page; with Stack present nothing changes.
  5. Share a fresh synthetic file to Stack; edit, save; Stack updates; Open in Ami Pro works.
  6. Restore after `pm clear app.amipro.editor` (Ami Pro only): link map rebuilt, Stack shows the record only after the file is opened once. Also Filen not answering at first start, then answering: restore happens on the second start.
  7. Text check: shorter dialog text matches `docs/UI_TEXT_REVIEW.md`.
  8. Release step (owner): tag `v0.6.1`, APK + SHA256SUMS, notes from `dist/RELEASE_NOTES_0.6.1_DRAFT.md` renamed, push main and tag.
- **Plan change (2026-11-23):** `list_owned` is not in released Stack 0.19.0 (unreleased). Final-build step 6 (restore after `pm clear`) is "NOT RUN" unless Stack 0.20.0 is on the phone; it is not a failure. In the real Stack use only documents created for the test, and remove only those. The upgrade-over step runs against the real Stack (`org.stackdocs.app`), so the earlier checks-package links stop working.

## 0.6.1 device run (2026-11-23, test phone, release-signed default-package build, sha256 39f6f506..., commit b5e8359; real Stack 0.19.0 `org.stackdocs.app`)
- **PASS** install over the flagged 0.6.0-code build with `adb install -r` (6 -> 7); recent files kept (3 entries); no `StackLink` log lines at start.
- **PASS** Share a fresh synthetic file (`v061-test.sam`, Downloads) to the real Stack: listed "Linked document - Ami Pro"; edit + Save re-published (Stack search for the new word finds it); Stack's "Open in Ami Pro" opens it. No ids, paths or UUIDs in logcat for `StackLink` or the app.
- **PASS** Check for updates: "You have the newest version (0.6.1)".
- **NOT RUN** install-explanation dialog (needs a published release above 0.6.1 to reach Install); Get Stack with Stack absent (Stack is installed; would need an uninstall); restore after `pm clear` (real Stack 0.19.0 has no `list_owned`; needs Stack 0.20.0); the Filen-not-answering retry; the UI text comparison by eye.
- Cleanup: my `v061-test` record removed from the real Stack (Recently deleted, 30 days) and `v061-test.sam` deleted from Downloads. Nothing else of Stack's or Filen's touched. `nested-t2.sam` and its checks-build link are still in place for Filen.
- The phone now runs the default-package 0.6.1 candidate; the earlier checks-package links are not usable from it.
