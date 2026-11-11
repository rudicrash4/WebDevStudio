package id.webdev.studio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.webdev.studio.data.Project
import id.webdev.studio.github.GhRepo

/** Kartu koneksi GitHub di Settings: tempel Personal Access Token → diverifikasi → disimpan terenkripsi (Keystore). */
@Composable
fun GitHubCard(vm: AppViewModel) {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.ghInit() }
    Card(colors = CardDefaults.cardColors(containerColor = Dev.Surface), border = BorderStroke(1.dp, Dev.Border)) {
        Column(Modifier.padding(14.dp)) {
            val login = vm.ghLogin
            if (login != null) {
                KeyValue("Status", "Terhubung")
                KeyValue("Akun", login)
                Text("Token disimpan terenkripsi di perangkat ini (Android Keystore).", color = Dev.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.ghDisconnect() }) { Text("Putuskan & hapus token") }
            } else {
                Text("Hubungkan dengan Personal Access Token agar proyek bisa diunggah ke repo GitHub Anda. " +
                    "Buat token di GitHub → Settings → Developer settings → Personal access tokens: " +
                    "klasik dengan scope \"repo\", atau fine-grained dengan Contents: Read and write (dan Administration: Read and write bila ingin membuat repo baru).",
                    color = Dev.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(token, { token = it.trim() }, singleLine = true, label = { Text("Token (ghp_… / github_pat_…)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.ghConnect(token); token = "" }, enabled = token.isNotEmpty() && vm.ghBusy == null) { Text("Hubungkan") }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens/new?scopes=repo&description=WebDev%20Studio")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Buat token") }
                }
            }
            vm.ghError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Dev.Red, fontSize = 12.sp, fontFamily = Dev.Mono) }
            vm.ghBusy?.let {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 12.sp, color = Dev.Muted)
                }
            }
        }
    }
}

/** Dialog unggah proyek ke GitHub: pilih repo (atau buat baru), branch, pesan commit. */
@Composable
fun GitHubUploadDialog(vm: AppViewModel, p: Project, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<GhRepo?>(null) }
    var createNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(p.name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "project" }) }
    var newPrivate by remember { mutableStateOf(true) }
    var branch by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Upload dari WebDev Studio") }
    var subdir by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.ghInit(); vm.ghClearResult(); if (vm.ghLogin != null && vm.ghRepos.isEmpty()) vm.ghLoadRepos() }

    val busy = vm.ghBusy
    val result = vm.ghResult
    val uploading = started && busy != null && result == null

    AlertDialog(
        onDismissRequest = { if (!uploading) onDismiss() },
        containerColor = Dev.Surface,
        title = { Text("Unggah ke GitHub — ${p.name}", color = Dev.Text, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    vm.ghLogin == null -> Text("Belum terhubung ke GitHub. Buka Settings → GitHub, tempel token, lalu kembali ke sini.", color = Dev.Amber, fontSize = 13.sp)
                    uploading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                            Text(busy ?: "", color = Dev.Text, fontSize = 12.sp, fontFamily = Dev.Mono)
                        }
                    }
                    result != null -> {
                        result.fold(
                            onSuccess = { r ->
                                Text(r.message, color = Dev.Green, fontSize = 13.sp)
                                if (r.skipped.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Dilewati (${r.skipped.size}):", color = Dev.Amber, fontSize = 12.sp)
                                    r.skipped.take(12).forEach { Text("• $it", color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono) }
                                    if (r.skipped.size > 12) Text("… dan ${r.skipped.size - 12} lainnya", color = Dev.Muted, fontSize = 11.sp)
                                }
                                r.commitUrl?.let { u -> TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Buka commit di GitHub") } }
                            },
                            onFailure = { Text(it.message ?: "Gagal", color = Dev.Red, fontSize = 13.sp) }
                        )
                    }
                    else -> {
                        Text("Satu commit berisi semua file proyek. Dikecualikan otomatis: node_modules, .git, .env*, *.pem, *.key. " +
                            "File yang sama dengan repo tidak diunggah ulang; file di repo tidak pernah dihapus.", color = Dev.Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.FilterChip(selected = !createNew, onClick = { createNew = false }, label = { Text("Repo yang ada") })
                            androidx.compose.material3.FilterChip(selected = createNew, onClick = { createNew = true; selected = null }, label = { Text("Repo baru") })
                        }
                        if (createNew) {
                            OutlinedTextField(newName, { newName = it.filter { c -> c.isLetterOrDigit() || c in "._-" } }, singleLine = true, label = { Text("Nama repo") }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Privat", color = Dev.Text, modifier = Modifier.weight(1f)); Switch(newPrivate, { newPrivate = it })
                            }
                        } else {
                            OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("Cari repo (${vm.ghRepos.size})") }, modifier = Modifier.fillMaxWidth())
                            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                                vm.ghRepos.filter { filter.isBlank() || it.fullName.contains(filter, true) }.forEach { r ->
                                    Text((if (selected?.fullName == r.fullName) "● " else "○ ") + r.fullName + if (r.private) "  🔒" else "",
                                        color = if (selected?.fullName == r.fullName) Dev.Blue else Dev.Text, fontSize = 13.sp,
                                        modifier = Modifier.fillMaxWidth().clickable { selected = r; if (branch.isEmpty()) branch = r.defaultBranch }.padding(vertical = 8.dp))
                                }
                                if (vm.ghRepos.isEmpty()) Text(vm.ghBusy ?: "Tidak ada repo yang dapat ditulis.", color = Dev.Muted, fontSize = 12.sp)
                            }
                        }
                        OutlinedTextField(branch, { branch = it.trim() }, singleLine = true, label = { Text("Branch (kosong = default)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(subdir, { subdir = it }, singleLine = true, label = { Text("Subfolder di repo (opsional)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(message, { message = it }, label = { Text("Pesan commit") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (vm.ghLogin != null && !uploading && result == null) {
                Button(
                    enabled = (createNew && newName.isNotBlank()) || (!createNew && selected != null),
                    onClick = { started = true; vm.ghUpload(p, selected, if (createNew) newName else null, newPrivate, branch, message, subdir) }
                ) { Text("Unggah") }
            } else if (uploading) {
                TextButton(onClick = { vm.ghCancelUpload() }) { Text("Batalkan", color = Dev.Red) }
            } else {
                TextButton(onClick = onDismiss) { Text("Tutup") }
            }
        },
        dismissButton = { if (!uploading && result == null) TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
