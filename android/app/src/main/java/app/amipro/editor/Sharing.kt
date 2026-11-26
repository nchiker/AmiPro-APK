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
}

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

    /** Null status (Filen missing or call refused) hides the item. [knownShared] is only what this app itself shared earlier. */
    fun items(status: PartnerStatus?, hasLocator: Boolean, knownShared: Boolean): List<ShareItem> {
        if (status == null) return emptyList()
        val connected = status.state == "connected" && status.name.isNotBlank()
        val who = if (connected) status.name else "partner"
        val share = ShareItem.Share(if (connected) "Share with $who" else "Share with partner…", !hasLocator, who)
            .let { if (!hasLocator && connected) it.copy(label = "Share with $who…") else it }
        val stop = if (connected && hasLocator && knownShared) ShareItem.Stop("Stop sharing with $who", who) else null
        return listOfNotNull(share, stop)
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

    fun intent(launch: ShareLaunch): Intent = Intent(Sharing.ACTION).setPackage("app.filenfiles")
        .putExtra(Sharing.EXTRA_UUID, launch.uuid).putExtra(Sharing.EXTRA_STOP, launch.stop)
}
