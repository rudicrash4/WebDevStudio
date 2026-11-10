package id.webdev.studio.projects

import id.webdev.studio.data.HostingMode
import id.webdev.studio.fs.ProjectFs
import id.webdev.studio.fs.exists
import id.webdev.studio.fs.readText
import org.json.JSONObject

data class Analysis(
    val techs: List<String>,
    val mode: HostingMode,
    val devScript: String?,
    val backendEntry: String?,
    val staticRoot: String,
    val notes: List<String>,
    val hasNodeModules: Boolean,
    val nativeDeps: List<String>
)

/**
 * Analisis proyek berdasarkan ISI package.json dan konfigurasi, bukan hanya nama file.
 */
object ProjectAnalyzer {

    /** Paket npm dengan addon native yang umumnya tidak punya binary Android (bionic). */
    private val nativeProblem = mapOf(
        "sharp" to "memakai libvips native (tidak ada build Android)",
        "bcrypt" to "addon C++ native; gunakan 'bcryptjs'",
        "sqlite3" to "addon native; gunakan 'sql.js' atau 'better-sqlite3' hanya jika ter-build untuk Android",
        "better-sqlite3" to "addon native; perlu kompilasi untuk Android",
        "canvas" to "addon native (Cairo)",
        "node-sass" to "addon native; gunakan 'sass'",
        "puppeteer" to "mengunduh Chromium desktop",
        "playwright" to "mengunduh browser desktop",
        "electron" to "binary desktop",
        "fsevents" to "khusus macOS (opsional, aman diabaikan)",
        "node-canvas" to "addon native",
        "serialport" to "addon native",
        "node-gyp" to "butuh toolchain kompilasi"
    )

    private val devTools = setOf(
        "vite", "webpack-dev-server", "webpack", "parcel", "@vitejs/plugin-react", "@vitejs/plugin-vue",
        "next", "nuxt", "@sveltejs/kit", "svelte", "astro", "react-scripts", "@angular/cli", "snowpack"
    )

