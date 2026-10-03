package com.joshreimer.toryaccess.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.joshreimer.toryaccess.MainActivity
import com.joshreimer.toryaccess.R
import com.joshreimer.toryaccess.graph
import com.joshreimer.toryaccess.ssh.SessionStatus
import com.joshreimer.toryaccess.tor.TorPhase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Keeps the process (and therefore the tor child + SSH sockets) alive while the app is
 * backgrounded. Without a foreground service Android freezes/kills us within minutes.
 */
class HubService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…", 0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        observe()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        val g = graph
        val connected = g.sessions.sessions.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(0 to 0)
            else combine(list.map { it.status }) { st -> st.count { it is SessionStatus.Connected } to st.size }
        }
        lifecycleScope.launch {
            combine(g.tor.state, connected) { tor, (live, total) -> Triple(tor, live, total) }
                .collect { (tor, live, total) ->
                    if ((tor.phase == TorPhase.STOPPED || tor.phase == TorPhase.ERROR) && total == 0) {
                        stopSelf()
                        return@collect
                    }
                    val torText = when (tor.phase) {
                        TorPhase.READY -> "Tor ready"
                        TorPhase.BOOTSTRAPPING, TorPhase.STARTING -> "Tor ${tor.progress}%"
                        TorPhase.ERROR -> "Tor error"
                        TorPhase.STOPPED -> "Tor off"
                    }
                    val sessText = when (total) {
                        0 -> "no sessions"
                        1 -> if (live == 1) "1 session" else "1 session (offline)"
                        else -> "$live/$total sessions live"
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification("$torText · $sessText", tor.progress.takeIf { tor.phase != TorPhase.READY } ?: 0))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.sessions.closeAll()
            graph.stopTor()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply { if (progress in 1..99) setProgress(100, progress, false) }
            .addAction(0, "Stop all", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "hub"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.joshreimer.toryaccess.STOP_ALL"

        /** Safe to call repeatedly. Fails quietly if we're in the background (Android 12+ rule). */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, HubService::class.java))
            } catch (e: Exception) {
                Log.w("HubService", "Couldn't start foreground service (app in background?)", e)
            }
        }
    }
}
