package app.amipro.editor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Which file is linked to Stack under which id, kept in app-private storage so a rename or move keeps the link. */
class LinkMap(context: Context) {
    private val prefs = context.getSharedPreferences("stacklink", Context.MODE_PRIVATE)

    private fun read(): JSONObject = try { JSONObject(prefs.getString("map", null) ?: "{}") } catch (e: Exception) { JSONObject() }

    /** The id this file has, or a fresh one that is NOT kept until [keep] is called (after Stack accepted it). */
    @Synchronized fun idOrNew(uri: Uri): String = existingId(uri) ?: UUID.randomUUID().toString()

    @Synchronized fun keep(uri: Uri, id: String) {
        prefs.edit().putString("map", read().put(uri.toString(), id).toString())
            .putStringSet("gone", (prefs.getStringSet("gone", emptySet()) ?: emptySet()) - id).apply()
    }

    /** Every (uri, link id) pair. */
    @Synchronized fun entries(): List<Pair<Uri, String>> {
        val map = read()
        return map.keys().asSequence().map { Uri.parse(it) to map.optString(it) }.filter { it.second.isNotEmpty() }.toList()
    }

    /** True if Stack was already told this link's file is gone (cleared when the file is published again). */
    @Synchronized fun goneReported(id: String) = id in (prefs.getStringSet("gone", emptySet()) ?: emptySet())

    @Synchronized fun markGoneReported(id: String) {
        prefs.edit().putStringSet("gone", (prefs.getStringSet("gone", emptySet()) ?: emptySet()) + id).apply()
    }

    @Synchronized fun existingId(uri: Uri): String? = read().optString(uri.toString()).takeIf { it.isNotEmpty() }

    @Synchronized fun uriFor(linkId: String): Uri? {
        val map = read()
        for (key in map.keys()) if (map.optString(key) == linkId) return Uri.parse(key)
        return null
    }
}

/** What a share produces: Stack's answer, or why it did not happen. */
sealed class ShareResult {
    data class Done(val created: Boolean, val changed: Boolean) : ShareResult()
    data class Failed(val reason: String) : ShareResult()
}

/** One document as Stack wants it (docs/STACK_LINK_CONTRACT.md, contract 1). */
class LinkDocument(
    val linkId: String, val title: String, val modified: Long, val hash: String,
    val uri: Uri, val text: String, val preview: File?, val protected: Boolean,
)

/** The sending side of the Stack link: the four provider calls, in order, with nothing kept on failure. */
class StackLink(private val context: Context, private val stackPackage: String = BuildConfig.STACK_PACKAGE) {
    private val authority get() = "$stackPackage.link"
    private val resolver get() = context.contentResolver

    fun installed(): Boolean = try { context.packageManager.getPackageInfo(stackPackage, 0); true } catch (e: Exception) { false }

    fun share(doc: LinkDocument): ShareResult {
        if (!installed()) return ShareResult.Failed("Stack is not installed on this phone.")
        var token: String? = null
        try {
            val versions = resolver.call(authority, "contract", null, null)
                ?: return ShareResult.Failed("Stack did not answer. Open Stack once and try again.")
            if (1 !in versions.getInt("min", 1)..versions.getInt("max", 0)) return ShareResult.Failed("Stack and Ami Pro need an update to understand each other.")
            val begun = resolver.call(authority, "begin", record(doc).toString(), null) ?: return ShareResult.Failed("Stack did not answer.")
            begun.getString("error")?.let { return ShareResult.Failed("Stack refused it: $it ${begun.getString("message").orEmpty()}".trim()) }
            token = begun.getString("token") ?: return ShareResult.Failed("Stack did not answer.")
            stage(token, "text", doc.text.toByteArray(Charsets.UTF_8).inputStream())
            doc.preview?.let { f -> f.inputStream().use { stage(token, "preview", it) } }
            val done = resolver.call(authority, "commit", token, null) ?: return ShareResult.Failed("Stack did not answer.")
            done.getString("error")?.let { return ShareResult.Failed("Stack refused it: $it ${done.getString("message").orEmpty()}".trim()) }
            token = null
            return ShareResult.Done(done.getBoolean("created"), done.getBoolean("content_changed"))
        } catch (e: SecurityException) {
            return ShareResult.Failed("Stack does not trust this copy of Ami Pro.")
        } catch (e: Exception) {
            return ShareResult.Failed("Could not reach Stack: ${e.message}")
        } finally {
            token?.let { try { resolver.call(authority, "abort", it, null) } catch (e: Exception) { } }
        }
    }

