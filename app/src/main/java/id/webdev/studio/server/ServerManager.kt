package id.webdev.studio.server

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import id.webdev.studio.data.HostingMode
import id.webdev.studio.data.LogLevel
import id.webdev.studio.data.NetEntry
import id.webdev.studio.data.Project
import id.webdev.studio.data.ServerInfo
import id.webdev.studio.data.ServerStatus
import id.webdev.studio.data.SettingsStore
import id.webdev.studio.data.SourceType
import id.webdev.studio.developer.DevToolsBus
import id.webdev.studio.fs.FileFs
import id.webdev.studio.fs.ProjectFs
import id.webdev.studio.fs.SafFs
import id.webdev.studio.fs.SubFs
import id.webdev.studio.projects.WorkspaceSync
import id.webdev.studio.runtime.ManagedProcess
import id.webdev.studio.runtime.NodeRuntime
import id.webdev.studio.runtime.ProcessRunner
import id.webdev.studio.runtime.ScriptResolver
import id.webdev.studio.runtime.Step
import id.webdev.studio.services.ServerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Pusat pengelola server: Static (in-process) dan Node.js (proses terpisah).
 * Status RUNNING hanya diberikan setelah port benar-benar menerima koneksi TCP.
 */
class ServerManager(
    private val app: Context,
    val runtime: NodeRuntime,
    private val settings: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val _servers = MutableStateFlow<Map<String, ServerInfo>>(emptyMap())
    val servers: StateFlow<Map<String, ServerInfo>> = _servers.asStateFlow()

    private val _runtime = MutableStateFlow<NodeRuntime.Status?>(null)
    val runtimeState: StateFlow<NodeRuntime.Status?> = _runtime.asStateFlow()

    private class Handle(val project: Project) {
        var job: Job? = null
        var static: StaticHttpServer? = null
        @Volatile var proc: ManagedProcess? = null
        @Volatile var stopping = false
        @Volatile var detectedPort: Int? = null
        val tail = ArrayDeque<String>()
    }

    private val handles = ConcurrentHashMap<String, Handle>()

    // ---------- util umum ----------

    fun fsFor(p: Project): ProjectFs = when (p.sourceType) {
        SourceType.SAF -> SafFs(app, Uri.parse(p.sourceUri))
        SourceType.LOCAL -> FileFs(File(p.sourceUri))
        SourceType.ASSET -> id.webdev.studio.fs.AssetFs(app.assets, p.sourceUri)
    }

    fun workspaceDir(p: Project): File = when (p.sourceType) {
        SourceType.LOCAL -> File(p.sourceUri)
        SourceType.SAF -> File(app.filesDir, "workspaces/${p.id}")
        SourceType.ASSET -> File(app.filesDir, "workspaces/${p.id}")
    }

    // ---------- edit file (editor kode) ----------
    // Untuk proyek SAF, Node memakai salinan di workspaces/<id>; perubahan juga ditulis ke salinan itu
    // supaya dev server (HMR) langsung melihatnya tanpa menunggu sinkron berikutnya.

    private fun mirrorTarget(p: Project, path: String): File? {
        if (p.sourceType != SourceType.SAF) return null
        val ws = workspaceDir(p)
        if (!ws.isDirectory) return null
        val safe = id.webdev.studio.fs.safePath(path)?.takeIf { it.isNotEmpty() } ?: return null
        if (safe.split('/').any { it == "node_modules" || it == ".git" }) return null
        return File(ws, safe)
    }

    fun writeFile(p: Project, path: String, data: ByteArray): String? {
        val err = fsFor(p).writeFile(path, data)
        if (err == null) mirrorTarget(p, path)?.let { t -> runCatching { t.parentFile?.mkdirs(); t.writeBytes(data) } }
        return err
    }

    fun makeDir(p: Project, path: String): String? {
        val err = fsFor(p).makeDir(path)
        if (err == null) mirrorTarget(p, path)?.let { t -> runCatching { t.mkdirs() } }
        return err
    }

    fun removePath(p: Project, path: String): String? {
        val err = fsFor(p).remove(path)
        if (err == null) mirrorTarget(p, path)?.let { t -> runCatching { t.deleteRecursively() } }
        return err
    }

    fun renamePath(p: Project, path: String, newName: String): String? {
        val err = fsFor(p).rename(path, newName)
        if (err == null) mirrorTarget(p, path)?.let { t -> runCatching { if (validNameLocal(newName)) t.renameTo(File(t.parentFile, newName)) } }
        return err
    }

    private fun validNameLocal(n: String) = id.webdev.studio.fs.validName(n)

    fun depsInstalled(p: Project): Boolean {
        val nm = File(workspaceDir(p), "node_modules")
        return nm.isDirectory && (nm.list()?.isNotEmpty() == true)
    }

    fun refreshRuntime() { _runtime.value = runtime.check() }

    private fun log(id: String, level: LogLevel, text: String) {
        main.post { DevToolsBus.of(id).addServer(level, text) }
    }

    private fun setInfo(id: String, f: (ServerInfo?) -> ServerInfo?) {
        while (true) {
            val cur = _servers.value
            val next = f(cur[id])
            val m = if (next == null) cur - id else cur + (id to next)
            if (_servers.compareAndSet(cur, m)) break
        }
        updateService()
    }

    private fun baseInfo(p: Project, status: ServerStatus, port: Int = p.port, error: String? = null) = ServerInfo(
        projectId = p.id, projectName = p.name,
        serverType = when (p.hostingMode) {
            HostingMode.STATIC -> "Static HTTP Server"
            HostingMode.DEV -> "Development Server (Node.js)"
            HostingMode.BACKEND -> "Backend (Node.js)"
            HostingMode.UNSUPPORTED -> "-"
        },
        port = port, status = status, runtimeName = p.runtimeLabel, error = error
    )

    val activeCount: Int get() = _servers.value.values.count { it.isActive }

    private fun updateService() {
        val n = activeCount
        main.post {
            if (n > 0) ServerService.start(app, n) else ServerService.stop(app)
        }
    }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket().use { s -> s.reuseAddress = true; s.bind(InetSocketAddress("127.0.0.1", port)); true }
    } catch (e: Exception) { false }

    private fun pickPort(preferred: Int): Int {
        val reserved = _servers.value.values.filter { it.isActive }.map { it.port }.toSet()
        var p = preferred.coerceIn(1024, 65000)
        repeat(300) { if (p !in reserved && isFree(p)) return p; p++ }
        throw IllegalStateException("Tidak ada port kosong di sekitar $preferred (port conflict)")
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (e: Exception) { false }

    private fun fail(h: Handle, msg: String) {
        h.stopping = true
        h.static?.stop(); h.static = null
        h.proc?.killTree(); h.proc = null
        handles.remove(h.project.id, h)
        log(h.project.id, LogLevel.ERROR, msg)
        setInfo(h.project.id) { cur -> baseInfo(h.project, ServerStatus.FAILED, cur?.port ?: h.project.port, msg) }
    }

    // ---------- START ----------

    fun start(project: Project) {
        if (handles.containsKey(project.id)) return
        if (project.hostingMode == HostingMode.UNSUPPORTED) {
            main.post { DevToolsBus.of(project.id).addServer(LogLevel.ERROR, project.notes.firstOrNull() ?: "Proyek tidak didukung") }
            setInfo(project.id) { baseInfo(project, ServerStatus.FAILED, error = project.notes.firstOrNull() ?: "Proyek tidak didukung") }
            return
        }
        val h = Handle(project)
        if (handles.putIfAbsent(project.id, h) != null) return
        setInfo(project.id) { baseInfo(project, ServerStatus.STARTING) }
        h.job = scope.launch {
            try {
                if (project.hostingMode == HostingMode.STATIC) runStatic(h) else runNode(h)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!h.stopping) fail(h, friendly(e))
            }
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.BindException -> "Port conflict: ${e.message}"
        is OutOfMemoryError -> "Out of memory — hentikan server lain lalu coba lagi."
        is SecurityException -> "Izin ditolak: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun runStatic(h: Handle) {
        val p = h.project
        var fs: ProjectFs = fsFor(p)
        if (p.staticRoot.isNotBlank()) fs = SubFs(fs, p.staticRoot)
        if (fs.stat("") == null || fs.list("").isEmpty()) {
            fail(h, "Folder proyek kosong atau izin akses tidak lagi berlaku. Impor ulang proyek ini.")
            return
        }
        if (fs.stat("index.html") == null)
            log(p.id, LogLevel.WARN, "index.html tidak ada di root yang disajikan.")
        val port = pickPort(p.port)
        val logs = DevToolsBus.of(p.id)
        val srv = StaticHttpServer(
            fs = fs, port = port, spaFallback = settings.spaFallback,
            onRequest = { method, path, status, ms, bytes ->
                main.post { logs.addNet(NetEntry(System.currentTimeMillis(), method, path, status, ms, bytes, status >= 400, "server")) }
            },
            onLog = { log(p.id, LogLevel.INFO, it) }
        )
        srv.start()
        h.static = srv
        setInfo(p.id) { baseInfo(p, ServerStatus.STARTING, port) }
        var ok = false
        repeat(20) { if (!ok) { ok = canConnect(port); if (!ok) delay(100) } }
        if (!ok) { fail(h, "Server tidak menerima koneksi di port $port"); return }
        setInfo(p.id) { baseInfo(p, ServerStatus.RUNNING, port) }
        log(p.id, LogLevel.INFO, "RUNNING — http://127.0.0.1:$port/")
    }

    private fun activeNodeCount() = _servers.value.values.count {
        it.isActive && it.runtimeName == "Node.js"
    }

    private suspend fun runNode(h: Handle) {
        val p = h.project
        val st = runtime.check()
        _runtime.value = st
        if (!st.installed) {
            fail(h, "Runtime Node.js belum siap: ${st.message} — buka Settings > Runtime.")
            return
        }
        if (activeNodeCount() > MAX_NODE_SERVERS) {
            fail(h, "Batas $MAX_NODE_SERVERS server Node.js bersamaan tercapai (menjaga RAM/CPU). Hentikan salah satu.")
            return
        }
        val ws = workspaceDir(p)
        if (p.sourceType == SourceType.SAF) {
            WorkspaceSync.sync(fsFor(p), ws, { log(p.id, LogLevel.INFO, it) }, { h.stopping })
            if (h.stopping) return
        }
        if (needsDeps(ws)) {
            handles.remove(p.id, h)
            log(p.id, LogLevel.WARN, "DEPENDENCIES NOT INSTALLED — jalankan INSTALL DEPENDENCIES.")
            setInfo(p.id) { baseInfo(p, ServerStatus.NEEDS_DEPS) }
            return
        }

        val port = pickPort(p.port)
        val node = runtime.nodeBin.absolutePath
        val resolver = ScriptResolver(ws, node)
        val steps: List<Step> = try {
            if (p.devScript != null) resolver.resolveScript(p.devScript)
            else if (p.backendEntry != null) listOf(Step(listOf(node, p.backendEntry), emptyMap(), "node ${p.backendEntry}", "node"))
            else throw IllegalStateException("Tidak ada script dev/start atau entry backend yang dapat dijalankan.")
        } catch (e: UnsupportedOperationException) {
            fail(h, e.message ?: "Script tidak didukung"); return
        } catch (e: IllegalStateException) {
            fail(h, e.message ?: "Script tidak dapat di-resolve"); return
        } catch (e: IllegalArgumentException) {
            fail(h, e.message ?: "Script tidak ada"); return
        }
        if (steps.isEmpty()) { fail(h, "Script kosong"); return }

        val baseEnv = runtime.environment(mapOf(
            "PORT" to port.toString(), "HOST" to "127.0.0.1", "HOSTNAME" to "127.0.0.1",
            "NODE_ENV" to "development"
        ))

        for (s in steps.dropLast(1)) {
            log(p.id, LogLevel.INFO, "\$ ${s.display}")
            val code = ProcessRunner.runToEnd(s.argv, ws, baseEnv + s.env, { h.stopping }) { line, err ->
                capture(h, line, err)
            }
            if (h.stopping) return
            if (code != 0) { fail(h, "Langkah '${s.display}' gagal (exit $code)"); return }
        }

        val last = steps.last()
        val argv = last.argv + resolver.portArgs(last.binName, last.argv.drop(2), port)
        log(p.id, LogLevel.INFO, "\$ ${argv.drop(1).joinToString(" ") { it.removePrefix(ws.absolutePath + "/") }}   (port $port)")
        val mp = ProcessRunner.spawn(argv, ws, baseEnv + last.env) { line, err -> capture(h, line, err) }
        h.proc = mp
        setInfo(p.id) { baseInfo(p, ServerStatus.STARTING, port) }

        // ---- tunggu sampai port benar-benar menerima koneksi ----
        var current = port
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var ready = false
        while (System.currentTimeMillis() < deadline && !h.stopping) {
            h.detectedPort?.let { dp -> if (dp != current) { current = dp; setInfo(p.id) { baseInfo(p, ServerStatus.STARTING, dp) } } }
            if (mp.exitCode != null) break
            if (canConnect(current)) { ready = true; break }
            delay(400)
        }
        if (h.stopping) return
        if (!ready) {
            val code = mp.exitCode
            val tail = synchronized(h.tail) { h.tail.takeLast(6).joinToString("\n") }
            fail(h, if (code != null) "Proses berhenti sebelum siap (exit $code).\n$tail"
            else "Server tidak merespons dalam ${READY_TIMEOUT_MS / 1000}s di port $current.\n$tail")
            return
        }
        setInfo(p.id) { baseInfo(p, ServerStatus.RUNNING, current) }
        log(p.id, LogLevel.INFO, "RUNNING — http://127.0.0.1:$current/")

        // ---- pantau crash ----
        while (!h.stopping && mp.exitCode == null) delay(1000)
        if (!h.stopping) fail(h, "Server berhenti tak terduga (exit ${mp.exitCode}).\n" +
            synchronized(h.tail) { h.tail.takeLast(6).joinToString("\n") })
    }

    private val portRegex = Regex("""https?://(?:localhost|127\.0\.0\.1|\[::1]|0\.0\.0\.0):(\d{2,5})""")

    private fun capture(h: Handle, line: String, isErr: Boolean) {
        val clean = line.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
        if (clean.isBlank()) return
        synchronized(h.tail) { h.tail.addLast(clean); if (h.tail.size > 30) h.tail.removeFirst() }
        portRegex.find(clean)?.groupValues?.get(1)?.toIntOrNull()?.let { h.detectedPort = it }
        log(h.project.id, if (isErr) LogLevel.STDERR else LogLevel.STDOUT, clean)
    }

    private fun needsDeps(ws: File): Boolean {
        val pj = File(ws, "package.json")
        if (!pj.exists()) return false
        val j = try { JSONObject(pj.readText()) } catch (e: Exception) { return false }
        val has = (j.optJSONObject("dependencies")?.length() ?: 0) + (j.optJSONObject("devDependencies")?.length() ?: 0) > 0
        if (!has) return false
        val nm = File(ws, "node_modules")
        return !(nm.isDirectory && nm.list()?.isNotEmpty() == true)
    }

    // ---------- INSTALL DEPENDENCIES ----------

    fun installDependencies(project: Project) {
        if (handles.containsKey(project.id)) return
        val h = Handle(project)
        if (handles.putIfAbsent(project.id, h) != null) return
        setInfo(project.id) { baseInfo(project, ServerStatus.INSTALLING) }
        h.job = scope.launch {
            try {
                val st = runtime.check(); _runtime.value = st
                if (!st.installed) { fail(h, "Runtime Node.js belum siap: ${st.message}"); return@launch }
                if (st.npmVersion == null) { fail(h, "npm tidak tersedia di runtime."); return@launch }
                val ws = workspaceDir(project)
                if (project.sourceType == SourceType.SAF)
                    WorkspaceSync.sync(fsFor(project), ws, { log(project.id, LogLevel.INFO, it) }, { h.stopping })
                if (h.stopping) return@launch
                val args = mutableListOf(runtime.nodeBin.absolutePath, runtime.npmCli.absolutePath, "install",
                    "--no-audit", "--no-fund", "--loglevel=http")
                if (settings.npmIgnoreScripts) args += "--ignore-scripts"
                log(project.id, LogLevel.INFO, "\$ npm install${if (settings.npmIgnoreScripts) " --ignore-scripts" else ""}")
                val code = ProcessRunner.runToEnd(args, ws, runtime.environment(), { h.stopping }) { line, err ->
                    capture(h, line, err)
                }
                if (h.stopping) return@launch
                if (code != 0) {
                    val all = synchronized(h.tail) { h.tail.joinToString("\n") }
                    fail(h, "npm install gagal (exit $code).${hintFor(all)}\n" + synchronized(h.tail) { h.tail.takeLast(5).joinToString("\n") })
                    return@launch
                }
                log(project.id, LogLevel.INFO, "Dependency terpasang.")
                handles.remove(project.id, h)
                setInfo(project.id) { null }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { if (!h.stopping) fail(h, friendly(e)) }
        }
    }

    private fun hintFor(output: String): String {
        val o = output.lowercase()
        return when {
            "node-gyp" in o || "gyp err" in o ->
                "\nPetunjuk: ada paket dengan addon native yang harus dikompilasi. Android tidak punya toolchain kompilasi; cari alternatif pure-JS/WASM."
            "enotfound" in o || "eai_again" in o || "etimedout" in o ->
                "\nPetunjuk: jaringan/DNS bermasalah saat mengunduh paket."
            "eacces" in o || "eperm" in o -> "\nPetunjuk: izin file ditolak di workspace."
            "enospc" in o -> "\nPetunjuk: penyimpanan perangkat penuh."
            "no matching version" in o || "notarget" in o -> "\nPetunjuk: versi paket tidak ditemukan di registry."
            else -> ""
        }
    }

    // ---------- STOP / RESTART ----------

    fun stop(projectId: String) {
        val h = handles[projectId]
        if (h == null) { setInfo(projectId) { null }; return }
        h.stopping = true
        scope.launch {
            try { h.static?.stop() } catch (_: Exception) {}
            try { h.proc?.killTree() } catch (_: Exception) {}
            try { h.job?.cancelAndJoin() } catch (_: Exception) {}
            // Pastikan tidak ada proses tertinggal setelah job selesai.
            try { h.proc?.killTree() } catch (_: Exception) {}
            handles.remove(projectId, h)
            log(projectId, LogLevel.INFO, "Server dihentikan")
            setInfo(projectId) { null }
        }
    }

    fun stopAll() { handles.keys.toList().forEach { stop(it) } }

    fun restart(project: Project) {
        val h = handles[project.id]
        scope.launch {
            if (h != null) {
                h.stopping = true
                try { h.static?.stop() } catch (_: Exception) {}
                try { h.proc?.killTree() } catch (_: Exception) {}
                try { h.job?.cancelAndJoin() } catch (_: Exception) {}
                handles.remove(project.id, h)
                setInfo(project.id) { null }
                delay(300)
            }
            start(project)
        }
    }

    fun clearInfo(projectId: String) {
        if (!handles.containsKey(projectId)) setInfo(projectId) { null }
    }

    fun info(projectId: String): ServerInfo? = _servers.value[projectId]

    companion object {
        const val MAX_NODE_SERVERS = 2
        const val READY_TIMEOUT_MS = 120_000L
    }
}
