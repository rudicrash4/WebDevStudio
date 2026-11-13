package id.webdev.studio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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

/** Ekspor ke GitHub: link repo (.git), username, token — seperti `git push` di Termux, tanpa perintah. */
@Composable
fun GitHubUploadDialog(vm: AppViewModel, p: Project, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var repoUrl by remember { mutableStateOf(vm.ghSavedRepo(p)) }
    var user by remember { mutableStateOf(vm.ghSavedUser()) }
    var token by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Update dari WebDev Studio") }
    var hasToken by remember { mutableStateOf(vm.ghHasToken()) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.ghClearResult() }

    val busy = vm.ghBusy
    val result = vm.ghResult
    val uploading = started && busy != null && result == null

    AlertDialog(
        onDismissRequest = { if (!uploading) onDismiss() },
        containerColor = Dev.Surface,
        title = { Text("Ekspor ke GitHub — ${p.name}", color = Dev.Text, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text(busy ?: "", color = Dev.Text, fontSize = 12.sp, fontFamily = Dev.Mono)
                    }
                    result != null -> result.fold(
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
                        onFailure = { Text(it.message ?: it.javaClass.simpleName, color = Dev.Red, fontSize = 13.sp) }
                    )
                    else -> {
                        OutlinedTextField(repoUrl, { repoUrl = it }, singleLine = true, label = { Text("Link repo (https://github.com/user/repo.git)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(user, { user = it.trim() }, singleLine = true, label = { Text("Username GitHub") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(token, { token = it.trim() }, singleLine = true,
                            label = { Text(if (hasToken) "Token (kosong = pakai yang tersimpan)" else "Token (ghp_… / github_pat_…)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(branch, { branch = it.trim() }, singleLine = true, label = { Text("Branch (kosong = default)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(message, { message = it }, label = { Text("Pesan commit") }, modifier = Modifier.fillMaxWidth())
                        Text("Satu commit berisi semua file proyek. Dikecualikan: node_modules, .git, .env*, *.pem, *.key. " +
                            "File yang sama dengan repo tidak diunggah ulang dan file di repo tidak dihapus. " +
                            "Jika repo belum ada dan username sama dengan pemilik link, repo dibuat otomatis (privat). Token disimpan terenkripsi di perangkat.",
                            color = Dev.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        if (hasToken) TextButton(onClick = { vm.ghForget(); hasToken = false }) { Text("Hapus token tersimpan", color = Dev.Red) }
                    }
                }
            }
        },
        confirmButton = {
            when {
                uploading -> TextButton(onClick = { vm.ghCancelUpload() }) { Text("Batalkan", color = Dev.Red) }
                result != null -> TextButton(onClick = onDismiss) { Text("Tutup") }
                else -> Button(
                    enabled = repoUrl.isNotBlank() && (token.isNotBlank() || hasToken),
                    onClick = { started = true; vm.ghPush(p, repoUrl, user, token, branch, message, "") }
                ) { Icon(AppIcons.Upload, "Ekspor") }
            }
        },
        dismissButton = { if (!uploading && result == null) TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
