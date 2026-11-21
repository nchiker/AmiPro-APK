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
import uniffi.amipro_mobile.Align
import uniffi.amipro_mobile.AmiException
import uniffi.amipro_mobile.FoundText
import uniffi.amipro_mobile.LineSpacingChoice
import uniffi.amipro_mobile.ObjectInfo
import uniffi.amipro_mobile.PictureImage
import uniffi.amipro_mobile.TableView
import androidx.compose.ui.text.TextRange
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

/** Where the caret or selection is: from (paragraph [p0], UTF-16 offset [s0]) to ([p1], [s1]), in document order. */
class FocusPos(val p0: Int, val s0: Int, val p1: Int, val s1: Int) {
    val collapsed get() = p0 == p1 && s0 == s1
}

/** Formatting chosen at a caret, applied to the text typed next (as Ami Pro does); null = leave as is. */
data class Pending(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val size: Float? = null,
    val family: String? = null,
    /** 0xRRGGBB */
    val colour: Int? = null,
) {
    val any get() = bold != null || italic != null || underline != null || strike != null || size != null || family != null || colour != null
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
    /** True when the open file needed a password: it is never shared with Stack without an explicit yes. */
    private var protectedFile = false
    private val linkMap = LinkMap(app)
    private val stack = StackLink(app)
    /** Set when the open file is password-protected and Share with Stack needs the person's warning first. */
    var pendingProtectedShare by mutableStateOf(false)
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
    /** Where the caret should go after the whole text was re-read: an offset in [docText]. */
    var caretTarget by mutableStateOf<Int?>(null)
    /** Bumped when only the look (not the text) of paragraphs changed, so the field restyles. */
    var look by mutableStateOf(0)
        private set
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
                protectedFile = password != null
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
        protectedFile = false
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
        caretTarget = 0
    }

    /** A file chosen in the picker or sent by another app: asks first if edits would be lost. */
    fun requestOpen(uri: Uri) {
        if (isOpen && edited) pendingOpen = uri else open(uri)
    }


    /** The whole text: paragraphs joined by a newline. The editor's one text field holds exactly this. */
    val docText: String get() = paragraphs.joinToString("\n") { it.text }

    /** Offset in [docText] of ([paragraph], [offset]). */
    fun globalOffset(paragraph: Int, offset: Int): Int {
        var at = 0
        for (i in 0 until minOf(paragraph, paragraphs.size)) at += paragraphs[i].text.length + 1
        return at + offset
    }

    /** The paragraph the caret (or the start of the selection) is in. */
    val focusParagraph: Paragraph? get() = focus?.let { paragraphs.getOrNull(it.p0) }

    /** Tracks the caret or selection; [typed] is true when this move only follows typing (a pending format stays). */
    fun onSelection(p0: Int, s0: Int, p1: Int, s1: Int, typed: Boolean) {
        val moved = focus?.let { it.p0 != p0 || it.s0 != s0 || it.p1 != p1 || it.s1 != s1 } ?: true
        if (!typed && moved) pending = Pending()
        focus = FocusPos(p0, s0, p1, s1)
        updateFormat()
    }

    private fun updateFormat() {
        val d = doc
        val f = focus
        format = if (d != null && f != null) d.formatStateBetween(f.p0.toUInt(), f.s0.toUInt(), f.p1.toUInt(), f.s1.toUInt()) else null
    }

    private fun applyPending(d: AmiDoc, index: Int, from: Int, to: Int) {
        val p = pending
        val i = index.toUInt(); val a = from.toUInt(); val b = to.toUInt()
        p.bold?.let { d.setAttribute(i, a, b, Attr.BOLD, it) }
        p.italic?.let { d.setAttribute(i, a, b, Attr.ITALIC, it) }
        p.underline?.let { d.setAttribute(i, a, b, Attr.UNDERLINE, it) }
        p.strike?.let { d.setAttribute(i, a, b, Attr.STRIKETHROUGH, it) }
        if (p.size != null || p.family != null || p.colour != null) d.setFontBetween(i, a, i, b, p.family, p.size, p.colour?.toUInt())
    }

    /** Re-reads every paragraph's look from the core without touching the text field. */
    private fun refreshParagraphs() {
        val d = doc ?: return
        val all = d.paragraphs()
        paragraphs.clear(); paragraphs.addAll(all)
        look++
    }

    private fun afterFormat() {
        refreshParagraphs()
        edited = true
        updateFormat()
        refreshMatches()
    }

    private fun failure(what: String, e: Exception) { message = "$what: ${(e as? AmiException.Failed)?.detail ?: e.message}" }

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
        if (!f.collapsed) {
            try {
                d.setAttributeBetween(f.p0.toUInt(), f.s0.toUInt(), f.p1.toUInt(), f.s1.toUInt(), attr, !now)
                afterFormat()
            } catch (e: Exception) { failure("Could not format", e) }
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

    fun changeSize(delta: Float) = setFont(size = (shownSize + delta).coerceIn(6f, 72f))

    /** Face, size or colour (0xRRGGBB) over the selection, or for the next typed text at a caret. */
    fun setFont(family: String? = null, size: Float? = null, colour: Int? = null) {
        val d = doc ?: return
        val f = focus ?: return
        if (!f.collapsed) {
            try {
                d.setFontBetween(f.p0.toUInt(), f.s0.toUInt(), f.p1.toUInt(), f.s1.toUInt(), family, size, colour?.toUInt())
                afterFormat()
            } catch (e: Exception) { failure("Could not change the font", e) }
        } else {
            pending = pending.copy(family = family ?: pending.family, size = size ?: pending.size, colour = colour ?: pending.colour)
        }
    }

    /** Every face worth offering for this document. */
    fun fonts(): List<String> = doc?.fonts().orEmpty()

    /** Gives the paragraphs of the selection one of the document's styles. */
    fun setStyle(id: ULong) = onParagraphs("change the style") { d, a, b -> for (i in a..b) d.setParagraphStyle(i.toUInt(), id); pending = Pending() }

    fun setAlignment(align: Align) = onParagraphs("align") { d, a, b -> d.setAlignment(a.toUInt(), b.toUInt(), align) }

    /** Moves the left indent of the selected paragraphs by [deltaTwips] (a quarter inch is 360). */
    fun changeIndent(deltaTwips: Int) = onParagraphs("change the indent") { d, a, b ->
        // Every paragraph keeps its own difference: move each from its own value.
        for (i in a..b) {
            val now = paragraphs.getOrNull(i)?.indentLeft ?: 0
            d.setIndents(i.toUInt(), i.toUInt(), (now + deltaTwips).coerceAtLeast(0), null, null)
        }
    }

    fun setLineSpacing(choice: LineSpacingChoice) = onParagraphs("change the spacing") { d, a, b -> d.setLineSpacing(a.toUInt(), b.toUInt(), choice) }

    private fun onParagraphs(what: String, change: (AmiDoc, Int, Int) -> Unit) {
        val d = doc ?: return
        val f = focus ?: return
        try {
            change(d, f.p0, f.p1)
            afterFormat()
        } catch (e: Exception) { failure("Could not $what", e) }
    }

    /** Re-reads everything (the text too): after a change the field cannot have made itself. */
    private fun refreshAll() {
        val d = doc ?: return
        val all = d.paragraphs()
        paragraphs.clear(); paragraphs.addAll(all)
        if (focus != null && focus!!.p1 >= all.size) { focus = null; format = null }
        edited = d.isEdited()
        epoch++
        look++
    }

    /**
     * One text change in the field: ([p0], [s0]) to ([p1], [s1]) replaced by [text], which may hold newlines (a split) and
     * may start or end in other paragraphs (a join). [caretBefore] is where the caret goes if the change is refused.
     */
    fun replaceRange(p0: Int, s0: Int, p1: Int, s1: Int, text: String, caretBefore: Int) {
        val d = doc ?: return
        // A pasted object marker would be an object with nothing behind it.
        val clean = text.replace("\r", "").replace(OBJECT_CHAR.toString(), "")
        try {
            d.replaceRange(p0.toUInt(), s0.toUInt(), p1.toUInt(), s1.toUInt(), clean)
            if (pending.any && clean.isNotEmpty() && '\n' !in clean && p0 == p1) applyPending(d, p0, s0, s0 + clean.length)
            if (p0 != p1 || '\n' in clean) refreshParagraphs()
            else { d.paragraph(p0.toUInt())?.let { paragraphs[p0] = it }; look++ }
            edited = true
            refreshMatches()
        } catch (e: AmiException.ProtectedObject) {
            // A picture, frame or table is kept untouched: put the text back and leave the caret where it was.
            message = "That is a picture, frame or table AmiPro cannot show. It is kept; edit the text around it, or delete it from the Pictures and tables list."
            refreshAll()
            caretTarget = caretBefore
        } catch (e: Exception) {
            failure("Could not make that change", e)
            refreshAll()
            caretTarget = caretBefore
        }
    }

    // ---- find and replace ----
    var findOpen by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findMatchCase by mutableStateOf(false)
    var findWholeWord by mutableStateOf(false)
    var replaceWith by mutableStateOf("")
    var matches by mutableStateOf<List<FoundText>>(emptyList())
        private set
    var matchIndex by mutableStateOf(-1)
        private set
    /** A range of [docText] the field should select and scroll to. */
    var selectRequest by mutableStateOf<TextRange?>(null)

    fun openFind() { findOpen = true; refreshMatches() }
    fun closeFind() { findOpen = false; matches = emptyList(); matchIndex = -1 }

    private fun globalRange(m: FoundText) = TextRange(globalOffset(m.paragraph.toInt(), m.start.toInt()), globalOffset(m.paragraph.toInt(), m.end.toInt()))

    /** Looks again with the current options, keeping the match nearest the old one. */
    fun refreshMatches() {
        val d = doc
        if (!findOpen || d == null || findQuery.isEmpty()) { matches = emptyList(); matchIndex = -1; return }
        val old = matches.getOrNull(matchIndex)
        matches = d.find(findQuery, findMatchCase, findWholeWord)
        matchIndex = when {
            matches.isEmpty() -> -1
            old == null -> 0
            else -> matches.indexOfFirst { it.paragraph > old.paragraph || (it.paragraph == old.paragraph && it.start >= old.start) }.let { if (it < 0) 0 else it }
        }
    }

    /** Runs the search from the caret and selects the first match after it. */
    fun find() {
        refreshMatches()
        if (matches.isEmpty()) { message = "No matches"; return }
        val from = focus?.let { globalOffset(it.p1, it.s1) } ?: 0
        val next = matches.indexOfFirst { globalRange(it).start >= from }.let { if (it < 0) 0 else it }
        matchIndex = next
        selectRequest = globalRange(matches[next])
    }

    fun step(forward: Boolean) {
        if (matches.isEmpty()) { find(); return }
        matchIndex = (matchIndex + if (forward) 1 else -1 + matches.size) % matches.size
        selectRequest = globalRange(matches[matchIndex])
    }

    fun replaceCurrent() {
        val m = matches.getOrNull(matchIndex) ?: return find()
        val at = globalRange(m).start
        replaceRange(m.paragraph.toInt(), m.start.toInt(), m.paragraph.toInt(), m.end.toInt(), replaceWith, at)
        epoch++ // the text changed under the field
        caretTarget = at + replaceWith.length
        refreshMatches()
        if (matches.isNotEmpty()) {
            matchIndex = matches.indexOfFirst { globalRange(it).start >= at + replaceWith.length }.let { if (it < 0) 0 else it }
            selectRequest = globalRange(matches[matchIndex])
        }
    }

    fun replaceEverything() {
        val d = doc ?: return
        if (findQuery.isEmpty()) return
        try {
            val n = d.replaceAll(findQuery, replaceWith, findMatchCase, findWholeWord).toInt()
            message = if (n == 0) "No matches" else "Replaced $n"
            if (n > 0) { refreshAll(); edited = true; caretTarget = focus?.let { globalOffset(it.p1, it.s1) } ?: 0 }
            refreshMatches()
        } catch (e: Exception) { failure("Could not replace", e) }
    }

    // ---- pictures, frames and tables ----
    fun objects(): List<ObjectInfo> = doc?.objects().orEmpty()
    fun pictureImage(o: ObjectInfo): PictureImage? = doc?.pictureImage(o.paragraph, o.offset)
    fun tableText(o: ObjectInfo): TableView? = doc?.tableText(o.paragraph, o.offset)
    fun frameText(o: ObjectInfo): String? = doc?.frameText(o.paragraph, o.offset)

    /** Removes the object and what it owns (one undo step; Undo brings it back). */
    fun deleteObject(o: ObjectInfo) {
        val d = doc ?: return
        try {
            d.deleteObject(o.paragraph, o.offset)
            refreshAll()
            edited = true
            caretTarget = globalOffset(o.paragraph.toInt(), o.offset.toInt())
            message = "Deleted. Undo brings it back."
        } catch (e: Exception) { failure("Could not delete", e) }
    }

    /** What printing needs, read once. */
    fun printData(): PrintData? {
        val d = doc ?: return null
        return PrintData(paragraphs.toList(), d.objects(), d.pageSetup(), { o -> d.pictureImage(o.paragraph, o.offset) }, { o -> d.tableText(o.paragraph, o.offset) }, { o -> d.frameText(o.paragraph, o.offset) })
    }

    // ---- Share with Stack ----
    /** More menu: puts this document (text and a preview) in Stack, where it can be searched and opened again. */
    fun shareWithStack(confirmedProtected: Boolean = false) {
        pendingProtectedShare = false
        val uri = source
        if (doc == null || uri == null) { message = "Save the document to a file first, then share it with Stack."; return }
        if (edited) { message = "Save your changes first, so Stack gets what is in the file."; return }
        if (!stack.installed()) { message = "Stack is not installed on this phone. Install Stack to share documents with it."; return }
        if (protectedFile && !confirmedProtected) { pendingProtectedShare = true; return }
        val snapshot = snapshotForStack() ?: return
        viewModelScope.launch {
            busy = true
            val result = try {
                val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read this file") }
                send(uri, bytes, snapshot)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { ShareResult.Failed("Could not share: ${e.message}")
            } finally { busy = false }
            message = when (result) {
                is ShareResult.Done -> if (result.created) "Shared with Stack" else if (result.changed) "Stack updated" else "Stack already has this version"
                is ShareResult.Failed -> result.reason
            }
        }
    }

    /** After a save: tells Stack about the new version, but only for a file already shared. Quiet unless it fails. */
    private fun publishIfShared(uri: Uri, bytes: ByteArray) {
        if (linkMap.existingId(uri) == null || !stack.installed()) return
        // Everything about THIS document is captured now, so opening another one meanwhile cannot change what is sent.
        val snapshot = snapshotForStack() ?: return
        viewModelScope.launch {
            val result = try { send(uri, bytes, snapshot) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { ShareResult.Failed("${e.message}") }
            if (result is ShareResult.Failed) message = "Saved, but Stack was not updated: ${result.reason}"
        }
    }

    /** What Stack needs from the open document, read at one moment. */
    private class StackSnapshot(val doc: AmiDoc, val data: PrintData, val name: String, val protectedFile: Boolean)

    private fun snapshotForStack(): StackSnapshot? {
        val d = doc ?: return null
        val data = printData() ?: return null
        return StackSnapshot(d, data, name, protectedFile)
    }

    /** Builds the preview and the record for [bytes] (the file as it is on disk) and hands them to Stack. */
    private suspend fun send(uri: Uri, bytes: ByteArray, snap: StackSnapshot): ShareResult = withContext(Dispatchers.IO) {
        val pdf = File.createTempFile("stack-preview", ".pdf", app.cacheDir)
        try {
            val preview = try {
                DocumentPrintAdapter(app, snap.name, snap.data).writePdf(pdf)
                pdf.takeIf { it.length() in 1..StackLink.MAX_PREVIEW_BYTES }
            } catch (e: Exception) { android.util.Log.w("StackLink", "preview failed", e); null }
            val linkId = linkMap.idOrNew(uri)
            val result = stack.share(LinkDocument(
                linkId = linkId, title = StackLink.cleanTitle(snap.name),
                modified = StackLink.lastModified(app, uri), hash = StackLink.sha256(bytes), uri = uri,
                text = StackLink.clipText(snap.doc.plainText()), preview = preview, protected = snap.protectedFile,
            ))
            if (result is ShareResult.Done) linkMap.keep(uri, linkId)
            result
        } finally { pdf.delete() }
    }

    fun undo() = history { it.undo() }
    fun redo() = history { it.redo() }
    private fun history(step: (AmiDoc) -> Boolean) {
        val d = doc ?: return
        if (step(d)) { refreshAll(); edited = true; refreshMatches() }
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
                publishIfShared(uri, bytes)
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
        doc?.close(); doc = null; source = null; paragraphs.clear(); isOpen = false; edited = false; isNew = false; focus = null; format = null; pending = Pending(); closeFind()
    }
}

