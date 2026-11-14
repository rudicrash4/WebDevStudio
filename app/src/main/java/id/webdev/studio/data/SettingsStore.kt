package id.webdev.studio.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "dark" | "light" | "system" */
    var themeMode: String by mutableStateOf(sp.getString("theme", "dark") ?: "dark")
        private set

    /** "internal" | "external" */
    var browserMode: String by mutableStateOf(sp.getString("browser", "internal") ?: "internal")
        private set

    var defaultPort: Int by mutableStateOf(sp.getInt("port", 8080))
        private set

    /** Fallback ke index.html untuk rute SPA pada Static Hosting. */
    var spaFallback: Boolean by mutableStateOf(sp.getBoolean("spa", false))
        private set

    /** Jalankan `npm install` dengan --ignore-scripts (lebih aman di Android). */
    var npmIgnoreScripts: Boolean by mutableStateOf(sp.getBoolean("ignore_scripts", true))
        private set

    /** Sembunyikan tombol navigasi sistem (geser dari tepi bawah untuk memunculkan sementara). */
    var hideNav: Boolean by mutableStateOf(sp.getBoolean("hide_nav", true))
        private set
    fun updateHideNav(v: Boolean) { hideNav = v; sp.edit().putBoolean("hide_nav", v).apply() }

    fun updateThemeMode(v: String) { themeMode = v; sp.edit().putString("theme", v).apply() }
    fun updateBrowserMode(v: String) { browserMode = v; sp.edit().putString("browser", v).apply() }
    fun updateDefaultPort(v: Int) {
        val p = v.coerceIn(1024, 65535)
        defaultPort = p; sp.edit().putInt("port", p).apply()
    }
    fun updateSpaFallback(v: Boolean) { spaFallback = v; sp.edit().putBoolean("spa", v).apply() }
    fun updateNpmIgnoreScripts(v: Boolean) {
        npmIgnoreScripts = v; sp.edit().putBoolean("ignore_scripts", v).apply()
    }
}
