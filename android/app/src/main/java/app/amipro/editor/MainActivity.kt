package app.amipro.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App(vm) } }
        }
    }
}

@Composable
private fun App(vm: EditorViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    if (vm.isOpen) {
        var confirmClose by remember { mutableStateOf(false) }
        BackHandler { if (vm.edited) confirmClose = true else vm.close() }
        EditorScreen(vm, onOpen = { picker.launch(arrayOf("*/*")) }, onClose = { if (vm.edited) confirmClose = true else vm.close() })
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
    vm.pendingLosses?.let { losses ->
        AlertDialog(
            onDismissRequest = { vm.pendingLosses = null },
            title = { Text("Saving will lose") },
            text = { Column { losses.forEach { Text("• $it") } } },
            confirmButton = { TextButton(onClick = { vm.save() }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = { vm.pendingLosses = null }) { Text("Cancel") } },
        )
    }
}
