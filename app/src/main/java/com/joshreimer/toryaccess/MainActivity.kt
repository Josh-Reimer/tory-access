package com.joshreimer.toryaccess

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.joshreimer.toryaccess.data.TorMode
import com.joshreimer.toryaccess.tor.TorPhase
import com.joshreimer.toryaccess.ui.HostEditorScreen
import com.joshreimer.toryaccess.ui.HubScreen
import com.joshreimer.toryaccess.ui.KeysScreen
import com.joshreimer.toryaccess.ui.Navigator
import com.joshreimer.toryaccess.ui.PromptHost
import com.joshreimer.toryaccess.ui.Screen
import com.joshreimer.toryaccess.ui.SettingsScreen
import com.joshreimer.toryaccess.ui.TerminalScreen
import com.joshreimer.toryaccess.ui.TorScreen
import com.joshreimer.toryaccess.ui.theme.ToryTheme

class MainActivity : ComponentActivity() {
    private val nav = Navigator()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* service works either way */ }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = graph

        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val s = graph.settings.value
        if (s.torMode == TorMode.EMBEDDED && s.autoStartTor && graph.tor.state.value.phase == TorPhase.STOPPED) {
            graph.startTor()
        }
        handleAutomation(intent)

        setContent {
            ToryTheme {
                val pending by graph.prompts.current.collectAsState()
                // testTagsAsResourceId: Compose testTags show up as resource-ids in
                // `uiautomator dump`, so agents driving the app over Shizuku/adb can target them.
                Surface(
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BackHandler(enabled = nav.canPop) { nav.pop() }
                    AnimatedContent(
                        targetState = nav.current,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.Hub -> HubScreen(graph, nav)
                            is Screen.EditHost -> HostEditorScreen(graph, nav, screen.hostId)
                            Screen.Keys -> KeysScreen(graph, nav)
                            Screen.Terminal -> TerminalScreen(graph, nav)
                            Screen.Tor -> TorScreen(graph, nav)
                            Screen.Settings -> SettingsScreen(graph, nav)
                        }
                    }
                    PromptHost(graph.prompts, pending)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
    }

    /**
     * Debug builds only: lets an agent drive the app from a shell (adb or Shizuku `rish`) with
     * `am start`, which — unlike a broadcast — is allowed to bring us to the foreground and
     * start the foreground service. See scripts/device.sh.
     *
     *   am start -n <pkg>/com.joshreimer.toryaccess.MainActivity --es tory.cmd start-tor
     *   am start -n <pkg>/com.joshreimer.toryaccess.MainActivity --es tory.connect my-vps
     */
    private fun handleAutomation(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val g = graph
        intent.getStringExtra(EXTRA_CMD)?.let { cmd ->
            Log.i(AUTOMATION_TAG, "cmd=$cmd")
            when (cmd) {
                "start-tor" -> g.startTor()
                "stop-tor" -> g.stopTor()
                "restart-tor" -> g.restartTor()
                "hub" -> while (nav.canPop) nav.pop()
                "terminal" -> if (g.sessions.sessions.value.isNotEmpty()) nav.push(Screen.Terminal)
                "close-all" -> g.sessions.closeAll()
                else -> Log.w(AUTOMATION_TAG, "unknown cmd $cmd")
            }
        }
        intent.getStringExtra(EXTRA_CONNECT)?.let { label ->
            val host = g.hosts.findByLabel(label)
            if (host == null) {
                Log.w(AUTOMATION_TAG, "connect: no host labelled '$label'")
            } else {
                Log.i(AUTOMATION_TAG, "connect: ${host.display}")
                g.sessions.open(host)
                nav.push(Screen.Terminal)
            }
        }
        intent.removeExtra(EXTRA_CMD)
        intent.removeExtra(EXTRA_CONNECT)
    }

    companion object {
        const val AUTOMATION_TAG = "ToryAutomation"
        const val EXTRA_CMD = "tory.cmd"
        const val EXTRA_CONNECT = "tory.connect"
    }
}
