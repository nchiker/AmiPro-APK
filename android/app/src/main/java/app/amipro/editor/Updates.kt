package app.amipro.editor

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the "Check for updates" dialog shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val installed: String) : UpdateState
    data class Available(val version: String, val notes: String) : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
    /** Android's confirmation screen is up (or was); the result arrives from [InstallResultReceiver]. */
    data class Installing(val version: String) : UpdateState
    /** The person must allow this app to install apps (Android's own settings page was opened). */
    /** [opened]: false = explaining first; true = Android's settings page was opened and we wait for the person to come back. */
    data class NeedsPermission(val version: String, val opened: Boolean = false) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/** A published release as GitHub describes it. [sha256] is null when GitHub gave no digest for the file. */
data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val sha256: String?)

/**
 * Looks for a newer release on GitHub, downloads it, checks it, and hands it to Android's installer.
 *
 * The network is used ONLY when the person taps "Check for updates" (and then "Download", "Install"): nothing runs in the
 * background, nothing is sent but the anonymous request itself, and documents are never involved. Nothing installs by itself:
 * Android shows its own confirmation, and refuses a file that was not signed with the key of the installed app.
 */
class Updater(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var release: Release? = null
    private val dir get() = File(app.cacheDir, "update")
    private val apk get() = File(dir, "update.apk")

    val installedVersion: String
        get() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    fun reset() { if (state !is UpdateState.Checking && state !is UpdateState.Downloading) state = UpdateState.Idle }

    fun check() {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val latest = withContext(Dispatchers.IO) { fetchLatest() }
                if (isNewer(latest.version, installedVersion)) {
                    release = latest
                    UpdateState.Available(latest.version, latest.notes)
                } else UpdateState.UpToDate(installedVersion)
            } catch (e: Exception) {
                UpdateState.Failed("Could not look for updates: ${e.message ?: "no connection"}. Nothing was changed.")
            }
        }
    }

    fun download() {
        val r = release ?: return
        if (state !is UpdateState.Available) return
        state = UpdateState.Downloading(r.version, 0)
        scope.launch {
            state = try {
                withContext(Dispatchers.IO) { fetchApk(r) }
                UpdateState.Ready(r.version)
            } catch (e: Exception) {
                dir.deleteRecursively()
                UpdateState.Failed("${e.message ?: "The download failed"}. Nothing was changed.")
            }
        }
    }

    /** Starts Android's installer. If this app may not install apps yet, opens the setting that allows it and waits. */
    fun install(context: Context) {
        val version = when (val s = state) { is UpdateState.Ready -> s.version; is UpdateState.NeedsPermission -> s.version; else -> return }
        if (!app.packageManager.canRequestPackageInstalls()) {
            val explained = (state as? UpdateState.NeedsPermission)?.opened == false
            if (!explained) { state = UpdateState.NeedsPermission(version, opened = false); return }
            state = UpdateState.NeedsPermission(version, opened = true)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        state = UpdateState.Installing(version)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { startSession() }
            } catch (e: Exception) {
                state = UpdateState.Failed("Android could not start the installation: ${e.message ?: "unknown error"}. Nothing was changed.")
            }
        }
    }

    internal fun onInstallStatus(status: Int, detail: String?) {
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS -> { dir.deleteRecursively(); UpdateState.Idle }
            PackageInstaller.STATUS_FAILURE_ABORTED -> (release?.version ?: installedVersion).let { UpdateState.Ready(it) }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Android refused the update because it does not match the installed app (${detail ?: "signature"}). Nothing was changed.")
            else -> UpdateState.Failed("Android could not install the update (${detail ?: "code $status"}). Nothing was changed.")
        }
    }

    private fun fetchLatest(): Release {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) throw IllegalStateException("No release has been published yet")
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (!a.optString("name").endsWith(".apk")) continue
                val digest = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                return Release(j.getString("tag_name"), j.optString("body").trim().take(1200), a.getString("browser_download_url"), a.optLong("size"), digest)
            }
            throw IllegalStateException("The newest release has no app file")
        } finally { c.disconnect() }
    }

    private fun open(url: String, accept: String?): HttpURLConnection {
        require(url.startsWith("https://")) { "Only secure addresses are used" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            accept?.let { setRequestProperty("Accept", it) }
        }
    }

    private fun fetchApk(r: Release) {
        dir.deleteRecursively(); dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val c = open(r.apkUrl, null)
        try {
            if (c.responseCode != 200) throw IllegalStateException("The download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            var lastPercent = -1
            apk.outputStream().buffered().use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                        if (pct != lastPercent) { lastPercent = pct; state = UpdateState.Downloading(r.version, pct) }
                    }
                }
            }
        } finally { c.disconnect() }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !sha.equals(r.sha256, ignoreCase = true)) throw IllegalStateException("The downloaded file does not match its published checksum")
        verifyApk()
    }

    /** Before offering Install: the file is this app, newer, and signed with the same key as the installed one. */
    private fun verifyApk() {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val file = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IllegalStateException("The file is not an app Android can read")
        if (file.packageName != app.packageName) throw IllegalStateException("The file is for a different app (${file.packageName}); a debug build cannot be updated from a release")
        val mine = pm.getPackageInfo(app.packageName, flags)
        if (file.longVersionCode <= mine.longVersionCode) throw IllegalStateException("The file is not newer than the installed app")
        val theirs = file.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet().orEmpty()
        val ours = mine.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet().orEmpty()
        if (theirs.isEmpty() || theirs != ours) throw IllegalStateException("The file is not signed with the same key as this app")
    }

    private fun startSession() {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("update.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        const val REPO = "nchiker/AmiPro-APK"

        /** "v0.2.0" newer than "0.1.0"? Compares the dotted numbers; anything unreadable is not newer. */
        fun isNewer(tag: String, installed: String): Boolean {
            fun parts(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toLong() }.toList()
            val a = parts(tag); val b = parts(installed)
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Android's installer reports here; its confirmation screen is passed on to the user, the result goes to [Updater]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        (context.applicationContext as AmiProApp).updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
