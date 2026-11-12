package id.webdev.studio.ui

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.webdev.studio.data.HostingMode
import id.webdev.studio.data.Project
import id.webdev.studio.data.ServerInfo
import id.webdev.studio.data.ServerStatus

// ======================= HOME =======================

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onImport: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onGoProjects: () -> Unit,
    onGoServers: () -> Unit,
    onCompat: () -> Unit
) {
    val servers by vm.servers.servers.collectAsState()
    val running = servers.values.count { it.status == ServerStatus.RUNNING }
    val total = vm.projects.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Dev.Surface2),
                contentAlignment = Alignment.Center
            ) { Text("</>", color = Dev.Blue, fontFamily = Dev.Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("WEBDEV STUDIO", color = Dev.Text, fontFamily = Dev.Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)
                Text("Local Development Environment", color = Dev.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp)) {
            Text("IMPORT PROJECT", fontFamily = Dev.Mono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        SectionTitle("Server status")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Running", running.toString(), Dev.Green, Modifier.weight(1f)) { onGoServers() }
            StatCard("Stopped", (total - running).coerceAtLeast(0).toString(), Dev.Muted, Modifier.weight(1f)) { onGoProjects() }
            StatCard("Projects", total.toString(), Dev.Blue, Modifier.weight(1f)) { onGoProjects() }
        }

        SectionTitle("Recent projects")
        if (vm.projects.isEmpty()) {
            EmptyHint("Belum ada proyek. Tekan IMPORT PROJECT untuk memilih folder atau ZIP.")
        } else {
            vm.projects.take(5).forEach { p ->
                ProjectRow(p, servers[p.id]) { onOpenProject(p) }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onGoProjects) { Text("Project Manager →", color = Dev.Blue) }
        }

        SectionTitle("Tools")
        OutlinedButton(onClick = onCompat, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Dev.Border)) {
            Text("WebView Compatibility Test", color = Dev.Text)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Dev.Surface),
        border = BorderStroke(1.dp, Dev.Border)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Dev.Mono)
            Text(label, color = Dev.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Dev.Surface).padding(20.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Dev.Muted, fontSize = 13.sp) }
}

@Composable
fun ProjectRow(p: Project, info: ServerInfo?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Dev.Surface),
        border = BorderStroke(1.dp, Dev.Border)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(info?.status)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Dev.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(p.kindLabel, color = Dev.Muted, fontSize = 12.sp)
            }
            Chip(p.hostingMode.label.substringBefore(' ').uppercase(), if (p.hostingMode == HostingMode.UNSUPPORTED) Dev.Red else Dev.Blue)
        }
    }
}

// ======================= PROJECT MANAGER =======================

