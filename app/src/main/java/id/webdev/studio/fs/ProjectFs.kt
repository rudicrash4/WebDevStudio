package id.webdev.studio.fs

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Entri file/folder. [size] = -1 bila ukuran tidak diketahui (provider SAF tertentu).
 * [path] selalu relatif terhadap root proyek, dipisahkan "/", tanpa awalan "/".
 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long
)

/** Abstraksi akses file proyek. SAF URI BUKAN path filesystem biasa, jadi semua akses lewat sini. */
interface ProjectFs {
    fun list(path: String): List<FsEntry>
    fun stat(path: String): FsEntry?
    fun open(path: String, offset: Long = 0): InputStream?
    fun refresh() {}

    // ---- penulisan (editor kode). Mengembalikan null bila sukses, atau pesan galat. ----
    val canWrite: Boolean get() = false
    fun writeFile(path: String, data: ByteArray): String? = "Proyek ini read-only"
    fun makeDir(path: String): String? = "Proyek ini read-only"
    fun remove(path: String): String? = "Proyek ini read-only"
    fun rename(path: String, newName: String): String? = "Proyek ini read-only"
}

fun validName(n: String) = n.isNotBlank() && n != "." && n != ".." && n.length <= 200 &&
    n.none { it == '/' || it == '\\' || it == '\u0000' }

/** Normalisasi path; null bila mengandung ".." / backslash / NUL (cegah directory traversal). */
fun safePath(raw: String): String? {
    if (raw.indexOf('\u0000') >= 0 || raw.indexOf('\\') >= 0) return null
    val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) return null
    return parts.joinToString("/")
}

fun joinPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

private val entryOrder = compareBy<FsEntry>({ !it.isDir }, { it.name.lowercase() })

fun ProjectFs.readText(path: String, maxBytes: Int = 1_000_000): String? {
    val s = open(path) ?: return null
    return s.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}

fun ProjectFs.exists(path: String) = stat(path) != null

/** Proyek di storage aplikasi (hasil ekstraksi ZIP). */
class FileFs(root: File) : ProjectFs {
    private val rootCanon: File = root.canonicalFile

    private fun resolve(path: String): File? {
        val safe = safePath(path) ?: return null
        val f = if (safe.isEmpty()) rootCanon else File(rootCanon, safe)
        val c = try { f.canonicalFile } catch (e: Exception) { return null }
        val ok = c.path == rootCanon.path || c.path.startsWith(rootCanon.path + File.separator)
        return if (ok) c else null
    }

    private fun entryOf(f: File, path: String) = FsEntry(
        name = f.name, path = path, isDir = f.isDirectory,
        size = if (f.isDirectory) 0 else f.length(), lastModified = f.lastModified()
    )

    override fun list(path: String): List<FsEntry> {
        val safe = safePath(path) ?: return emptyList()
        val dir = resolve(safe) ?: return emptyList()
        val kids = dir.listFiles() ?: return emptyList()
        return kids.map { entryOf(it, joinPath(safe, it.name)) }.sortedWith(entryOrder)
    }

    override fun stat(path: String): FsEntry? {
        val safe = safePath(path) ?: return null
        val f = resolve(safe) ?: return null
        if (!f.exists()) return null
        return entryOf(f, safe).copy(name = if (safe.isEmpty()) "" else f.name)
    }

    override fun open(path: String, offset: Long): InputStream? {
        val f = resolve(path) ?: return null
        if (!f.isFile) return null
        return try {
            FileInputStream(f).also { if (offset > 0) it.channel.position(offset) }
        } catch (e: Exception) { null }
    }

    override val canWrite: Boolean get() = true
    private fun child(path: String): File? {
        val safe = safePath(path) ?: return null
        return if (safe.isEmpty()) null else resolve(safe)
    }
    override fun writeFile(path: String, data: ByteArray): String? {
        val f = child(path) ?: return "Path tidak valid"
        if (f.isDirectory) return "Itu folder, bukan file"
        return try { f.parentFile?.mkdirs(); f.writeBytes(data); null } catch (e: Exception) { "Gagal menulis: ${e.message}" }
    }
    override fun makeDir(path: String): String? {
        val f = child(path) ?: return "Path tidak valid"
        return if (f.isDirectory || f.mkdirs()) null else "Gagal membuat folder"
    }
    override fun remove(path: String): String? {
        val f = child(path) ?: return "Path tidak valid"
        return if (f.deleteRecursively()) null else "Gagal menghapus"
    }
    override fun rename(path: String, newName: String): String? {
        val f = child(path) ?: return "Path tidak valid"
        if (!validName(newName)) return "Nama tidak valid"
        val t = File(f.parentFile, newName)
        if (t.exists()) return "Nama sudah dipakai"
        return if (f.renameTo(t)) null else "Gagal mengganti nama"
    }
}

