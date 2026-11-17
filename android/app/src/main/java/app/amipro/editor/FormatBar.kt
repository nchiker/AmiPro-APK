package app.amipro.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import uniffi.amipro_mobile.Align
import uniffi.amipro_mobile.Attr
import uniffi.amipro_mobile.LineSpacingChoice
import kotlin.math.roundToInt

private val Palette = listOf(
    0x000000, 0x5F6368, 0xC62828, 0xE65100, 0xF9A825, 0x2E7D32, 0x00838F, 0x1A73E8, 0x4527A0, 0xAD1457, 0x795548,
)

/** Character formatting (B I U S, size, face, colour), paragraph style, alignment, indents and line spacing for the selection. */
@Composable
fun FormatBar(vm: EditorViewModel) {
    val state = vm.format ?: return
    val para = vm.focusParagraph
    val p = vm.pending
    var styleMenu by remember { mutableStateOf(false) }
    var fontMenu by remember { mutableStateOf(false) }
    var colourMenu by remember { mutableStateOf(false) }
    var spacingMenu by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Column {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Toggle("B", TextStyle(fontWeight = FontWeight.Bold), p.bold ?: state.bold, "Bold") { vm.toggle(Attr.BOLD) }
                Toggle("I", TextStyle(fontStyle = FontStyle.Italic), p.italic ?: state.italic, "Italic") { vm.toggle(Attr.ITALIC) }
                Toggle("U", TextStyle(textDecoration = TextDecoration.Underline), p.underline ?: state.underline, "Underline") { vm.toggle(Attr.UNDERLINE) }
                Toggle("S", TextStyle(textDecoration = TextDecoration.LineThrough), p.strike ?: state.strikethrough, "Strikethrough") { vm.toggle(Attr.STRIKETHROUGH) }
                IconButton(onClick = { vm.changeSize(-1f) }) { Text("A-", style = MaterialTheme.typography.titleMedium) }
                Text(vm.shownSize.roundToInt().toString(), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { vm.changeSize(1f) }) { Text("A+", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { fontMenu = true }) { Text((p.family ?: "Font") + " ▾", maxLines = 1) }
                DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                    vm.fonts().forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, fontFamily = fontFor(name)) },
                            onClick = { fontMenu = false; vm.setFont(family = name) },
                        )
                    }
                }
                IconButton(onClick = { colourMenu = true }) {
                    Text("A", style = MaterialTheme.typography.titleMedium.copy(color = p.colour?.let { Color(0xFF000000.toInt() or it) } ?: MaterialTheme.colorScheme.onSurface, textDecoration = TextDecoration.Underline))
                }
                DropdownMenu(expanded = colourMenu, onDismissRequest = { colourMenu = false }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Palette.forEach { rgb ->
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(32.dp).background(Color(0xFF000000.toInt() or rgb), CircleShape)
                                    .clickable { colourMenu = false; vm.setFont(colour = rgb) },
                            )
                        }
                    }
                }
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
            HorizontalDivider()
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val align = para?.align
                Toggle(Icons.AutoMirrored.Filled.FormatAlignLeft, "Align left", align == Align.LEFT) { vm.setAlignment(Align.LEFT) }
                Toggle(Icons.Filled.FormatAlignCenter, "Centre", align == Align.CENTRE) { vm.setAlignment(Align.CENTRE) }
                Toggle(Icons.AutoMirrored.Filled.FormatAlignRight, "Align right", align == Align.RIGHT) { vm.setAlignment(Align.RIGHT) }
                Toggle(Icons.Filled.FormatAlignJustify, "Justify", align == Align.JUSTIFY) { vm.setAlignment(Align.JUSTIFY) }
                IconButton(onClick = { vm.changeIndent(-360) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Decrease indent") }
                IconButton(onClick = { vm.changeIndent(360) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Increase indent") }
                IconButton(onClick = { spacingMenu = true }) { Icon(Icons.Filled.FormatLineSpacing, "Line spacing") }
                DropdownMenu(expanded = spacingMenu, onDismissRequest = { spacingMenu = false }) {
                    listOf("Single" to LineSpacingChoice.SINGLE, "1.5 lines" to LineSpacingChoice.ONE_AND_HALF, "Double" to LineSpacingChoice.DOUBLE, "As the style" to LineSpacingChoice.STYLE)
                        .forEach { (label, choice) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { spacingMenu = false; vm.setLineSpacing(choice) })
                        }
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

@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, checked: Boolean, onClick: () -> Unit) {
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }) { Icon(icon, description) }
}
