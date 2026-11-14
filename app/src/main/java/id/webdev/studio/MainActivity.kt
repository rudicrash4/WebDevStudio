package id.webdev.studio

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import id.webdev.studio.browser.BrowserScreen
import id.webdev.studio.data.HostingMode
import id.webdev.studio.data.Project
import id.webdev.studio.data.ServerStatus
import id.webdev.studio.explorer.ExplorerScreen
import id.webdev.studio.ui.AppViewModel
import id.webdev.studio.ui.Dev
import id.webdev.studio.ui.HomeScreen
import id.webdev.studio.ui.ImportChooser
import id.webdev.studio.ui.ImportStateDialogs
import id.webdev.studio.ui.ProjectsScreen
import id.webdev.studio.ui.ServersScreen
import id.webdev.studio.ui.SettingsScreen
import id.webdev.studio.ui.WebDevTheme

private enum class Tab { HOME, PROJECTS, SERVERS, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            androidx.compose.runtime.LaunchedEffect(vm.settings.hideNav) { applySystemBars() }
            WebDevTheme(vm.settings.themeMode) { Root(vm) }
        }
    }

    /** Tombol navigasi disembunyikan (immersive): tidak sengaja tertekan; geser dari tepi untuk memunculkan sementara. */
    fun applySystemBars() {
        val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (vm.settings.hideNav) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        } else {
            c.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var explorerId by rememberSaveable { mutableStateOf<String?>(null) }
    var browserId by rememberSaveable { mutableStateOf<String?>(null) }
    var browserTab by rememberSaveable { mutableStateOf(0) }
    var showChooser by rememberSaveable { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.importFolder(uri)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.inspectZip(uri)
    }
    val runtimeZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.installRuntimeFromZip(uri)
    }

    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast = null }
    }

    /** Preview: internal atau eksternal sesuai pengaturan; server harus RUNNING. */
    fun preview(p: Project) {
        val info = vm.servers.info(p.id)
        if (vm.settings.browserMode == "external" && info?.status == ServerStatus.RUNNING) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            browserTab = 0; browserId = p.id
        }
    }

    val bid = browserId
    val eid = explorerId
    val bproj = bid?.let { vm.findProject(it) }
    val eproj = eid?.let { vm.findProject(it) }

    when {
        bproj != null -> {
            BrowserScreen(vm, bproj, browserTab) {
                if (bproj.id == AppViewModel.COMPAT_ID) vm.servers.stop(bproj.id)
                browserId = null
            }
        }
        eproj != null -> {
            BackHandler { explorerId = null }
            Box(Modifier.fillMaxSize().background(Dev.Bg).statusBarsPadding()) {
                ExplorerScreen(vm, eproj) { explorerId = null }
            }
        }
        else -> {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val items = listOf(
                Triple(Tab.HOME, "Home", Icons.Filled.Home),
                Triple(Tab.PROJECTS, "Projects", Icons.Filled.List),
                Triple(Tab.SERVERS, "Servers", Icons.Filled.PlayArrow),
                Triple(Tab.SETTINGS, "Settings", Icons.Filled.Settings)
            )
            val content: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    if (tab != Tab.HOME) BackHandler { tab = Tab.HOME }
                    when (tab) {
                        Tab.HOME -> HomeScreen(
                            vm, onImport = { showChooser = true },
                            onOpenProject = { explorerId = it.id },
                            onGoProjects = { tab = Tab.PROJECTS }, onGoServers = { tab = Tab.SERVERS },
                            onCompat = {
                                val c = vm.compatProject()
                                vm.servers.start(c)
                                browserTab = 0; browserId = c.id
                            }
                        )
                        Tab.PROJECTS -> ProjectsScreen(vm, onImport = { showChooser = true },
                            onOpen = { explorerId = it.id }, onPreview = { preview(it) })
                        Tab.SERVERS -> ServersScreen(vm, onPreview = { preview(it) },
                            onLogs = { browserTab = 3; browserId = it.id })
                        Tab.SETTINGS -> SettingsScreen(vm, onPickRuntimeZip = {
                            runtimeZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        })
                    }
                }
            }
            if (landscape) {
                // Landscape: rail di sisi kiri (tinggi layar terpakai penuh untuk konten), rail bisa digulir.
                Row(Modifier.fillMaxSize().background(Dev.Bg).systemBarsPadding()) {
                    NavigationRail(containerColor = Dev.Surface, modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                        items.forEach { (t, label, icon) ->
                            NavigationRailItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = Dev.Surface2)
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
            } else {
                Scaffold(
                    containerColor = Dev.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Dev.Surface) {
                            items.forEach { (t, label, icon) ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(icon, label) }, label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Dev.Surface2)
                                )
                            }
                        }
                    }
                ) { pad -> Box(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) { content() } }
            }
        }
    }

    if (showChooser) {
        ImportChooser(
            onFolder = { showChooser = false; folderPicker.launch(null) },
            onZip = {
                showChooser = false
                zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
            onClose = { showChooser = false }
        )
    }
    ImportStateDialogs(vm) { p ->
        tab = Tab.PROJECTS
    }
}
