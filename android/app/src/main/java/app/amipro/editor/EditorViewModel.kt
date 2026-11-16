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
import uniffi.amipro_mobile.Attr
import uniffi.amipro_mobile.FormatState
import uniffi.amipro_mobile.StyleInfo
import uniffi.amipro_mobile.FileKind
import uniffi.amipro_mobile.Paragraph
import uniffi.amipro_mobile.newDocument
import uniffi.amipro_mobile.openDocument
import java.io.File
import java.io.FileNotFoundException

/** A file the user chose that needs its password before it opens. */
class PasswordRequest(val uri: Uri, val wrong: Boolean)

private enum class AfterLosses { SAVE, COPY }

/** Where the caret or selection is: paragraph index and UTF-16 offsets. */
class FocusPos(val paragraph: Int, val start: Int, val end: Int)

/** Formatting chosen at a caret, applied to the text typed next (as Ami Pro does); null = leave as is. */
data class Pending(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val size: Float? = null,
) {
    val any get() = bold != null || italic != null || underline != null || strike != null || size != null
}

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
    /** A document that has no file yet: Save asks where to put it. */
    var isNew by mutableStateOf(false)
        private set
    /** Set when "New document" was chosen while edits were unsaved: asks before throwing them away. */
    var pendingNew by mutableStateOf(false)

    var focus by mutableStateOf<FocusPos?>(null)
        private set
    /** What the formatting buttons show for the selection (or the character before the caret). */
    var format by mutableStateOf<FormatState?>(null)
        private set
    var pending by mutableStateOf(Pending())
        private set
    var styles by mutableStateOf<List<StyleInfo>>(emptyList())
        private set
    /** Where the caret should go after the paragraph list changed: paragraph, UTF-16 offset. */
    var caretTarget by mutableStateOf<Pair<Int, Int>?>(null)
    /** Bumped whenever the paragraph list changed shape or was re-read from the core, so every field reloads its text. */
    var epoch by mutableStateOf(0)
        private set

    private val resolver get() = app.contentResolver

    private val recentStore = RecentFiles(app)
    /** Documents opened or saved before, newest first, offered on the start screen. */
    val recents = mutableStateListOf<RecentFile>().apply { addAll(recentStore.load()) }

    private fun setRecents(list: List<RecentFile>) { recents.clear(); recents.addAll(list) }

    private fun remember(uri: Uri, displayName: String, write: Boolean) {
        recentStore.add(uri, displayName, write, source)?.let { setRecents(it) }
    }

    fun forget(uri: Uri) = setRecents(recentStore.remove(uri, source))

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
                remember(uri, displayName, canWriteBack)
                keepsOriginal = true
                isNew = false
                styles = opened.styles()
                focus = null; format = null; pending = Pending()
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
                // A recent file that was deleted or whose access was withdrawn would fail on every tap.
                if ((e is SecurityException || e is FileNotFoundException) && recents.any { it.uri == uri }) {
                    forget(uri)
                    message = "That file is no longer available, so it was removed from the recent list."
                }
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

    /** New document: asks first if edits would be lost. */
    fun requestNew() {
        if (isOpen && edited) pendingNew = true else newDocument()
    }

    fun newDocument() {
        pendingNew = false
        doc?.close()
        val d = newDocument(System.currentTimeMillis() / 1000)
        doc = d
        source = null
        name = "Untitled.sam"
        isNew = true
        canWriteBack = false
        keepsOriginal = false
        styles = d.styles()
        focus = null; format = null; pending = Pending()
        notes = emptyList()
        refreshAll()
        edited = false
        isOpen = true
        caretTarget = 0 to 0
    }

    /** A file chosen in the picker or sent by another app: asks first if edits would be lost. */
    fun requestOpen(uri: Uri) {
        if (isOpen && edited) pendingOpen = uri else open(uri)
    }


    /** Tracks the caret or selection; [typed] is true when this move only follows typing (a pending format stays). */
    fun onSelection(index: Int, start: Int, end: Int, typed: Boolean) {
        val moved = focus?.let { it.paragraph != index || it.start != start || it.end != end } ?: true
        if (!typed && moved) pending = Pending()
        focus = FocusPos(index, minOf(start, end), maxOf(start, end))
        updateFormat()
    }

    private fun updateFormat() {
        val d = doc
        val f = focus
        format = if (d != null && f != null) d.formatState(f.paragraph.toUInt(), f.start.toUInt(), f.end.toUInt()) else null
    }

    private fun applyPending(d: AmiDoc, index: Int, from: Int, to: Int) {
        val p = pending
        val i = index.toUInt(); val a = from.toUInt(); val b = to.toUInt()
        p.bold?.let { d.setAttribute(i, a, b, Attr.BOLD, it) }
        p.italic?.let { d.setAttribute(i, a, b, Attr.ITALIC, it) }
        p.underline?.let { d.setAttribute(i, a, b, Attr.UNDERLINE, it) }
        p.strike?.let { d.setAttribute(i, a, b, Attr.STRIKETHROUGH, it) }
        p.size?.let { d.setSize(i, a, b, it) }
    }

    private fun afterFormat(index: Int) {
        doc?.paragraph(index.toUInt())?.let { paragraphs[index] = it }
        edited = true
        updateFormat()
    }

    /** A bold/italic/underline/strikethrough button: over the selection, or for the next typed text at a caret. */
    fun toggle(attr: Attr) {
        val d = doc ?: return
        val f = focus ?: return
        val cur = format ?: return
        val now = when (attr) {
            Attr.BOLD -> pending.bold ?: cur.bold
            Attr.ITALIC -> pending.italic ?: cur.italic
            Attr.UNDERLINE -> pending.underline ?: cur.underline
            Attr.STRIKETHROUGH -> pending.strike ?: cur.strikethrough
        }
        if (f.start != f.end) {
            try {
                d.setAttribute(f.paragraph.toUInt(), f.start.toUInt(), f.end.toUInt(), attr, !now)
                afterFormat(f.paragraph)
            } catch (e: Exception) {
                message = "Could not format: ${(e as? AmiException.Failed)?.detail ?: e.message}"
            }
        } else {
            pending = when (attr) {
                Attr.BOLD -> pending.copy(bold = !now)
                Attr.ITALIC -> pending.copy(italic = !now)
                Attr.UNDERLINE -> pending.copy(underline = !now)
                Attr.STRIKETHROUGH -> pending.copy(strike = !now)
            }
        }
    }

    /** The size now shown: a pending size at a caret, else the selection's. */
    val shownSize: Float get() = pending.size ?: format?.sizePoints ?: 12f

    fun changeSize(delta: Float) {
        val d = doc ?: return
        val f = focus ?: return
        val size = (shownSize + delta).coerceIn(6f, 72f)
        if (f.start != f.end) {
            try {
                d.setSize(f.paragraph.toUInt(), f.start.toUInt(), f.end.toUInt(), size)
                afterFormat(f.paragraph)
            } catch (e: Exception) {
                message = "Could not change the size: ${(e as? AmiException.Failed)?.detail ?: e.message}"
            }
        } else {
            pending = pending.copy(size = size)
        }
    }

    /** Gives the focused paragraph one of the document's styles. */
    fun setStyle(id: ULong) {
        val d = doc ?: return
        val f = focus ?: return
        try {
            d.setParagraphStyle(f.paragraph.toUInt(), id)
            pending = Pending()
            afterFormat(f.paragraph)
        } catch (e: Exception) {
            message = "Could not change the style: ${(e as? AmiException.Failed)?.detail ?: e.message}"
        }
    }

    private fun refreshAll() {
        val d = doc ?: return
        val all = d.paragraphs()
        paragraphs.clear(); paragraphs.addAll(all)
        if (focus != null && focus!!.paragraph >= all.size) { focus = null; format = null }
        edited = d.isEdited()
        epoch++
    }

    /** One text change in paragraph [index]: [start, end) UTF-16 replaced by [text]. */
    fun replace(index: Int, start: Int, end: Int, text: String, caretAfter: Int) {
        val d = doc ?: return
        try {
            val before = d.paragraphCount().toInt()
            d.replaceText(index.toUInt(), start.toUInt(), end.toUInt(), text)
            if (pending.any && text.isNotEmpty() && !text.contains('\n') && focus?.paragraph == index) applyPending(d, index, start, start + text.length)
            if (d.paragraphCount().toInt() != before || text.contains('\n')) {
                refreshAll()
                // Enter put the caret at the start of the new paragraph.
                caretTarget = if (text.contains('\n')) (index + text.count { it == '\n' }) to 0 else index to caretAfter
            } else {
                d.paragraph(index.toUInt())?.let { paragraphs[index] = it }
                edited = true
            }
        } catch (e: AmiException.ProtectedObject) {
            // A picture, frame or table is kept untouched: put the text back and leave the caret where it was.
            message = "That is a picture, frame or table AmiPro cannot show. It is kept; edit the text around it."
            refreshAll()
            caretTarget = index to end
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
                val ext = if (d.kind() == FileKind.SAM) "sam" else "amipro"
                copyRequest = if (isNew) "$base.$ext" else "$base copy.$ext"
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
                val wasNew = isNew
                isNew = false
                canWriteBack = true
                keepsOriginal = false
                remember(uri, copyName, true)
                notes = notes.filterNot { it.startsWith("Opened for reading only") }
                edited = false
                message = if (wasNew) "Saved as $copyName" else "Saved a copy as $copyName"
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
        doc?.close(); doc = null; source = null; paragraphs.clear(); isOpen = false; edited = false; isNew = false; focus = null; format = null; pending = Pending()
    }
}
