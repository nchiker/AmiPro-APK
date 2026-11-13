package app.amipro.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import uniffi.amipro_mobile.Attr
import kotlin.math.roundToInt

/** Bold, italic, underline, strikethrough, size and paragraph style for the focused paragraph. */
@Composable
fun FormatBar(vm: EditorViewModel) {
    val state = vm.format ?: return
    val p = vm.pending
    var styleMenu by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Toggle("B", TextStyle(fontWeight = FontWeight.Bold), p.bold ?: state.bold, "Bold") { vm.toggle(Attr.BOLD) }
            Toggle("I", TextStyle(fontStyle = FontStyle.Italic), p.italic ?: state.italic, "Italic") { vm.toggle(Attr.ITALIC) }
            Toggle("U", TextStyle(textDecoration = TextDecoration.Underline), p.underline ?: state.underline, "Underline") { vm.toggle(Attr.UNDERLINE) }
            Toggle("S", TextStyle(textDecoration = TextDecoration.LineThrough), p.strike ?: state.strikethrough, "Strikethrough") { vm.toggle(Attr.STRIKETHROUGH) }
            IconButton(onClick = { vm.changeSize(-1f) }) { Text("A-", style = MaterialTheme.typography.titleMedium) }
            Text(vm.shownSize.roundToInt().toString(), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.changeSize(1f) }) { Text("A+", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = { styleMenu = true }) { Text(state.styleName + " ▾", maxLines = 1) }
            DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                vm.styles.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.name, fontWeight = if (style.id == state.styleId) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { styleMenu = false; vm.setStyle(style.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, look: TextStyle, checked: Boolean, description: String, onClick: () -> Unit) {
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Text(label, style = MaterialTheme.typography.titleMedium.merge(look))
    }
}
