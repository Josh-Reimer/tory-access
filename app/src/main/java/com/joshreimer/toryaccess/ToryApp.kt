package com.joshreimer.toryaccess

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.joshreimer.toryaccess.data.HostRepository
import com.joshreimer.toryaccess.data.KeyRepository
import com.joshreimer.toryaccess.data.KnownHostsRepository
import com.joshreimer.toryaccess.data.SettingsRepository
import com.joshreimer.toryaccess.data.TorMode
import com.joshreimer.toryaccess.service.HubService
import com.joshreimer.toryaccess.ssh.PromptGate
import com.joshreimer.toryaccess.ssh.SessionManager
import com.joshreimer.toryaccess.ssh.SshConnector
import com.joshreimer.toryaccess.tor.TorDaemon
import kotlinx.coroutines.MainScope

/** Hand-wired dependency graph; small enough not to need a DI framework. */
class AppGraph(private val app: Context) {
    val scope = MainScope()
    private val dir = app.filesDir

    val settings = SettingsRepository(dir)
    val hosts = HostRepository(dir)
    val keys = KeyRepository(dir)
    val knownHosts = KnownHostsRepository(dir)
    val prompts = PromptGate()
    val tor = TorDaemon(app, scope)

    private val connector = SshConnector(tor, settings, hosts, keys, knownHosts, prompts, ::startTor)
    val sessions = SessionManager(app, connector, scope)

    /** Starts the embedded daemon (if that's the configured mode) under the foreground service. */
    fun startTor() {
        if (settings.value.torMode != TorMode.EMBEDDED) return
        HubService.ensureRunning(app)
        tor.start(settings.value)
    }

    fun stopTor() = tor.stop()

    /** Restart so torrc picks up new bridges/ports. */
    fun restartTor() {
        if (tor.isRunning) tor.stop()
        startTor()
    }
}

class ToryApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(HubService.CHANNEL_ID, "Tor & SSH sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Tor or an SSH session is running"
                setShowBadge(false)
            }
        )
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as ToryApp).graph