@Composable
fun ProjectsScreen(
    vm: AppViewModel,
    onImport: () -> Unit,
    onOpen: (Project) -> Unit,
    onPreview: (Project) -> Unit
) {
    val servers by vm.servers.servers.collectAsState()
    var removing by remember { mutableStateOf<Project?>(null) }
    var settingsFor by remember { mutableStateOf<Project?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROJECT MANAGER", color = Dev.Text, fontFamily = Dev.Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Button(onClick = onImport, shape = RoundedCornerShape(8.dp)) { Text("+ IMPORT") }
        }
        if (vm.projects.isEmpty()) {
            Box(Modifier.padding(16.dp)) { EmptyHint("Belum ada proyek.") }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.projects, key = { it.id }) { p ->
                    ProjectCard(vm, p, servers[p.id], onOpen, onPreview,
                        onSettings = { settingsFor = p }, onRemove = { removing = p })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Hapus dari daftar?") },
            text = {
                Text("\"${p.name}\" hanya dihapus dari WebDev Studio. Folder asli di penyimpanan Anda TIDAK dihapus." +
                    if (p.sourceType == id.webdev.studio.data.SourceType.LOCAL) "\n\nCatatan: proyek ini berasal dari ZIP, jadi salinan hasil ekstraksi milik aplikasi ikut dihapus." else "")
            },
            confirmButton = { TextButton(onClick = { vm.removeProject(p); removing = null }) { Text("Hapus dari daftar", color = Dev.Red) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Batal") } }
        )
    }
    settingsFor?.let { p -> ProjectSettingsDialog(vm, p) { settingsFor = null } }
}

@Composable
private fun ProjectCard(
    vm: AppViewModel, p: Project, info: ServerInfo?,
    onOpen: (Project) -> Unit, onPreview: (Project) -> Unit,
    onSettings: () -> Unit, onRemove: () -> Unit
) {
    val sm = vm.servers
    val active = info?.isActive == true
    var ghOpen by remember { mutableStateOf(false) }
    if (ghOpen) GitHubUploadDialog(vm, p) { ghOpen = false }
    Card(
        colors = CardDefaults.cardColors(containerColor = Dev.Surface),
        border = BorderStroke(1.dp, Dev.Border), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = Dev.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Chip(statusText(info?.status), statusColor(info?.status))
            }
            Spacer(Modifier.height(6.dp))
            Text(p.sourceUri.let { if (p.sourceType == id.webdev.studio.data.SourceType.SAF) android.net.Uri.decode(it).substringAfterLast("tree/") else it.substringAfterLast("/projects/") },
                color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            KeyValue("Jenis", p.kindLabel)
            KeyValue("Hosting", p.hostingMode.label)
            KeyValue("Runtime", p.runtimeLabel)
            KeyValue("Port", p.port.toString())
            if (p.notes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                p.notes.take(3).forEach { Text("• $it", color = Dev.Amber, fontSize = 11.sp) }
            }
            info?.error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Dev.Red, fontSize = 12.sp, fontFamily = Dev.Mono) }
            Spacer(Modifier.height(10.dp))

            // tindakan utama
            when {
                info?.status == ServerStatus.NEEDS_DEPS -> {
                    Text("DEPENDENCIES NOT INSTALLED", color = Dev.Amber, fontFamily = Dev.Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { sm.installDependencies(p) }, modifier = Modifier.fillMaxWidth()) { androidx.compose.material3.Icon(AppIcons.Download, "Install dependencies") }
                }
                info?.status == ServerStatus.INSTALLING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("npm install berjalan…", color = Dev.Muted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.IconButton(onClick = { sm.stop(p.id) }) { androidx.compose.material3.Icon(AppIcons.Stop, "Batal", tint = Dev.Red) }
                    }
                }
                active -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPreview(p) }, enabled = info?.status == ServerStatus.RUNNING, modifier = Modifier.weight(1f)) { androidx.compose.material3.Icon(AppIcons.Preview, "Preview") }
                        OutlinedButton(onClick = { sm.restart(p) }, modifier = Modifier.weight(1f)) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Refresh, "Restart") }
                        Button(onClick = { sm.stop(p.id) }, colors = ButtonDefaults.buttonColors(containerColor = Dev.Red, contentColor = androidx.compose.ui.graphics.Color.White), modifier = Modifier.weight(1f)) { androidx.compose.material3.Icon(AppIcons.Stop, "Stop") }
                    }
                }
                else -> {
                    Button(
                        onClick = { vm.touch(p); sm.start(p) },
                        enabled = p.hostingMode != HostingMode.UNSUPPORTED,
                        modifier = Modifier.fillMaxWidth()
                    ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, "Start hosting") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.IconButton(onClick = { onOpen(p) }) { androidx.compose.material3.Icon(AppIcons.Folder, "Open", tint = Dev.Blue) }
                androidx.compose.material3.IconButton(onClick = onSettings) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, "Settings", tint = Dev.Blue) }
                androidx.compose.material3.IconButton(onClick = { ghOpen = true }) { androidx.compose.material3.Icon(AppIcons.Upload, "Unggah ke GitHub", tint = Dev.Blue) }
                if (p.hostingMode == HostingMode.DEV || p.hostingMode == HostingMode.BACKEND)
                    androidx.compose.material3.IconButton(onClick = { sm.installDependencies(p) }, enabled = !active) { androidx.compose.material3.Icon(AppIcons.Download, "npm install", tint = if (active) Dev.Muted else Dev.Blue) }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onRemove) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Delete, "Remove", tint = Dev.Red) }
            }
        }
    }
}

@Composable
fun ProjectSettingsDialog(vm: AppViewModel, p: Project, onClose: () -> Unit) {
    var port by remember { mutableStateOf(p.port.toString()) }
    var mode by remember { mutableStateOf(p.hostingMode) }
    var script by remember { mutableStateOf(p.devScript ?: "") }
    var staticRoot by remember { mutableStateOf(p.staticRoot) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Project Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(p.name, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port (tetap agar origin konsisten)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Mode hosting", color = Dev.Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(HostingMode.STATIC, HostingMode.DEV, HostingMode.BACKEND).forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label.substringBefore(' ')) })
                    }
                }
                if (mode != HostingMode.STATIC) {
                    OutlinedTextField(script, { script = it }, label = { Text("Script npm (mis. dev / start)") }, singleLine = true)
                } else {
                    OutlinedTextField(staticRoot, { staticRoot = it.trim().trim('/') }, label = { Text("Subfolder yang disajikan (mis. dist)") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pn = port.toIntOrNull()?.coerceIn(1024, 65535) ?: p.port
                vm.updateProject(p.copy(
                    port = pn, hostingMode = mode,
                    devScript = script.ifBlank { null }, staticRoot = staticRoot
                ))
                onClose()
            }) { Text("Simpan") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.rescan(p); onClose() }) { Text("Analisis ulang") }
                TextButton(onClick = onClose) { Text("Batal") }
            }
        }
    )
}

