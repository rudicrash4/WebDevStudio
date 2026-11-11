package id.webdev.studio.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Runtime Node.js sungguhan (binary `node` untuk Android/bionic arm64) beserta npm.
 *
 * Runtime TIDAK dianggap tersedia sebelum `node --version` benar-benar berhasil dijalankan.
 * Sumber runtime (urutan):
 *   1. Aset APK `node-runtime.zip` (disiapkan CI: scripts/fetch-node-runtime.py)
 *   2. ZIP runtime yang diimpor pengguna (Settings > Runtime)
 */
class NodeRuntime(private val ctx: Context) {

    data class Status(
        val installed: Boolean,
        val nodeVersion: String?,
        val npmVersion: String?,
        val message: String,
        val bundledAvailable: Boolean,
        val abi: String
    )

    val root: File = File(ctx.filesDir, "runtime/usr")
    val nodeBin: File get() = File(root, "bin/node")
    val npmCli: File get() = File(root, "lib/node_modules/npm/bin/npm-cli.js")
    val homeDir: File = File(ctx.filesDir, "runtime/home").apply { mkdirs() }
    val tmpDir: File = File(ctx.cacheDir, "tmp").apply { mkdirs() }
    val npmCache: File = File(ctx.filesDir, "runtime/npm-cache").apply { mkdirs() }

    val primaryAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private fun bundledAssetName(): String? =
        listOf(ASSET_XZ, ASSET_ZIP).firstOrNull { n -> try { ctx.assets.open(n).close(); true } catch (e: Exception) { false } }

    fun bundledAvailable(): Boolean = bundledAssetName() != null

