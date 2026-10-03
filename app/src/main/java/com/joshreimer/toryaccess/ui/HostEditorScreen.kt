package com.joshreimer.toryaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.joshreimer.toryaccess.AppGraph
import com.joshreimer.toryaccess.data.AuthMethod
import com.joshreimer.toryaccess.data.Host
import com.joshreimer.toryaccess.data.Route
import com.joshreimer.toryaccess.data.SecretBox
import com.joshreimer.toryaccess.ui.theme.MonoStyle
import com.joshreimer.toryaccess.ui.theme.ToryColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditorScreen(graph: AppGraph, nav: Navigator, hostId: String?) {
    val existing = remember(hostId) { hostId?.let(graph.hosts::get) }
    val keys by graph.keys.keys.collectAsState()

    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var hostname by remember { mutableStateOf(existing?.hostname.orEmpty()) }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "root") }
    var auth by remember { mutableStateOf(existing?.auth ?: if (keys.isEmpty()) AuthMethod.PASSWORD else AuthMethod.KEY) }
    var keyId by remember { mutableStateOf(existing?.keyId ?: keys.firstOrNull()?.id) }
    var newPassword by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(existing?.route ?: Route.TOR) }
    var group by remember { mutableStateOf(existing?.group.orEmpty()) }
    var startup by remember { mutableStateOf(existing?.startupCommand.orEmpty()) }
    var tried by remember { mutableStateOf(false) }

    val isOnion = hostname.trim().lowercase().endsWith(".onion")
    val portNum = port.toIntOrNull()
    val hostError = hostname.isBlank() || hostname.any { it.isWhitespace() }
    val portError = portNum == null || portNum !in 1..65535
    val userError = username.isBlank()
    val keyError = auth == AuthMethod.KEY && keys.none { it.id == keyId }
    val valid = !hostError && !portError && !userError && !keyError

    fun save() {
        tried = true
        if (!valid) return
        val password = when {
            newPassword.isNotEmpty() -> SecretBox.seal(newPassword)
            clearPassword -> null
            else -> existing?.password
        }
        val base = existing ?: Host(label = "", hostname = "", username = "")
        graph.hosts.upsert(
            base.copy(
                label = label.ifBlank { hostname.trim() },
                hostname = hostname.trim(),
                port = portNum!!,
                username = username.trim(),
                auth = auth,
                keyId = if (auth == AuthMethod.KEY) keyId else existing?.keyId,
                password = password,
                route = route,
                group = group.trim(),
                startupCommand = startup,
            )
        )
        nav.pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New host" else "Edit ${existing.label}") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { TextButton(onClick = ::save, modifier = Modifier.testTag("host_save")) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                label, { label = it }, label = { Text("Name") }, placeholder = { Text("my-vps") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host_label"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    hostname, { hostname = it }, label = { Text("Host / IP / .onion") },
                    singleLine = true, textStyle = MonoStyle, isError = tried && hostError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).testTag("host_hostname"),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") },
                    singleLine = true, isError = tried && portError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp).testTag("host_port"),
                )
            }
            OutlinedTextField(
                username, { username = it }, label = { Text("Username") }, singleLine = true,
                isError = tried && userError, textStyle = MonoStyle,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("host_username"),
            )

            Text("Route", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = route == Route.TOR || isOnion, onClick = { route = Route.TOR },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Through Tor") }
                SegmentedButton(
                    selected = route == Route.DIRECT && !isOnion, onClick = { route = Route.DIRECT }, enabled = !isOnion,
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Direct") }
            }
            if (route == Route.DIRECT && !isOnion) {
                Hint("Direct connections reveal your real IP to the server and your network.", ToryColors.Warn)
            } else if (isOnion) {
                Hint("Onion service: end-to-end inside Tor, no exit relay involved.", ToryColors.Onion)
            }

            Text("Authentication", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = auth == AuthMethod.KEY, onClick = { auth = AuthMethod.KEY },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("SSH key") }
                SegmentedButton(
                    selected = auth == AuthMethod.PASSWORD, onClick = { auth = AuthMethod.PASSWORD },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Password") }
            }
            if (auth == AuthMethod.KEY) {
                if (keys.isEmpty()) {
                    Hint("No keys yet.", ToryColors.Danger)
                    TextButton(onClick = { nav.push(Screen.Keys) }) { Text("Create or import a key") }
                } else {
                    var open by remember { mutableStateOf(false) }
                    val selected = keys.firstOrNull { it.id == keyId }
                    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                        OutlinedTextField(
                            value = selected?.let { "${it.label} · ${it.type}" } ?: "Choose a key",
                            onValueChange = {}, readOnly = true, label = { Text("Key") },
                            isError = tried && keyError,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            keys.forEach { k ->
                                DropdownMenuItem(
                                    text = { Column { Text(k.label); Text(k.fingerprint, style = MonoStyle) } },
                                    onClick = { keyId = k.id; open = false },
                                )
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    newPassword, { newPassword = it; clearPassword = false },
                    label = { Text(if (existing?.password != null && !clearPassword) "Password (saved — type to replace)" else "Password (optional)") },
                    supportingText = { Text("Leave empty to be asked on every connect.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("host_password"),
                )
                if (existing?.password != null && !clearPassword && newPassword.isEmpty()) {
                    TextButton(onClick = { clearPassword = true }) { Text("Forget saved password") }
                }
            }

            OutlinedTextField(
                group, { group = it }, label = { Text("Group (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                startup, { startup = it }, label = { Text("Run after login (optional)") },
                placeholder = { Text("tmux new -A -s main", style = MonoStyle) },
                singleLine = true, textStyle = MonoStyle,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Tip: `tmux new -A -s main` keeps your shell alive across Tor circuit drops.", MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(8.dp))
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save host") }
        }
    }
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = color, modifier = Modifier.padding(top = 2.dp).width(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
