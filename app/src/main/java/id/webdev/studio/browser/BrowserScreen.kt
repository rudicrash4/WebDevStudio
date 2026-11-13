package id.webdev.studio.browser

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import android.webkit.CookieManager
import id.webdev.studio.data.HostingMode
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import id.webdev.studio.BuildConfig
import id.webdev.studio.data.JsMessage
import id.webdev.studio.data.LogLevel
import id.webdev.studio.data.NetEntry
import id.webdev.studio.data.Project
import id.webdev.studio.data.ServerStatus
import id.webdev.studio.developer.DevToolsBus
import id.webdev.studio.developer.InjectedScripts
import id.webdev.studio.developer.ProjectLogs
import id.webdev.studio.ui.AppViewModel
import id.webdev.studio.ui.AppIcons
import id.webdev.studio.ui.Dev
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val main = Handler(Looper.getMainLooper())

/** Jembatan JS → Kotlin. Hanya menulis log; tidak mengekspos file/aksi sensitif. */
class DevBridge(private val logs: ProjectLogs) {
    @JavascriptInterface fun error(type: String?, msg: String?, file: String?, line: Int, stack: String?) {
        val m = JsMessage(System.currentTimeMillis(), LogLevel.ERROR, (msg ?: "").take(2000), (file ?: "").take(500), line,
            (type ?: "Error").take(80), (stack ?: "").take(4000))
        main.post { logs.addJs(m) }
    }
    @JavascriptInterface fun resourceError(tag: String?, url: String?) {
        val m = JsMessage(System.currentTimeMillis(), LogLevel.ERROR, "Gagal memuat <${(tag ?: "").lowercase()}>: ${(url ?: "").take(500)}",
            (url ?: "").take(500), 0, "ResourceError")
        main.post { logs.addJs(m) }
    }
    @JavascriptInterface fun net(method: String?, url: String?, status: Int, ms: Long, failed: Boolean) {
        val n = NetEntry(System.currentTimeMillis(), (method ?: "GET").uppercase().take(10), (url ?: "").take(600), status, ms, -1, failed || status >= 400, "page")
        main.post { logs.addNet(n) }
    }
}

/** JS yang dijalankan di halaman: hapus Service Worker + Cache API (+ storage bila [storage]) lalu muat ulang. */
private fun webResetJs(storage: Boolean): String {
    val pre = if (storage)
        "var P=[];try{localStorage.clear();sessionStorage.clear()}catch(e){};try{if(indexedDB.databases)P.push(indexedDB.databases().then(function(ds){ds.forEach(function(d){indexedDB.deleteDatabase(d.name)})}))}catch(e){};"
    else "var P=[];"
    return "(function(){" + pre + "var done=function(){location.reload()};try{" +
        "if(navigator.serviceWorker)P.push(navigator.serviceWorker.getRegistrations().then(function(rs){return Promise.all(rs.map(function(r){return r.unregister()}))}));" +
        "if(window.caches)P.push(caches.keys().then(function(ks){return Promise.all(ks.map(function(k){return caches.delete(k)}))}));" +
        "Promise.all(P).then(done,done)}catch(e){done()}})()"
}

/** Ukuran viewport (px CSS = dp) untuk menguji halaman seperti di perangkat lain. */
data class DeviceSize(val name: String, val w: Int, val h: Int)

private val DEVICE_PRESETS = listOf(
    DeviceSize("Ponsel kecil", 320, 568),
    DeviceSize("Ponsel 360×640", 360, 640),
    DeviceSize("Ponsel 390×844", 390, 844),
    DeviceSize("Ponsel besar 412×915", 412, 915),
    DeviceSize("Tablet kecil 600×960", 600, 960),
    DeviceSize("Tablet 768×1024", 768, 1024),
    DeviceSize("Tablet besar 1024×1366", 1024, 1366),
    DeviceSize("Laptop 1280×800", 1280, 800),
    DeviceSize("Desktop 1920×1080", 1920, 1080),
)