    /** Tells Stack the file is gone. Null when Stack took it (or did not know the link); otherwise a short reason. */
    fun removed(linkId: String, reason: String): String? {
        if (!installed()) return "not_installed"
        return try {
            val body = JSONObject().put("contract", 1).put("owner", context.packageName).put("link_id", linkId).put("reason", reason)
            val answer = resolver.call(authority, "removed", body.toString(), null) ?: return "no_answer"
            answer.getString("error")?.takeIf { it != "unknown_link" }
        } catch (e: SecurityException) { "untrusted" } catch (e: Exception) { "unreachable" }
    }

    private fun stage(token: String, part: String, input: java.io.InputStream) {
        val out = resolver.openOutputStream(Uri.parse("content://$authority/stage/$token/$part"), "w") ?: error("Stack would not take the $part")
        out.use { input.copyTo(it) }
    }

    private fun record(doc: LinkDocument): JSONObject = JSONObject()
        .put("contract", 1)
        .put("owner", context.packageName)
        .put("link_id", doc.linkId)
        .put("kind", "doc")
        .put("title", doc.title)
        .put("mime", "application/x-amipro-document")
        .put("modified", doc.modified)
        .put("hash", doc.hash)
        .put("locators", JSONArray().put(JSONObject().put("type", "saf").put("uri", doc.uri.toString())))
        .put("shared", false)
        .put("protected", doc.protected)

    companion object {
        const val MAX_TEXT_CHARS = 1_000_000
        const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** 1 to 200 characters, no control or direction-changing characters (what Stack's validator accepts). */
        fun cleanTitle(name: String): String {
            val base = name.substringBeforeLast('.').ifBlank { name }
            val kept = base.filter { c ->
                !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT.toInt() &&
                    c != Char(0x2028) && c != Char(0x2029)
            }.trim()
            val cut = if (kept.length > 200) kept.take(if (Character.isHighSurrogate(kept[199])) 199 else 200).trimEnd() else kept
            return cut.ifBlank { "Untitled" }
        }

        /** Never more characters than Stack takes, and never half of a surrogate pair. */
        fun clipText(text: String): String {
            if (text.length <= MAX_TEXT_CHARS) return text
            val end = if (Character.isHighSurrogate(text[MAX_TEXT_CHARS - 1])) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
            return text.substring(0, end)
        }

        fun lastModified(context: Context, uri: Uri): Long = try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        } catch (e: Exception) { null } ?: System.currentTimeMillis()
    }
}

/** The state of a linked file, as far as we can be sure. Only [Gone] may ever be reported as a deletion. */
sealed class FileRead {
    class Bytes(val bytes: ByteArray) : FileRead()
    object Gone : FileRead()
    object Unreachable : FileRead()
}

/**
 * Slice 4: answering Stack's `stat` and `refresh`, and telling Stack when a linked file is gone. A file counts as gone
 * only when the system says the document does not exist; any other failure (no permission, storage away) is "unreachable".
 */
class StackSync(private val context: Context) {
    private val links = LinkMap(context)
    private val stack = StackLink(context)

