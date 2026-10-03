package com.joshreimer.toryaccess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joshreimer.toryaccess.AppGraph
import com.joshreimer.toryaccess.data.AuthMethod
import com.joshreimer.toryaccess.data.Host
import com.joshreimer.toryaccess.data.Route
import com.joshreimer.toryaccess.data.SshKey
import com.joshreimer.toryaccess.data.TorMode
import com.joshreimer.toryaccess.ssh.SessionStatus
import com.joshreimer.toryaccess.ssh.SshTerminalSession
import com.joshreimer.toryaccess.ssh.friendlySshError
import com.joshreimer.toryaccess.ssh.parseHostSpec
import com.joshreimer.toryaccess.termux.TermuxBridge
import com.joshreimer.toryaccess.tor.TorPhase
import com.joshreimer.toryaccess.tor.TorState
import com.joshreimer.toryaccess.ui.theme.MonoStyle
import com.joshreimer.toryaccess.ui.theme.ToryColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(graph: AppGraph, nav: Navigator) {
    val context = LocalContext.current
    val hosts by graph.hosts.hosts.collectAsState()
    val keys by graph.keys.keys.collectAsState()
    val tor by graph.tor.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val sessions by graph.sessions.sessions.collectAsState()
    var query by remember { mutableStateOf("") }
    var deployFor by remember { mutableStateOf<Host?>(null) }
    var confirmDelete by remember { mutableStateOf<Host?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var pendingTermuxHost by remember { mutableStateOf<Host?>(null) }
    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val h = pendingTermuxHost
        pendingTermuxHost = null
        if (granted && h != null) openInTermux(context, graph, h)
        else if (!granted) Toast.makeText(context, "Termux permission denied", Toast.LENGTH_SHORT).show()
    }

    fun connect(host: Host) {
        val existing = sessions.firstOrNull { it.host.id == host.id && it.status.value !is SessionStatus.Closed }
        if (existing != null) graph.sessions.setActive(existing.id) else graph.sessions.open(host)
        nav.push(Screen.Terminal)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tory Access", fontWeight = FontWeight.SemiBold)
                        Text(
                            "SSH hub over Tor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { nav.push(Screen.Keys) }, modifier = Modifier.testTag("nav_keys")) {
                        Icon(Icons.Outlined.Key, "SSH keys")
                    }
                    IconButton(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.testTag("nav_settings")) {
                        Icon(Icons.Outlined.Settings, "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.push(Screen.EditHost(null)) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add host") },
                modifier = Modifier.testTag("add_host"),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                TorCard(
                    state = tor,
                    mode = settings.torMode,
                    external = "${settings.externalSocksHost}:${settings.externalSocksPort}",
                    onStart = graph::startTor,
                    onStop = graph::stopTor,
                    onNewIdentity = {
                        graph.scope.launch {
                            val ok = graph.tor.newIdentity()
                            Toast.makeText(context, if (ok) "New circuits for new connections" else "Tor isn't running", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDetails = { nav.push(Screen.Tor) },
                )
            }
            item {
                QuickConnect(onConnect = { spec ->
                    val host = Host(
                        label = spec.hostname,
                        hostname = spec.hostname,
                        port = spec.port,
                        username = spec.username,
                        auth = if (keys.isEmpty()) AuthMethod.PASSWORD else AuthMethod.KEY,
                        keyId = keys.firstOrNull()?.id,
                    )
                    graph.sessions.open(host)
                    nav.push(Screen.Terminal)
                })
            }
            if (sessions.isNotEmpty()) {
                item { SectionHeader("Open sessions") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sessions, key = { it.id }) { s ->
                            SessionChip(s) {
                                graph.sessions.setActive(s.id)
                                nav.push(Screen.Terminal)
                            }
                        }
                    }
                }
            }

            val filtered = hosts.filter {
                query.isBlank() || it.label.contains(query, true) || it.hostname.contains(query, true) ||
                    it.group.contains(query, true) || it.username.contains(query, true)
            }
            item {
                SectionHeader("Hosts") {
                    if (hosts.size > 4) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Filter") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            singleLine = true,
                            modifier = Modifier.width(180.dp).height(52.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (hosts.isEmpty()) {
                item { EmptyHosts(onAdd = { nav.push(Screen.EditHost(null)) }) }
            }
            filtered.groupBy { it.group.trim() }.toSortedMap().forEach { (group, list) ->
                if (group.isNotEmpty()) {
                    item(key = "g:$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                }
                items(list.sortedByDescending { it.lastConnectedAt }, key = { it.id }) { host ->
                    val live = sessions.filter { it.host.id == host.id }
                    HostCard(
                        host = host,
                        keyLabel = host.keyId?.let { id -> keys.firstOrNull { it.id == id }?.label },
                        liveSessions = live,
                        onConnect = { connect(host) },
                        onNewTab = {
                            graph.sessions.open(host)
                            nav.push(Screen.Terminal)
                        },
                        onEdit = { nav.push(Screen.EditHost(host.id)) },
                        onDuplicate = {
                            graph.hosts.upsert(host.copy(id = java.util.UUID.randomUUID().toString(), label = host.label + " copy", lastConnectedAt = 0))
                        },
                        onCopyCommand = {
                            val cmd = TermuxBridge.sshCommandLine(host, socksPortFor(graph))
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh", cmd))
                            Toast.makeText(context, "Copied: $cmd", Toast.LENGTH_SHORT).show()
                        },
                        onOpenTermux = {
                            when {
                                !TermuxBridge.isInstalled(context) -> Toast.makeText(context, "Termux isn't installed", Toast.LENGTH_SHORT).show()
                                TermuxBridge.hasPermission(context) -> openInTermux(context, graph, host)
                                else -> {
                                    pendingTermuxHost = host
                                    termuxPermission.launch(TermuxBridge.PERMISSION)
                                }
                            }
                        },
                        onDeployKey = { deployFor = host },
                        onDelete = { confirmDelete = host },
                    )
                }
            }
        }
    }

    deployFor?.let { host -> DeployKeyDialog(graph, host, keys, onDismiss = { deployFor = null }) }
    confirmDelete?.let { host ->
        AlertDialog(
            modifier = AutomationTags,
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${host.label}?") },
            text = { Text("Its saved password (if any) is deleted too. Trusted host keys are kept.") },
            confirmButton = {
                TextButton(onClick = { graph.hosts.delete(host.id); confirmDelete = null }) {
                    Text("Delete", color = ToryColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun socksPortFor(graph: AppGraph): Int? {
    val s = graph.settings.value
    return when (s.torMode) {
        TorMode.EXTERNAL -> s.externalSocksPort
        TorMode.EMBEDDED -> graph.tor.state.value.socksPort.takeIf { it > 0 } ?: s.socksPort
    }
}

private fun openInTermux(context: android.content.Context, graph: AppGraph, host: Host) {
    if (host.effectiveRoute == Route.TOR && graph.settings.value.torMode == TorMode.EMBEDDED &&
        graph.tor.state.value.phase != TorPhase.READY
    ) {
        Toast.makeText(context, "Start Tor first — Termux's ssh will use our SOCKS port", Toast.LENGTH_LONG).show()
        graph.startTor()
        return
    }
    try {
        TermuxBridge.open(context, host, socksPortFor(graph))
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Termux refused: set allow-external-apps=true in ~/.termux/termux.properties (${e.javaClass.simpleName})",
            Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
private fun TorCard(
    state: TorState,
    mode: TorMode,
    external: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewIdentity: () -> Unit,
    onDetails: () -> Unit,
) {
    val (color, title) = when {
        mode == TorMode.EXTERNAL -> ToryColors.Onion to "External SOCKS proxy"
        state.phase == TorPhase.READY -> ToryColors.Live to "Tor connected"
        state.phase == TorPhase.ERROR -> ToryColors.Danger to "Tor error"
        state.phase == TorPhase.STOPPED -> MaterialTheme.colorScheme.outline to "Tor stopped"
        else -> ToryColors.Warn to "Connecting to Tor · ${state.progress}%"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("tor_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
        onClick = onDetails,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.VpnLock, null, tint = color) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("tor_status"))
                    Text(
                        when (mode) {
                            TorMode.EXTERNAL -> "Routing through $external (e.g. Orbot)"
                            TorMode.EMBEDDED -> if (state.phase == TorPhase.READY) "SOCKS5 127.0.0.1:${state.socksPort}" else state.summary
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(mode == TorMode.EMBEDDED && (state.phase == TorPhase.BOOTSTRAPPING || state.phase == TorPhase.STARTING)) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = ToryColors.Warn,
                )
            }
            if (mode == TorMode.EMBEDDED) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase == TorPhase.STOPPED || state.phase == TorPhase.ERROR) {
                        FilledTonalButton(onClick = onStart, modifier = Modifier.testTag("tor_start")) { Text("Start Tor") }
                    } else {
                        OutlinedButton(onClick = onStop, modifier = Modifier.testTag("tor_stop")) { Text("Stop") }
                    }
                    if (state.phase == TorPhase.READY) {
                        FilledTonalButton(onClick = onNewIdentity, modifier = Modifier.testTag("tor_newnym")) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New circuits")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickConnect(onConnect: (com.joshreimer.toryaccess.ssh.HostSpec) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun go() {
        val spec = parseHostSpec(text)
        if (spec == null) error = true else { error = false; text = ""; onConnect(spec) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = false },
        placeholder = { Text("user@host.onion:22", style = MonoStyle) },
        label = { Text("Quick connect") },
        leadingIcon = { Icon(Icons.Outlined.Bolt, null) },
        trailingIcon = {
            IconButton(onClick = ::go, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Connect") }
        },
        isError = error,
        supportingText = if (error) ({ Text("Use user@host or user@host:port") }) else null,
        singleLine = true,
        textStyle = MonoStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { go() }),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("quick_connect"),
    )
}

@Composable
private fun SessionChip(s: SshTerminalSession, onClick: () -> Unit) {
    val status by s.status.collectAsState()
    val title by s.title.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor(status))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun statusColor(status: SessionStatus): Color = when (status) {
    is SessionStatus.Connected -> ToryColors.Live
    is SessionStatus.Connecting -> ToryColors.Warn
    is SessionStatus.Closed -> if (status.error) ToryColors.Danger else MaterialTheme.colorScheme.outline
}

@Composable
private fun HostCard(
    host: Host,
    keyLabel: String?,
    liveSessions: List<SshTerminalSession>,
    onConnect: () -> Unit,
    onNewTab: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenTermux: () -> Unit,
    onDeployKey: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val tor = host.effectiveRoute == Route.TOR
    Card(
        onClick = onConnect,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).testTag("host_${host.label}"),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background((if (tor) ToryColors.Onion else ToryColors.Warn).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (host.isOnion) Text("🧅", style = MaterialTheme.typography.titleLarge)
                else Icon(if (tor) Icons.Outlined.VpnLock else Icons.Outlined.Public, null, tint = if (tor) ToryColors.Onion else ToryColors.Warn)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (liveSessions.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        StatusDot(statusColor(liveSessions.first().status.collectAsState().value))
                        if (liveSessions.size > 1) Text(" ×${liveSessions.size}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    host.display, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        host.isOnion -> Badge("onion service", ToryColors.Onion)
                        tor -> Badge("via Tor", ToryColors.Onion)
                        else -> Badge("clearnet", ToryColors.Warn)
                    }
                    Badge(
                        if (host.auth == AuthMethod.KEY) (keyLabel ?: "key missing") else "password",
                        if (host.auth == AuthMethod.KEY && keyLabel == null) ToryColors.Danger else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("host_menu_${host.label}")) {
                    Icon(Icons.Outlined.MoreVert, "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = AutomationTags) {
                    DropdownMenuItem(text = { Text("New tab") }, leadingIcon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { menu = false; onNewTab() })
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Outlined.FileCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Install public key…") }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, onClick = { menu = false; onDeployKey() })
                    DropdownMenuItem(text = { Text("Open in Termux") }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) }, onClick = { menu = false; onOpenTermux() })
                    DropdownMenuItem(text = { Text("Copy ssh command") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; onCopyCommand() })
                    DropdownMenuItem(text = { Text("Delete", color = ToryColors.Danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ToryColors.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun EmptyHosts(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🧅", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("No hosts yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Add a VPS by IP, domain or .onion address. Every connection goes through Tor unless you mark the host as clearnet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onAdd) { Text("Add your first host") }
    }
}

@Composable
private fun DeployKeyDialog(graph: AppGraph, host: Host, keys: List<SshKey>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(keys.firstOrNull()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AlertDialog(

        modifier = AutomationTags,
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("Install key on ${host.label}") },
        text = {
            Column {
                when {
                    keys.isEmpty() -> Text("Create or import a key first (Keys screen).")
                    result != null -> Text(result!!.second, color = if (result!!.first) ToryColors.Live else ToryColors.Danger)
                    busy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(busy!!)
                    }
                    else -> {
                        Text("Logs in with the password, then appends the public key to ~/.ssh/authorized_keys (like ssh-copy-id).")
                        Spacer(Modifier.height(8.dp))
                        keys.forEach { k ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = k },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected?.id == k.id, onClick = { selected = k })
                                Column {
                                    Text(k.label)
                                    Text(k.fingerprint, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val r = result
            when {
                r != null && r.first -> TextButton(onClick = {
                    graph.hosts.upsert(host.copy(auth = AuthMethod.KEY, keyId = selected?.id))
                    onDismiss()
                }) { Text("Use key for this host") }
                r != null -> TextButton(onClick = onDismiss) { Text("Close") }
                else -> TextButton(
                    enabled = selected != null && busy == null,
                    onClick = {
                        val key = selected ?: return@TextButton
                        busy = "Connecting…"
                        scope.launch {
                            result = try {
                                val out = graph.sessions.deployKey(host, key) { busy = it }
                                true to "Installed. ${out.lines().lastOrNull().orEmpty()}"
                            } catch (e: Exception) {
                                false to friendlySshError(e)
                            }
                            busy = null
                        }
                    },
                ) { Text("Install") }
            }
        },
        dismissButton = {
            if (busy == null && result == null) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
