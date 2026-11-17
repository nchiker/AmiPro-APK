package app.amipro.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import uniffi.amipro_mobile.ObjectInfo
import uniffi.amipro_mobile.ObjectKind

/** When the caret is next to a picture, frame or table: its name with View and Delete. */
@Composable
fun ObjectBar(vm: EditorViewModel, onList: () -> Unit) {
    val f = vm.focus ?: return
    val objects = remember(vm.look, vm.epoch) { vm.objects() }
    if (objects.isEmpty()) return
    // The object the caret touches, or the one exactly selected.
    val near = objects.firstOrNull { o ->
        val p = o.paragraph.toInt(); val at = o.offset.toInt()
        (f.p0 == p && f.p1 == p) && ((f.collapsed && (f.s0 == at || f.s0 == at + 1)) || (f.s0 == at && f.s1 == at + 1))
    } ?: return
    var viewing by remember(near) { mutableStateOf(false) }
    var confirm by remember(near) { mutableStateOf(false) }
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(near.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton(onClick = { viewing = true }) { Text("View") }
            TextButton(onClick = { confirm = true }) { Text("Delete") }
        }
    }
    if (viewing) ObjectViewer(vm, near) { viewing = false }
    if (confirm) ConfirmDelete(vm, near) { confirm = false }
}

@Composable
private fun ConfirmDelete(vm: EditorViewModel, o: ObjectInfo, onDone: () -> Unit) = AlertDialog(
    onDismissRequest = onDone,
    title = { Text("Delete ${o.label}?") },
    text = { Text("It is removed from the document. Undo brings it back.") },
    confirmButton = { TextButton(onClick = { vm.deleteObject(o); onDone() }) { Text("Delete") } },
    dismissButton = { TextButton(onClick = onDone) { Text("Keep") } },
)

/** Every picture, frame and table of the document, to look at or delete. */
@Composable
fun ObjectsDialog(vm: EditorViewModel, onClose: () -> Unit) {
    val objects = remember(vm.epoch) { vm.objects().filter { it.kind == ObjectKind.PICTURE || it.kind == ObjectKind.TABLE || it.kind == ObjectKind.TEXT_FRAME } }
    var viewing by remember { mutableStateOf<ObjectInfo?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pictures and tables") },
        text = {
            if (objects.isEmpty()) Text("This document has no pictures, frames or tables.")
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                objects.forEach { o ->
                    Text(o.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { viewing = o }.padding(vertical = 12.dp))
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
    viewing?.let { ObjectViewer(vm, it) { viewing = null } }
}

@Composable
private fun ObjectViewer(vm: EditorViewModel, o: ObjectInfo, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(o.label, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (o.kind) {
                    ObjectKind.PICTURE -> {
                        val bitmap = remember(o) { vm.pictureImage(o)?.let { BitmapFactory.decodeByteArray(it.png, 0, it.png.size)?.asImageBitmap() } }
                        if (bitmap != null) Image(bitmap, o.label, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        else Text("This kind of picture cannot be shown on the phone. It is kept in the document and printed as a box.")
                    }
                    ObjectKind.TABLE -> {
                        val table = remember(o) { vm.tableText(o) }
                        if (table == null) Text("This table cannot be shown.")
                        else table.cells.chunked(table.columns.toInt()).forEach { row ->
                            Row(Modifier.fillMaxWidth().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                row.forEach { cell -> Text(cell.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(4.dp)) }
                            }
                        }
                    }
                    ObjectKind.TEXT_FRAME -> Text(remember(o) { vm.frameText(o) }.orEmpty().ifBlank { "(empty)" })
                    else -> Text(o.label)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (o.kind != ObjectKind.PAGE_BREAK || true) TextButton(onClick = { confirm = true }) { Text("Delete") } },
    )
    if (confirm) ConfirmDelete(vm, o) { confirm = false; onClose() }
}
