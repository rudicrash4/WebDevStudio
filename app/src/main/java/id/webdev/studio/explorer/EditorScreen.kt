package id.webdev.studio.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.webdev.studio.ui.AppIcons
import id.webdev.studio.ui.Dev

/** Editor kode layar penuh: pewarnaan sintaks, nomor baris, undo/redo, baris tombol karakter cepat, simpan. */
@Composable
fun EditorScreen(
    name: String, path: String, initial: String, editable: Boolean, notice: String?,
    onSave: (String, (String?) -> Unit) -> Unit, onClose: () -> Unit
) {
    var tfv by remember(path) { mutableStateOf(TextFieldValue(initial)) }
    var saved by remember(path) { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val undo = remember(path) { ArrayList<String>() }
    val redo = remember(path) { ArrayList<String>() }
    var lastPush by remember(path) { mutableStateOf(0L) }
    val dirty = tfv.text != saved
    val kind = remember(name) { CodeHighlight.kind(name) }
    val transform = remember(kind) { CodeTransformation(kind) }
    val style = TextStyle(fontFamily = Dev.Mono, fontSize = 13.sp, lineHeight = 18.sp, color = Dev.Text)

    fun change(nv: TextFieldValue) {
        if (nv.text != tfv.text) {
            val now = System.currentTimeMillis()
            if (undo.isEmpty() || now - lastPush > 600) { undo.add(tfv.text); if (undo.size > 200) undo.removeAt(0) }
            lastPush = now
            redo.clear()
        }
        tfv = nv
    }
    fun insert(s: String) {
        val sel = tfv.selection
        val t = tfv.text.replaceRange(sel.min, sel.max, s)
        change(TextFieldValue(t, TextRange(sel.min + s.length)))
    }
    fun doSave() {
        if (saving || !editable) return
        saving = true; error = null
        val text = tfv.text
        onSave(text) { err -> saving = false; if (err == null) saved = text else error = err }
    }

    BackHandler { if (dirty) confirmDiscard = true else onClose() }

    Column(Modifier.fillMaxSize().background(Dev.Bg).imePadding()) {
        Row(Modifier.fillMaxWidth().background(Dev.Surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (dirty) confirmDiscard = true else onClose() }) { Icon(Icons.Filled.Close, "Tutup", tint = Dev.Text) }
            Column(Modifier.weight(1f)) {
                Text(name + if (dirty) " ●" else "", color = Dev.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                Text("/$path", color = Dev.Muted, fontSize = 10.sp, fontFamily = Dev.Mono, maxLines = 1)
            }
            IconButton(enabled = editable && undo.isNotEmpty(), onClick = {
                val prev = undo.removeAt(undo.size - 1); redo.add(tfv.text); lastPush = 0; tfv = TextFieldValue(prev, TextRange(prev.length))
            }) { Icon(AppIcons.Undo, "Undo", tint = if (editable && undo.isNotEmpty()) Dev.Text else Dev.Muted) }
            IconButton(enabled = editable && redo.isNotEmpty(), onClick = {
                val nx = redo.removeAt(redo.size - 1); undo.add(tfv.text); lastPush = 0; tfv = TextFieldValue(nx, TextRange(nx.length))
            }) { Icon(AppIcons.Redo, "Redo", tint = if (editable && redo.isNotEmpty()) Dev.Text else Dev.Muted) }
            IconButton(enabled = editable && dirty && !saving, onClick = { doSave() }) {
                Icon(AppIcons.Save, "Simpan", tint = if (editable && dirty) Dev.Green else Dev.Muted)
            }
        }
        notice?.let { Text(it, color = Dev.Amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        error?.let { Text(it, color = Dev.Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }

        val vs = rememberScrollState()
        val hs = rememberScrollState()
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vs).padding(top = 6.dp)) {
            val lines = tfv.text.count { it == '\n' } + 1
            Text(buildString { for (i in 1..lines) { append(i); if (i < lines) append('\n') } },
                style = style.copy(color = Dev.Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, lineHeight = 18.sp),
                modifier = Modifier.padding(horizontal = 6.dp).widthIn(min = 28.dp))
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                BasicTextField(
                    value = tfv, onValueChange = { if (editable) change(it) }, readOnly = !editable,
                    textStyle = style, cursorBrush = SolidColor(Dev.Blue), visualTransformation = transform,
                    modifier = Modifier.widthIn(min = 320.dp).padding(end = 24.dp, bottom = 120.dp)
                )
            }
        }

        if (editable) {
            Row(Modifier.fillMaxWidth().background(Dev.Surface).horizontalScroll(rememberScrollState())) {
                listOf("⇥" to "  ", "{" to "{", "}" to "}", "(" to "(", ")" to ")", "[" to "[", "]" to "]", "<" to "<", ">" to ">",
                    "/" to "/", "\"" to "\"", "'" to "'", ";" to ";", ":" to ":", "=" to "=", "$" to "$", "`" to "`").forEach { (label, ins) ->
                    Text(label, color = Dev.Text, fontFamily = Dev.Mono, fontSize = 16.sp,
                        modifier = Modifier.clickable { insert(ins) }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, containerColor = Dev.Surface,
        title = { Text("Perubahan belum disimpan", color = Dev.Text) },
        text = { Text("Buang perubahan pada $name?", color = Dev.Muted) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Buang", color = Dev.Red) } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDiscard = false }) { Text("Lanjut edit") }
                if (editable) TextButton(onClick = { confirmDiscard = false; doSave() }) { Text("Simpan") }
            }
        }
    )
}
