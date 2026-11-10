package id.webdev.studio.projects

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ZipInfo(val fileCount: Int, val totalBytes: Long, val rootFolder: String?)

/** Impor ZIP dengan perlindungan ZIP Slip, zip-bomb sederhana, dan symlink. */
object ZipImporter {
    private const val MAX_TOTAL = 4L * 1024 * 1024 * 1024 // 4 GB
    private const val MAX_FILES = 200_000

    private fun cleanName(raw: String): String? {
        val n = raw.replace('\\', '/').trimStart('/')
        if (n.isEmpty()) return null
        if (n.split('/').any { it == ".." }) return null
        if (n.indexOf('\u0000') >= 0) return null
        return n
    }

    /** Pass 1: baca ringkasan arsip tanpa mengekstrak. */
    fun inspect(ctx: Context, uri: Uri): ZipInfo {
        var files = 0
        var total = 0L
        val tops = HashSet<String>()
        var hasRootFiles = false
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zin ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val n = cleanName(e.name)
                    if (n != null && !n.startsWith("__MACOSX/")) {
                        if (!e.isDirectory) {
                            files++
                            if (e.size > 0) total += e.size
                            if (n.contains('/')) tops += n.substringBefore('/') else hasRootFiles = true
                        } else tops += n.trimEnd('/').substringBefore('/')
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        } ?: throw IllegalStateException("Tidak dapat membuka ZIP")
        val root = if (!hasRootFiles && tops.size == 1) tops.first() else null
        return ZipInfo(files, total, root)
    }

    /**
     * Pass 2: ekstrak ke [destRoot]. Bila arsip hanya berisi satu folder teratas,
     * folder itu dilepas (di-flatten) agar proyek langsung berada di root.
     */
    fun extract(
        ctx: Context, uri: Uri, destRoot: File, info: ZipInfo,
        onProgress: (Int) -> Unit = {}
    ) {
        destRoot.mkdirs()
        val canonRoot = destRoot.canonicalFile
        val strip = info.rootFolder?.let { "$it/" }
        var count = 0
        var written = 0L
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zin ->
                var e: ZipEntry? = zin.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    var n = cleanName(e.name)
                    if (n != null && !n.startsWith("__MACOSX/")) {
                        if (strip != null) n = if (n.startsWith(strip)) n.removePrefix(strip) else if (n == strip.trimEnd('/')) "" else n
                        if (n.isNotEmpty()) {
                            val target = File(canonRoot, n).canonicalFile
                            // ZIP SLIP: target wajib berada di dalam root
                            if (!target.path.startsWith(canonRoot.path + File.separator)) {
                                throw SecurityException("Entri ZIP berbahaya ditolak: ${e.name}")
                            }
                            if (e.isDirectory) target.mkdirs()
                            else {
                                if (++count > MAX_FILES) throw SecurityException("Jumlah file melebihi batas")
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    while (true) {
                                        val r = zin.read(buf)
                                        if (r < 0) break
                                        written += r
                                        if (written > MAX_TOTAL) throw SecurityException("Ukuran hasil ekstraksi melebihi batas")
                                        out.write(buf, 0, r)
                                    }
                                }
                                if (count % 25 == 0) onProgress(count)
                            }
                        }
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        } ?: throw IllegalStateException("Tidak dapat membuka ZIP")
        onProgress(count)
    }
}
