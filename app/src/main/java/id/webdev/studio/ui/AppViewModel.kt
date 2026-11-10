package id.webdev.studio.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.webdev.studio.WebDevApp
import id.webdev.studio.data.HostingMode
import id.webdev.studio.data.Project
import id.webdev.studio.data.SourceType
import id.webdev.studio.fs.FileFs
import id.webdev.studio.fs.ProjectFs
import id.webdev.studio.fs.SafFs
import id.webdev.studio.projects.ProjectAnalyzer
import id.webdev.studio.projects.ZipImporter
import id.webdev.studio.projects.ZipInfo
import id.webdev.studio.runtime.NodeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface ImportState {
    data object Idle : ImportState
    data class Busy(val message: String) : ImportState
    data class ZipConfirm(val uri: Uri, val name: String, val info: ZipInfo) : ImportState
    data class Failed(val message: String) : ImportState
    data class Done(val project: Project) : ImportState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WebDevApp
    val settings get() = app.settings
    val servers get() = app.servers
    val runtime: NodeRuntime get() = app.runtime

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var importState by mutableStateOf<ImportState>(ImportState.Idle)
        private set
    var runtimeBusy by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    init {
        reload()
        viewModelScope.launch(Dispatchers.IO) { app.servers.refreshRuntime() }
    }

    fun reload() { projects = app.db.all() }

    fun findProject(id: String): Project? =
        if (id == COMPAT_ID) compatProject() else projects.firstOrNull { it.id == id }

    fun compatProject() = Project(
        id = COMPAT_ID, name = "WebView Compatibility", sourceType = SourceType.ASSET,
        sourceUri = "compat", techs = listOf("HTML", "JavaScript", "WebAssembly", "PWA"),
        hostingMode = HostingMode.STATIC, port = 9400, createdAt = 0, lastOpened = 0
    )

    fun dismissImport() { importState = ImportState.Idle }

    private fun allocPort(): Int {
        val used = projects.map { it.port }.toSet() + COMPAT_PORT
        var p = settings.defaultPort
        while (p in used) p++
        return p
    }

    private fun analyzeInto(
        id: String, name: String, type: SourceType, uri: String, fs: ProjectFs,
        base: Project? = null
    ): Project {
        val a = ProjectAnalyzer.analyze(fs)
        val now = System.currentTimeMillis()
        return Project(
            id = id, name = name, sourceType = type, sourceUri = uri,
            techs = a.techs, hostingMode = a.mode,
            port = base?.port ?: allocPort(),
            devScript = a.devScript, backendEntry = a.backendEntry,
            staticRoot = a.staticRoot, notes = a.notes,
            createdAt = base?.createdAt ?: now, lastOpened = now
        )
    }

    // ---------- Import folder (SAF) ----------

    fun importFolder(uri: Uri) {
        val ctx = app
        viewModelScope.launch {
            importState = ImportState.Busy("Mengambil izin akses folder…")
            try {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { /* provider tidak mendukung izin persisten */ }
                val p = withContext(Dispatchers.IO) {
                    val name = DocumentFile.fromTreeUri(ctx, uri)?.name ?: "Project"
                    val fs = SafFs(ctx, uri)
                    importState = ImportState.Busy("Menganalisis proyek…")
                    val existing = projects.firstOrNull { it.sourceUri == uri.toString() }
                    val proj = analyzeInto(existing?.id ?: UUID.randomUUID().toString(), name, SourceType.SAF, uri.toString(), fs, existing)
                    app.db.upsert(proj)
                    proj
                }
                reload()
                importState = ImportState.Done(p)
            } catch (e: Throwable) {
                importState = ImportState.Failed("Gagal mengimpor folder: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---------- Import ZIP ----------

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "project"
        }
        return "project"
    }

    fun inspectZip(uri: Uri) {
        viewModelScope.launch {
            importState = ImportState.Busy("Membaca arsip ZIP…")
            try {
                val info = withContext(Dispatchers.IO) { ZipImporter.inspect(app, uri) }
                importState = ImportState.ZipConfirm(uri, displayName(uri).removeSuffix(".zip").removeSuffix(".ZIP"), info)
            } catch (e: Throwable) {
                importState = ImportState.Failed("ZIP tidak valid: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun confirmZip(c: ImportState.ZipConfirm) {
        viewModelScope.launch {
            importState = ImportState.Busy("Mengekstrak ZIP…")
            val id = UUID.randomUUID().toString()
            val dest = File(app.filesDir, "projects/$id")
            try {
                val p = withContext(Dispatchers.IO) {
                    ZipImporter.extract(app, c.uri, dest, c.info) { n ->
                        importState = ImportState.Busy("Mengekstrak… $n file")
                    }
                    importState = ImportState.Busy("Menganalisis proyek…")
                    val proj = analyzeInto(id, c.name, SourceType.LOCAL, dest.absolutePath, FileFs(dest))
                    app.db.upsert(proj)
                    proj
                }
                reload()
                importState = ImportState.Done(p)
            } catch (e: Throwable) {
                dest.deleteRecursively()
                importState = ImportState.Failed("Gagal mengekstrak ZIP: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---------- Kelola proyek ----------

    fun rescan(p: Project) {
        viewModelScope.launch {
            val np = withContext(Dispatchers.IO) {
                val fs = app.servers.fsFor(p)
                fs.refresh()
                // pertahankan override pengguna (port); mode dianalisis ulang
                analyzeInto(p.id, p.name, p.sourceType, p.sourceUri, fs, p)
            }
            app.db.upsert(np); reload(); toast = "Analisis ulang: ${np.hostingMode.label}"
        }
    }

    fun updateProject(p: Project) {
        viewModelScope.launch(Dispatchers.IO) { app.db.upsert(p); withContext(Dispatchers.Main) { reload() } }
    }

    fun touch(p: Project) {
        viewModelScope.launch(Dispatchers.IO) { app.db.upsert(p.copy(lastOpened = System.currentTimeMillis())); withContext(Dispatchers.Main) { reload() } }
    }

    /**
     * Menghapus proyek dari daftar aplikasi. Folder asli pengguna TIDAK dihapus.
     * Hanya salinan milik aplikasi (workspace Node.js / hasil ekstraksi ZIP) yang dibersihkan.
     */
    fun removeProject(p: Project) {
        app.servers.stop(p.id)
        viewModelScope.launch(Dispatchers.IO) {
            app.db.delete(p.id)
            id.webdev.studio.developer.DevToolsBus.drop(p.id)
            when (p.sourceType) {
                SourceType.SAF -> {
                    File(app.filesDir, "workspaces/${p.id}").deleteRecursively()
                    File(app.filesDir, "workspaces/${p.id}.manifest.json").delete()
                    val others = app.db.all().any { it.sourceUri == p.sourceUri }
                    if (!others) try {
                        app.contentResolver.releasePersistableUriPermission(Uri.parse(p.sourceUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                }
                SourceType.LOCAL -> File(p.sourceUri).deleteRecursively()
                SourceType.ASSET -> {}
            }
            withContext(Dispatchers.Main) { reload() }
        }
    }

    // ---------- Runtime Node.js ----------

    fun installRuntimeFromAsset() {
        viewModelScope.launch {
            runtimeBusy = "Memasang runtime dari APK…"
            try {
                val st = withContext(Dispatchers.IO) { runtime.installFromAsset { runtimeBusy = it } }
                app.servers.refreshRuntime()
                toast = if (st.installed) "Node ${st.nodeVersion} siap" else st.message
            } catch (e: Throwable) { toast = "Gagal: ${e.message}" }
            runtimeBusy = null
        }
    }

    fun installRuntimeFromZip(uri: Uri) {
        viewModelScope.launch {
            runtimeBusy = "Mengekstrak runtime…"
            try {
                val st = withContext(Dispatchers.IO) { runtime.installFromUri(uri) { runtimeBusy = it } }
                app.servers.refreshRuntime()
                toast = if (st.installed) "Node ${st.nodeVersion} siap" else st.message
            } catch (e: Throwable) { toast = "Gagal: ${e.message}" }
            runtimeBusy = null
        }
    }

    fun recheckRuntime() {
        viewModelScope.launch {
            runtimeBusy = "Memeriksa runtime…"
            withContext(Dispatchers.IO) { app.servers.refreshRuntime() }
            runtimeBusy = null
        }
    }

    fun clearCaches(): String {
        var freed = 0L
        listOf(app.cacheDir, runtime.npmCache).forEach { d ->
            freed += d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            d.listFiles()?.forEach { it.deleteRecursively() }
        }
        runtime.tmpDir.mkdirs()
        return "Cache dibersihkan (${freed / 1024} KB)"
    }

    companion object {
        const val COMPAT_ID = "__compat"
        const val COMPAT_PORT = 9400
    }
}
