package id.webdev.studio.github

import id.webdev.studio.fs.ProjectFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.util.Base64

class GitHubException(message: String, val code: Int = -1) : Exception(message)

data class GhRepo(val fullName: String, val defaultBranch: String, val private: Boolean, val empty: Boolean = false)

data class UploadResult(val commitUrl: String?, val uploaded: Int, val unchanged: Int, val skipped: List<String>, val message: String)

/** Klien GitHub REST API minimal (tanpa dependensi) — Git Data API untuk satu commit berisi banyak file. */
class GitHubClient(private val token: String) {

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val url = if (path.startsWith("http")) path else "https://api.github.com$path"
        var attempt = 0
        while (true) {
            attempt++
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000; c.readTimeout = 120_000
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                c.setRequestProperty("User-Agent", "WebDevStudio-Android")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                if ((code == 502 || code == 503 || code == 504) && attempt < 3) { Thread.sleep(1500L * attempt); continue }
                return code to text
            } catch (e: java.io.IOException) {
                if (attempt < 3) { Thread.sleep(1500L * attempt); continue }
                throw GitHubException("Tidak dapat terhubung ke GitHub: ${e.message}")
            } finally { c.disconnect() }
        }
    }

    private fun ok(r: Pair<Int, String>, what: String): JSONObject {
        if (r.first in 200..299) return if (r.second.isBlank()) JSONObject() else JSONObject(r.second)
        val msg = runCatching { JSONObject(r.second).optString("message") }.getOrDefault("")
        val hint = when (r.first) {
            401 -> " (token tidak valid / kedaluwarsa)"
            403 -> " (token tidak punya izin atau terkena rate limit)"
            404 -> " (tidak ditemukan, atau token tanpa akses ke repo ini)"
            else -> ""
        }
        throw GitHubException("$what gagal: HTTP ${r.first} $msg$hint", r.first)
    }

    fun login(): String = ok(call("GET", "/user"), "Verifikasi token").getString("login")

    fun listRepos(): List<GhRepo> {
        val out = ArrayList<GhRepo>()
        for (page in 1..3) {
            val r = call("GET", "/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator&page=$page")
            if (r.first !in 200..299) { ok(r, "Daftar repo") }
            val arr = JSONArray(r.second)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val perms = o.optJSONObject("permissions")
                if (perms != null && !perms.optBoolean("push", false)) continue
                out.add(GhRepo(o.getString("full_name"), o.optString("default_branch", "main"), o.optBoolean("private"), o.optInt("size", 0) == 0))
            }
            if (arr.length() < 100) break
        }
        return out
    }

    fun createRepo(name: String, private: Boolean): GhRepo {
        val o = ok(call("POST", "/user/repos", JSONObject().put("name", name).put("private", private)
            .put("auto_init", true).put("description", "Dibuat dari WebDev Studio")), "Buat repo")
        return GhRepo(o.getString("full_name"), o.optString("default_branch", "main"), o.optBoolean("private"))
    }

    private val excludedDirs = setOf(".git", "node_modules", ".gradle", ".idea", "__pycache__")
    private fun excluded(name: String, isDir: Boolean): Boolean =
        if (isDir) name in excludedDirs
        else name == ".DS_Store" || name == "Thumbs.db" || name == ".env" || name.startsWith(".env.") && name != ".env.example" ||
            name.endsWith(".pem") || name.endsWith(".key")

    private fun gitBlobSha(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${data.size}\u0000".toByteArray())
        md.update(data)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unggah isi proyek sebagai SATU commit. File yang sama persis dengan isi repo tidak diunggah ulang;
     * file di repo yang tidak ada di proyek TIDAK dihapus. node_modules, .git, file .env dan kunci privat dikecualikan.
     */
    fun upload(
        fs: ProjectFs, repo: GhRepo, branch: String, message: String, subdir: String,
        onProgress: (String) -> Unit, cancelled: () -> Boolean
    ): UploadResult {
        val skipped = ArrayList<String>()
        val prefix = subdir.trim('/').let { if (it.isEmpty()) "" else "$it/" }

        // 1) kumpulkan file
        onProgress("Membaca daftar file…")
        val files = ArrayList<Pair<String, Long>>()
        fun walk(dir: String) {
            for (e in fs.list(dir)) {
                if (cancelled()) throw GitHubException("Dibatalkan")
                if (excluded(e.name, e.isDir)) { if (!e.isDir) skipped.add("${e.path} (dikecualikan)"); continue }
                if (e.isDir) walk(e.path) else files.add(e.path to e.size)
            }
        }
        walk("")
        if (files.isEmpty()) throw GitHubException("Proyek tidak berisi file yang bisa diunggah")
        if (files.size > MAX_FILES) throw GitHubException("Terlalu banyak file (${files.size} > $MAX_FILES). Unggah hasil build atau kecualikan folder besar.")

        // 2) posisi branch
        onProgress("Memeriksa branch $branch…")
        val repoPath = "/repos/${repo.fullName}"
        var ref = call("GET", "$repoPath/git/ref/heads/$branch")
        var parentSha: String
        var createBranch = false
        if (ref.first == 404 || ref.first == 409) {
            val def = call("GET", "$repoPath/git/ref/heads/${repo.defaultBranch}")
            if (def.first == 409 || def.first == 404) {
                // repo benar-benar kosong: Git Data API butuh minimal satu commit -> inisialisasi lewat Contents API
                onProgress("Repo kosong, membuat commit awal…")
                ok(call("PUT", "$repoPath/contents/README.md", JSONObject().put("message", "Initial commit")
                    .put("content", Base64.encodeToString("# ${repo.fullName.substringAfter('/')}\n".toByteArray(), Base64.NO_WRAP))
                    .put("branch", repo.defaultBranch)), "Inisialisasi repo kosong")
                ref = call("GET", "$repoPath/git/ref/heads/${repo.defaultBranch}")
                parentSha = ok(ref, "Baca branch").getJSONObject("object").getString("sha")
                createBranch = branch != repo.defaultBranch
            } else {
                parentSha = ok(def, "Baca branch default").getJSONObject("object").getString("sha")
                createBranch = true
            }
        } else {
            parentSha = ok(ref, "Baca branch").getJSONObject("object").getString("sha")
        }
        val baseCommit = ok(call("GET", "$repoPath/git/commits/$parentSha"), "Baca commit")
        val baseTreeSha = baseCommit.getJSONObject("tree").getString("sha")

        // 3) tree remote (untuk melewati file yang tidak berubah)
        onProgress("Membandingkan dengan isi repo…")
        val remote = HashMap<String, String>()
        val tr = call("GET", "$repoPath/git/trees/$baseTreeSha?recursive=1")
        if (tr.first in 200..299) {
            val o = JSONObject(tr.second)
            val arr = o.optJSONArray("tree") ?: JSONArray()
            for (i in 0 until arr.length()) { val t = arr.getJSONObject(i); if (t.optString("type") == "blob") remote[t.getString("path")] = t.getString("sha") }
        }

        // 4) unggah blob untuk file yang berubah
        val entries = JSONArray()
        var uploaded = 0; var unchanged = 0
        files.forEachIndexed { i, (path, size) ->
            if (cancelled()) throw GitHubException("Dibatalkan")
            onProgress("(${i + 1}/${files.size}) $path")
            if (size > MAX_FILE_BYTES) { skipped.add("$path (${size / 1_000_000} MB > ${MAX_FILE_BYTES / 1_000_000} MB)"); return@forEachIndexed }
            val data = try {
                fs.open(path)?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toByteArray() }
            } catch (e: Exception) { null }
            if (data == null) { skipped.add("$path (tidak bisa dibaca)"); return@forEachIndexed }
            val remotePath = prefix + path
            val sha = gitBlobSha(data)
            if (remote[remotePath] == sha) { unchanged++; return@forEachIndexed }
            val b = ok(call("POST", "$repoPath/git/blobs", JSONObject().put("content", Base64.encodeToString(data, Base64.NO_WRAP)).put("encoding", "base64")),
                "Unggah $path")
            entries.put(JSONObject().put("path", remotePath).put("mode", "100644").put("type", "blob").put("sha", b.getString("sha")))
            uploaded++
        }
        if (uploaded == 0) return UploadResult(null, 0, unchanged, skipped, "Tidak ada perubahan — repo sudah sama dengan proyek.")

        // 5) tree → commit → ref
        onProgress("Membuat commit ($uploaded file)…")
        val tree = ok(call("POST", "$repoPath/git/trees", JSONObject().put("base_tree", baseTreeSha).put("tree", entries)), "Buat tree")
        val commit = ok(call("POST", "$repoPath/git/commits", JSONObject().put("message", message).put("tree", tree.getString("sha"))
            .put("parents", JSONArray().put(parentSha))), "Buat commit")
        val newSha = commit.getString("sha")
        if (createBranch) ok(call("POST", "$repoPath/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", newSha)), "Buat branch")
        else ok(call("PATCH", "$repoPath/git/refs/heads/$branch", JSONObject().put("sha", newSha)), "Perbarui branch")
        return UploadResult("https://github.com/${repo.fullName}/commit/$newSha", uploaded, unchanged, skipped,
            "Berhasil: $uploaded file diunggah, $unchanged tidak berubah.")
    }

    private companion object {
        const val MAX_FILES = 3000
        const val MAX_FILE_BYTES = 25_000_000L
    }
}
