package com.joshreimer.toryaccess.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joshreimer.toryaccess.AppGraph
import com.joshreimer.toryaccess.BuildConfig
import com.joshreimer.toryaccess.data.TorMode
import com.joshreimer.toryaccess.termux.TermuxBridge
import com.joshreimer.toryaccess.ui.theme.MonoStyle
import com.joshreimer.toryaccess.ui.theme.ToryColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, nav: Navigator) {
    val settings by graph.settings.settings.collectAsState()
    val known by graph.knownHosts.entries.collectAsState()
    val context = LocalContext.current

    var socksPort by remember { mutableStateOf(settings.socksPort.toString()) }
    var extHost by remember { mutableStateOf(settings.externalSocksHost) }
    var extPort by remember { mutableStateOf(settings.externalSocksPort.toString()) }
    var bridges by remember { mutableStateOf(settings.bridges) }
    var termuxRefresh by remember { mutableIntStateOf(0) }
    val termuxPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { termuxRefresh++ }

    fun applyTor() {
        graph.settings.update {
            it.copy(
                socksPort = socksPort.toIntOrNull()?.takeIf { p -> p in 1024..65535 } ?: it.socksPort,
                externalSocksHost = extHost.trim().ifBlank { "127.0.0.1" },
                externalSocksPort = extPort.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: it.externalSocksPort,
                bridges = bridges,
            )
        }
        if (graph.tor.isRunning) graph.restartTor()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            SectionHeader("Tor")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = settings.torMode == TorMode.EMBEDDED,
                        onClick = { graph.settings.update { it.copy(torMode = TorMode.EMBEDDED) } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Built-in tor") }
                    SegmentedButton(
                        selected = settings.torMode == TorMode.EXTERNAL,
                        onClick = {
                            graph.stopTor()
                            graph.settings.update { it.copy(torMode = TorMode.EXTERNAL) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("External (Orbot)") }
                }
                if (settings.torMode == TorMode.EMBEDDED) {
                    OutlinedTextField(
                        socksPort, { socksPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Preferred SOCKS port") },
                        supportingText = { Text("Falls back to a free port if taken. Not 9050, to avoid clashing with Orbot.") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("Start Tor when the app opens", settings.autoStartTor) { v -> graph.settings.update { it.copy(autoStartTor = v) } }
                    OutlinedTextField(
                        bridges, { bridges = it },
                        label = { Text("Bridges (one per line)") },
                        placeholder = { Text("203.0.113.5:443 4352E58420E68F5E40BF7C74FADDCCD9D1349413", style = MonoStyle.copy(fontSize = 11.sp)) },
                        textStyle = MonoStyle.copy(fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(extHost, { extHost = it }, label = { Text("SOCKS host") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            extPort, { extPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp),
                        )
                    }
                    Text(
                        "Orbot exposes SOCKS on 127.0.0.1:9050 by default. Hosts marked “Direct” still bypass it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = ::applyTor, modifier = Modifier.testTag("settings_apply_tor")) {
                    Text(if (graph.tor.isRunning) "Apply & restart Tor" else "Apply")
                }
            }

            SectionHeader("Terminal")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Font size: ${settings.fontSizeSp.roundToInt()}sp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { v -> graph.settings.update { it.copy(fontSizeSp = v.roundToInt().toFloat()) } },
                    valueRange = 8f..24f,
                )
                SwitchRow("Keep screen on in terminal", settings.keepScreenOn) { v -> graph.settings.update { it.copy(keepScreenOn = v) } }
            }

            SectionHeader("Termux")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val installed = remember(termuxRefresh) { TermuxBridge.isInstalled(context) }
                val permitted = remember(termuxRefresh) { TermuxBridge.hasPermission(context) }
                Text(
                    when {
                        !installed -> "Termux not found."
                        permitted -> "Termux connected — “Open in Termux” on a host launches its ssh through our Tor SOCKS port."
                        else -> "Termux found. Grant the RUN_COMMAND permission to hand hosts to Termux's ssh."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (installed && !permitted) {
                    FilledTonalButton(onClick = { termuxPerm.launch(TermuxBridge.PERMISSION) }) { Text("Grant permission") }
                }
                Text(
                    "In Termux, once:\n  pkg install openssh netcat-openbsd\n  echo allow-external-apps=true >> ~/.termux/termux.properties\n  termux-reload-settings",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Trusted host keys (${known.size})")
            if (known.isEmpty()) {
                Text(
                    "None yet. You'll be asked to verify each server's fingerprint on first connect.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            known.sortedBy { it.host }.forEach { k ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${k.type}  ${k.fingerprint}", style = MonoStyle.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { graph.knownHosts.remove(k.host, k.type) }) {
                        Icon(Icons.Outlined.Delete, "Forget", tint = ToryColors.Danger)
                    }
                }
            }

            SectionHeader("About")
            Text(
                "Tory Access ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) · ${BuildConfig.APPLICATION_ID}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
