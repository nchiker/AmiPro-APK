# UI text review (2026-11-22)

Rules: title 6 words or fewer; body 2 short sentences (about 25 words), one idea; specific button verbs; no internal words; warnings state the consequence once. Messages that carried raw system error text now say what happened in plain words (the detail is not lost for the person: retry or Save a copy are still offered).

| # | File | Before | After |
|---|---|---|---|
| 1 | MainActivity.kt | Android only lets an app install updates after you allow it once. Next, Android shows a settings page for AmiPro: switch on "Allow from this source", then come back here. AmiPro will not install anything without your confirmation, and your documents are not touched. | Android needs your OK once. Next, switch on "Allow from this source" for AmiPro, then come back. |
| 2 | MainActivity.kt | Allow AmiPro to install apps on the page Android just opened, then come back and tap Install again. | Switch on "Allow from this source", then tap Install again. |
| 3 | MainActivity.kt | Stack will keep readable text and a preview of this document without the password. Share only if that is what you want. | Stack will keep readable text and a preview, without the password. |
| 4 | MainActivity.kt | Looking for a newer version on GitHub... | Checking for updates... |
| 5 | MainActivity.kt | Version ${state.version.removePrefix("v")} is downloaded and checked. Android will ask you to confirm the installation; your documents are not touched. | Version ${state.version.removePrefix("v")} is ready. Android will ask you to confirm. |
| 6 | MainActivity.kt | Text("The file is downloaded over a secure connection and checked against the installed app before anything is installed.", style = MaterialTheme.typography.bodySmall) | Text("It is checked before it is installed.", style = MaterialTheme.typography.bodySmall) |
| 7 | MainActivity.kt | Waiting for Android's installer... | Installing... |
| 8 | MainActivity.kt | Version ${state.version.removePrefix("v")} is available (installed: ${updater.installedVersion}). | Version ${state.version.removePrefix("v")} is available. You have ${updater.installedVersion}. |
| 9 | Updates.kt | Could not look for updates: ${e.message ?: "no connection"}. Nothing was changed. | Could not check for updates. Check your connection. |
| 10 | Updates.kt | ${e.message ?: "The download failed"}. Nothing was changed. | The download failed. Nothing was changed. |
| 11 | Updates.kt | Android could not start the installation: ${e.message ?: "unknown error"}. Nothing was changed. | Android could not start the install. Nothing was changed. |
| 12 | Updates.kt | Android refused the update because it does not match the installed app (${detail ?: "signature"}). Nothing was changed. | Android refused this update. Nothing was changed. |
| 13 | Updates.kt | Android could not install the update (${detail ?: "code $status"}). Nothing was changed. | The update did not install. Nothing was changed. |
| 14 | EditorViewModel.kt | Save the document to a file first, then share it with Stack. | Save the document first, then share. |
| 15 | EditorViewModel.kt | Save your changes first, so Stack gets what is in the file. | Save your changes first. |
| 16 | EditorViewModel.kt | Stack is not installed on this phone. Install Stack to share documents with it. | Stack is not installed. |
| 17 | EditorViewModel.kt | "Could not share: ${e.message}" | "Could not share this document." |
| 18 | EditorViewModel.kt | Saved, but Stack was not updated: ${result.reason} | Saved. Stack was not updated. |
| 19 | StackLink.kt | Stack is not installed on this phone. | Stack is not installed. |
| 20 | StackLink.kt | Stack did not answer. Open Stack once and try again. | Stack did not answer. Open Stack, then try again. |
| 21 | StackLink.kt | Stack and Ami Pro need an update to understand each other. | Update Stack and Ami Pro, then try again. |
| 22 | StackLink.kt | "Stack refused it: $it ${begun.getString("message").orEmpty()}".trim() | "Stack did not accept this document." |
| 23 | StackLink.kt | "Stack refused it: $it ${done.getString("message").orEmpty()}".trim() | "Stack did not accept this document." |
| 24 | StackLink.kt | Stack does not trust this copy of Ami Pro. | Stack does not accept this copy of Ami Pro. |
| 25 | StackLink.kt | "Could not reach Stack: ${e.message}" | "Could not reach Stack." |

Not changed: titles ("Updates", "Share a password-protected file?"), buttons (Download, Install, Try again, Share anyway, Cancel, Not now), and the loss list before saving (it is the content the person decides on). "Continue" on the install explanation is the one new button; it only opens the Android settings page.

Follow-up (same day): "Stack is not installed." now has a "Get Stack" action (opens https://github.com/nchiker/stack/releases/tag/v0.19.0) on the start screen and in the editor message bar. The raw error text removed from messages is kept in the log (`adb logcat -s StackLink Updates`), not on screen; no "Details" link was built.
