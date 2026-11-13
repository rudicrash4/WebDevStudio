package id.webdev.studio.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.webdev.studio.data.Project
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

private data class Row2(val e: FsEntry, val depth: Int)
private const val MAX_EDIT_BYTES = 1_500_000

private sealed class Dlg {
    data class NewItem(val dir: String, val folder: Boolean) : Dlg()
    data class Rename(val e: FsEntry) : Dlg()
    data class Delete(val e: FsEntry) : Dlg()
}

private class EditTarget(val e: FsEntry, val text: String, val editable: Boolean, val notice: String?)

/** File Explorer + editor: pohon folder, buat/ubah nama/hapus file & folder, buka di editor kode. */
@Composable
fun ExplorerScreen(vm: AppViewModel, project: Project, onBack: () -> Unit) {
    val fs: ProjectFs = remember(project.id) { vm.servers.fsFor(project) }
    val children = remember(project.id) { mutableStateMapOf<String, List<FsEntry>>() }
    val expanded = remember(project.id) { mutableStateListOf<String>() }
    val loading = remember(project.id) { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf<String?>(null) }
    var selectedDir by remember(project.id) { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(path: String) {
        loading.add(path)
        val list = withContext(Dispatchers.IO) { fs.list(path) }
        children[path] = list
        loading.remove(path)
    }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) { fs.refresh() }
        children.clear()
        val keep = expanded.toList()
        load("")
        keep.forEach { if (it in expanded) load(it) }
        stats = withContext(Dispatchers.IO) {
            var files = 0; var bytes = 0L
            fun walk(d: String, depth: Int) {
                if (depth > 6 || files > 20000) return
                for (e in fs.list(d)) {
                    if (e.isDir) { if (e.name != "node_modules" && e.name != ".git") walk(e.path, depth + 1) }
                    else { files++; if (e.size > 0) bytes += e.size }
                }
            }
            walk("", 0)
            "$files file · ${humanSize(bytes)} (tanpa node_modules/.git)"
        }
    }

    fun flatten(path: String, depth: Int, out: MutableList<Row2>) {
        for (e in children[path].orEmpty()) {
            out.add(Row2(e, depth))
            if (e.isDir && e.path in expanded) flatten(e.path, depth + 1, out)
        }
    }
    val rows = ArrayList<Row2>().also { flatten("", 0, it) }
    fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
    fun parentOf(p: String) = p.substringBeforeLast('/', "")

    /** Jalankan operasi file di IO, muat ulang folder terkait, tandai perubahan agar browser menyegarkan halaman. */
    fun op(reload: String, block: () -> String?) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { block() }
            if (err != null) vm.toast = err
            else {
                vm.bumpFileRev(project.id)
                withContext(Dispatchers.IO) { fs.refresh() }
                load(reload)
            }
        }
    }

    fun openFile(e: FsEntry) {
        scope.launch {
            if (!MimeTypes.looksTextual(e.name)) {
                editing = EditTarget(e, "", false, "File biner (${MimeTypes.forName(e.name)}), ${humanSize(e.size)} — tidak bisa diedit sebagai teks.")
                return@launch
            }
            val txt = withContext(Dispatchers.IO) { fs.readText(e.path, MAX_EDIT_BYTES) }
            editing = when {
                txt == null -> EditTarget(e, "", false, "Tidak dapat membaca file.")
                e.size > MAX_EDIT_BYTES -> EditTarget(e, txt, false, "File terlalu besar untuk diedit (${humanSize(e.size)}); hanya tampil sebagian.")
                else -> EditTarget(e, txt, fs.canWrite, if (fs.canWrite) null else "Proyek ini read-only.")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Kembali", tint = Dev.Text) }
                Column(Modifier.weight(1f)) {
                    Text(project.name, color = Dev.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                    Text(stats ?: "membaca…", color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono, maxLines = 1)
                }
                if (fs.canWrite) {
                    IconButton(onClick = { dlg = Dlg.NewItem(selectedDir, false) }) { Icon(AppIcons.File, "File baru", tint = Dev.Blue) }
                    IconButton(onClick = { dlg = Dlg.NewItem(selectedDir, true) }) { Icon(AppIcons.Folder, "Folder baru", tint = Dev.Blue) }
                }
                IconButton(onClick = { refreshKey++ }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Dev.Text) }
            }
            if (fs.canWrite) Text("Baru dibuat di: /$selectedDir  (ketuk folder untuk memilih)", color = Dev.Muted, fontSize = 10.sp, fontFamily = Dev.Mono,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
            if (rows.isEmpty() && "" !in loading) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    Text("Folder kosong atau tidak dapat dibaca.", color = Dev.Muted)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.e.path }) { r ->
                    val e = r.e
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (e.isDir && e.path == selectedDir) Dev.Surface else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                if (e.isDir) {
                                    selectedDir = e.path
                                    if (e.path in expanded) expanded.remove(e.path)
                                    else { expanded.add(e.path); if (children[e.path] == null) scope.launch { load(e.path) } }
                                } else openFile(e)
                            }
                            .padding(start = (12 + r.depth * 16).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (e.isDir) (if (e.path in expanded) "▾" else "▸") else " ", color = Dev.Muted, fontFamily = Dev.Mono, modifier = Modifier.width(14.dp))
                        Icon(if (e.isDir) AppIcons.Folder else AppIcons.File, null, tint = if (e.isDir) Dev.Blue else Dev.Muted, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(e.name, color = Dev.Text.copy(alpha = if (e.isDir) 1f else 0.9f), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (e.path in loading) CircularProgressIndicator(Modifier.width(14.dp), strokeWidth = 1.5.dp)
                        else if (!e.isDir) Text(humanSize(e.size), color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono)
                        if (fs.canWrite) Box {
                            IconButton(onClick = { menuFor = e.path }) { Icon(Icons.Filled.MoreVert, "Menu", tint = Dev.Muted) }
                            DropdownMenu(expanded = menuFor == e.path, onDismissRequest = { menuFor = null }) {
                                if (!e.isDir) DropdownMenuItem(text = { Text("Edit") }, onClick = { menuFor = null; openFile(e) })
                                if (e.isDir) {
                                    DropdownMenuItem(text = { Text("File baru di sini") }, onClick = { menuFor = null; dlg = Dlg.NewItem(e.path, false) })
                                    DropdownMenuItem(text = { Text("Folder baru di sini") }, onClick = { menuFor = null; dlg = Dlg.NewItem(e.path, true) })
                                }
                                DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menuFor = null; dlg = Dlg.Rename(e) })
                                DropdownMenuItem(text = { Text("Hapus", color = Dev.Red) }, onClick = { menuFor = null; dlg = Dlg.Delete(e) })
                            }
                        }
                    }
                }
            }
        }

        editing?.let { t ->
            EditorScreen(
                name = t.e.name, path = t.e.path, initial = t.text, editable = t.editable, notice = t.notice,
                onSave = { text, done ->
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { vm.servers.writeFile(project, t.e.path, text.toByteArray(Charsets.UTF_8)) }
                        if (err == null) { vm.bumpFileRev(project.id); vm.toast = "Tersimpan: ${t.e.name}"; withContext(Dispatchers.IO) { fs.refresh() }; load(parentOf(t.e.path)) }
                        done(err)
                    }
                },
                onClose = { editing = null }
            )
        }
    }

    when (val d = dlg) {
        is Dlg.NewItem -> NameDialog(
            title = if (d.folder) "Folder baru di /${d.dir}" else "File baru di /${d.dir}", initial = "", confirm = "Buat",
            onDismiss = { dlg = null }
        ) { name ->
            dlg = null
            val path = join(d.dir, name)
            if (d.dir.isNotEmpty() && d.dir !in expanded) expanded.add(d.dir)
            if (d.folder) op(d.dir) { vm.servers.makeDir(project, path) }
            else scope.launch {
                val err = withContext(Dispatchers.IO) { vm.servers.writeFile(project, path, ByteArray(0)) }
                if (err != null) { vm.toast = err; return@launch }
                vm.bumpFileRev(project.id)
                withContext(Dispatchers.IO) { fs.refresh() }
                load(d.dir)
                withContext(Dispatchers.IO) { fs.stat(path) }?.let { openFile(it) }
            }
        }
        is Dlg.Rename -> NameDialog(title = "Ganti nama", initial = d.e.name, confirm = "Simpan", onDismiss = { dlg = null }) { name ->
            dlg = null
            if (name != d.e.name) op(parentOf(d.e.path)) { vm.servers.renamePath(project, d.e.path, name) }
        }
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dlg = null }, containerColor = Dev.Surface,
            title = { Text("Hapus ${d.e.name}?", color = Dev.Text) },
            text = { Text(if (d.e.isDir) "Folder beserta seluruh isinya akan dihapus permanen dari proyek." else "File akan dihapus permanen dari proyek.", color = Dev.Muted) },
            confirmButton = { TextButton(onClick = { dlg = null; op(parentOf(d.e.path)) { vm.servers.removePath(project, d.e.path) } }) { Text("Hapus", color = Dev.Red) } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Batal") } }
        )
        null -> {}
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val ok = id.webdev.studio.fs.validName(v.trim())
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Dev.Surface,
        title = { Text(title, color = Dev.Text, fontSize = 16.sp) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("Nama (tanpa /)") }, isError = v.isNotEmpty() && !ok) },
        confirmButton = { TextButton(enabled = ok, onClick = { onOk(v.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
