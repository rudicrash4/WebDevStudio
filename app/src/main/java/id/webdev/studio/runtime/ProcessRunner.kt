package id.webdev.studio.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/** Proses anak yang dikelola. PID didapat tanpa refleksi: `sh -c 'echo $$; exec "$@"'`. */
class ManagedProcess(
    val process: Process,
    val pid: Int
) {
    @Volatile var exitCode: Int? = null

    fun isAlive(): Boolean = try { process.exitValue(); false } catch (e: IllegalThreadStateException) { true }

    /** Hentikan proses beserta turunannya (mis. esbuild yang di-spawn Vite). */
    fun killTree(graceMs: Long = 3000) {
        val pids = ProcessUtil.descendants(pid) + pid
        pids.reversed().forEach { ProcessUtil.signal(it, "TERM") }
        try { process.waitFor(graceMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        val left = pids.filter { ProcessUtil.exists(it) }
        left.forEach { ProcessUtil.signal(it, "KILL") }
        if (isAlive()) process.destroyForcibly()
        // Bersihkan juga anak yang baru lahir saat proses sekarat.
        ProcessUtil.descendants(pid).forEach { ProcessUtil.signal(it, "KILL") }
    }
}

object ProcessUtil {
    fun exists(pid: Int) = File("/proc/$pid").exists()

    private fun ppidOf(pid: Int): Int? = try {
        // format: pid (comm) S ppid ...  — comm bisa mengandung spasi/kurung
        val s = File("/proc/$pid/stat").readText()
        s.substring(s.lastIndexOf(')') + 2).split(' ')[1].toInt()
    } catch (e: Exception) { null }

    fun descendants(root: Int): List<Int> {
        val all = File("/proc").list()?.mapNotNull { it.toIntOrNull() } ?: return emptyList()
        val parent = HashMap<Int, Int>()
        for (p in all) ppidOf(p)?.let { parent[p] = it }
        val result = ArrayList<Int>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((c, pp) in parent) if (pp == cur && c != root && c !in result) { result += c; queue.add(c) }
        }
        return result
    }

    fun signal(pid: Int, sig: String) {
        if (pid <= 1) return // jangan pernah mengirim sinyal ke pid -1 / 0 / init
        try { android.os.Process.sendSignal(pid, if (sig == "KILL") 9 else 15) } catch (_: Exception) {}
    }
}

object ProcessRunner {

    /**
     * Menjalankan [argv] di [workDir]. Setiap baris output dikirim ke [onLine] (isStderr).
     * Output stderr digabung ke aliran terpisah agar tampilan Server Console dapat membedakan.
     */
    fun spawn(
        argv: List<String>,
        workDir: File,
        env: Map<String, String>,
        onLine: (line: String, isStderr: Boolean) -> Unit
    ): ManagedProcess {
        val shell = listOf("/system/bin/sh", "-c", "echo \$\$; exec \"\$@\"", "sh") + argv
        val pb = ProcessBuilder(shell).directory(workDir)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close() // stdin tidak dipakai
        // Satu BufferedReader dipakai untuk stdout: baris pertama = PID, sisanya = log.
        val stdout = p.inputStream.bufferedReader()
        val pid = stdout.readLine()?.trim()?.toIntOrNull() ?: -1
        val mp = ManagedProcess(p, pid)

        Thread({
            try { stdout.forEachLine { onLine(it, false) } } catch (_: Exception) {}
        }, "proc-out-$pid").apply { isDaemon = true }.start()
        Thread({
            try { p.errorStream.bufferedReader().forEachLine { onLine(it, true) } } catch (_: Exception) {}
        }, "proc-err-$pid").apply { isDaemon = true }.start()
        Thread({
            val code = try { p.waitFor() } catch (e: InterruptedException) { -1 }
            mp.exitCode = code
        }, "proc-wait-$pid").apply { isDaemon = true }.start()
        return mp
    }

    /** Jalankan sampai selesai; kembalikan exit code (atau -1 bila dibatalkan lewat [cancelled]). */
    fun runToEnd(
        argv: List<String>, workDir: File, env: Map<String, String>,
        cancelled: () -> Boolean,
        onLine: (String, Boolean) -> Unit
    ): Int {
        val mp = spawn(argv, workDir, env, onLine)
        while (true) {
            if (cancelled()) { mp.killTree(); return -1 }
            val code = mp.exitCode
            if (code != null) {
                Thread.sleep(150) // beri waktu thread output menguras sisa log
                return code
            }
            Thread.sleep(120)
        }
    }
}
