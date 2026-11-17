package app.amipro.editor

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel get() = (application as AmiProApp).editor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A rotation or process restore re-delivers the original intent: only act on a fresh launch.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App(vm) } }
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
    val updater = (context.applicationContext as AmiProApp).updater
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    val openUpdates = { updater.reset(); showUpdates = true; updater.check() }
    val print = {
        val activity = context as android.app.Activity
        val printer = activity.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
        printer.print(vm.name.ifBlank { "Document" }, DocumentPrintAdapter(activity, vm.name, vm.paragraphs.toList()), null)
        Unit
    }
    LaunchedEffect(vm.copyRequest) {
        vm.copyRequest?.let { copyPicker.launch(it); vm.copyRequest = null }
    }
    if (vm.isOpen) {
        var confirmClose by remember { mutableStateOf(false) }
        BackHandler { if (vm.edited) confirmClose = true else vm.close() }
        EditorScreen(vm, onOpen = { picker.launch(arrayOf("*/*")) }, onNew = vm::requestNew, onCopy = vm::requestCopy, onPrint = print, onUpdates = openUpdates, onClose = { if (vm.edited) confirmClose = true else vm.close() })
        if (confirmClose) AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close without saving?") },
            text = { Text("Your changes to ${vm.name} have not been saved.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.close() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
        )
    } else {
        StartScreen(vm, onOpen = { picker.launch(arrayOf("*/*")) }, onUpdates = openUpdates, version = updater.installedVersion)
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

/** Open and New, with the documents used before listed underneath (centred as before when there are none). */
@Composable
private fun StartScreen(vm: EditorViewModel, onOpen: () -> Unit, onUpdates: () -> Unit, version: String) {
    val recents = vm.recents
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = if (recents.isEmpty()) Arrangement.spacedBy(16.dp, Alignment.CenterVertically) else Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AmiPro", style = MaterialTheme.typography.headlineLarge)
        Button(onClick = onOpen, enabled = !vm.busy) { Text("Open a document") }
        OutlinedButton(onClick = vm::newDocument, enabled = !vm.busy) { Text("New document") }
        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onUpdates) { Text("Check for updates (version $version)") }
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Recent", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(recents.toList(), key = { it.uri.toString() }) { file ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !vm.busy) { vm.requestOpen(file.uri) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
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
                    HorizontalDivider()
                }
            }
        }
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
                    is UpdateState.Checking -> Text("Looking for a newer version on GitHub...")
                    is UpdateState.UpToDate -> Text("You have the newest version (${state.installed}).")
                    is UpdateState.Available -> {
                        Text("Version ${state.version.removePrefix("v")} is available (installed: ${updater.installedVersion}).")
                        if (state.notes.isNotBlank()) Text(state.notes, style = MaterialTheme.typography.bodySmall)
                        Text("The file is downloaded over a secure connection and checked against the installed app before anything is installed.", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloading -> Text("Downloading ${state.version.removePrefix("v")}... ${state.percent}%")
                    is UpdateState.Ready -> Text("Version ${state.version.removePrefix("v")} is downloaded and checked. Android will ask you to confirm the installation; your documents are not touched.")
                    is UpdateState.NeedsPermission -> Text("Allow AmiPro to install apps on the page Android just opened, then come back and tap Install again.")
                    is UpdateState.Installing -> Text("Waiting for Android's installer...")
                    is UpdateState.Failed -> Text(state.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = updater::download) { Text("Download") }
                is UpdateState.Ready, is UpdateState.NeedsPermission -> TextButton(onClick = { updater.install(context) }) { Text("Install") }
                is UpdateState.Failed -> TextButton(onClick = updater::check) { Text("Try again") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state is UpdateState.Available || state is UpdateState.Ready || state is UpdateState.Failed) "Not now" else "Close") } },
    )
}
