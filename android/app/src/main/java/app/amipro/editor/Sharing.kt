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

    /**
     * Null status (Filen missing or call refused) hides the item. [state] is Filen's `share_state` for the document; null =
     * unknown (offline, not answered): Share is offered. Shared -> Stop; shared only through a folder -> a disabled note.
     */
    fun items(status: PartnerStatus?, hasLocator: Boolean, state: ShareState?): List<ShareItem> {
        if (status == null) return emptyList()
        val connected = status.state == "connected" && status.name.isNotBlank()
        val who = if (connected) status.name else "partner"
        if (connected && hasLocator && state != null) {
            if (state.shared) return listOf(ShareItem.Stop("Stop sharing with $who", who))
            if (!state.via.isNullOrBlank()) return listOf(ShareItem.Via("Shared with $who in ${state.via}"))
        }
        val share = ShareItem.Share(if (connected) "Share with $who" else "Share with partner…", !hasLocator, who)
            .let { if (!hasLocator && connected) it.copy(label = "Share with $who…") else it }
        return listOf(share)
    }

    /** The snackbar text for Filen's result, or null to say nothing. */
    fun resultMessage(ok: Boolean, shared: Boolean?, stop: Boolean, name: String, error: String?, detail: String?): String? {
        if (ok) return if (stop) "Stopped sharing" else if (shared == true) "Shared with $name" else null
        if (error == null || error == USER_CANCELLED) return null
        return "Couldn't share: " + (detail?.takeIf { it.isNotBlank() } ?: "try again.")
    }
}

/** Calls into Filen Files for sharing. Every failure means "no answer", never an exception. */
class FilenSharing(private val context: Context) {
    fun partnerStatus(): PartnerStatus? = try {
        val a = context.contentResolver.call("app.filenfiles.stacklink", "partner_status", null, null)
        val state = a?.getString("state")
        if (a == null || a.getString("error") != null || state == null) null else PartnerStatus(state, a.getString("name").orEmpty())
    } catch (e: Exception) { null }

    /** Null when Filen does not know (offline, not found, signed out, no answer). */
    fun shareState(uuid: String): ShareState? = try {
        val a = context.contentResolver.call("app.filenfiles.stacklink", "share_state", null,
            android.os.Bundle().apply { putString(Sharing.EXTRA_UUID, uuid) })
        if (a == null || a.getString("error") != null || !a.containsKey("shared")) null
        else ShareState(a.getBoolean("shared"), a.getString("shared_via")?.takeIf { it.isNotBlank() })
    } catch (e: Exception) { null }

    fun intent(launch: ShareLaunch): Intent = Intent(Sharing.ACTION).setPackage("app.filenfiles")
        .putExtra(Sharing.EXTRA_UUID, launch.uuid).putExtra(Sharing.EXTRA_STOP, launch.stop)
}