// ======================= SERVER MANAGER =======================

@Composable
fun ServersScreen(vm: AppViewModel, onPreview: (Project) -> Unit, onLogs: (Project) -> Unit) {
    val servers by vm.servers.servers.collectAsState()
    val list = servers.values.sortedBy { it.projectName }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SERVER MANAGER", color = Dev.Text, fontFamily = Dev.Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) EmptyHint("Tidak ada server. Jalankan proyek dari Project Manager.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.projectId }) { s ->
                val p = vm.findProject(s.projectId)
                Card(colors = CardDefaults.cardColors(containerColor = Dev.Surface), border = BorderStroke(1.dp, Dev.Border)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.projectName, color = Dev.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Chip(statusText(s.status), statusColor(s.status))
                        }
                        Spacer(Modifier.height(6.dp))
                        KeyValue("Server", s.serverType)
                        KeyValue("Alamat", s.url)
                        KeyValue("Port", s.port.toString())
                        KeyValue("Runtime", s.runtimeName)
                        s.error?.let { Text(it, color = Dev.Red, fontSize = 12.sp, fontFamily = Dev.Mono) }
                        Spacer(Modifier.height(8.dp))
                        if (p != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.servers.start(p) }, enabled = !s.isActive && s.status != ServerStatus.NEEDS_DEPS, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("START", fontSize = 12.sp) }
                            Button(onClick = { vm.servers.stop(p.id) }, enabled = s.isActive, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Dev.Red, contentColor = androidx.compose.ui.graphics.Color.White), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("STOP", fontSize = 12.sp) }
                            OutlinedButton(onClick = { vm.servers.restart(p) }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("RESTART", fontSize = 12.sp) }
                            OutlinedButton(onClick = { onPreview(p) }, enabled = s.status == ServerStatus.RUNNING, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("PREVIEW", fontSize = 12.sp) }
                        }
                        if (p != null) TextButton(onClick = { onLogs(p) }) { Text("Server console") }
                    }
                }
            }
        }
    }
}

