package com.joshreimer.toryaccess.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.joshreimer.toryaccess.ssh.PendingPrompt
import com.joshreimer.toryaccess.ssh.Prompt
import com.joshreimer.toryaccess.ssh.PromptGate
import com.joshreimer.toryaccess.ssh.SecretReply
import com.joshreimer.toryaccess.ui.theme.MonoStyle
import com.joshreimer.toryaccess.ui.theme.ToryColors

@Composable
fun PromptHost(gate: PromptGate, pending: PendingPrompt?) {
    pending ?: return
    // key() so a second prompt never inherits the first one's typed text.
    key(pending) {
        when (val p = pending.prompt) {
            is Prompt.HostKey -> HostKeyDialog(p, onResult = { gate.respond(pending, it) })
            is Prompt.Secret -> SecretDialog(p, onResult = { gate.respond(pending, it) })
        }
    }
}

@Composable
private fun HostKeyDialog(p: Prompt.HostKey, onResult: (Boolean) -> Unit) {
    AlertDialog(
        modifier = AutomationTags,
        onDismissRequest = { onResult(false) },
        icon = {
            Icon(
                if (p.changed) Icons.Outlined.Warning else Icons.Outlined.GppMaybe, null,
                tint = if (p.changed) ToryColors.Danger else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(if (p.changed) "Host key CHANGED" else "Trust this server?") },
        text = {
            Column {
                if (p.changed) {
                    Text(
                        "The key for ${p.host} is different from the one you trusted before. " +
                            "This could mean the server was reinstalled — or that someone is intercepting the connection.",
                        color = ToryColors.Danger,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Previously trusted", style = MaterialTheme.typography.labelMedium)
                    Text(p.previousFingerprint.orEmpty(), style = MonoStyle)
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text("First connection to ${p.hostLabel} (${p.host}). Compare the fingerprint with the server's:")
                    Spacer(Modifier.height(4.dp))
                    Text("ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                }
                Text(p.keyType, style = MaterialTheme.typography.labelMedium)
                Text(p.fingerprint, style = MonoStyle, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("hostkey_fingerprint"))
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(true) }, modifier = Modifier.testTag("hostkey_accept")) {
                Text(if (p.changed) "Replace key" else "Trust & connect", color = if (p.changed) ToryColors.Danger else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }, modifier = Modifier.testTag("hostkey_reject")) { Text("Abort") }
        },
    )
}

@Composable
private fun SecretDialog(p: Prompt.Secret, onResult: (SecretReply?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        modifier = AutomationTags,
        onDismissRequest = { onResult(null) },
        icon = { Icon(Icons.Outlined.Key, null) },
        title = { Text(p.title) },
        text = {
            Column {
                Text(p.message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (p.echo) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (p.echo) KeyboardType.Text else KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onResult(SecretReply(text, save)) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("secret_input"),
                )
                if (p.offerSave) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = save, onCheckedChange = { save = it })
                        Text("Save (encrypted with Android Keystore)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(SecretReply(text, save)) }, modifier = Modifier.testTag("secret_ok")) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}
