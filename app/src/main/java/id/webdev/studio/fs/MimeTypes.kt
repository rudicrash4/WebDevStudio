package id.webdev.studio.fs

object MimeTypes {
    private val map = mapOf(
        "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "mjs" to "text/javascript", "cjs" to "text/javascript",
        "json" to "application/json", "map" to "application/json",
        "webmanifest" to "application/manifest+json", "manifest" to "application/manifest+json",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "webp" to "image/webp", "gif" to "image/gif", "avif" to "image/avif", "ico" to "image/x-icon",
        "bmp" to "image/bmp", "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm",
        "ogv" to "video/ogg", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "wav" to "audio/wav", "m4a" to "audio/mp4", "flac" to "audio/flac",
        "wasm" to "application/wasm", "glb" to "model/gltf-binary", "gltf" to "model/gltf+json",
        "bin" to "application/octet-stream", "txt" to "text/plain", "md" to "text/plain",
        "xml" to "application/xml", "csv" to "text/csv", "ts" to "text/plain", "tsx" to "text/plain",
        "jsx" to "text/javascript", "vue" to "text/plain",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject", "pdf" to "application/pdf", "zip" to "application/zip",
        "nes" to "application/octet-stream", "ktx2" to "image/ktx2", "hdr" to "application/octet-stream",
        "obj" to "text/plain", "mtl" to "text/plain", "fbx" to "application/octet-stream"
    )

    private val textual = setOf(
        "text/html", "text/css", "text/javascript", "text/plain", "text/csv",
        "application/json", "application/xml", "application/manifest+json", "image/svg+xml"
    )

    fun forName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = map[ext] ?: "application/octet-stream"
        return if (base in textual) "$base; charset=utf-8" else base
    }

    /** Heuristik ekstensi untuk viewer teks di File Explorer. */
    private val textExt = setOf(
        "html", "htm", "css", "js", "mjs", "cjs", "jsx", "ts", "tsx", "json", "md", "txt", "xml",
        "svg", "csv", "yml", "yaml", "toml", "ini", "env", "gitignore", "lock", "vue", "map",
        "webmanifest", "manifest", "sh", "py", "php", "obj", "mtl", "gltf"
    )

    fun looksTextual(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in textExt || !name.contains('.')
    }
}
