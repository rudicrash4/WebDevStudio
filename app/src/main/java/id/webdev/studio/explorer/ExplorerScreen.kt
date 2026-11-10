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
import id.webdev.studio.ui.AppViewModel
import id.webdev.studio.ui.Dev
import id.webdev.studio.ui.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Row2(val e: FsEntry, val depth: Int)
private const val MAX_VIEW_BYTES = 300_000

/** File Explorer: pohon folder (lazy, rekursif per ekspansi), ukuran file, viewer teks. Read-only. */
@Composable
fun ExplorerScreen(vm: AppViewModel, project: Project, onBack: () -> Unit) {
    val fs: ProjectFs = remember(project.id) { vm.servers.fsFor(project) }
    val children = remember(project.id) { mutableStateMapOf<String, List<FsEntry>>() }
    val expanded = remember(project.id) { mutableStateListOf<String>() }
    val loading = remember(project.id) { mutableStateListOf<String>() }
    var viewing by remember { mutableStateOf<Pair<FsEntry, String>?>(null) }
    var viewMsg by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf<String?>(null) }
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
        // ringkasan rekursif (dibatasi) untuk header
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Kembali") }
            Column(Modifier.weight(1f)) {
                Text(project.name, color = Dev.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(stats ?: "membaca…", color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono)
            }
            TextButton(onClick = { refreshKey++ }) { Text("Refresh") }
        }
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
                        .clickable {
                            if (e.isDir) {
                                if (e.path in expanded) expanded.remove(e.path)
                                else { expanded.add(e.path); if (children[e.path] == null) scope.launch { load(e.path) } }
                            } else scope.launch {
                                viewMsg = null
                                if (!MimeTypes.looksTextual(e.name)) {
                                    viewing = e to ""
                                    viewMsg = "File biner (${MimeTypes.forName(e.name)}), ${humanSize(e.size)} — tidak ditampilkan sebagai teks."
                                } else {
                                    val txt = withContext(Dispatchers.IO) { fs.readText(e.path, MAX_VIEW_BYTES) }
                                    viewing = e to (txt ?: "")
                                    viewMsg = when {
                                        txt == null -> "Tidak dapat membaca file."
                                        e.size > MAX_VIEW_BYTES -> "Ditampilkan sebagian: ${humanSize(MAX_VIEW_BYTES.toLong())} pertama dari ${humanSize(e.size)}."
                                        else -> null
                                    }
                                }
                            }
                        }
                        .padding(start = (12 + r.depth * 16).dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (e.isDir) (if (e.path in expanded) "▾" else "▸") else " ", color = Dev.Muted, fontFamily = Dev.Mono, modifier = Modifier.width(14.dp))
                    Text(if (e.isDir) "DIR" else "   ", color = Dev.Blue, fontFamily = Dev.Mono, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                    Text(e.name, color = if (e.isDir) Dev.Text else Dev.Text.copy(alpha = 0.9f), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    if (e.path in loading) CircularProgressIndicator(Modifier.width(14.dp), strokeWidth = 1.5.dp)
                    else if (!e.isDir) Text(humanSize(e.size), color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono)
                }
            }
        }
    }

    viewing?.let { (e, text) ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(Modifier.fillMaxSize().background(Dev.Bg)) {
                Row(Modifier.fillMaxWidth().background(Dev.Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewing = null }) { Text("✕ Tutup") }
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = Dev.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                        Text("/${e.path} · ${humanSize(e.size)}", color = Dev.Muted, fontSize = 10.sp, fontFamily = Dev.Mono, maxLines = 1)
                    }
                }
                viewMsg?.let { Text(it, color = Dev.Amber, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    val lines = text.split('\n')
                    Column {
                        lines.forEachIndexed { i, l ->
                            Row {
                                Text("${i + 1}".padStart(4), color = Dev.Muted, fontFamily = Dev.Mono, fontSize = 11.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(l.ifEmpty { " " }, color = Dev.Text, fontFamily = Dev.Mono, fontSize = 11.sp, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
