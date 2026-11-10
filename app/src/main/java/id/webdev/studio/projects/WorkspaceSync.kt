package id.webdev.studio.projects

import id.webdev.studio.fs.ProjectFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Node.js tidak bisa membaca URI SAF, jadi proyek SAF disalin (sinkron inkremental) ke
 * penyimpanan aplikasi sebelum dijalankan. File asli pengguna TIDAK pernah diubah.
 * node_modules, .git, dan hasil instal tinggal di workspace salinan.
 */
object WorkspaceSync {

    private val skipDirs = setOf("node_modules", ".git", ".gradle", ".idea", ".cache")

    data class Result(val copied: Int, val removed: Int, val total: Int)

    fun sync(
        src: ProjectFs, workspace: File,
        onLog: (String) -> Unit, cancelled: () -> Boolean
    ): Result {
        workspace.mkdirs()
        val manifestFile = File(workspace.parentFile, workspace.name + ".manifest.json")
        val old = HashMap<String, String>()
        if (manifestFile.exists()) {
            try {
                val j = JSONObject(manifestFile.readText())
                j.keys().forEach { old[it] = j.getString(it) }
            } catch (_: Exception) {}
        }
        val now = LinkedHashMap<String, String>()
        var copied = 0

        fun walk(dir: String) {
            if (cancelled()) return
            for (e in src.list(dir)) {
                if (cancelled()) return
                if (e.isDir) {
                    if (e.name in skipDirs) continue
                    File(workspace, e.path).mkdirs()
                    walk(e.path)
                } else {
                    val sig = "${e.size}:${e.lastModified}"
                    now[e.path] = sig
                    val dst = File(workspace, e.path)
                    val unchanged = dst.exists() && old[e.path] == sig && (e.size < 0 || dst.length() == e.size)
                    if (!unchanged) {
                        dst.parentFile?.mkdirs()
                        val ins = src.open(e.path) ?: run { onLog("Gagal membaca ${e.path}"); null }
                        if (ins != null) {
                            ins.use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 64 * 1024) } }
                            copied++
                        }
                    }
                }
            }
        }

        onLog("Menyinkronkan proyek ke workspace…")
        walk("")
        var removed = 0
        for (p in old.keys) {
            if (p !in now) {
                val f = File(workspace, p)
                if (f.exists() && f.delete()) removed++
            }
        }
        val j = JSONObject()
        now.forEach { (k, v) -> j.put(k, v) }
        manifestFile.writeText(j.toString())
        onLog("Sinkronisasi selesai: $copied disalin, $removed dihapus, ${now.size} file total")
        return Result(copied, removed, now.size)
    }
}
