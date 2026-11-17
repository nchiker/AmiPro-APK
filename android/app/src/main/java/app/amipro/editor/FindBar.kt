package app.amipro.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Find and replace: the search runs on the whole text; matches are highlighted in the page and stepped with the arrows. */
@Composable
fun FindBar(vm: EditorViewModel) {
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                CompactField(
                    vm.findQuery, { vm.findQuery = it; vm.refreshMatches() }, "Find",
                    Modifier.weight(1f).focusRequester(focus), ImeAction.Search, KeyboardActions(onSearch = { vm.find() }),
                )
                Text(
                    if (vm.matches.isEmpty()) (if (vm.findQuery.isEmpty()) "" else "0") else "${vm.matchIndex + 1}/${vm.matches.size}",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp).widthIn(min = 36.dp),
                )
                IconButton(onClick = { vm.step(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = { vm.step(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
                IconButton(onClick = vm::closeFind) { Icon(Icons.Filled.Close, "Close find") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactField(vm.replaceWith, { vm.replaceWith = it }, "Replace with", Modifier.weight(1f), ImeAction.Done, KeyboardActions.Default)
                TextButton(onClick = vm::replaceCurrent, enabled = vm.matches.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = vm::replaceEverything, enabled = vm.matches.isNotEmpty()) { Text("All") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = vm.findMatchCase, onClick = { vm.findMatchCase = !vm.findMatchCase; vm.refreshMatches() }, label = { Text("Match case") })
                FilterChip(selected = vm.findWholeWord, onClick = { vm.findWholeWord = !vm.findWholeWord; vm.refreshMatches() }, label = { Text("Whole word") })
            }
        }
    }
}

/** A single-line field 40 dp tall, so the find bar leaves the page visible. */
@Composable
private fun CompactField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, ime: ImeAction, actions: KeyboardActions) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ime), keyboardActions = actions,
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            }
        },
    )
}
