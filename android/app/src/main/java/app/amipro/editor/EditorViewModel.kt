package app.amipro.editor

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.amipro_mobile.AmiDoc
import uniffi.amipro_mobile.AmiException
import uniffi.amipro_mobile.FileKind
import uniffi.amipro_mobile.Paragraph
import uniffi.amipro_mobile.openDocument
import java.io.File

/** A file the user chose that needs its password before it opens. */
class PasswordRequest(val uri: Uri, val wrong: Boolean)

private enum class AfterLosses { SAVE, COPY }

/**
 * The open document and everything about it. It belongs to the app process ([AmiProApp.editor]), not to an
 * activity: a file manager can start this app in a second window, and every window must see the same document
 * (and the same unsaved-changes guard).
 */
class EditorViewModel(private val app: Application) {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var doc: AmiDoc? = null
    private var source: Uri? = null

    val paragraphs = mutableStateListOf<Paragraph>()
    var name by mutableStateOf("")
        private set
    var isOpen by mutableStateOf(false)
        private set
    var edited by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var notes by mutableStateOf<List<String>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
    /** Losses to confirm before a save or a copy (an empty list is never shown). */
    var pendingLosses by mutableStateOf<List<String>?>(null)
    private var afterLosses = AfterLosses.SAVE
    /** The file a user picked while edits were unsaved: asks before replacing the open document. */
    var pendingOpen by mutableStateOf<Uri?>(null)
    /** Set to a suggested file name when the system "create file" screen should be shown. */
    var copyRequest by mutableStateOf<String?>(null)
    /** False when the system gave read access only: Save then makes a copy instead. */
    var canWriteBack by mutableStateOf(true)
        private set
    private var keepsOriginal = true
    /** Where the caret should go after the paragraph list changed: paragraph, UTF-16 offset. */
    var caretTarget by mutableStateOf<Pair<Int, Int>?>(null)
    /** Bumped whenever the paragraph list changed shape or was re-read from the core, so every field reloads its text. */
    var epoch by mutableStateOf(0)
        private set

    private val resolver get() = app.contentResolver

