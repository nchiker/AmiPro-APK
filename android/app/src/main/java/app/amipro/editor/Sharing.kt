package app.amipro.editor

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Filen's answer to `partner_status` (I4 F9). */
class PartnerStatus(val state: String, val name: String)

/** What the menu shows for sharing one document with Filen's partner. */
sealed class ShareItem {
    /** [label] is the whole menu text. [needsFilen] is true when the document is not in Filen yet. */
    data class Share(val label: String, val needsFilen: Boolean, val name: String) : ShareItem()
    data class Stop(val label: String, val name: String) : ShareItem()
    /** The document is not shared itself but a folder above it is: shown, not tappable. */
    data class Via(val label: String) : ShareItem()
}

/** Filen's answer to `share_state` (I4 F10): shared with the partner, and/or only through a folder above it. */
class ShareState(val shared: Boolean, val via: String?)

/** What to start in Filen: share (or stop sharing) the file with this Filen uuid. */
data class ShareLaunch(val uuid: String, val stop: Boolean)

/** The pure decisions: menu items for a partner state, and the message for Filen's result. */
object Sharing {
    const val ACTION = "app.filenfiles.action.SHARE_WITH_PARTNER"
    const val EXTRA_UUID = "app.filenfiles.extra.FILEN_UUID"
    const val EXTRA_STOP = "app.filenfiles.extra.STOP"
    const val EXTRA_SHARED = "app.filenfiles.extra.SHARED"
    const val EXTRA_ERROR = "app.filenfiles.extra.ERROR"
    const val EXTRA_MESSAGE = "app.filenfiles.extra.MESSAGE"
    const val USER_CANCELLED = "user_cancelled"

    /** The menu text for putting this document in Stack's own search. It is not about the partner, so it does not say "Share". */
    const val SHOW_IN_STACK = "Show in Stack search"

    /**
     * Null status (Filen missing or call refused) hides the item. [state] is Filen's `share_state` for the document; null =
     * unknown (offline, not answered): Share is offered. Shared -> Stop; shared only through a folder -> a disabled note.
     */
    fun items(status: PartnerStatus?, hasLocator: Boolean, state: ShareState?): List<ShareItem> {
        if (status == null) return emptyList()
        val connected = status.state == "connected" && status.name.isNotBlank()
        val who = if (connected) clean(status.name, MAX_NAME).ifEmpty { "partner" } else "partner"
        if (connected && hasLocator && state != null) {
            if (state.shared) return listOf(ShareItem.Stop("Stop sharing with $who", who))
            if (clean(state.via, MAX_NAME).isNotEmpty()) return listOf(ShareItem.Via("Shared with $who in ${clean(state.via, MAX_NAME)}"))
        }
        val share = ShareItem.Share(if (connected) "Share with $who" else "Share with partner…", !hasLocator, who)
            .let { if (!hasLocator && connected) it.copy(label = "Share with $who…") else it }
        return listOf(share)
    }

    const val MAX_NAME = 40
    const val MAX_MESSAGE = 200

    /** Text from another app, made safe to show: control and bidi characters removed, whitespace folded, cut to [max] characters. */
    fun clean(text: String?, max: Int): String {
        if (text == null) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); i += Character.charCount(cp)
            val t = Character.getType(cp)
            val bidi = cp in 0x200E..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x061C
            if (cp == '\n'.code || cp == '\t'.code || cp == ' '.code) { sb.append(' '); continue }
            if (bidi || t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() || t == Character.SURROGATE.toInt() ||
                t == Character.PRIVATE_USE.toInt() || t == Character.UNASSIGNED.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
                t == Character.PARAGRAPH_SEPARATOR.toInt()) continue
            sb.appendCodePoint(cp)
        }
        val folded = sb.toString().replace(Regex(" +"), " ").trim()
        if (folded.codePointCount(0, folded.length) <= max) return folded
        return folded.substring(0, folded.offsetByCodePoints(0, max))
    }

    /** The snackbar text for Filen's result, or null to say nothing. */
    fun resultMessage(ok: Boolean, shared: Boolean?, stop: Boolean, name: String, error: String?, detail: String?): String? {
        val name = clean(name, MAX_NAME).ifEmpty { "partner" }
        if (ok) return if (stop) "Stopped sharing" else if (shared == true) "Shared with $name" else null
        if (error == null || error == USER_CANCELLED) return null
        return "Couldn't share: " + (clean(detail, MAX_MESSAGE).ifEmpty { "try again." })
    }
}

/** Filen Files is trusted only if it is the pinned package with the pinned single signing certificate. */
object FilenPin {
    const val PACKAGE = "app.filenfiles"
    const val CERT = "59ccca4c8c1126ec48257a8f3051c6be5eccdd5888df82215560ce2ff8b580bf"

    /** The pure decision: [certs] are the SHA-256 digests (lowercase hex) of every signer; exactly one, and it must match. */
    fun decide(packageName: String?, certs: List<String>): Boolean = packageName == PACKAGE && certs.size == 1 && certs[0] == CERT

    @Suppress("DEPRECATION")
    private fun certs(context: Context): List<String>? = try {
        val info = context.packageManager.getPackageInfo(PACKAGE, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info.signingInfo
        if (si == null || si.hasMultipleSigners()) null
        else si.apkContentsSigners.map { sig ->
            java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }

    private fun installed(context: Context) = decide(PACKAGE, certs(context) ?: emptyList())

    /** True when [authority] is served by the pinned Filen Files. Checked before every provider call. */
    fun authority(context: Context, authority: String): Boolean = try {
        val p = context.packageManager.resolveContentProvider(authority, 0)
        p != null && p.packageName == PACKAGE && installed(context)
    } catch (e: Exception) { false }

    /** True when [intent] resolves to an activity of the pinned Filen Files. Checked before every intent. */
    fun activity(context: Context, intent: Intent): Boolean = try {
        val r = context.packageManager.resolveActivity(intent, 0)
        r?.activityInfo?.packageName == PACKAGE && installed(context)
    } catch (e: Exception) { false }
}

/** Calls into Filen Files for sharing. Every failure means "no answer", never an exception. */
class FilenSharing(private val context: Context) {
    fun partnerStatus(): PartnerStatus? = try {
        if (!FilenPin.authority(context, AUTHORITY)) return null
        val a = context.contentResolver.call(AUTHORITY, "partner_status", null, null)
        val state = a?.getString("state")
        if (a == null || a.getString("error") != null || state == null) null else PartnerStatus(state, a.getString("name").orEmpty())
    } catch (e: Exception) { null }

    /** Null when Filen does not know (offline, not found, signed out, no answer). */
    fun shareState(uuid: String): ShareState? = try {
        if (!FilenPin.authority(context, AUTHORITY)) return null
        val a = context.contentResolver.call(AUTHORITY, "share_state", null,
            android.os.Bundle().apply { putString(Sharing.EXTRA_UUID, uuid) })
        if (a == null || a.getString("error") != null || !a.containsKey("shared")) null
        else ShareState(a.getBoolean("shared"), a.getString("shared_via")?.takeIf { it.isNotBlank() })
    } catch (e: Exception) { null }

    /** The share intent, or null when Filen Files is not the pinned app. */
    fun intent(launch: ShareLaunch): Intent? =
        Intent(Sharing.ACTION).setPackage(FilenPin.PACKAGE).putExtra(Sharing.EXTRA_UUID, launch.uuid).putExtra(Sharing.EXTRA_STOP, launch.stop)
            .takeIf { FilenPin.activity(context, it) }

    private companion object { const val AUTHORITY = "app.filenfiles.stacklink" }
}