// ======================= SETTINGS =======================

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onPickRuntimeZip: () -> Unit
) {
    val s = vm.settings
    val rt by vm.servers.runtimeState.collectAsState()
    var portText by remember { mutableStateOf(s.defaultPort.toString()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("SETTINGS", color = Dev.Text, fontFamily = Dev.Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)

        SectionTitle("Tema")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Dark", "light" to "Light", "system" to "System").forEach { (k, l) ->
                FilterChip(selected = s.themeMode == k, onClick = { s.updateThemeMode(k) }, label = { Text(l) })
            }
        }

        SectionTitle("Default browser mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.browserMode == "internal", onClick = { s.updateBrowserMode("internal") }, label = { Text("Internal") })
            FilterChip(selected = s.browserMode == "external", onClick = { s.updateBrowserMode("external") }, label = { Text("Eksternal") })
        }

        SectionTitle("Hosting")
        OutlinedTextField(
            portText, { portText = it.filter(Char::isDigit).take(5); portText.toIntOrNull()?.takeIf { v -> v in 1024..65535 }?.let { v -> s.updateDefaultPort(v) } },
            label = { Text("Default hosting port (proyek baru)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        SwitchRow("SPA fallback (Static)", "Rute tak dikenal dialihkan ke index.html", s.spaFallback, s::updateSpaFallback)

        SectionTitle("GitHub")
        GitHubCard(vm)

        SectionTitle("Runtime Node.js")
        Card(colors = CardDefaults.cardColors(containerColor = Dev.Surface), border = BorderStroke(1.dp, Dev.Border)) {
            Column(Modifier.padding(14.dp)) {
                val st = rt
                KeyValue("Status", if (st == null) "memeriksa…" else if (st.installed) "SIAP" else "BELUM SIAP")
                KeyValue("Node", st?.nodeVersion ?: "-")
                KeyValue("npm", st?.npmVersion ?: "-")
                KeyValue("ABI", st?.abi ?: vm.runtime.primaryAbi)
                KeyValue("Runtime di APK", if (st?.bundledAvailable == true) "ada" else "tidak ada")
                Spacer(Modifier.height(6.dp))
                Text(st?.message ?: "", color = if (st?.installed == true) Dev.Green else Dev.Amber, fontSize = 12.sp)
                vm.runtimeBusy?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text(it, fontSize = 12.sp, color = Dev.Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val busy = vm.runtimeBusy != null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.installRuntimeFromAsset() }, enabled = !busy && st?.bundledAvailable == true, modifier = Modifier.weight(1f)) { Text("Pasang dari APK", fontSize = 12.sp) }
                    OutlinedButton(onClick = onPickRuntimeZip, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Impor ZIP", fontSize = 12.sp) }
                }
                TextButton(onClick = { vm.recheckRuntime() }, enabled = !busy) { Text("Uji ulang runtime (node --version)") }
            }
        }
        SwitchRow("npm install --ignore-scripts", "Lebih aman & kompatibel di Android; matikan jika paket butuh postinstall", s.npmIgnoreScripts, s::updateNpmIgnoreScripts)
        Text("Maks. ${id.webdev.studio.server.ServerManager.MAX_NODE_SERVERS} server Node.js bersamaan. RAM tersedia: ${mi.availMem / 1048576} MB dari ${mi.totalMem / 1048576} MB",
            color = Dev.Muted, fontSize = 12.sp)

        SectionTitle("Cache")
        OutlinedButton(onClick = { vm.toast = vm.clearCaches() }) { Text("Bersihkan cache (npm & sementara)") }

        SectionTitle("Project storage")
        val used = remember { java.io.File(ctx.filesDir, "workspaces").walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            java.io.File(ctx.filesDir, "projects").walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        Text("Salinan workspace & ZIP: ${humanSize(used)} di penyimpanan aplikasi.", color = Dev.Muted, fontSize = 12.sp)
        Text("Proyek dari folder dibaca lewat Storage Access Framework; file asli tidak diubah.", color = Dev.Muted, fontSize = 12.sp)

        SectionTitle("About")
        Text("WebDev Studio 0.1.0", color = Dev.Text, fontWeight = FontWeight.SemiBold)
        Text("Local Web Development Environment untuk Android: HTTP server statis, runtime Node.js, WebView.\ntargetSdk 28 digunakan agar binary native Node/npm dapat dieksekusi (sideload, bukan Play Store).",
            color = Dev.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dev.Text, fontSize = 14.sp)
            Text(sub, color = Dev.Muted, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

// ======================= IMPORT DIALOGS =======================

@Composable
fun ImportChooser(onFolder: () -> Unit, onZip: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Import Project") },
        text = { Text("Pilih sumber proyek. Folder dibaca langsung lewat izin akses penyimpanan (read-only); ZIP diekstrak ke penyimpanan aplikasi.") },
        confirmButton = { TextButton(onClick = onFolder) { Text("Select Project Folder") } },
        dismissButton = { Row { TextButton(onClick = onZip) { Text("Import ZIP") }; TextButton(onClick = onClose) { Text("Batal") } } }
    )
}

@Composable
fun ImportStateDialogs(vm: AppViewModel, onDone: (Project) -> Unit) {
    when (val st = vm.importState) {
        ImportState.Idle -> {}
        is ImportState.Busy -> AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text("Mengimpor") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(st.message) } }
        )
        is ImportState.ZipConfirm -> AlertDialog(
            onDismissRequest = { vm.dismissImport() },
            title = { Text("Informasi arsip") },
            text = {
                Column {
                    KeyValue("Nama", st.name)
                    KeyValue("File", st.info.fileCount.toString())
                    KeyValue("Ukuran (terkompresi→asli)", humanSize(st.info.totalBytes))
                    KeyValue("Folder induk", st.info.rootFolder ?: "(tidak ada)")
                    Spacer(Modifier.height(8.dp))
                    Text("Struktur folder dipertahankan. Entri berbahaya (ZIP Slip) akan menggagalkan ekstraksi.", color = Dev.Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmZip(st) }) { Text("Ekstrak & Impor") } },
            dismissButton = { TextButton(onClick = { vm.dismissImport() }) { Text("Batal") } }
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissImport() },
            title = { Text("Impor gagal") }, text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { vm.dismissImport() }) { Text("OK") } }
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissImport() },
            title = { Text("Proyek diimpor") },
            text = {
                Column {
                    Text(st.project.name, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Teknologi", st.project.kindLabel)
                    KeyValue("Hosting", st.project.hostingMode.label)
                    st.project.notes.forEach { Text("• $it", color = Dev.Amber, fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissImport(); onDone(st.project) }) { Text("Buka proyek") } },
            dismissButton = { TextButton(onClick = { vm.dismissImport() }) { Text("Tutup") } }
        )
    }
}
