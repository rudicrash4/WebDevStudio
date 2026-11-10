package id.webdev.studio.server

import id.webdev.studio.fs.MimeTypes
import id.webdev.studio.fs.ProjectFs
import id.webdev.studio.fs.safePath
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP/1.1 server statis yang berjalan di proses aplikasi (tanpa Node.js).
 * - GET/HEAD, Range (206/416), MIME benar (termasuk application/wasm & ES modules)
 * - Bind ke 127.0.0.1 secara default; path traversal ditolak
 * - Membaca file lewat [ProjectFs] sehingga bekerja di atas SAF
 */
class StaticHttpServer(
    private val fs: ProjectFs,
    private val port: Int,
    private val spaFallback: Boolean,
    private val bindAddress: String = "127.0.0.1",
    private val onRequest: (method: String, path: String, status: Int, ms: Long, bytes: Long) -> Unit =
        { _, _, _, _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool() as ThreadPoolExecutor
    private val openSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    val isRunning: Boolean get() = running.get()

    /** Melempar IOException (mis. BindException) bila port tidak bisa dipakai. */
    @Throws(IOException::class)
    fun start() {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), 64)
        socket = ss
        running.set(true)
        pool.execute { acceptLoop(ss) }
        onLog("Static server aktif di http://$bindAddress:$port/")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { socket?.close() } catch (_: Exception) {}
        synchronized(openSockets) { openSockets.toList() }.forEach { try { it.close() } catch (_: Exception) {} }
        pool.shutdownNow()
        onLog("Static server dihentikan")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val c = try { ss.accept() } catch (e: Exception) { break }
            try {
                pool.execute { handle(c) }
            } catch (e: Exception) { try { c.close() } catch (_: Exception) {} }
        }
    }

    private class Request(val method: String, val target: String, val version: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0 // cari \r\n\r\n
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (matched == 4) break
            if (head.size() > 16 * 1024) return null
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 3) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return Request(first[0].uppercase(), first[1], first[2], headers)
    }

    private fun handle(sock: Socket) {
        openSockets.add(sock)
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream(), 8192)
            val out = sock.getOutputStream()
            while (running.get()) {
                val req = try { readRequest(input) } catch (e: SocketTimeoutException) { null } ?: break
                val keep = respond(req, out)
                out.flush()
                if (!keep) break
            }
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            openSockets.remove(sock)
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private val httpDate: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 301 -> "Moved Permanently"
        304 -> "Not Modified"
        400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"; else -> "Internal Server Error"
    }

    private fun writeHead(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code ${statusText(code)}\r\n")
        sb.append("Date: ").append(httpDate.get()!!.format(Date())).append("\r\n")
        sb.append("Server: WebDevStudio\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun simple(out: OutputStream, code: Int, msg: String, head: Boolean, keep: Boolean): Long {
        val body = "<!doctype html><meta charset=utf-8><title>$code</title><body style=\"font-family:sans-serif;background:#0d1117;color:#c9d1d9;padding:24px\"><h2>$code ${statusText(code)}</h2><p>${msg.replace("<", "&lt;")}</p></body>"
            .toByteArray()
        writeHead(
            out, code, listOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-store",
                "Connection" to if (keep) "keep-alive" else "close"
            )
        )
        if (!head) out.write(body)
        return body.size.toLong()
    }

    /** @return true bila koneksi boleh dipakai ulang */
    private fun respond(req: Request, out: OutputStream): Boolean {
        val t0 = System.currentTimeMillis()
        val keep = !req.headers["connection"].equals("close", true) && req.version == "HTTP/1.1"
        val head = req.method == "HEAD"
        var status = 200
        var sent = 0L
        val rawPath = req.target.substringBefore('?').substringBefore('#')

        try {
            if (req.method == "OPTIONS") {
                writeHead(out, 204, listOf(
                    "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                    "Access-Control-Allow-Headers" to (req.headers["access-control-request-headers"] ?: "*"),
                    "Content-Length" to "0", "Connection" to if (keep) "keep-alive" else "close"))
                status = 204
                return keep
            }
            if (req.method != "GET" && !head) {
                status = 405
                sent = simple(out, 405, "Hanya GET dan HEAD yang didukung.", false, keep)
                return keep
            }

            val decoded = try {
                // '+' di path BUKAN spasi; lindungi sebelum decode
                URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                status = 400
                sent = simple(out, 400, "Path tidak valid.", head, keep)
                return keep
            }
            val safe = safePath(decoded)
            if (safe == null) {
                status = 403
                sent = simple(out, 403, "Akses ditolak.", head, keep)
                return keep
            }

            var path = safe
            var entry = fs.stat(path)
            if (entry != null && entry.isDir) {
                val idx = if (path.isEmpty()) "index.html" else "$path/index.html"
                val idxEntry = fs.stat(idx)
                if (idxEntry != null && !idxEntry.isDir) {
                    // Arahkan folder tanpa "/" agar URL relatif benar
                    if (!rawPath.endsWith("/")) {
                        status = 301
                        val loc = rawPath + "/" + (req.target.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" })
                        writeHead(out, 301, listOf("Location" to loc, "Content-Length" to "0",
                            "Connection" to if (keep) "keep-alive" else "close"))
                        return keep
                    }
                    path = idx; entry = idxEntry
                } else {
                    status = 403
                    sent = simple(out, 403, "Listing direktori dinonaktifkan.", head, keep)
                    return keep
                }
            }
            if (entry == null) {
                val looksLikeAsset = path.substringAfterLast('/').contains('.')
                if (spaFallback && !looksLikeAsset) {
                    val idx = fs.stat("index.html")
                    if (idx != null && !idx.isDir) { path = "index.html"; entry = idx }
                }
            }
            if (entry == null) {
                status = 404
                sent = simple(out, 404, "Tidak ditemukan: /$safe", head, keep)
                return keep
            }

            val size = entry.size
            val ctype = MimeTypes.forName(path)
            val etag = "W/\"${entry.size}-${entry.lastModified}\""
            val common = ArrayList<Pair<String, String>>()
            common += "Content-Type" to ctype
            common += "Accept-Ranges" to "bytes"
            common += "Cache-Control" to "no-cache"
            common += "ETag" to etag
            if (entry.lastModified > 0) common += "Last-Modified" to httpDate.get()!!.format(Date(entry.lastModified))
            common += "Connection" to if (keep) "keep-alive" else "close"

            if (req.headers["if-none-match"] == etag) {
                status = 304
                writeHead(out, 304, common.filter { it.first != "Content-Type" })
                return keep
            }

            // ---- Range ----
            val rangeHdr = req.headers["range"]
            var start = 0L
            var end = if (size >= 0) size - 1 else -1L
            var partial = false
            if (rangeHdr != null && rangeHdr.startsWith("bytes=") && size >= 0) {
                val spec = rangeHdr.removePrefix("bytes=").substringBefore(',').trim()
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-', "").trim()
                var ok = true
                try {
                    if (a.isEmpty()) {            // suffix: bytes=-N
                        val n = b.toLong()
                        start = maxOf(0L, size - n); end = size - 1
                    } else {
                        start = a.toLong()
                        end = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
                    }
                } catch (e: NumberFormatException) { ok = false }
                if (!ok || start > end || start >= size) {
                    status = 416
                    writeHead(out, 416, listOf("Content-Range" to "bytes */$size", "Content-Length" to "0",
                        "Connection" to if (keep) "keep-alive" else "close"))
                    return keep
                }
                partial = true
            }

            val input = fs.open(path, start)
            if (input == null) {
                status = 404
                sent = simple(out, 404, "Tidak dapat membuka: /$safe", head, keep)
                return keep
            }

            input.use { ins ->
                if (partial) {
                    status = 206
                    val len = end - start + 1
                    writeHead(out, 206, common + listOf(
                        "Content-Range" to "bytes $start-$end/$size", "Content-Length" to len.toString()))
                    if (!head) sent = copy(ins, out, len)
                } else {
                    status = 200
                    if (size >= 0) {
                        writeHead(out, 200, common + ("Content-Length" to size.toString()))
                        if (!head) sent = copy(ins, out, size)
                    } else {
                        // Ukuran tidak diketahui: tutup koneksi sebagai penanda akhir body.
                        writeHead(out, 200, common.filter { it.first != "Connection" } + ("Connection" to "close"))
                        if (!head) sent = copy(ins, out, Long.MAX_VALUE)
                        return false
                    }
                }
            }
            return keep
        } catch (e: IOException) {
            status = if (status == 200) 499 else status // klien memutus koneksi
            return false
        } finally {
            onRequest(req.method, rawPath, status, System.currentTimeMillis() - t0, sent)
        }
    }

    private fun copy(ins: InputStream, out: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var left = limit
        var total = 0L
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            out.write(buf, 0, r)
            total += r; left -= r
        }
        return total
    }
}
