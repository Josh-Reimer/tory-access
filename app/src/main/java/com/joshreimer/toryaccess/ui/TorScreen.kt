package com.joshreimer.toryaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joshreimer.toryaccess.AppGraph
import com.joshreimer.toryaccess.data.TorMode
import com.joshreimer.toryaccess.tor.Circuit
import com.joshreimer.toryaccess.tor.TorPhase
import com.joshreimer.toryaccess.ui.theme.MonoStyle
import com.joshreimer.toryaccess.ui.theme.ToryColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorScreen(graph: AppGraph, nav: Navigator) {
    val state by graph.tor.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var circuits by remember { mutableStateOf<List<Circuit>>(emptyList()) }
    fun refresh() = scope.launch { circuits = graph.tor.circuits() }
    LaunchedEffect(state.phase) { if (state.phase == TorPhase.READY) refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tor") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh circuits") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (settings.torMode == TorMode.EXTERNAL) {
                            Text("External proxy mode", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Connections use ${settings.externalSocksHost}:${settings.externalSocksPort}. " +
                                    "The embedded daemon is idle. Switch modes in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("${state.phase.name.lowercase().replaceFirstChar(Char::uppercase)} · ${state.progress}%", style = MaterialTheme.typography.titleMedium)
                            Text(state.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                color = if (state.phase == TorPhase.READY) ToryColors.Live else ToryColors.Warn,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            )
                            if (state.socksPort > 0) {
                                Text("SOCKS5  127.0.0.1:${state.socksPort}", style = MonoStyle)
                                Text(
                                    "Other apps on this phone (e.g. Termux: ssh -o ProxyCommand='nc -X 5 -x 127.0.0.1:${state.socksPort} %h %p') can use it too.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.phase == TorPhase.STOPPED || state.phase == TorPhase.ERROR) {
                                    FilledTonalButton(onClick = graph::startTor) { Text("Start") }
                                } else {
                                    OutlinedButton(onClick = graph::stopTor) { Text("Stop") }
                                    OutlinedButton(onClick = graph::restartTor) { Text("Restart") }
                                }
                                if (state.phase == TorPhase.READY) {
                                    FilledTonalButton(onClick = { scope.launch { graph.tor.newIdentity(); refresh() } }) { Text("New circuits") }
                                }
                            }
                        }
                    }
                }
            }
            if (state.skippedBridges.isNotEmpty()) {
                item {
                    Text(
                        "Skipped ${state.skippedBridges.size} bridge line(s): only plain IP:port bridges are supported " +
                            "(obfs4/snowflake/webtunnel need a pluggable transport this build doesn't bundle — use Orbot via External mode).",
                        color = ToryColors.Warn,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            val built = circuits.filter { it.status == "BUILT" }
            item { SectionHeader("Circuits (${built.size} built)") }
            if (built.isEmpty()) {
                item {
                    Text(
                        if (state.phase == TorPhase.READY) "No built circuits yet — connect to a host." else "Tor isn't ready.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(built, key = { it.id }) { c -> CircuitRow(c) }

            item { SectionHeader("Log") }
            items(state.log.takeLast(120).reversed()) { line ->
                Text(
                    line,
                    style = MonoStyle.copy(fontSize = 10.sp),
                    color = if (line.startsWith("!!") || line.contains("[warn]") || line.contains("[err]")) ToryColors.Warn
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun CircuitRow(c: Circuit) {
    val roles = when {
        c.purpose.startsWith("HS_CLIENT_REND") -> listOf("Guard", "Middle", "Rendezvous")
        c.purpose.startsWith("HS_") -> listOf("Guard", "Middle", "Intro")
        else -> listOf("Guard", "Middle", "Exit")
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${c.id}", style = MonoStyle)
                Spacer(Modifier.width(8.dp))
                Badge(c.purpose.ifBlank { "GENERAL" }, if (c.purpose.startsWith("HS_")) ToryColors.Onion else MaterialTheme.colorScheme.secondary)
            }
            c.hops.forEachIndexed { i, hop ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        roles.getOrElse(i) { "Hop ${i + 1}" },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(82.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(hop.nickname.ifBlank { "?" }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(hop.fingerprint.take(8), style = MonoStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
