package app.amipro.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val Blue = Color(0xFF1A73E8)
private val LightScheme = lightColorScheme(
    primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFFDCECF9), onPrimaryContainer = Color(0xFF0B3D91),
    background = Color.White, surface = Color.White, onSurface = Color(0xFF202124), onSurfaceVariant = Color(0xFF5F6368),
    surfaceVariant = Color(0xFFF1F3F4), outlineVariant = Color(0xFFDADCE0),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF062E6F), primaryContainer = Color(0xFF1F3A5F), onPrimaryContainer = Color(0xFFD2E3FC),
    background = Color(0xFF17181A), surface = Color(0xFF17181A), onSurface = Color(0xFFE8EAED), onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceVariant = Color(0xFF26282B), outlineVariant = Color(0xFF3C4043),
)

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel get() = (application as AmiProApp).editor

    override fun onStart() {
        super.onStart()
        // Slice 4: if a file shared with Stack was deleted, tell Stack (only when the system says it does not exist).
        Thread { try { StackSync(applicationContext).restoreLinks(); StackSync(applicationContext).sweep() } catch (e: Exception) { } }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A rotation or process restore re-delivers the original intent: only act on a fresh launch.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val choice = (application as AmiProApp).themeChoice
            val dark = when (choice) { ThemeChoice.SYSTEM -> isSystemInDarkTheme(); ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true }
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable((if (dark) DarkScheme else LightScheme).background.toArgb()))
                @Suppress("DEPRECATION") run { window.statusBarColor = android.graphics.Color.TRANSPARENT; window.navigationBarColor = android.graphics.Color.TRANSPARENT }
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) { Surface(Modifier.fillMaxSize()) { App(vm) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Open with" from a file manager or another app (VIEW opens for reading, EDIT also asks to write). */
    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            intent.data?.let { vm.requestOpen(it) }
        }
    }
}

