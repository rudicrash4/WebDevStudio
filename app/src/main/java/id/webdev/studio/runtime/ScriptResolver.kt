package id.webdev.studio.runtime

import org.json.JSONObject
import java.io.File

/** Satu langkah proses yang dapat dieksekusi langsung (tanpa shebang / `/usr/bin/env`). */
data class Step(
    val argv: List<String>,
    val env: Map<String, String>,
    val display: String,
    val binName: String
)

/**
 * Android tidak punya `/usr/bin/env`, sehingga skrip npm yang memakai shebang
 * `#!/usr/bin/env node` tidak bisa dieksekusi langsung. Resolver ini membaca script di
 * package.json, memecahnya (`&&`), dan menerjemahkan perintah paket (mis. `vite`) menjadi
 * `node <file js dari node_modules>`.
 */
class ScriptResolver(
    private val projectDir: File,
    private val nodePath: String
) {
    private val envAssign = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    fun scriptsOf(): Map<String, String> {
        val f = File(projectDir, "package.json")
        if (!f.exists()) return emptyMap()
        return try {
            val j = JSONObject(f.readText()).optJSONObject("scripts") ?: return emptyMap()
            j.keys().asSequence().associateWith { j.optString(it) }
        } catch (e: Exception) { emptyMap() }
    }

    /** Memecah `a && b` menjadi langkah berurutan; hanya konstruksi shell sederhana yang didukung. */
    fun resolveScript(name: String, extraArgs: List<String> = emptyList(), depth: Int = 0): List<Step> {
        val scripts = scriptsOf()
        val cmd = scripts[name] ?: throw IllegalArgumentException("Script '$name' tidak ada di package.json")
        if (depth > 5) throw IllegalStateException("Rekursi script terlalu dalam")
        val segments = splitAnd(cmd)
        val steps = ArrayList<Step>()
        for ((i, seg) in segments.withIndex()) {
            val isLast = i == segments.lastIndex
            steps += resolveSegment(seg.trim(), if (isLast) extraArgs else emptyList(), depth)
        }
        return steps
    }

    private fun splitAnd(cmd: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = 0.toChar()
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); sb.append(c) }
            else if (c == '"' || c == '\'') { quote = c; sb.append(c) }
            else if (c == '&' && i + 1 < cmd.length && cmd[i + 1] == '&') { out += sb.toString(); sb.clear(); i++ }
            else if (c == '|' || c == ';' || c == '>' || c == '<' || c == '`' || c == '$' && i + 1 < cmd.length && cmd[i + 1] == '(') {
                throw UnsupportedOperationException(
                    "Script memakai konstruksi shell yang belum didukung ('$c'): $cmd")
            }
            else sb.append(c)
            i++
        }
        out += sb.toString()
        return out.filter { it.isNotBlank() }
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = 0.toChar()
        var has = false
        for (c in s) {
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() else sb.append(c) }
            else if (c == '"' || c == '\'') { quote = c; has = true }
            else if (c.isWhitespace()) { if (has || sb.isNotEmpty()) { out += sb.toString(); sb.clear(); has = false } }
            else sb.append(c)
        }
        if (has || sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun resolveSegment(seg: String, extra: List<String>, depth: Int): List<Step> {
        val tokens = tokenize(seg).toMutableList()
        val env = LinkedHashMap<String, String>()
        while (tokens.isNotEmpty() && envAssign.matches(tokens[0])) {
            val kv = tokens.removeAt(0)
            env[kv.substringBefore('=')] = kv.substringAfter('=')
        }
        if (tokens.isEmpty()) return emptyList()
        val bin = tokens[0]
        val args = tokens.drop(1) + extra

        return when (bin) {
            "node" -> listOf(Step(listOf(nodePath) + args, env, "node ${args.joinToString(" ")}", "node"))
            "npm", "npx", "yarn", "pnpm", "bun" -> {
                if ((bin == "npm" || bin == "yarn" || bin == "pnpm") && args.firstOrNull() == "run" && args.size >= 2) {
                    val pass = args.drop(2).filter { it != "--" }
                    resolveScript(args[1], pass, depth + 1)
                } else if (bin == "npx" && args.isNotEmpty()) {
                    resolveSegment(args.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }, emptyList(), depth)
                } else throw UnsupportedOperationException(
                    "Perintah '$seg' memanggil $bin secara langsung; gunakan script yang menjalankan paket secara langsung.")
            }
            else -> {
                val js = findBinJs(bin)
                    ?: throw IllegalStateException(
                        "Perintah '$bin' tidak ditemukan di node_modules/.bin. Jalankan INSTALL DEPENDENCIES terlebih dahulu.")
                listOf(Step(listOf(nodePath, js.absolutePath) + args, env, "$bin ${args.joinToString(" ")}".trim(), bin))
            }
        }
    }

    /** Cari file JS dari sebuah bin paket. */
    fun findBinJs(bin: String): File? {
        val nm = File(projectDir, "node_modules")
        val link = File(nm, ".bin/$bin")
        if (link.exists()) {
            val c = try { link.canonicalFile } catch (e: Exception) { link }
            if (c.isFile && looksJs(c)) return c
        }
        // Fallback: baca "bin" dari package.json semua paket (level atas dan scoped).
        val pkgDirs = nm.listFiles()?.flatMap { d ->
            if (d.name.startsWith("@")) d.listFiles()?.toList().orEmpty() else listOf(d)
        }.orEmpty()
        for (d in pkgDirs) {
            val pj = File(d, "package.json")
            if (!pj.exists()) continue
            try {
                val j = JSONObject(pj.readText())
                val b = j.opt("bin")
                val rel = when (b) {
                    is String -> if (d.name == bin) b else null
                    is JSONObject -> if (b.has(bin)) b.getString(bin) else null
                    else -> null
                } ?: continue
                val f = File(d, rel)
                if (f.isFile) return f
            } catch (_: Exception) {}
        }
        return null
    }

    private fun looksJs(f: File): Boolean {
        if (f.name.endsWith(".js") || f.name.endsWith(".mjs") || f.name.endsWith(".cjs")) return true
        return try {
            f.bufferedReader().use { it.readLine().orEmpty() }.let { it.startsWith("#!") && it.contains("node") }
        } catch (e: Exception) { false }
    }

    /** Argumen tambahan agar dev server memakai host/port yang ditentukan aplikasi. */
    fun portArgs(binName: String, existing: List<String>, port: Int): List<String> {
        val has = existing.any { it == "--port" || it.startsWith("--port=") || it == "-p" }
        val hostSet = existing.any { it == "--host" || it.startsWith("--host=") || it == "-H" }
        return when (binName) {
            "vite" -> buildList {
                if (!hostSet) { add("--host"); add("127.0.0.1") }
                if (!has) { add("--port"); add(port.toString()); add("--strictPort") }
            }
            "webpack-dev-server", "webpack" -> if (existing.contains("serve") || binName == "webpack-dev-server") buildList {
                if (!hostSet) { add("--host"); add("127.0.0.1") }
                if (!has) { add("--port"); add(port.toString()) }
            } else emptyList()
            "next" -> buildList {
                if (!has) { add("-p"); add(port.toString()) }
                if (!hostSet) { add("-H"); add("127.0.0.1") }
            }
            "astro" -> buildList {
                if (!hostSet) { add("--host"); add("127.0.0.1") }
                if (!has) { add("--port"); add(port.toString()) }
            }
            else -> emptyList()
        }
    }
}