private enum class Tab3(val label: String) { PREVIEW("PREVIEW"), CONSOLE("CONSOLE"), NETWORK("NETWORK"), SERVER("SERVER"), ERRORS("ERRORS") }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: AppViewModel, project: Project, initialTab: Int = 0, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val servers by vm.servers.servers.collectAsState()
    val info = servers[project.id]
    val logs = remember(project.id) { DevToolsBus.of(project.id) }
    var tab by remember { mutableStateOf(Tab3.values().getOrElse(initialTab) { Tab3.PREVIEW }) }
    var fullscreen by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var loadingPct by remember { mutableStateOf(0) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var fileCb by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var wv by remember { mutableStateOf<WebView?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var jsEnabled by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<DeviceSize?>(null) }
    var autoFull by remember { mutableStateOf(false) }
    val resetOnLoad = remember(project.id) { booleanArrayOf(false) }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCb; fileCb = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    val running = info?.status == ServerStatus.RUNNING
    val baseUrl = info?.url

    // Muat ketika server siap (atau port berubah).
    LaunchedEffect(baseUrl, running) {
        if (running && baseUrl != null && wv != null && (wv?.url.isNullOrEmpty() || wv?.url == "about:blank")) wv?.loadUrl(baseUrl)
    }

    fun resetWeb(storage: Boolean) {
        val w = wv ?: return
        w.clearCache(true); w.clearFormData()
        if (storage) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        }
        w.evaluateJavascript(webResetJs(storage), null) // halaman memuat ulang sendiri setelah selesai
        main.post { logs.addServer(LogLevel.INFO, if (storage) "Data web dihapus (cache, service worker, storage, cookie)" else "Cache web & service worker dihapus") }
    }

    // File proyek diubah lewat editor -> halaman statis dimuat ulang tanpa cache lama (dev server memakai HMR).
    val rev = vm.fileRev[project.id] ?: 0
    LaunchedEffect(rev, wv) {
        val w = wv ?: return@LaunchedEffect
        if (rev != (vm.webRevSeen[project.id] ?: 0)) {
            vm.webRevSeen[project.id] = rev
            if (project.hostingMode == HostingMode.STATIC) {
                w.clearCache(true)
                if (running && !w.url.isNullOrEmpty() && w.url != "about:blank") resetWeb(autoFull) else resetOnLoad[0] = true
            }
        }
    }

    fun isLocal(u: Uri) = u.host == "127.0.0.1" || u.host == "localhost"

    val bridge = remember(project.id) { DevBridge(logs) }
    val injection = remember { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    BackHandler(enabled = true) {
        when {
            customView != null -> { customCb?.onCustomViewHidden() }
            fullscreen -> fullscreen = false
            tab != Tab3.PREVIEW -> tab = Tab3.PREVIEW
            wv?.canGoBack() == true -> wv?.goBack()
            else -> onBack()
        }
    }

    // Mode layar penuh: sembunyikan system bar.
    DisposableEffect(fullscreen, customView) {
        val act = ctx as? Activity
        val ctl = act?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (fullscreen || customView != null) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(Unit) { onDispose { wv?.let { it.stopLoading(); it.destroy() }; wv = null } }

    fun applyDevice(d: DeviceSize?) {
        device = d
        wv?.settings?.let { st -> applyUa(st, desktopMode || (d != null && d.w >= 900)) }
        wv?.reload()
    }
    fun navigate(raw0: String) {
        // Alamat hanya boleh menuju server lokal; selain itu dibuka di browser eksternal.
        val raw = raw0.trim()
        val target = when {
            raw.startsWith("/") && baseUrl != null -> baseUrl.trimEnd('/') + raw
            raw.startsWith("http") -> raw
            baseUrl != null -> baseUrl.trimEnd('/') + "/" + raw
            else -> raw
        }
        val u = Uri.parse(target)
        if (isLocal(u)) wv?.loadUrl(target)
        else ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val chrome = !fullscreen && customView == null
    Column(
        Modifier.fillMaxSize().background(Dev.Bg)
            .then(if (chrome) Modifier.statusBarsPadding().navigationBarsPadding().imePadding() else Modifier)
    ) {
        if (!fullscreen) {
            // ---- Toolbar ringkas: ✕  [alamat]  ⟳  ⋮ ----
            Row(Modifier.fillMaxWidth().height(44.dp).background(Dev.Surface).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Tutup", tint = Dev.Text) }
                BasicTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    modifier = Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp)).background(Dev.Surface2),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Dev.Mono, fontSize = 12.sp, color = Dev.Text),
                    cursorBrush = SolidColor(Dev.Blue),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                    decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) { inner() } }
                )
                TextButton(onClick = { wv?.reload() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Refresh, "Muat ulang", tint = Dev.Text) }
                TextButton(onClick = { sizeDialog = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { androidx.compose.material3.Icon(AppIcons.Devices, "Ukuran layar", tint = if (device != null) Dev.Blue else Dev.Text) }
                TextButton(onClick = { tab = if (tab == Tab3.PREVIEW) Tab3.CONSOLE else Tab3.PREVIEW }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { androidx.compose.material3.Icon(AppIcons.Terminal, "DevTools", tint = if (tab != Tab3.PREVIEW) Dev.Blue else Dev.Text) }
                Box {
                    TextButton(onClick = { menuOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, "Menu", tint = Dev.Text) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Kembali") }, enabled = canBack, onClick = { menuOpen = false; wv?.goBack() })
                        DropdownMenuItem(text = { Text("Maju") }, enabled = canForward, onClick = { menuOpen = false; wv?.goForward() })
                        DropdownMenuItem(text = { Text("Ukuran layar…" + (device?.let { "  (${it.w}×${it.h})" } ?: "")) }, onClick = { menuOpen = false; sizeDialog = true })
                        DropdownMenuItem(text = { Text(if (tab == Tab3.PREVIEW) "Buka DevTools" else "Tutup DevTools") },
                            onClick = { menuOpen = false; tab = if (tab == Tab3.PREVIEW) Tab3.CONSOLE else Tab3.PREVIEW })
                        DropdownMenuItem(text = { Text("Layar penuh") }, leadingIcon = { androidx.compose.material3.Icon(AppIcons.Fullscreen, null) }, onClick = { menuOpen = false; fullscreen = true })
                        DropdownMenuItem(text = { Text((if (desktopMode) "✓ " else "") + "Mode desktop") },
                            onClick = { menuOpen = false; desktopMode = !desktopMode; wv?.settings?.let { st -> applyUa(st, desktopMode || ((device?.w ?: 0) >= 900)) }; wv?.reload() })
                        DropdownMenuItem(text = { Text((if (jsEnabled) "✓ " else "") + "JavaScript") },
                            onClick = { menuOpen = false; jsEnabled = !jsEnabled; wv?.settings?.javaScriptEnabled = jsEnabled; wv?.reload() })
                        DropdownMenuItem(text = { Text("Hapus data web") }, leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Delete, null) },
                            onClick = { menuOpen = false; resetWeb(true) })
                        DropdownMenuItem(text = { Text((if (autoFull) "✓ " else "") + "Hapus semua data saat file berubah") }, onClick = { autoFull = !autoFull })
                        DropdownMenuItem(text = { Text("Hapus cache") }, leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Delete, null) }, onClick = { menuOpen = false; wv?.clearCache(true); main.post { logs.addServer(LogLevel.INFO, "Cache WebView dibersihkan") } })
                        DropdownMenuItem(text = { Text("Buka di browser eksternal") }, enabled = baseUrl != null,
                            onClick = { menuOpen = false; baseUrl?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                    }
                }
            }
            if (loadingPct in 1..99) androidx.compose.material3.LinearProgressIndicator(progress = { loadingPct / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
          BoxWithConstraints(Modifier.fillMaxSize().background(if (device != null) androidx.compose.ui.graphics.Color(0xFF05070A) else Dev.Bg), contentAlignment = Alignment.Center) {
            val d = device
            val sc = if (d == null) 1f else minOf(1f, maxWidth.value / d.w, maxHeight.value / d.h)
            val frame = if (d == null) Modifier.fillMaxSize()
                else Modifier.requiredSize(d.w.dp, d.h.dp).graphicsLayer { scaleX = sc; scaleY = sc }.border(BorderStroke(1.dp, Dev.Blue))
            // WebView selalu hidup (tidak di-recreate saat berpindah tab / ukuran).
            AndroidView(
                modifier = frame,
                factory = { c ->
                    WebView(c).apply {
                        setBackgroundColor(0xFF0D1117.toInt())
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            allowFileAccess = false
                            allowContentAccess = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            cacheMode = WebSettings.LOAD_DEFAULT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                        addJavascriptInterface(bridge, InjectedScripts.BRIDGE)
                        if (injection) {
                            try {
                                WebViewCompat.addDocumentStartJavaScript(this, InjectedScripts.MONITOR, setOf("*"))
                            } catch (_: Exception) {}
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                val u = r.url
                                if (isLocal(u) || u.scheme == "about" || u.scheme == "blob" || u.scheme == "data") return false
                                // Navigasi keluar dari server lokal → browser eksternal (jaga origin & keamanan).
                                try { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                                return true
                            }
                            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) {
                                if (url != null && url != "about:blank") address = url
                                canBack = v.canGoBack(); canForward = v.canGoForward()
                            }
                            override fun onPageFinished(v: WebView, url: String?) {
                                canBack = v.canGoBack(); canForward = v.canGoForward()
                                if (resetOnLoad[0] && url != null && url != "about:blank") { resetOnLoad[0] = false; v.evaluateJavascript(webResetJs(autoFull), null) }
                                if (url != null) address = url
                            }
                            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                                canBack = v.canGoBack(); canForward = v.canGoForward()
                            }
                            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                                val n = NetEntry(System.currentTimeMillis(), r.method, r.url.toString(), 0, 0, -1, true, "page")
                                main.post { logs.addNet(n); logs.addJs(JsMessage(System.currentTimeMillis(), LogLevel.ERROR, "${e.description}: ${r.url}", r.url.toString(), 0, "NetworkError")) }
                            }
                            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                                if (e.statusCode >= 400) main.post {
                                    logs.addJs(JsMessage(System.currentTimeMillis(), LogLevel.ERROR, "HTTP ${e.statusCode} ${r.url}", r.url.toString(), 0, "HttpError"))
                                }
                            }
                            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                                main.post { logs.addJs(JsMessage(System.currentTimeMillis(), LogLevel.ERROR, "WebView render process berhenti (crash=${d.didCrash()}). Muat ulang halaman.", "", 0, "WebViewCrash")) }
                                // Return true: aplikasi tidak ikut jatuh; pengguna dapat membuka ulang preview.
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, p: Int) { loadingPct = p }
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                val level = when (m.messageLevel()) {
                                    ConsoleMessage.MessageLevel.ERROR -> LogLevel.ERROR
                                    ConsoleMessage.MessageLevel.WARNING -> LogLevel.WARN
                                    else -> LogLevel.INFO
                                }
                                val msg = m.message() ?: ""
                                // Error JS tak tertangkap sudah dilaporkan oleh skrip injeksi (dengan stack trace).
                                if (injection && level == LogLevel.ERROR && msg.startsWith("Uncaught")) return true
                                val jm = JsMessage(System.currentTimeMillis(), level, msg, m.sourceId() ?: "", m.lineNumber(),
                                    if (level == LogLevel.ERROR) "ConsoleError" else "")
                                main.post { logs.addJs(jm) }
                                return true
                            }
                            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                                fileCb?.onReceiveValue(null)
                                fileCb = cb
                                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; cb.onReceiveValue(null); false }
                            }
                            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                                customView = view; customCb = cb
                            }
                            override fun onHideCustomView() { customView = null; customCb = null }
                            override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
                        }
                        setDownloadListener { url, _, _, _, _ ->
                            try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        }
                        wv = this
                        val u = vm.servers.info(project.id)
                        if (u?.status == ServerStatus.RUNNING) loadUrl(u.url)
                    }
                },
                update = { }
            )
            if (d != null) Text("${d.w}×${d.h} · ${(sc * 100).toInt()}%", color = Dev.Text, fontSize = 10.sp, fontFamily = Dev.Mono,
                modifier = Modifier.align(Alignment.BottomCenter).background(Dev.Surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
          }

            // Layar tunggu bila server belum RUNNING
            if (!running && tab == Tab3.PREVIEW) {
                Column(Modifier.fillMaxSize().background(Dev.Bg).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (info == null) "Server belum berjalan." else "Status: ${id.webdev.studio.ui.statusText(info.status)}", color = Dev.Text, fontFamily = Dev.Mono)
                    info?.error?.let { Text(it, color = Dev.Red, fontSize = 12.sp, fontFamily = Dev.Mono, modifier = Modifier.padding(top = 8.dp)) }
                }
            }

            if (fullscreen && customView == null) {
                TextButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    androidx.compose.material3.Icon(AppIcons.Fullscreen, "Keluar layar penuh", tint = Dev.Text, modifier = Modifier.background(Dev.Surface.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(6.dp))
                }
            }
            customView?.let { v ->
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                    factory = { c -> FrameLayout(c).apply { (v.parent as? ViewGroup)?.removeView(v); addView(v) } }
                )
            }
        }

        // ---- DevTools sebagai panel bawah (tidak menutupi halaman sepenuhnya) ----
        if (!fullscreen && tab != Tab3.PREVIEW) {
            androidx.compose.material3.HorizontalDivider(color = Dev.Muted.copy(alpha = 0.3f))
            Column(Modifier.weight(0.9f).fillMaxWidth().background(Dev.Bg)) {
                Row(Modifier.fillMaxWidth().background(Dev.Surface), verticalAlignment = Alignment.CenterVertically) {
                    Tab3.values().filter { it != Tab3.PREVIEW }.forEach { t ->
                        val badge = if (t == Tab3.ERRORS) logs.js.count { it.level == LogLevel.ERROR }.takeIf { it > 0 }?.let { " ($it)" } ?: "" else ""
                        Text(t.label + badge, fontSize = 10.sp, fontFamily = Dev.Mono, maxLines = 1,
                            color = if (tab == t) Dev.Blue else Dev.Muted,
                            modifier = Modifier.weight(1f).clickable { tab = t }.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    TextButton(onClick = { tab = Tab3.PREVIEW }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Tutup DevTools", tint = Dev.Text) }
                }
                when (tab) {
                    Tab3.CONSOLE -> LogPanel { JsConsole(logs, onClear = { logs.js.clear() }) }
                    Tab3.NETWORK -> LogPanel { NetworkPanel(logs, onClear = { logs.net.clear() }) }
                    Tab3.SERVER -> LogPanel { ServerConsole(logs, onClear = { logs.server.clear() }) }
                    Tab3.ERRORS -> LogPanel { ErrorViewer(logs, onClear = { logs.js.removeAll { it.level == LogLevel.ERROR } }) }
                    else -> {}
                }
            }
        }
    }

    if (sizeDialog) DeviceSizeDialog(device, onDismiss = { sizeDialog = false }, onPick = { sizeDialog = false; applyDevice(it) })
}

@Composable
private fun DeviceSizeDialog(current: DeviceSize?, onDismiss: () -> Unit, onPick: (DeviceSize?) -> Unit) {
    var cw by remember { mutableStateOf((current?.w ?: 390).toString()) }
    var ch by remember { mutableStateOf((current?.h ?: 844).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dev.Surface,
        title = { Text("Ukuran layar", color = Dev.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable
                fun row(label: String, selected: Boolean, onClick: () -> Unit) {
                    Text((if (selected) "● " else "○ ") + label, color = if (selected) Dev.Blue else Dev.Text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp))
                }
                row("Penuh (ukuran layar perangkat ini)", current == null) { onPick(null) }
                DEVICE_PRESETS.forEach { p -> row(p.name, current?.w == p.w && current.h == p.h) { onPick(p) } }
                Text("Kustom (px CSS)", color = Dev.Muted, fontSize = 11.sp, fontFamily = Dev.Mono, modifier = Modifier.padding(top = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(cw, { cw = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Lebar") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    Text("×", color = Dev.Text, modifier = Modifier.padding(horizontal = 6.dp))
                    OutlinedTextField(ch, { ch = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Tinggi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Row {
                    TextButton(onClick = { val t = cw; cw = ch; ch = t }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Refresh, "Putar / tukar lebar-tinggi") }
                    TextButton(onClick = {
                        val w = cw.toIntOrNull()?.coerceIn(200, 4000); val h = ch.toIntOrNull()?.coerceIn(200, 4000)
                        if (w != null && h != null) onPick(DeviceSize("Kustom", w, h))
                    }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, "Terapkan") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Tutup") } }
    )
}

private fun applyUa(s: WebSettings, desktop: Boolean) {
    val ua = s.userAgentString
    if (desktop) {
        s.userAgentString = ua.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
        s.useWideViewPort = true
    } else {
        s.userAgentString = null
    }
}

@Composable
private fun LogPanel(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Dev.Bg)) { content() }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private fun t(ms: Long) = timeFmt.format(Date(ms))

@Composable
private fun PanelHeader(title: String, count: Int, onClear: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Dev.Surface).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$title ($count)", color = Dev.Muted, fontFamily = Dev.Mono, fontSize = 11.sp, modifier = Modifier.weight(1f))
        extra()
        TextButton(onClick = onClear) { Text("Clear", fontSize = 12.sp) }
    }
}

@Composable
private fun JsConsole(logs: ProjectLogs, onClear: () -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.js.size) { if (logs.js.isNotEmpty()) state.scrollToItem(logs.js.size - 1) }
    Column {
        PanelHeader("JavaScript Console", logs.js.size, onClear)
        if (logs.js.isEmpty()) Text("Belum ada pesan console.", color = Dev.Muted, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(logs.js.size) { i ->
                val m = logs.js[i]
                val c = when (m.level) { LogLevel.ERROR -> Dev.Red; LogLevel.WARN -> Dev.Amber; else -> Dev.Text }
                Text("${t(m.time)}  ${m.message}" + if (m.source.isNotEmpty() && m.line > 0) "\n   ↳ ${m.source.substringAfterLast('/')}:${m.line}" else "",
                    color = c, fontFamily = Dev.Mono, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun NetworkPanel(logs: ProjectLogs, onClear: () -> Unit) {
    var filter by remember { mutableStateOf("all") }
    val list = logs.net.filter { filter == "all" || it.source == filter }
    val state = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) state.scrollToItem(list.size - 1) }
    Column {
        PanelHeader("Network Monitor", list.size, onClear) {
            listOf("all", "server", "page").forEach { f ->
                TextButton(onClick = { filter = f }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                    Text(f, fontSize = 11.sp, color = if (filter == f) Dev.Blue else Dev.Muted)
                }
            }
        }
        if (list.isEmpty()) Text("Belum ada request tercatat. 'server' = request ke HTTP server internal (Static); 'page' = fetch/XHR dari halaman.", color = Dev.Muted, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(list.size) { i ->
                val n = list[i]
                val c = if (n.failed) Dev.Red else if (n.status in 300..399) Dev.Amber else Dev.Green
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(n.method, color = Dev.Blue, fontFamily = Dev.Mono, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
                    Text(if (n.status == 0) "ERR" else n.status.toString(), color = c, fontFamily = Dev.Mono, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
                    Text(n.url, color = Dev.Text, fontFamily = Dev.Mono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${n.durationMs}ms", color = Dev.Muted, fontFamily = Dev.Mono, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ServerConsole(logs: ProjectLogs, onClear: () -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.server.size) { if (logs.server.isNotEmpty()) state.scrollToItem(logs.server.size - 1) }
    Column {
        PanelHeader("Server Console", logs.server.size, onClear)
        if (logs.server.isEmpty()) Text("Belum ada output server.", color = Dev.Muted, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(logs.server.size) { i ->
                val l = logs.server[i]
                val c = when (l.level) { LogLevel.ERROR, LogLevel.STDERR -> Dev.Red; LogLevel.WARN -> Dev.Amber; LogLevel.INFO -> Dev.Blue; else -> Dev.Text }
                Text("${t(l.time)}  ${l.text}", color = c, fontFamily = Dev.Mono, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun ErrorViewer(logs: ProjectLogs, onClear: () -> Unit) {
    val errs = logs.js.filter { it.level == LogLevel.ERROR }
    Column {
        PanelHeader("Error Viewer", errs.size, onClear)
        if (errs.isEmpty()) Text("Tidak ada error.", color = Dev.Green, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxSize()) {
            items(errs.size) { i ->
                val e = errs[i]
                Column(Modifier.fillMaxWidth().padding(12.dp).background(Dev.Surface, RoundedCornerShape(8.dp)).padding(10.dp)) {
                    Text(e.errorType.ifEmpty { "Error" }, color = Dev.Red, fontFamily = Dev.Mono, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 12.sp)
                    Text(e.message, color = Dev.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    if (e.source.isNotEmpty()) Text("File: ${e.source}", color = Dev.Muted, fontFamily = Dev.Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    if (e.line > 0) Text("Line: ${e.line}", color = Dev.Muted, fontFamily = Dev.Mono, fontSize = 10.sp)
                    if (e.stack.isNotEmpty()) Text(e.stack, color = Dev.Muted, fontFamily = Dev.Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(t(e.time), color = Dev.Muted, fontSize = 10.sp)
                }
            }
        }
    }
}