    /** Environment untuk semua proses Node (npm, vite, backend). */
    fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["PREFIX"] = root.absolutePath
        env["LD_LIBRARY_PATH"] = File(root, "lib").absolutePath
        env["PATH"] = File(root, "bin").absolutePath + ":/system/bin:/system/xbin"
        env["npm_config_cache"] = npmCache.absolutePath
        env["npm_config_update_notifier"] = "false"
        env["npm_config_fund"] = "false"
        env["npm_config_audit"] = "false"
        env["npm_config_script_shell"] = "/system/bin/sh"
        env["npm_config_prefix"] = root.absolutePath
        env["NODE_ENV_WEBDEV"] = "1"
        env["FORCE_COLOR"] = "0"
        env["NO_COLOR"] = "1"
        env["BROWSER"] = "none"
        env["CI"] = "false"
        // Build Termux memuat konfigurasi OpenSSL dari /data/data/com.termux/... (tidak boleh diakses aplikasi ini;
        // Node keluar dengan kode 13). Arahkan ke berkas milik kita sendiri.
        val ossl = File(homeDir, "openssl.cnf")
        if (!ossl.exists()) runCatching { ossl.writeText("# WebDev Studio: konfigurasi OpenSSL kosong\n") }
        env["OPENSSL_CONF"] = ossl.absolutePath
        File(root, "lib/ossl-modules").takeIf { it.isDirectory }?.let { env["OPENSSL_MODULES"] = it.absolutePath }
        File(root, "etc/tls/cert.pem").takeIf { it.isFile }?.let { env["SSL_CERT_FILE"] = it.absolutePath }
        env.putAll(extra)
        return env
    }

    /** Menjalankan `node --version` / `npm --version` untuk membuktikan runtime berfungsi. */
    fun check(): Status {
        val abi = primaryAbi
        val bundled = bundledAvailable()
        if (!nodeBin.exists()) {
            return Status(false, null, null,
                if (bundled) "Runtime tersedia di APK tetapi belum dipasang."
                else "Runtime Node.js belum terpasang dan tidak ada di APK. Impor ZIP runtime.",
                bundled, abi)
        }
        if (!abi.startsWith("arm64")) {
            return Status(false, null, null,
                "ABI perangkat $abi tidak didukung runtime bawaan (hanya arm64-v8a).", bundled, abi)
        }
        val v = runCapture(listOf(nodeBin.absolutePath, "--version"))
        if (v.first != 0) {
            return Status(false, null, null,
                "node ada tetapi gagal dijalankan (exit ${v.first}): ${v.second.take(400)}", bundled, abi)
        }
        if (!npmCli.exists()) {
            return Status(true, v.second.trim(), null,
                "Node berjalan, tetapi npm-cli.js tidak ada di runtime (${npmCli.absolutePath}).", bundled, abi)
        }
        val n = runCapture(listOf(nodeBin.absolutePath, npmCli.absolutePath, "--version"))
        val npmV = n.second.lineSequence().map { it.trim() }
            .lastOrNull { Regex("""\d+\.\d+\.\d+""").matches(it) }
        if (n.first != 0 || npmV == null) {
            // Tampilkan galat nyata (bukan pesan generik) supaya penyebabnya terlihat di Settings.
            val detail = n.second.trim().lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n").take(900)
            return Status(true, v.second.trim(), null,
                "npm gagal dijalankan (exit ${n.first}):\n$detail", bundled, abi)
        }
        return Status(true, v.second.trim(), npmV, "Runtime siap.", bundled, abi)
    }

    private fun runCapture(cmd: List<String>): Pair<Int, String> = try {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(environment())
        runCatching { pb.directory(File(environment()["HOME"] ?: ctx.filesDir.absolutePath).also { it.mkdirs() }) }
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 to "timeout" }
        else p.exitValue() to text
    } catch (e: Exception) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }

    /** Pasang dari aset APK. */
    fun installFromAsset(onProgress: (String) -> Unit): Status {
        if (!bundledAvailable()) return check()
        ctx.assets.open(bundledAssetName()!!).use { extract(it, onProgress) }
        return check()
    }

    /** Pasang dari ZIP yang dipilih pengguna. */
    fun installFromUri(uri: Uri, onProgress: (String) -> Unit): Status {
        val s = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Tidak dapat membuka ZIP")
        s.use { extract(it, onProgress) }
        return check()
    }

    fun uninstall() { root.deleteRecursively() }

    /** Deteksi format dari magic bytes: xz (tar.xz) atau zip. */
    private fun extract(input: java.io.InputStream, onProgress: (String) -> Unit) {
        val bin = java.io.BufferedInputStream(input, 256 * 1024)
        bin.mark(8)
        val magic = ByteArray(6); var got = 0
        while (got < 6) { val r = bin.read(magic, got, 6 - got); if (r < 0) break; got += r }
        bin.reset()
        val isXz = got >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte() &&
            magic[3] == 0x58.toByte() && magic[4] == 0x5A.toByte()
        val tmp = File(ctx.filesDir, "runtime/usr.new")
        tmp.deleteRecursively(); tmp.mkdirs()
        val canon = tmp.canonicalFile
        val n = if (isXz) extractTarXz(bin, canon, onProgress) else extractZip(bin, canon, onProgress)
        File(canon, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        root.deleteRecursively()
        root.parentFile?.mkdirs()
        if (!tmp.renameTo(root)) throw java.io.IOException("Gagal memindahkan runtime ke lokasi akhir")
        onProgress("Selesai mengekstrak $n file")
    }

    private fun safeTarget(canon: File, rawName: String): File? {
        val name = rawName.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.split('/').contains("..")) return null
        val target = File(canon, name).canonicalFile
        if (!target.path.startsWith(canon.path + File.separator)) throw SecurityException("Entri berbahaya: $rawName")
        return target
    }

    private fun extractTarXz(input: java.io.InputStream, canon: File, onProgress: (String) -> Unit): Int {
        var n = 0
        val tin = org.tukaani.xz.XZInputStream(input)
        val hdr = ByteArray(512)
        val buf = ByteArray(128 * 1024)
        fun readFully(b: ByteArray, len: Int): Boolean {
            var o = 0
            while (o < len) { val r = tin.read(b, o, len - o); if (r < 0) return false; o += r }
            return true
        }
        fun skip(count: Long) { var left = count; while (left > 0) { val r = tin.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r } }
        fun str(off: Int, len: Int): String { var e = off; while (e < off + len && hdr[e] != 0.toByte()) e++; return String(hdr, off, e - off, Charsets.UTF_8) }
        fun octal(off: Int, len: Int): Long = str(off, len).trim().ifEmpty { "0" }.toLong(8)
        var longName: String? = null
        while (readFully(hdr, 512)) {
            if (hdr.all { it == 0.toByte() }) break
            var name = str(0, 100)
            if (str(257, 5) == "ustar" && hdr[262] != ' '.code.toByte()) { val pre = str(345, 155); if (pre.isNotEmpty()) name = "$pre/$name" }
            val size = octal(124, 12)
            val type = hdr[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L' -> { val b = ByteArray(size.toInt()); readFully(b, b.size); skip(padded - size); longName = String(b, Charsets.UTF_8).trimEnd('\u0000') }
                '0', '\u0000' -> {
                    val target = safeTarget(canon, longName ?: name); longName = null
                    if (target == null) { skip(padded) } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var left = size
                            while (left > 0) { val r = tin.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw java.io.EOFException("arsip terpotong"); out.write(buf, 0, r); left -= r }
                        }
                        skip(padded - size)
                        if (target.path.startsWith(canon.path + "/bin/")) target.setExecutable(true, false)
                        if (++n % 200 == 0) onProgress("Mengekstrak runtime… $n file")
                    }
                }
                '5' -> { safeTarget(canon, longName ?: name)?.mkdirs(); longName = null }
                else -> { longName = null; skip(padded) }
            }
        }
        return n
    }

    private fun extractZip(input: java.io.InputStream, canon: File, onProgress: (String) -> Unit): Int {
        var n = 0
        ZipInputStream(input).use { zin ->
            var e = zin.nextEntry
            val buf = ByteArray(128 * 1024)
            while (e != null) {
                val target = safeTarget(canon, e.name)
                if (target != null) {
                    if (e.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            while (true) { val r = zin.read(buf); if (r < 0) break; out.write(buf, 0, r) }
                        }
                        if (target.path.startsWith(canon.path + "/bin/")) target.setExecutable(true, false)
                        if (++n % 200 == 0) onProgress("Mengekstrak runtime… $n file")
                    }
                }
                zin.closeEntry(); e = zin.nextEntry
            }
        }
        return n
    }

    companion object {
        const val ASSET_XZ = "node-runtime.tar.xz"
        const val ASSET_ZIP = "node-runtime.zip"
    }
}
