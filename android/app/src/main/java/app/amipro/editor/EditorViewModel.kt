package app.amipro.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class EditorViewModel(app: Application) : AndroidViewModel(app) {
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
    /** Losses to confirm before a save (empty list is never shown). */
    var pendingLosses by mutableStateOf<List<String>?>(null)
    /** Where the caret should go after the paragraph list changed: paragraph, UTF-16 offset. */
    var caretTarget by mutableStateOf<Pair<Int, Int>?>(null)
    /** Bumped whenever the paragraph list changed shape or was re-read from the core, so every field reloads its text. */
    var epoch by mutableStateOf(0)
        private set

    private val resolver get() = getApplication<Application>().contentResolver

    fun open(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            busy = true
            try {
                val (bytes, displayName) = withContext(Dispatchers.IO) {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read this file")
                    val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment ?: "document"
                    bytes to display
                }
                val opened = withContext(Dispatchers.Default) { openDocument(bytes, displayName.substringBeforeLast('.'), password) }
                doc?.close()
                doc = opened
                source = uri
                name = displayName
                notes = opened.report().notes
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

    /** Asks about losses first when there are any, otherwise saves straight away. */
    fun requestSave() {
        val d = doc ?: return
        if (d.kind() != FileKind.SAM) { save(); return }
        val losses = d.samLosses(System.currentTimeMillis() / 1000)
        if (losses.isEmpty()) save() else pendingLosses = losses
    }

    fun save() {
        val d = doc ?: return
        val uri = source ?: return
        pendingLosses = null
        viewModelScope.launch {
            busy = true
            try {
                val bytes = d.save(System.currentTimeMillis() / 1000)
                withContext(Dispatchers.IO) {
                    keepOriginalOnce(uri)
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write to this file")
                }
                edited = false
                message = "Saved"
            } catch (e: Exception) {
                message = "Could not save: ${(e as? AmiException.Failed)?.detail ?: e.message}. Your edits are still open."
            } finally {
                busy = false
            }
        }
    }

    /** The first save of a file keeps the untouched original in the app's own storage (SAF has no sibling .bak). */
    private fun keepOriginalOnce(uri: Uri) {
        val dir = File(getApplication<Application>().filesDir, "originals").apply { mkdirs() }
        val copy = File(dir, "${uri.toString().hashCode().toUInt()}-$name")
        if (copy.exists()) return
        resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
    }

    fun close() {
        doc?.close(); doc = null; source = null; paragraphs.clear(); isOpen = false; edited = false
    }
}
