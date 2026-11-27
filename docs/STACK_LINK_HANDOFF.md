# Stack Link: AmiPro side (handoff written 2026-11-21)

Read first: `~/Projects/Stack/docs/STACK_LINK_CONTRACT.md` (the contract and every decision), then this file, then
`docs/STATUS.md` ("Planned: Stack link"). The Stack side of slice 2 is built, committed and tested
(`~/Projects/Stack`, commits `d5f11c2`, `04d46ae`, `57360db`). The AmiPro side is built and released (0.6.1 restore, 0.7.0 folder sharing pending); see docs/STATUS.md.

## Goal of this slice
The user writes a document in AmiPro, taps **Share with Stack**, and it appears in Stack's search and list with its
text and a preview. In Stack, **Open in Ami Pro** opens the real file here. Two people use this; keep it simple.

## What to build here (in this order)
1. **Text for search.** A facade function returning plain text of the main text plus every table cell and text frame
   (`table_text`, `frame_text` already exist in `crates/amipro-mobile/src/extras.rs`). No drawings, charts, equations or
   metafiles (decided: not needed). Audit: 34% of reference files have tables, 14% have more text in tables than body.
2. **Preview PDF.** Reuse the print layout (`Printing.kt`) to write a PDF to a file without the print dialog. Drawings and
   charts stay labelled boxes (decided). Cap: PDF at most 32 MiB.
3. **Share with Stack action** (More menu, and on recent-file rows). Builds the record, sends it, shows the result.
4. **`link_id` map** in app-private storage: a UUID per file, minted at first share, kept across rename and move.
5. **Publish on save and on close** for files already shared (not on every keystroke).
6. **Provider `app.amipro.editor.stacklink`** (exported) implementing `open` (below). Later: `stat`, `refresh`, `delete`
   (slice 4), not now.
7. **Manifest and release:** `<queries>` for Stack's package(s), the provider, version bump, a signed release.

## Protocol to Stack (already implemented and tested on Stack's side)
Authority is `<Stack package>.link`. Real Stack: `org.stackdocs.app.link`. On the test phone the checks build is
`org.stackdocs.app.checks.linkcontract.link` (build with `-PstackChecks -PstackChecksNamespace=linkcontract`; see
Stack `app/README.md`). Make the Stack package configurable for testing; default to the real one.

1. `ContentResolver.call(authority, "contract", null, null)` returns `min` and `max` ints. Use contract 1.
2. `call(authority, "begin", recordJson, null)` returns `token` (a UUID string), or `error` (`invalid`,
   `unsupported_contract` with `version`, `unknown_method`) and `message`. A caller that is not allowed gets a
   `SecurityException`.
3. Write the text: `openOutputStream(Uri.parse(authority + "/stage/" + token + "/text"), "w")`, UTF-8, at most 4 MiB
   (limit is 1 MiB of characters). Optionally write `.../preview` the same way (PDF, starts with `%PDF-`, at most 32 MiB).
   Only write mode works; reads are refused.
4. `call(authority, "commit", token, null)` returns `id`, `created`, `content_changed`, or an `error`.
   `abort` with the token cancels. Tokens last 10 minutes; at most 8 can be open.

Record JSON (exact keys, unknown keys are refused):
`contract` 1; `owner` = this app's own package name (must equal the real caller or it is refused); `link_id` lowercase
UUID; `kind` `"doc"`; `title` (1 to 200 chars, no control or direction-changing characters); `mime`
`application/x-amipro-document`; `modified` epoch ms (whole number); `hash` SHA-256 of the file bytes, lowercase hex;
`locators` a list of 1 to 8 of `{type:"filen",uuid,path}`, `{type:"saf",uri}`, `{type:"owner",token}`;
optional `shared` and `protected` booleans. Reference implementation of the validator: Stack `LinkRecord.java`.
Same `hash` again keeps the old text and preview (an exact repeat), a different hash replaces them.

## Provider you expose: `app.amipro.editor.stacklink`
Stack calls `open` with the `link_id` as the argument and extras `mode` (`edit` or `view`). Answer with a Bundle holding
`intent`: an **explicit** intent for one of your own activities that opens the file (use your existing Open with path).
It must grant no URI permission flags and carry no clip data or selector, or Stack refuses it. If the `link_id` is not
in your map or the file is gone, return a Bundle with `error = "not_found"`. Check the caller: allow only Stack's
signing certificate (release SHA-256 `e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311`, from
`~/APK-Keys/README.txt`), exactly one signer, uid to a single package, same approach as Stack's `LinkCallers.java`.

## Rules that already bind this work
- **Protected `.SAM` files are never shared by default.** If the user insists, warn that Stack will hold readable text and
  a preview without the password, and set `protected: true`.
- Stack only pins the **release** AmiPro (`app.amipro.editor`, certificate `9a387c70...868a`). The debug build
  (`app.amipro.editor.debug`, debug key) is NOT trusted by Stack. Test the real path with the **release** build on the
  test phone (this repo's rule anyway), or ask the user to add the debug certificate to Stack's pin list.
- Test files must be synthetic (this repo's CLAUDE.md). Test phone: Pixel 4a, serial `0A121JEC203530` (always `adb -s`).
  The production phone is hands off. The Stack checks build on that phone installs under its own package names and does
  not touch the installed Stack.
- Do not share files from `~/AmiPro-Reference`. The audit test `reference_object_audit` is uncommitted in this repo.
- Keep it simple. Cheaper agents for routine, mechanical work; escalate security-sensitive design to a higher-tier agent
  only with the user's approval (they gave it for this project).

## Things to verify end to end once built
- Share a synthetic document with a table: Stack's search finds a word that is only inside the table.
- Edit and save it: Stack's saved text and preview update (hash changed).
- Stack's "Open in Ami Pro" opens the same file for editing.
- A password-protected file refuses to share without the warning.
- AmiPro must still work when Stack is not installed (the action explains, nothing crashes).

## Open items from the contract that touch this repo later
`stat`, `refresh`, `moved`, `removed` events (slice 4), the `filen` locator (slice 5), AmiPro-Linux accepting a file
argument (slice 6). Not part of this slice.
