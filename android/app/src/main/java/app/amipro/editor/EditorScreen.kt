package app.amipro.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.amipro_mobile.Align
import uniffi.amipro_mobile.Paragraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onOpen: () -> Unit, onNew: () -> Unit, onCopy: () -> Unit, onPrint: () -> Unit,
    onUpdates: () -> Unit, onTheme: () -> Unit, onObjects: () -> Unit, onClose: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(vm.message) {
        vm.message?.let {
            val getStack = it == StackLink.NOT_INSTALLED
            vm.message = null
            val r = snackbar.showSnackbar(it, actionLabel = if (getStack) "Get Stack" else null, duration = if (getStack) SnackbarDuration.Long else SnackbarDuration.Short)
            if (getStack && r == SnackbarResult.ActionPerformed) {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(StackLink.RELEASES_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (vm.findOpen) FindBar(vm)
                ObjectBar(vm, onObjects)
                if (!vm.findOpen) FormatBar(vm)
            }
        },
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
                        DropdownMenuItem(text = { Text("Find and replace...") }, onClick = { menu = false; vm.openFind() })
                        DropdownMenuItem(text = { Text("Pictures and tables...") }, onClick = { menu = false; onObjects() })
                        DropdownMenuItem(text = { Text("Print...") }, onClick = { menu = false; onPrint() }, enabled = !vm.busy)
                        DropdownMenuItem(text = { Text("Share with Stack") }, onClick = { menu = false; vm.shareWithStack() }, enabled = !vm.busy)
                        DropdownMenuItem(text = { Text("New document") }, onClick = { menu = false; onNew() })
                        DropdownMenuItem(text = { Text("Save a copy...") }, onClick = { menu = false; onCopy() }, enabled = !vm.busy)
                        DropdownMenuItem(text = { Text("Open another document...") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Appearance...") }, onClick = { menu = false; onTheme() })
                        DropdownMenuItem(text = { Text("Check for updates...") }, onClick = { menu = false; onUpdates() })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (vm.notes.isNotEmpty()) {
                Text(vm.notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
            DocumentField(vm, scroll)
        }
    }
}

/**
 * The whole document is one text field (paragraphs separated by a newline), so selection handles, copy, cut and paste work
 * across paragraphs. Every change is turned into one replacement between two (paragraph, offset) positions for the core.
 * The field is the source of truth for its text while the user types; it is re-read from the core only when [EditorViewModel.epoch]
 * changes (undo, redo, a refused edit, replace).
 */
@Composable
private fun DocumentField(vm: EditorViewModel, scroll: ScrollState) {
    val lastSelection = remember { intArrayOf(0) }
    var value by remember(vm.epoch) {
        val text = vm.docText
        val at = (vm.caretTarget ?: lastSelection[0]).coerceIn(0, text.length)
        mutableStateOf(TextFieldValue(text, TextRange(at)))
    }
    LaunchedEffect(vm.epoch) { vm.caretTarget = null }
    val focus = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldTop by remember { mutableFloatStateOf(0f) }
    val dark = isSystemInDarkTheme()

    LaunchedEffect(vm.selectRequest) {
        val r = vm.selectRequest ?: return@LaunchedEffect
        vm.selectRequest = null
        val range = TextRange(r.start.coerceIn(0, value.text.length), r.end.coerceIn(0, value.text.length))
        value = value.copy(selection = range)
        reportSelection(vm, value.text, range, typed = false)
        layout?.let { l ->
            val top = l.getBoundingBox(range.start.coerceAtMost((l.layoutInput.text.length - 1).coerceAtLeast(0))).top
            scroll.animateScrollTo((fieldTop + top - 160f).toInt().coerceAtLeast(0))
        }
    }

    val matchRanges = vm.matches.map { m ->
        TextRange(vm.globalOffset(m.paragraph.toInt(), m.start.toInt()), vm.globalOffset(m.paragraph.toInt(), m.end.toInt()))
    }
    val transformation = remember(vm.look, vm.epoch, dark, matchRanges, vm.matchIndex) {
        DocumentTransformation(vm.paragraphs.toList(), dark, matchRanges, vm.matchIndex)
    }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val old = value
            value = new
            val typed = new.text != old.text
            if (typed) {
                val change = diff(old.text, new.text)
                val (p0, s0) = locate(old.text, change.start)
                val (p1, s1) = locate(old.text, change.oldEnd)
                vm.replaceRange(p0, s0, p1, s1, change.inserted, old.selection.end)
            }
            lastSelection[0] = new.selection.end
            reportSelection(vm, new.text, new.selection, typed)
        },
        textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
        onTextLayout = { layout = it },
        decorationBox = { inner ->
            // An empty document would otherwise show nothing to tap.
            if (value.text.isEmpty()) {
                Text("Start typing", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .focusRequester(focus)
            .onGloballyPositioned { fieldTop = it.positionInParent().y }
            .onFocusChanged { if (it.isFocused) reportSelection(vm, value.text, value.selection, typed = false) },
    )
    LaunchedEffect(vm.epoch) {
        // A new empty document opens with the keyboard up.
        if (vm.docText.isEmpty()) runCatching { focus.requestFocus() }
    }
}

private fun reportSelection(vm: EditorViewModel, text: String, range: TextRange, typed: Boolean) {
    val (p0, s0) = locate(text, range.min)
    val (p1, s1) = locate(text, range.max)
    vm.onSelection(p0, s0, p1, s1, typed)
}

/** (paragraph, offset in it) of [offset] in a text whose paragraphs are separated by newlines. */
internal fun locate(text: String, offset: Int): Pair<Int, Int> {
    var paragraph = 0
    var lineStart = 0
    val end = offset.coerceIn(0, text.length)
    for (i in 0 until end) if (text[i] == '\n') { paragraph++; lineStart = i + 1 }
    return paragraph to (end - lineStart)
}

/** Ami Pro faces the phone does not have fall back to the nearest of serif, sans-serif or monospace. */
internal fun fontFor(family: String): FontFamily {
    val f = family.lowercase()
    return when {
        listOf("courier", "mono", "consolas", "lucida console", "terminal").any { it in f } -> FontFamily.Monospace
        listOf("times", "roman", "georgia", "garamond", "palatino", "century", "book", "serif").any { it in f } && "sans" !in f -> FontFamily.Serif
        else -> FontFamily.SansSerif
    }
}

/**
 * Draws each paragraph's own look (face, size, alignment, indents, line spacing, character spans) and the search matches over
 * the one text. Offsets map 1:1, so selection and the caret work as on plain text. When the field is momentarily ahead of
 * the core (a keystroke in flight) the paragraph count differs and the text is drawn plain for that frame.
 */
private class DocumentTransformation(
    private val paragraphs: List<Paragraph>,
    private val dark: Boolean,
    private val matches: List<TextRange>,
    private val current: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        // An object shows as a box, Ami Pro's hard return (U+2028) as a line break; both are one character, so offsets are unchanged.
        val shown = raw.replace(OBJECT_CHAR, Char(0x25A3)).replace(HARD_RETURN, '\n')
        val builder = AnnotatedString.Builder(shown)
        val lines = raw.split('\n')
        if (lines.size == paragraphs.size) {
            var start = 0
            for ((i, line) in lines.withIndex()) {
                val p = paragraphs[i]
                val end = start + line.length
                val big = (p.spans.mapNotNull { it.sizePoints }.maxOrNull() ?: 0f).coerceAtLeast(p.sizePoints)
                if (end > start) {
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = fontFor(p.family), fontSize = p.sizePoints.sp,
                            fontWeight = if (p.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (p.italic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (p.underline) TextDecoration.Underline else TextDecoration.None,
                        ),
                        start, end,
                    )
                }
                if (start < shown.length) {
                    // The newline that ends the paragraph is part of it and makes a short last line: a small gap between paragraphs.
                    if (end < shown.length) builder.addStyle(SpanStyle(fontSize = 4.sp), end, end + 1)
                    builder.addStyle(
                        ParagraphStyle(
                            textAlign = when (p.align) { Align.LEFT -> TextAlign.Start; Align.RIGHT -> TextAlign.End; Align.CENTRE -> TextAlign.Center; Align.JUSTIFY -> TextAlign.Justify },
                            lineHeight = if (p.lineSpacingFactor == 1f) androidx.compose.ui.unit.TextUnit.Unspecified else (big * 1.2f * p.lineSpacingFactor).sp,
                            textIndent = TextIndent(((p.indentLeft + p.indentFirst) / 20f).sp, (p.indentLeft / 20f).sp),
                        ),
                        start, (end + 1).coerceAtMost(shown.length),
                    )
                }
                for (s in p.spans) {
                    val from = (start + s.start.toInt()).coerceIn(start, end)
                    val to = (start + s.end.toInt()).coerceIn(from, end)
                    if (from == to) continue
                    builder.addStyle(
                        SpanStyle(
                            fontWeight = s.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
                            fontStyle = s.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                            textDecoration = combine(s.underline, s.strikethrough),
                            fontSize = s.sizePoints?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                            fontFamily = s.family?.let(::fontFor),
                            color = s.colour?.let { c -> Color(0xFF000000.toInt() or c.toInt()).takeUnless { dark && it.luminance() < 0.15f } } ?: Color.Unspecified,
                        ),
                        from, to,
                    )
                }
                start = end + 1
            }
        }
        for ((i, m) in matches.withIndex()) {
            val from = m.start.coerceIn(0, shown.length); val to = m.end.coerceIn(from, shown.length)
            if (from < to) builder.addStyle(
                SpanStyle(background = if (i == current) Color(0xFFFFB300).copy(alpha = 0.75f) else Color(0xFFFFEE58).copy(alpha = 0.45f)),
                from, to,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
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

internal val OBJECT_CHAR = Char(0xFFFC)
private val HARD_RETURN = Char(0x2028)