/** Proyek yang dipilih lewat Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE). */
class SafFs(private val ctx: Context, private val treeUri: Uri) : ProjectFs {

    private data class Node(
        val docId: String, val isDir: Boolean, val size: Long, val modified: Long
    )

    private val resolver = ctx.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootNode = Node(rootDocId, true, 0, 0)

    // Cache path -> docId. Ukuran/mtime selalu di-query ulang agar tidak basi.
    private val cache = ConcurrentHashMap<String, Node>()

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    override fun refresh() = cache.clear()

    private fun nodeFromRow(c: android.database.Cursor): Node {
        val mime = c.getString(2)
        return Node(
            docId = c.getString(0),
            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = if (c.isNull(3)) -1L else c.getLong(3),
            modified = if (c.isNull(4)) 0L else c.getLong(4)
        )
    }

    private fun queryChildren(dir: Node): List<Pair<String, Node>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.docId)
        val out = ArrayList<Pair<String, Node>>()
        try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) out.add(c.getString(1) to nodeFromRow(c))
            }
        } catch (e: Exception) { /* izin dicabut / folder hilang */ }
        return out
    }

    private fun queryDoc(docId: String): Node? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) nodeFromRow(c) else null
            }
        } catch (e: Exception) { null }
    }

    private fun nodeFor(path: String): Node? {
        if (path.isEmpty()) return rootNode
        cache[path]?.let { return it }
        val parent = path.substringBeforeLast('/', "")
        nodeFor(parent) ?: return null
        list(parent) // mengisi cache
        return cache[path]
    }

    override fun list(path: String): List<FsEntry> {
        val safe = safePath(path) ?: return emptyList()
        val dir = nodeFor(safe) ?: return emptyList()
        if (!dir.isDir) return emptyList()
        val out = ArrayList<FsEntry>()
        for ((name, n) in queryChildren(dir)) {
            val p = joinPath(safe, name)
            cache[p] = n
            out.add(FsEntry(name, p, n.isDir, if (n.isDir) 0 else n.size, n.modified))
        }
        return out.sortedWith(entryOrder)
    }

    override fun stat(path: String): FsEntry? {
        val safe = safePath(path) ?: return null
        if (safe.isEmpty()) return FsEntry("", "", true, 0, 0)
        val cached = cache[safe] ?: nodeFor(safe) ?: return null
        val fresh = queryDoc(cached.docId)
        if (fresh == null) { cache.remove(safe); return null }
        cache[safe] = fresh
        return FsEntry(
            safe.substringAfterLast('/'), safe, fresh.isDir,
            if (fresh.isDir) 0 else fresh.size, fresh.modified
        )
    }

    override fun open(path: String, offset: Long): InputStream? {
        val safe = safePath(path) ?: return null
        val node = cache[safe] ?: nodeFor(safe) ?: return null
        if (node.isDir) return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.docId)
        return try {
            val pfd = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val s = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                if (offset > 0) {
                    try { s.channel.position(offset) } catch (e: Exception) { skipFully(s, offset) }
                }
                s
            } else {
                resolver.openInputStream(uri)?.also { if (offset > 0) skipFully(it, offset) }
            }
        } catch (e: Exception) { null }
    }

    // ---------------- penulisan lewat DocumentsContract (butuh izin tulis pada pohon SAF) ----------------
    override val canWrite: Boolean get() = true

    private fun denied(e: Exception) =
        if (e is SecurityException) "Tidak ada izin tulis ke folder ini. Impor ulang folder yang sama (Home → Import) dan izinkan akses."
        else "Gagal: ${e.message ?: e.javaClass.simpleName}"

    private fun uriOf(n: Node) = DocumentsContract.buildDocumentUriUsingTree(treeUri, n.docId)

    override fun writeFile(path: String, data: ByteArray): String? {
        val safe = safePath(path)?.takeIf { it.isNotEmpty() } ?: return "Path tidak valid"
        return try {
            val existing = cache[safe] ?: nodeFor(safe)
            val uri: Uri
            if (existing == null) {
                val name = safe.substringAfterLast('/')
                if (!validName(name)) return "Nama tidak valid"
                val parent = nodeFor(safe.substringBeforeLast('/', "")) ?: return "Folder induk tidak ditemukan"
                val created = DocumentsContract.createDocument(resolver, uriOf(parent), "application/octet-stream", name)
                    ?: return "Penyedia penyimpanan menolak membuat file"
                uri = created
            } else {
                if (existing.isDir) return "Itu folder, bukan file"
                uri = uriOf(existing)
            }
            val out0 = try { resolver.openOutputStream(uri, "wt") } catch (e: Exception) { resolver.openOutputStream(uri, "w") }
            val out = out0 ?: return "Tidak dapat membuka file untuk ditulis"
            out.use { it.write(data) }
            cache.remove(safe)
            null
        } catch (e: Exception) { denied(e) }
    }

    override fun makeDir(path: String): String? {
        val safe = safePath(path)?.takeIf { it.isNotEmpty() } ?: return "Path tidak valid"
        return try {
            if (nodeFor(safe) != null) return null
            val name = safe.substringAfterLast('/')
            if (!validName(name)) return "Nama tidak valid"
            val parent = nodeFor(safe.substringBeforeLast('/', "")) ?: return "Folder induk tidak ditemukan"
            DocumentsContract.createDocument(resolver, uriOf(parent), DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: return "Penyedia penyimpanan menolak membuat folder"
            cache.remove(safe)
            null
        } catch (e: Exception) { denied(e) }
    }

    override fun remove(path: String): String? {
        val safe = safePath(path)?.takeIf { it.isNotEmpty() } ?: return "Path tidak valid"
        return try {
            val n = nodeFor(safe) ?: return "Tidak ditemukan"
            if (!DocumentsContract.deleteDocument(resolver, uriOf(n))) return "Gagal menghapus"
            cache.keys.removeAll { it == safe || it.startsWith("$safe/") }
            null
        } catch (e: Exception) { denied(e) }
    }

    override fun rename(path: String, newName: String): String? {
        val safe = safePath(path)?.takeIf { it.isNotEmpty() } ?: return "Path tidak valid"
        if (!validName(newName)) return "Nama tidak valid"
        return try {
            val n = nodeFor(safe) ?: return "Tidak ditemukan"
            DocumentsContract.renameDocument(resolver, uriOf(n), newName) ?: return "Penyedia penyimpanan menolak ganti nama"
            cache.clear()
            null
        } catch (e: Exception) { denied(e) }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) break else left-- } else left -= k
        }
    }
}

