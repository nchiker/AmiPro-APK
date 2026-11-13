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
    LaunchedEffect(vm.copyRequest) {
        vm.copyRequest?.let { copyPicker.launch(it); vm.copyRequest = null }
    }
    if (vm.isOpen) {
        var confirmClose by remember { mutableStateOf(false) }
        BackHandler { if (vm.edited) confirmClose = true else vm.close() }
        EditorScreen(vm, onOpen = { picker.launch(arrayOf("*/*")) }, onNew = vm::requestNew, onCopy = vm::requestCopy, onClose = { if (vm.edited) confirmClose = true else vm.close() })
        if (confirmClose) AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close without saving?") },
            text = { Text("Your changes to ${vm.name} have not been saved.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.close() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
        )
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("AmiPro", style = MaterialTheme.typography.headlineLarge)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !vm.busy) { Text("Open a document") }
            OutlinedButton(onClick = vm::newDocument, enabled = !vm.busy) { Text("New document") }
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
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
