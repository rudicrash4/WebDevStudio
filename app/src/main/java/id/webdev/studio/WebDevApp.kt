package id.webdev.studio

import android.app.Application
import id.webdev.studio.data.ProjectDb
import id.webdev.studio.data.SettingsStore
import id.webdev.studio.runtime.NodeRuntime
import id.webdev.studio.server.ServerManager

class WebDevApp : Application() {
    lateinit var db: ProjectDb
    lateinit var settings: SettingsStore
    lateinit var runtime: NodeRuntime
    lateinit var servers: ServerManager

    override fun onCreate() {
        super.onCreate()
        db = ProjectDb(this)
        settings = SettingsStore(this)
        runtime = NodeRuntime(this)
        servers = ServerManager(this, runtime, settings)
    }
}
