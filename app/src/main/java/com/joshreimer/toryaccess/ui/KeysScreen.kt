package com.joshreimer.toryaccess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joshreimer.toryaccess.AppGraph
import com.joshreimer.toryaccess.data.SshKey
import com.joshreimer.toryaccess.ssh.KeyKind
import com.joshreimer.toryaccess.ssh.KeyTools
import com.joshreimer.toryaccess.ui.theme.MonoStyle
import com.joshreimer.toryaccess.ui.theme.ToryColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface KeyDialog {
    data class Generate(val kind: KeyKind) : KeyDialog
    data class Import(val text: String) : KeyDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(graph: AppGraph, nav: Navigator) {
    val keys by graph.keys.keys.collectAsState()
    val hosts by graph.hosts.hosts.collectAsState()
    val context = LocalContext.current
    var fabMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }
    var confirmDelete by remember { mutableStateOf<SshKey?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }
        }.getOrNull()
        dialog = KeyDialog.Import(text.orEmpty())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH keys") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { fabMenu = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New key") },
                    modifier = Modifier.testTag("key_add"),
                )
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }, modifier = AutomationTags) {
                    DropdownMenuItem(text = { Text("Generate Ed25519 (recommended)") }, onClick = { fabMenu = false; dialog = KeyDialog.Generate(KeyKind.ED25519) }, modifier = Modifier.testTag("key_gen_ed25519"))
                    DropdownMenuItem(text = { Text("Generate RSA 4096") }, onClick = { fabMenu = false; dialog = KeyDialog.Generate(KeyKind.RSA) })
                    DropdownMenuItem(text = { Text("Import from file…") }, onClick = { fabMenu = false; pickFile.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("Paste private key…") }, onClick = { fabMenu = false; dialog = KeyDialog.Import("") })
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (keys.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Key, null, Modifier.size(48.dp), tint = ToryColors.Onion)
                        Spacer(Modifier.height(12.dp))
                        Text("No keys yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Generate an Ed25519 key, then use “Install public key…” on a host to push it over Tor.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(keys, key = { it.id }) { k ->
                val usedBy = hosts.count { it.keyId == k.id }
                KeyCard(
                    key = k,
                    usedBy = usedBy,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("public key", k.publicKey))
                        Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey)
                        context.startActivity(Intent.createChooser(send, "Share public key"))
                    },
                    onDelete = { confirmDelete = k },
                )
            }
        }
    }

    when (val d = dialog) {
        is KeyDialog.Generate -> GenerateDialog(graph, d.kind, onDone = { dialog = null })
        is KeyDialog.Import -> ImportDialog(graph, d.text, onDone = { dialog = null })
        null -> Unit
    }
    confirmDelete?.let { k ->
        AlertDialog(
            modifier = AutomationTags,
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${k.label}?") },
            text = { Text("Hosts using it will need another key. This can't be undone.") },
            confirmButton = { TextButton(onClick = { graph.keys.delete(k.id); confirmDelete = null }) { Text("Delete", color = ToryColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyCard(key: SshKey, usedBy: Int, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(key.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Badge(key.type, ToryColors.Onion)
            }
            Spacer(Modifier.height(6.dp))
            Text(key.fingerprint, style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                key.publicKey, style = MonoStyle.copy(fontSize = 11.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (usedBy == 0) "Not used by any host" else "Used by $usedBy host${if (usedBy == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "Copy public key") }
                IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share public key") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete", tint = ToryColors.Danger) }
            }
        }
    }
}

@Composable
private fun GenerateDialog(graph: AppGraph, kind: KeyKind, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("tory-${kind.name.lowercase()}") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = AutomationTags,
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Generate ${kind.label}") },
        text = {
            Column {
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("key_label"))
                if (busy) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating…")
                }
                error?.let { Text(it, color = ToryColors.Danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && label.isNotBlank(),
                modifier = Modifier.testTag("key_generate"),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val key = withContext(Dispatchers.Default) { KeyTools.generate(kind, label.trim()) }
                            graph.keys.add(key)
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                            busy = false
                        }
                    }
                },
            ) { Text("Generate") }
        },
        dismissButton = { if (!busy) TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(graph: AppGraph, initial: String, onDone: () -> Unit) {
    var label by remember { mutableStateOf("imported") }
    var text by remember { mutableStateOf(initial) }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = AutomationTags,
        onDismissRequest = onDone,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    text, { text = it }, label = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    textStyle = MonoStyle.copy(fontSize = 10.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("Passphrase (if encrypted)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let { Text(it, color = ToryColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    graph.keys.add(KeyTools.import((text.trim() + "\n").encodeToByteArray(), passphrase, label.trim().ifBlank { "imported" }))
                    onDone()
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
