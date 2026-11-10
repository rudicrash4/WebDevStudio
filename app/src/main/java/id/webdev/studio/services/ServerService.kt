package id.webdev.studio.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import id.webdev.studio.MainActivity
import id.webdev.studio.R
import id.webdev.studio.WebDevApp

/**
 * Foreground Service agar server tetap hidup saat pengguna berpindah ke Chrome/aplikasi lain.
 * Dimulai ketika ada server aktif, dihentikan saat tidak ada lagi.
 */
class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            (application as WebDevApp).servers.stopAll()
            return START_NOT_STICKY
        }
        val n = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForeground(NOTIF_ID, buildNotification(n))
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webdevstudio:servers").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        // Server tidak bisa dipulihkan otomatis setelah proses mati (port & proses ikut hilang).
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Server lokal", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("WebDev Studio")
            .setContentText("$count server berjalan di 127.0.0.1")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop semua", stop)
            .build()
    }

    override fun onDestroy() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    /** Pengguna menggeser aplikasi dari recent: hentikan semua server agar tidak ada proses yatim. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as WebDevApp).servers.stopAll()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL = "servers"
        private const val NOTIF_ID = 1001
        private const val EXTRA_COUNT = "count"
        const val ACTION_STOP_ALL = "id.webdev.studio.STOP_ALL"

        fun start(ctx: Context, count: Int) {
            val i = Intent(ctx, ServerService::class.java).putExtra(EXTRA_COUNT, count)
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }
    }
}
