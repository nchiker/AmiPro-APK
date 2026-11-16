package app.amipro.editor

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A document opened or saved before, kept so the start screen can offer it again. */
data class RecentFile(val uri: Uri, val name: String, val openedAt: Long)

/**
 * The most recently used documents, newest first, kept in the app's private preferences.
 *
 * A file picked through the system picker (or made on the create-file screen) can be given a lasting grant, so it
 * can be opened again after the app was closed. A file that another app merely handed over ("Open with") often
 * cannot; those are not listed, because a row that fails when tapped is worse than no row.
 */
class RecentFiles(context: Context) {
    private val prefs = context.getSharedPreferences("recent-files", Context.MODE_PRIVATE)
    private val resolver: ContentResolver = context.contentResolver

    fun load(): List<RecentFile> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                RecentFile(Uri.parse(o.getString("uri")), o.getString("name"), o.getLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Puts [uri] first. Returns the new list, or null when the file cannot be opened again later and so is not
     * listed. [keep] is the file open right now: its grant is never released.
     */
    fun add(uri: Uri, name: String, write: Boolean, keep: Uri?): List<RecentFile>? {
        if (!hold(uri, write)) return null
        val list = (listOf(RecentFile(uri, name, System.currentTimeMillis())) + load().filter { it.uri != uri })
        val shown = list.take(MAX)
        list.drop(MAX).forEach { release(it.uri, keep) }
        save(shown)
        return shown
    }

    fun remove(uri: Uri, keep: Uri?): List<RecentFile> {
        val rest = load().filter { it.uri != uri }
        release(uri, keep)
        save(rest)
        return rest
    }

    private fun save(list: List<RecentFile>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.openedAt)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** Asks for a lasting grant (read and, when we have it, write); true if one is held afterwards. */
    private fun hold(uri: Uri, write: Boolean): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Not offered for this file; it may still be held from an earlier open.
        }
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    private fun release(uri: Uri, keep: Uri?) {
        if (uri == keep) return
        try {
            val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
            var flags = 0
            if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Nothing held, nothing to release.
        }
    }

    private companion object {
        const val KEY = "list"
        const val MAX = 8
    }
}
