package app.amipro.editor

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import java.security.MessageDigest

/**
 * What Stack calls to open a linked file here. Only `open` exists in this version. Stack's own signing certificate is
 * the only caller answered (exactly one signer, uid mapped to a single package); anything else gets a refusal.
 */
class StackLinkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (!callerAllowed()) throw SecurityException("Not allowed")
        return when (method) {
            "open" -> open(arg.orEmpty(), extras?.getString("mode"))
            else -> Bundle().apply { putString("error", "unknown_method") }
        }
    }

    private fun open(linkId: String, mode: String?): Bundle {
        val context = context!!
        val uri = LinkMap(context).uriFor(linkId)
        val reachable = uri != null && try { context.contentResolver.openInputStream(uri)?.use { true } ?: false } catch (e: Exception) { false }
        if (uri == null || !reachable) return Bundle().apply { putString("error", "not_found") }
        // An explicit intent for our own activity; no grant flags, no clip data, no selector. The file is opened with our own grant.
        val intent = Intent(if (mode == "view") Intent.ACTION_VIEW else Intent.ACTION_EDIT)
            .setClassName(context.packageName, MainActivity::class.java.name)
            .setData(uri)
        return Bundle().apply { putParcelable("intent", intent) }
    }

    private fun callerAllowed(): Boolean {
        val context = context ?: return false
        val names = context.packageManager.getPackagesForUid(Binder.getCallingUid())
        val caller = names?.singleOrNull() ?: return false
        if (caller != BuildConfig.STACK_PACKAGE) return false
        return certificateOf(caller) in STACK_CERTIFICATES
    }

    /** SHA-256 (lowercase hex) of the package's one signing certificate; null if it has none, several, or is unknown. */
    @Suppress("DEPRECATION")
    private fun certificateOf(packageName: String): String? = try {
        val manager = context!!.packageManager
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo
            if (signing == null || signing.hasMultipleSigners()) null else signing.apkContentsSigners
        } else manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        if (signers == null || signers.size != 1) null
        else MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        /** Stack's release signing certificate (~/APK-Keys/README.txt). */
        val STACK_CERTIFICATES = setOf("e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311")
    }
}