@Composable
private fun App(vm: EditorViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.requestOpen(uri)
    }
    val copyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveCopyTo(uri)
    }
    val context = LocalContext.current
    val shareFilen = remember { FilenSharing(context) }
    val sharer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onShareResult(r.resultCode == Activity.RESULT_OK, r.data)
    }
    LaunchedEffect(vm.shareLaunch) {
        vm.shareLaunch?.let { l ->
            vm.shareLaunched(l)
            try { sharer.launch(shareFilen.intent(l)) } catch (e: Exception) { vm.onShareResult(false, Intent().putExtra(Sharing.EXTRA_ERROR, "failed")) }
        }
    }
    val updater = (context.applicationContext as AmiProApp).updater
    val appState = context.applicationContext as AmiProApp
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showObjects by rememberSaveable { mutableStateOf(false) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    val openUpdates = { updater.reset(); showUpdates = true; updater.check() }
    val print = {
        val activity = context as android.app.Activity
        val printer = activity.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
        val data = vm.printData()
        if (data != null) printer.print(vm.name.ifBlank { "Document" }, DocumentPrintAdapter(activity, vm.name, data), printAttributesFor(data.setup))
        Unit
    }
    LaunchedEffect(vm.copyRequest) {
        vm.copyRequest?.let { copyPicker.launch(it); vm.copyRequest = null }
    }
    if (vm.isOpen) {
        var confirmClose by remember { mutableStateOf(false) }
        BackHandler { if (vm.edited) confirmClose = true else vm.close() }
        EditorScreen(vm, onOpen = { picker.launch(arrayOf("*/*")) }, onNew = vm::requestNew, onCopy = vm::requestCopy, onPrint = print, onUpdates = openUpdates, onTheme = { showTheme = true }, onObjects = { showObjects = true }, onClose = { if (vm.edited) confirmClose = true else vm.close() })
        if (vm.needFilenFirst) AlertDialog(
            onDismissRequest = { vm.needFilenFirst = false },
            title = { Text("Save it to Filen first") },
            text = { Text("Sharing works on files in Filen. Save a copy there, then share.") },
            confirmButton = { TextButton(onClick = { vm.needFilenFirst = false; vm.requestCopy() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.needFilenFirst = false }) { Text("Cancel") } },
        )
        if (confirmClose) AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close without saving?") },
            text = { Text("Your changes to ${vm.name} have not been saved.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.close() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
        )
    } else {
        StartScreen(vm, onOpen = { picker.launch(arrayOf("*/*")) }, onUpdates = openUpdates, onTheme = { showTheme = true }, version = updater.installedVersion, theme = appState.themeChoice)
    }
    vm.passwordRequest?.let { request ->
        var password by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.passwordRequest = null },
            title = { Text("Password") },
            text = {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (request.wrong) "Wrong password, try again" else "This document is protected") },
                    isError = request.wrong,
                )
            },
            confirmButton = { TextButton(onClick = { vm.open(request.uri, password) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { vm.passwordRequest = null }) { Text("Cancel") } },
        )
    }
    if (vm.pendingProtectedShare) AlertDialog(
        onDismissRequest = { vm.pendingProtectedShare = false },
        title = { Text("Share a password-protected file?") },
        text = { Text("Stack will keep readable text and a preview, without the password.") },
        confirmButton = { TextButton(onClick = { vm.shareWithStack(confirmedProtected = true) }) { Text("Share anyway") } },
        dismissButton = { TextButton(onClick = { vm.pendingProtectedShare = false }) { Text("Cancel") } },
    )
    if (vm.pendingNew) AlertDialog(
        onDismissRequest = { vm.pendingNew = false },
        title = { Text("Discard your changes?") },
        text = { Text("${vm.name} has changes that are not saved. Starting a new document will throw them away.") },
        confirmButton = { TextButton(onClick = { vm.newDocument() }) { Text("Discard and start new") } },
        dismissButton = { TextButton(onClick = { vm.pendingNew = false }) { Text("Keep editing") } },
    )
    vm.pendingOpen?.let { next ->
        AlertDialog(
            onDismissRequest = { vm.pendingOpen = null },
            title = { Text("Discard your changes?") },
            text = { Text("${vm.name} has changes that are not saved. Opening another document will throw them away.") },
            confirmButton = { TextButton(onClick = { vm.pendingOpen = null; vm.open(next) }) { Text("Discard and open") } },
            dismissButton = { TextButton(onClick = { vm.pendingOpen = null }) { Text("Keep editing") } },
        )
    }
    if (showObjects && vm.isOpen) ObjectsDialog(vm) { showObjects = false }
    if (showTheme) AlertDialog(
        onDismissRequest = { showTheme = false },
        title = { Text("Appearance") },
        text = {
            Column {
                ThemeChoice.entries.forEach { choice ->
                    Row(Modifier.fillMaxWidth().clickable { appState.chooseTheme(choice); showTheme = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = appState.themeChoice == choice, onClick = { appState.chooseTheme(choice); showTheme = false })
                        Text(choice.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Close") } },
    )
    if (showUpdates) UpdateDialog(updater, context) { showUpdates = false; updater.reset() }
    vm.pendingLosses?.let { losses ->
        AlertDialog(
            onDismissRequest = { vm.pendingLosses = null },
            title = { Text("This format cannot keep") },
            text = { Column { losses.forEach { Text("• $it") } } },
            confirmButton = { TextButton(onClick = { vm.confirmLosses() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { vm.pendingLosses = null }) { Text("Cancel") } },
        )
    }
}

/** One tinted action row in the style of the Stack app: a pastel icon disc, a title and a caption. */
@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, caption: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Open and New as action rows, the documents used before underneath, appearance and updates at the bottom. */
@Composable
private fun StartScreen(vm: EditorViewModel, onOpen: () -> Unit, onUpdates: () -> Unit, onTheme: () -> Unit, version: String, theme: ThemeChoice) {
    val recents = vm.recents
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text("AmiPro", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
        ActionRow(Icons.Filled.FolderOpen, "Open a document", "Ami Pro .sam or .amipro files", !vm.busy, onOpen)
        ActionRow(Icons.Filled.NoteAdd, "New document", "Start with a blank page", !vm.busy, vm::newDocument)
        vm.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (it == StackLink.NOT_INSTALLED) {
                val ctx = LocalContext.current
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StackLink.RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Get Stack") }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
        if (recents.isEmpty()) {
            Text("Documents you open or save appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(recents.toList(), key = { it.uri.toString() }) { file ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !vm.busy) { vm.requestOpen(file.uri) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(file.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                android.text.format.DateUtils.getRelativeTimeSpanString(
                                    file.openedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.forget(file.uri) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${file.name} from recent")
                            }
                        },
                    )
                }
            }
        }
        HorizontalDivider()
        ActionRow(Icons.Filled.Palette, "Appearance", theme.label, true, onTheme)
        ActionRow(Icons.Filled.SystemUpdate, "Check for updates", "Version $version", true, onUpdates)
    }
}

@Composable
private fun UpdateDialog(updater: Updater, context: android.content.Context, onDismiss: () -> Unit) {
    val state = updater.state
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    AlertDialog(
        onDismissRequest = { if (!busy || state is UpdateState.Installing) onDismiss() },
        title = { Text("Updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is UpdateState.Idle -> Text("Installed version ${updater.installedVersion}.")
                    is UpdateState.Checking -> Text("Checking for updates...")
                    is UpdateState.UpToDate -> Text("You have the newest version (${state.installed}).")
                    is UpdateState.Available -> {
                        Text("Version ${state.version.removePrefix("v")} is available. You have ${updater.installedVersion}.")
                        if (state.notes.isNotBlank()) Text(state.notes, style = MaterialTheme.typography.bodySmall)
                        Text("It is checked before it is installed.", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloading -> Text("Downloading ${state.version.removePrefix("v")}... ${state.percent}%")
                    is UpdateState.Ready -> Text("Version ${state.version.removePrefix("v")} is ready. Android will ask you to confirm.")
                    is UpdateState.NeedsPermission -> if (!state.opened) Text("Android needs your OK once. Next, switch on \"Allow from this source\" for AmiPro, then come back.") else Text("Switch on \"Allow from this source\", then tap Install again.")
                    is UpdateState.Installing -> Text("Installing...")
                    is UpdateState.Failed -> Text(state.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = updater::download) { Text("Download") }
                is UpdateState.Ready -> TextButton(onClick = { updater.install(context) }) { Text("Install") }
                is UpdateState.NeedsPermission -> TextButton(onClick = { updater.install(context) }) { Text(if (state.opened) "Install" else "Continue") }
                is UpdateState.Failed -> TextButton(onClick = updater::check) { Text("Try again") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state is UpdateState.Available || state is UpdateState.Ready || state is UpdateState.Failed) "Not now" else "Close") } },
    )
}
