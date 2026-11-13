package app.amipro.editor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.amipro_mobile.Align
import uniffi.amipro_mobile.Paragraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onOpen: () -> Unit, onCopy: () -> Unit, onClose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { FormatBar(vm) },
        topBar = {
            TopAppBar(
                title = { Text(vm.name + if (vm.edited) " •" else "", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (vm.busy) CircularProgressIndicator(Modifier.padding(8.dp))
                    IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = vm::requestSave, enabled = vm.edited && !vm.busy) { Icon(Icons.Filled.Save, if (vm.canWriteBack) "Save" else "Save a copy") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Save a copy...") }, onClick = { menu = false; onCopy() }, enabled = !vm.busy)
                        DropdownMenuItem(text = { Text("Open another document...") }, onClick = { menu = false; onOpen() })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (vm.notes.isNotEmpty()) item {
                Text(vm.notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
            itemsIndexed(vm.paragraphs) { index, paragraph -> ParagraphField(vm, index, paragraph) }
        }
    }
}

@Composable
private fun ParagraphField(vm: EditorViewModel, index: Int, paragraph: Paragraph) {
    // While the user types, this field is the source of truth for its text: the core echoes every keystroke
    // back, and adopting an echo of an older keystroke over newer local text drops and misplaces characters.
    // The text is re-read from the core only when the paragraph list changed shape (split, join, undo, redo).
    var value by remember(index, vm.epoch) { mutableStateOf(TextFieldValue(paragraph.text)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.caretTarget) {
        val target = vm.caretTarget
        if (target != null && target.first == index) {
            value = value.copy(selection = TextRange(target.second.coerceAtMost(value.text.length)))
            vm.onSelection(index, value.selection.start, value.selection.end, true)
            runCatching { focus.requestFocus() }
            vm.caretTarget = null
        }
    }
    val dark = isSystemInDarkTheme()
    val transformation = remember(paragraph, dark) { StyleTransformation(paragraph, dark) }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val old = value
            value = new
            val typed = new.text != old.text
            if (typed) {
                val change = diff(old.text, new.text)
                vm.replace(index, change.start, change.oldEnd, change.inserted.replace("\r", ""), new.selection.end)
            }
            vm.onSelection(index, new.selection.start, new.selection.end, typed)
        },
        textStyle = baseStyle(paragraph).copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) vm.onSelection(index, value.selection.start, value.selection.end, false) }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                    value.selection.collapsed && value.selection.start == 0 && index > 0
                ) { vm.joinWithPrevious(index); true } else false
            },
    )
}

/** The paragraph style's own look (what the whole paragraph has before any span). */
private fun baseStyle(p: Paragraph) = TextStyle(
    fontFamily = fontFor(p.family),
    fontSize = p.sizePoints.sp,
    fontWeight = if (p.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (p.italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = if (p.underline) TextDecoration.Underline else TextDecoration.None,
    textAlign = when (p.align) {
        Align.LEFT -> TextAlign.Start; Align.RIGHT -> TextAlign.End; Align.CENTRE -> TextAlign.Center; Align.JUSTIFY -> TextAlign.Justify
    },
)

/** Ami Pro faces the phone does not have fall back to the nearest of serif, sans-serif or monospace. */
private fun fontFor(family: String): FontFamily {
    val f = family.lowercase()
    return when {
        listOf("courier", "mono", "consolas", "lucida console", "terminal").any { it in f } -> FontFamily.Monospace
        listOf("times", "roman", "georgia", "garamond", "palatino", "century", "book", "serif").any { it in f } && "sans" !in f -> FontFamily.Serif
        else -> FontFamily.SansSerif
    }
}

/** Draws the core's character spans (and object placeholders) over the text; offsets are unchanged. */
private class StyleTransformation(private val p: Paragraph, private val dark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // Same length, so offsets map 1:1: an object shows as a box, Ami Pro's hard return (U+2028) as a line break.
        val shown = text.text.replace(OBJECT_CHAR, Char(0x25A3)).replace(HARD_RETURN, '\n')
        val styled = AnnotatedString.Builder(shown).apply {
            for (s in p.spans) {
                val start = s.start.toInt().coerceIn(0, shown.length)
                val end = s.end.toInt().coerceIn(start, shown.length)
                if (start == end) continue
                addStyle(
                    SpanStyle(
                        fontWeight = s.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
                        fontStyle = s.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                        textDecoration = combine(s.underline, s.strikethrough),
                        fontSize = s.sizePoints?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                        fontFamily = s.family?.let(::fontFor),
                        color = s.colour?.let { c -> Color(0xFF000000.toInt() or c.toInt()).takeUnless { dark && it.luminance() < 0.15f } } ?: Color.Unspecified,
                    ),
                    start, end,
                )
            }
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private fun combine(underline: Boolean?, strike: Boolean?): TextDecoration? {
        if (underline == null && strike == null) return null
        val parts = listOfNotNull(
            TextDecoration.Underline.takeIf { underline == true },
            TextDecoration.LineThrough.takeIf { strike == true },
        )
        return if (parts.isEmpty()) TextDecoration.None else TextDecoration.combine(parts)
    }
}

private class Change(val start: Int, val oldEnd: Int, val inserted: String)

/** The smallest single replacement that turns [old] into [new] (never splitting a surrogate pair). */
private fun diff(old: String, new: String): Change {
    var prefix = 0
    val limit = minOf(old.length, new.length)
    while (prefix < limit && old[prefix] == new[prefix]) prefix++
    if (prefix > 0 && prefix < old.length && old[prefix].isLowSurrogate() && old[prefix - 1].isHighSurrogate()) prefix--
    var suffix = 0
    while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix--
    return Change(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
}

private val OBJECT_CHAR = Char(0xFFFC)
private val HARD_RETURN = Char(0x2028)
