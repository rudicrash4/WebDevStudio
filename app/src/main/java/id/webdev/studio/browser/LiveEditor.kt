package id.webdev.studio.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.webdev.studio.data.Project
import id.webdev.studio.explorer.EditorScreen
import id.webdev.studio.fs.FsEntry
import id.webdev.studio.fs.MimeTypes
import id.webdev.studio.fs.ProjectFs
import id.webdev.studio.fs.readText
import id.webdev.studio.ui.AppIcons
import id.webdev.studio.ui.AppViewModel
import id.webdev.studio.ui.Dev
import id.webdev.studio.ui.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class LiveTarget(val e: FsEntry, val text: String, val editable: Boolean, val notice: String?)

/**
 * Editor kode live di dalam browser: pilih file, ketik, simpan otomatis → halaman memuat ulang
 * (Static) atau HMR (Vite) tanpa keluar dari preview.
 */
@Composable
fun LiveEditorPanel(vm: AppViewModel, project: Project, modifier: Modifier, onClose: () -> Unit) {
    val fs = remember(project.id) { vm.servers.fsFor(project) }
    var current by remember(project.id) { mutableStateOf<LiveTarget?>(null) }
    val scope = rememberCoroutineScope()

    fun open(e: FsEntry) {
        scope.launch {
            val max = 1_500_000
            current = when {
                !MimeTypes.looksTextual(e.name) -> LiveTarget(e, "", false, "File biner — tidak bisa diedit.")
                else -> {
                    val txt = withContext(Dispatchers.IO) { fs.readText(e.path, max) }
                    when {
                        txt == null -> LiveTarget(e, "", false, "Tidak dapat membaca file.")
                        e.size > max -> LiveTarget(e, txt, false, "File terlalu besar (${humanSize(e.size)}); hanya tampil sebagian.")
                        else -> LiveTarget(e, txt, fs.canWrite, if (fs.canWrite) null else "Proyek ini read-only.")
                    }
                }
            }
        }
    }

    val t = current
    if (t == null) {
        LiveFileTree(fs, modifier, onPick = { open(it) }, onClose = onClose)
    } else {
        EditorScreen(
            name = t.e.name, path = t.e.path, initial = t.text, editable = t.editable, notice = t.notice,
            onSave = { text, done ->
                scope.launch {
                    val err = withContext(Dispatchers.IO) { vm.servers.writeFile(project, t.e.path, text.toByteArray(Charsets.UTF_8)) }
                    if (err == null) vm.bumpFileRev(project.id) else vm.toast = err
                    done(err)
                }
            },
            onClose = onClose, modifier = modifier, embedded = true, onPickFile = { current = null }
        )
    }
}

private class TreeRow(val e: FsEntry, val depth: Int)

@Composable
private fun LiveFileTree(fs: ProjectFs, modifier: Modifier, onPick: (FsEntry) -> Unit, onClose: () -> Unit) {
    val children = remember(fs) { mutableStateMapOf<String, List<FsEntry>>() }
    val expanded = remember(fs) { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    suspend fun load(path: String) { children[path] = withContext(Dispatchers.IO) { fs.list(path) } }
    LaunchedEffect(fs) { withContext(Dispatchers.IO) { fs.refresh() }; load("") }

    val rows = ArrayList<TreeRow>()
    fun flatten(path: String, depth: Int) {
        for (e in children[path].orEmpty()) {
            if (e.isDir && (e.name == "node_modules" || e.name == ".git")) continue
            rows.add(TreeRow(e, depth))
            if (e.isDir && e.path in expanded) flatten(e.path, depth + 1)
        }
    }
    flatten("", 0)

    Column(modifier.background(Dev.Bg)) {
        Row(Modifier.fillMaxWidth().background(Dev.Surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Tutup editor", tint = Dev.Text) }
            Text("Pilih file untuk diedit", color = Dev.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.e.path }) { r ->
                val e = r.e
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (e.isDir) {
                            if (e.path in expanded) expanded.remove(e.path)
                            else { expanded.add(e.path); if (children[e.path] == null) scope.launch { load(e.path) } }
                        } else onPick(e)
                    }.padding(start = (12 + r.depth * 16).dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (e.isDir) AppIcons.Folder else AppIcons.File, null, tint = if (e.isDir) Dev.Blue else Dev.Muted, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(e.name, color = Dev.Text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    if (!e.isDir) Text(humanSize(e.size), color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono)
                }
            }
        }
    }
}
