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

    /** The id for this file (its uri), minted on first use. */
    @Synchronized fun idFor(uri: Uri): String {
        val map = read()
        val key = uri.toString()
        val existing = map.optString(key)
        if (existing.isNotEmpty()) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("map", map.put(key, id).toString()).apply()
        return id
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
