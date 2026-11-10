package id.webdev.studio.data

enum class SourceType { SAF, LOCAL, ASSET }

enum class HostingMode(val label: String) {
    STATIC("Static Hosting"),
    DEV("Development Hosting"),
    BACKEND("Backend Hosting"),
    UNSUPPORTED("Tidak didukung")
}

data class Project(
    val id: String,
    val name: String,
    val sourceType: SourceType,
    /** SAF: tree URI. LOCAL: path absolut folder proyek di penyimpanan aplikasi. */
    val sourceUri: String,
    val techs: List<String>,
    val hostingMode: HostingMode,
    val port: Int,
    val devScript: String? = null,
    val backendEntry: String? = null,
    /** Subfolder yang disajikan pada Static Hosting (mis. "dist"); kosong = root proyek. */
    val staticRoot: String = "",
    val notes: List<String> = emptyList(),
    val createdAt: Long,
    val lastOpened: Long
) {
    val kindLabel: String
        get() {
            val priority = listOf(
                "React", "Vue", "Vite", "Three.js", "Express", "WebAssembly", "PWA",
                "TypeScript", "Node.js", "HTML", "JavaScript", "CSS"
            )
            val picked = priority.filter { it in techs }.take(3)
            return if (picked.isEmpty()) "Tidak dikenali" else picked.joinToString(" + ")
        }

    val runtimeLabel: String
        get() = when (hostingMode) {
            HostingMode.STATIC -> "Embedded HTTP Server"
            HostingMode.DEV, HostingMode.BACKEND -> "Node.js"
            HostingMode.UNSUPPORTED -> "-"
        }
}

enum class LogLevel { INFO, WARN, ERROR, STDOUT, STDERR }

data class LogLine(val time: Long, val level: LogLevel, val text: String)

data class JsMessage(
    val time: Long,
    val level: LogLevel,
    val message: String,
    val source: String = "",
    val line: Int = 0,
    val errorType: String = "",
    val stack: String = ""
)

data class NetEntry(
    val time: Long,
    val method: String,
    val url: String,
    val status: Int,
    val durationMs: Long,
    val bytes: Long,
    val failed: Boolean,
    /** "server" = tercatat oleh HTTP server internal; "page" = fetch/XHR dari halaman (WebView). */
    val source: String = "page"
)

enum class ServerStatus { NEEDS_DEPS, INSTALLING, STARTING, RUNNING, FAILED }

data class ServerInfo(
    val projectId: String,
    val projectName: String,
    val serverType: String,
    val port: Int,
    val status: ServerStatus,
    val runtimeName: String,
    val error: String? = null
) {
    val url: String get() = "http://127.0.0.1:$port/"
    val isActive: Boolean
        get() = status == ServerStatus.STARTING || status == ServerStatus.RUNNING ||
            status == ServerStatus.INSTALLING
}