    fun read(uri: Uri): FileRead = try {
        context.contentResolver.openInputStream(uri)?.use { FileRead.Bytes(it.readBytes()) } ?: FileRead.Unreachable
    } catch (e: java.io.FileNotFoundException) {
        if (documentMissing(uri)) FileRead.Gone else FileRead.Unreachable
    } catch (e: Exception) { FileRead.Unreachable }

    /** True only if the provider answers and has no such document (an empty answer, or its own "not found"). */
    private fun documentMissing(uri: Uri): Boolean = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.count == 0 } ?: false
    } catch (e: java.io.FileNotFoundException) { true } catch (e: Exception) { false }

    fun stat(linkId: String): android.os.Bundle {
        val out = android.os.Bundle()
        val uri = links.uriFor(linkId) ?: return out.apply { putBoolean("exists", false) }
        return when (val file = read(uri)) {
            is FileRead.Bytes -> out.apply {
                putBoolean("exists", true)
                putString("hash", StackLink.sha256(file.bytes))
                putLong("modified", StackLink.lastModified(context, uri))
                putString("locators", JSONArray().put(JSONObject().put("type", "saf").put("uri", uri.toString())).toString())
            }
            FileRead.Gone -> out.apply { putBoolean("exists", false) }
            FileRead.Unreachable -> out.apply { putString("error", "unreachable") }
        }
    }

    /** Re-publishes the file as it is on disk, without any screen. Protected files are never published this way. */
    fun refresh(linkId: String): android.os.Bundle {
        val out = android.os.Bundle()
        fun fail(code: String) = out.apply { putString("error", code) }
        val uri = links.uriFor(linkId) ?: return fail("not_found")
        val bytes = when (val file = read(uri)) {
            is FileRead.Bytes -> file.bytes
            FileRead.Gone -> return fail("not_found")
            FileRead.Unreachable -> return fail("unreachable")
        }
        val name = displayName(uri)
        val doc = try {
            uniffi.amipro_mobile.openDocument(bytes, name.substringBeforeLast('.'), null)
        } catch (e: uniffi.amipro_mobile.AmiException.PasswordRequired) { return fail("protected")
        } catch (e: uniffi.amipro_mobile.AmiException.WrongPassword) { return fail("protected")
        } catch (e: Exception) { return fail("unreachable") }
        try {
            val result = publish(uri, bytes, name, doc.plainText(), printDataOf(doc, doc.paragraphs()), false)
            return if (result is ShareResult.Done) out.apply { putBoolean("ok", true) } else fail("unreachable")
        } finally { doc.close() }
    }

    /** Preview, record, and the four calls: shared by Share, publish on save, and `refresh`. Keeps the link only on success. */
    fun publish(uri: Uri, bytes: ByteArray, name: String, text: String, data: PrintData, isProtected: Boolean): ShareResult {
        val pdf = File.createTempFile("stack-preview", ".pdf", context.cacheDir)
        try {
            val preview = try {
                DocumentPrintAdapter(context, name, data).writePdf(pdf)
                pdf.takeIf { it.length() in 1..StackLink.MAX_PREVIEW_BYTES }
            } catch (e: Exception) { android.util.Log.w("StackLink", "preview failed", e); null }
            val linkId = links.idOrNew(uri)
            val result = stack.share(LinkDocument(
                linkId = linkId, title = StackLink.cleanTitle(name),
                modified = StackLink.lastModified(context, uri), hash = StackLink.sha256(bytes), uri = uri,
                text = StackLink.clipText(text), preview = preview, protected = isProtected,
            ))
            if (result is ShareResult.Done) links.keep(uri, linkId)
            return result
        } finally { pdf.delete() }
    }

    /** Tells Stack about every linked file that is explicitly gone, once each. Call off the main thread. */
    fun sweep() {
        if (!stack.installed()) return
        for ((uri, id) in links.entries()) {
            if (links.goneReported(id)) continue
            if (read(uri) is FileRead.Gone && stack.removed(id, "deleted") == null) links.markGoneReported(id)
        }
    }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
}