/** Menyajikan subfolder (mis. "dist") sebagai root. */
class SubFs(private val base: ProjectFs, prefix: String) : ProjectFs {
    private val prefix = safePath(prefix) ?: ""
    private fun map(p: String): String {
        val s = safePath(p) ?: return "\u0000"
        return if (prefix.isEmpty()) s else joinPath(prefix, s)
    }
    override fun list(path: String): List<FsEntry> {
        val s = safePath(path) ?: return emptyList()
        return base.list(map(s)).map { it.copy(path = joinPath(s, it.name)) }
    }
    override fun stat(path: String): FsEntry? {
        val s = safePath(path) ?: return null
        return base.stat(map(s))?.copy(path = s)
    }
    override fun open(path: String, offset: Long) = base.open(map(path), offset)
    override fun refresh() = base.refresh()
}

/** Aset bawaan APK (dipakai oleh tes kompatibilitas WebView). */
class AssetFs(private val assets: AssetManager, private val dir: String) : ProjectFs {
    private fun full(p: String): String? {
        val s = safePath(p) ?: return null
        return if (s.isEmpty()) dir else "$dir/$s"
    }
    override fun list(path: String): List<FsEntry> {
        val f = full(path) ?: return emptyList()
        val s = safePath(path) ?: return emptyList()
        val names = try { assets.list(f) } catch (e: Exception) { null } ?: return emptyList()
        return names.mapNotNull { stat(joinPath(s, it)) }.sortedWith(entryOrder)
    }
    override fun stat(path: String): FsEntry? {
        val f = full(path) ?: return null
        val s = safePath(path) ?: return null
        val name = s.substringAfterLast('/')
        val kids = try { assets.list(f) } catch (e: Exception) { null }
        if (kids != null && kids.isNotEmpty()) return FsEntry(name, s, true, 0, 0)
        return try {
            assets.openFd(f).use { FsEntry(name, s, false, it.length, 0) }
        } catch (e: Exception) {
            try {
                // aset terkompresi: ukuran tidak diketahui tanpa membaca
                assets.open(f).use { ins ->
                    var n = 0L
                    val buf = ByteArray(8192)
                    while (true) { val r = ins.read(buf); if (r < 0) break; n += r }
                    FsEntry(name, s, false, n, 0)
                }
            } catch (e2: Exception) { null }
        }
    }
    override fun open(path: String, offset: Long): InputStream? {
        val f = full(path) ?: return null
        return try {
            assets.open(f).also { var l = offset; while (l > 0) { val k = it.skip(l); if (k <= 0) break; l -= k } }
        } catch (e: Exception) { null }
    }
}