    fun analyze(fs: ProjectFs): Analysis {
        val techs = linkedSetOf<String>()
        val notes = mutableListOf<String>()
        var devScript: String? = null
        var backendEntry: String? = null
        var nativeFound = emptyList<String>()

        val rootNames = fs.list("").map { it.name }.toSet()
        val hasIndex = "index.html" in rootNames
        val hasPkg = "package.json" in rootNames

        var deps = emptySet<String>()
        var scripts = emptyMap<String, String>()
        var pkgMain: String? = null
        var pkgType: String? = null

        if (hasPkg) {
            techs += "Node.js"
            val txt = fs.readText("package.json")
            if (txt != null) {
                try {
                    val j = JSONObject(txt)
                    val d = linkedSetOf<String>()
                    for (key in listOf("dependencies", "devDependencies", "optionalDependencies")) {
                        j.optJSONObject(key)?.keys()?.forEach { d += it }
                    }
                    deps = d
                    j.optJSONObject("scripts")?.let { s ->
                        scripts = s.keys().asSequence().associateWith { s.optString(it) }
                    }
                    pkgMain = j.optString("main").ifBlank { null }
                    pkgType = j.optString("type").ifBlank { null }
                } catch (e: Exception) {
                    notes += "package.json tidak valid: ${e.message}"
                }
            }
        }

        // --- Deteksi teknologi dari dependency ---
        if ("react" in deps || "react-dom" in deps) techs += "React"
        if ("vue" in deps) techs += "Vue"
        if ("vite" in deps || rootNames.any { it.startsWith("vite.config.") }) techs += "Vite"
        if ("three" in deps) techs += "Three.js"
        if ("express" in deps) techs += "Express"
        if ("typescript" in deps || "tsconfig.json" in rootNames) techs += "TypeScript"
        if ("jszip" in deps || "@zip.js/zip.js" in deps) techs += "ZIP"

        // --- Deteksi dari isi file ---
        var htmlText = ""
        if (hasIndex) {
            techs += "HTML"
            htmlText = fs.readText("index.html", 300_000) ?: ""
            if (htmlText.contains("three", true)) techs += "Three.js"
            if (htmlText.contains("rel=\"manifest\"", true) || htmlText.contains("rel='manifest'", true))
                techs += "PWA"
            if (htmlText.contains("<script type=\"module\"", true) && htmlText.contains("src=\"/src/", true)) {
                notes += "index.html memuat modul dari /src/ → butuh dev server (Vite), bukan static."
            }
        }

        val allNames = ArrayList<String>()
        collectNames(fs, "", 0, allNames)
        val lower = allNames.map { it.lowercase() }
        if (lower.any { it.endsWith(".js") || it.endsWith(".mjs") }) techs += "JavaScript"
        if (lower.any { it.endsWith(".css") }) techs += "CSS"
        if (lower.any { it.endsWith(".ts") || it.endsWith(".tsx") }) techs += "TypeScript"
        if (lower.any { it.endsWith(".vue") }) techs += "Vue"
        if (lower.any { it.endsWith(".wasm") }) techs += "WebAssembly"
        if (lower.any { it.endsWith(".glb") || it.endsWith(".gltf") }) techs += "3D Assets"
        if ("manifest.json" in rootNames || lower.any { it.endsWith("manifest.webmanifest") }) techs += "PWA"
        val swFiles = allNames.filter {
            val n = it.substringAfterLast('/').lowercase()
            n == "sw.js" || n == "service-worker.js" || n == "serviceworker.js" || n.startsWith("workbox-")
        }
        if (swFiles.isNotEmpty()) techs += "PWA"
        if (hasIndex && htmlText.contains("serviceWorker", true)) techs += "PWA"

        // --- Dependency native bermasalah ---
        nativeFound = deps.filter { it in nativeProblem }
        nativeFound.forEach { notes += "Paket '$it': ${nativeProblem[it]}." }

        // --- Entry backend ---
        val backendCandidates = listOfNotNull(pkgMain) +
            listOf("server.js", "app.js", "index.js", "backend/server.js", "server/index.js", "src/server.js")
        backendEntry = backendCandidates.firstOrNull { c ->
            (c.endsWith(".js") || c.endsWith(".cjs") || c.endsWith(".mjs")) && fs.stat(c)?.isDir == false
        }

        // --- Pilih script dev ---
        val scriptOrder = listOf("dev", "start", "serve", "develop", "preview")
        devScript = scriptOrder.firstOrNull { it in scripts }

        // --- Pilih mode hosting ---
        val needsBuildTool = deps.any { it in devTools } ||
            scripts.values.any { v -> devTools.any { t -> v.contains(t.substringAfterLast('/')) && t in deps } }
        val hasBackend = ("express" in deps || "fastify" in deps || "koa" in deps || "hono" in deps ||
            "ws" in deps || "socket.io" in deps) && backendEntry != null

        val hasPhpPy = "composer.json" in rootNames || "requirements.txt" in rootNames ||
            lower.any { it.endsWith(".php") && !it.contains("/") }

        var mode: HostingMode
        var staticRoot = ""

        when {
            hasPkg && needsBuildTool && devScript != null -> mode = HostingMode.DEV
            hasPkg && hasBackend -> mode = HostingMode.BACKEND
            hasPkg && devScript != null && !hasIndex -> mode = HostingMode.BACKEND
            hasIndex -> mode = HostingMode.STATIC
            else -> {
                // cari index.html di subfolder umum (hasil build)
                val sub = listOf("dist", "build", "public", "www", "docs", "out")
                    .firstOrNull { fs.exists("$it/index.html") }
                if (sub != null) {
                    mode = HostingMode.STATIC; staticRoot = sub
                    notes += "index.html ditemukan di '$sub/' → folder itu yang disajikan."
                } else if (hasPhpPy) {
                    mode = HostingMode.UNSUPPORTED
                    notes += "Proyek PHP/Python terdeteksi. Runtime PHP/Python belum tersedia; " +
                        "WebDev Studio saat ini mendukung Static dan Node.js."
                } else {
                    mode = HostingMode.UNSUPPORTED
                    notes += "Tidak ada index.html maupun package.json yang dapat dijalankan."
                }
            }
        }

        if (mode == HostingMode.DEV && fs.exists("dist/index.html")) {
            notes += "Folder dist/ juga ada; Anda bisa memilih Static Hosting di Project Settings untuk menyajikan hasil build."
        }
        if (mode == HostingMode.DEV && devScript != null)
            notes += "Script yang dijalankan: npm run $devScript (${scripts[devScript]})."
        if (mode == HostingMode.BACKEND && devScript == null && backendEntry != null)
            notes += "Backend dijalankan dengan: node $backendEntry."
        if (pkgType == "module" && mode == HostingMode.BACKEND) notes += "package.json type=module (ES Modules)."
        if (hasPhpPy && mode != HostingMode.UNSUPPORTED)
            notes += "File PHP/Python terdeteksi tetapi tidak dapat dijalankan (runtime tidak tersedia)."

        if (techs.isEmpty()) techs += "Tidak dikenali"

        return Analysis(
            techs = techs.toList(),
            mode = mode,
            devScript = devScript,
            backendEntry = if (mode == HostingMode.BACKEND || mode == HostingMode.DEV) backendEntry else null,
            staticRoot = staticRoot,
            notes = notes,
            hasNodeModules = "node_modules" in rootNames,
            nativeDeps = nativeFound
        )
    }

    /** Mengumpulkan path (relatif) hingga kedalaman terbatas, melewati node_modules & .git. */
    private fun collectNames(fs: ProjectFs, dir: String, depth: Int, out: MutableList<String>) {
        if (depth > 4 || out.size > 4000) return
        for (e in fs.list(dir)) {
            if (e.isDir) {
                if (e.name == "node_modules" || e.name == ".git" || e.name.startsWith(".")) continue
                collectNames(fs, e.path, depth + 1, out)
            } else out += e.path
        }
    }
}