    fun open(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            busy = true
            try {
                val (bytes, displayName) = withContext(Dispatchers.IO) {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read this file")
                    bytes to displayNameOf(uri)
                }
                val opened = withContext(Dispatchers.Default) { openDocument(bytes, displayName.substringBeforeLast('.'), password) }
                doc?.close()
                doc = opened
                source = uri
                name = displayName
                canWriteBack = canWrite(uri)
                keepsOriginal = true
                notes = opened.report().notes +
                    if (canWriteBack) emptyList() else listOf("Opened for reading only: Save makes a copy.")
                refreshAll()
                edited = false
                isOpen = true
                passwordRequest = null
            } catch (e: AmiException.PasswordRequired) {
                passwordRequest = PasswordRequest(uri, wrong = false)
            } catch (e: AmiException.WrongPassword) {
                passwordRequest = PasswordRequest(uri, wrong = true)
            } catch (e: AmiException.Failed) {
                message = "Could not open this file: ${e.detail}"
            } catch (e: Exception) {
                message = "Could not open this file: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun displayNameOf(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"

    private fun canWrite(uri: Uri): Boolean =
        app.checkUriPermission(
            uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    /** A file chosen in the picker or sent by another app: asks first if edits would be lost. */
    fun requestOpen(uri: Uri) {
        if (isOpen && edited) pendingOpen = uri else open(uri)
    }

    private fun refreshAll() {
        val d = doc ?: return
        val all = d.paragraphs()
        paragraphs.clear(); paragraphs.addAll(all)
        edited = d.isEdited()
        epoch++
    }

    /** One text change in paragraph [index]: [start, end) UTF-16 replaced by [text]. */
    fun replace(index: Int, start: Int, end: Int, text: String, caretAfter: Int) {
        val d = doc ?: return
        try {
            val before = d.paragraphCount().toInt()
            d.replaceText(index.toUInt(), start.toUInt(), end.toUInt(), text)
            if (d.paragraphCount().toInt() != before || text.contains('\n')) {
                refreshAll()
                // Enter put the caret at the start of the new paragraph.
                caretTarget = if (text.contains('\n')) (index + text.count { it == '\n' }) to 0 else index to caretAfter
            } else {
                d.paragraph(index.toUInt())?.let { paragraphs[index] = it }
                edited = true
            }
        } catch (e: Exception) {
            message = "Could not make that change: ${(e as? AmiException.Failed)?.detail ?: e.message}"
            refreshAll()
        }
    }

    /** Backspace at the start of paragraph [index]. */
    fun joinWithPrevious(index: Int) {
        val d = doc ?: return
        if (index == 0) return
        try {
            val offset = paragraphs[index - 1].text.length
            if (d.joinWithPrevious(index.toUInt())) {
                refreshAll()
                caretTarget = (index - 1) to offset
            }
        } catch (e: Exception) {
            message = "Could not join: ${(e as? AmiException.Failed)?.detail ?: e.message}"
        }
    }

    fun undo() = history { it.undo() }
    fun redo() = history { it.redo() }
    private fun history(step: (AmiDoc) -> Boolean) {
        val d = doc ?: return
        if (step(d)) { refreshAll(); edited = true }
    }

    /** Runs [after] straight away, or once the user has accepted what a SAM would lose. */
    private fun withLossCheck(after: AfterLosses) {
        val d = doc ?: return
        val losses = if (d.kind() == FileKind.SAM) d.samLosses(System.currentTimeMillis() / 1000) else emptyList()
        if (losses.isEmpty()) proceed(after) else { afterLosses = after; pendingLosses = losses }
    }

    private fun proceed(after: AfterLosses) {
        pendingLosses = null
        when (after) {
            AfterLosses.SAVE -> save()
            AfterLosses.COPY -> {
                val d = doc ?: return
                val base = name.substringBeforeLast('.')
                copyRequest = "$base copy." + if (d.kind() == FileKind.SAM) "sam" else "amipro"
            }
        }
    }

    fun confirmLosses() = proceed(afterLosses)

    /** Save: over the open file when the system allows it, otherwise as a copy. */
    fun requestSave() = if (canWriteBack) withLossCheck(AfterLosses.SAVE) else requestCopy()

    /** Save a copy: the system "create file" screen, then the open document becomes that copy. */
    fun requestCopy() = withLossCheck(AfterLosses.COPY)

    private fun save() {
        val d = doc ?: return
        val uri = source ?: return
        viewModelScope.launch {
            busy = true
            try {
                val bytes = d.save(System.currentTimeMillis() / 1000)
                withContext(Dispatchers.IO) {
                    if (keepsOriginal) keepOriginalOnce(uri)
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write to this file")
                }
                edited = false
                message = "Saved"
            } catch (e: Exception) {
                message = "Could not save: ${(e as? AmiException.Failed)?.detail ?: e.message}. Your edits are still open; try Save a copy."
            } finally {
                busy = false
            }
        }
    }

    fun saveCopyTo(uri: Uri) {
        val d = doc ?: return
        viewModelScope.launch {
            busy = true
            try {
                val bytes = d.save(System.currentTimeMillis() / 1000)
                val copyName = withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write to this file")
                    displayNameOf(uri)
                }
                // The copy is now the document being edited; the file it came from is left exactly as it was.
                source = uri
                name = copyName
                canWriteBack = true
                keepsOriginal = false
                notes = notes.filterNot { it.startsWith("Opened for reading only") }
                edited = false
                message = "Saved a copy as $copyName"
            } catch (e: Exception) {
                message = "Could not save the copy: ${(e as? AmiException.Failed)?.detail ?: e.message}. Your edits are still open."
            } finally {
                busy = false
            }
        }
    }

    /** The first save of a file keeps the untouched original in the app's own storage (SAF has no sibling .bak). */
    private fun keepOriginalOnce(uri: Uri) {
        val dir = File(app.filesDir, "originals").apply { mkdirs() }
        val copy = File(dir, "${uri.toString().hashCode().toUInt()}-$name")
        if (copy.exists()) return
        resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
    }

    fun close() {
        doc?.close(); doc = null; source = null; paragraphs.clear(); isOpen = false; edited = false
    }
}
