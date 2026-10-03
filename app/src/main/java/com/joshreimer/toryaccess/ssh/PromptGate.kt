package com.joshreimer.toryaccess.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Prompt {
    /** First contact with a server key (TOFU), or a key that changed since we trusted it. */
    data class HostKey(
        val hostLabel: String,
        val host: String,
        val keyType: String,
        val fingerprint: String,
        val previousFingerprint: String?,
    ) : Prompt {
        val changed: Boolean get() = previousFingerprint != null
    }

    /** Password, key passphrase, or a keyboard-interactive question (e.g. a 2FA code). */
    data class Secret(
        val title: String,
        val message: String,
        val echo: Boolean = false,
        val offerSave: Boolean = false,
    ) : Prompt
}

data class SecretReply(val text: String, val save: Boolean)

class PendingPrompt(val prompt: Prompt, internal val reply: CompletableDeferred<Any?>)

/**
 * Bridges JSch's blocking callbacks (running on an IO thread) to a Compose dialog.
 * Prompts are serialized so two sessions connecting at once never stack dialogs.
 */
class PromptGate {
    private val mutex = Mutex()
    private val _current = MutableStateFlow<PendingPrompt?>(null)
    val current: StateFlow<PendingPrompt?> = _current.asStateFlow()

    suspend fun confirm(prompt: Prompt.HostKey): Boolean = ask(prompt) as? Boolean ?: false

    suspend fun secret(prompt: Prompt.Secret): SecretReply? = ask(prompt) as? SecretReply

    fun confirmBlocking(prompt: Prompt.HostKey): Boolean = runBlocking { confirm(prompt) }
    fun secretBlocking(prompt: Prompt.Secret): SecretReply? = runBlocking { secret(prompt) }

    private suspend fun ask(prompt: Prompt): Any? = mutex.withLock {
        val pending = PendingPrompt(prompt, CompletableDeferred())
        _current.value = pending
        try {
            withTimeoutOrNull(5 * 60_000L) { pending.reply.await() }
        } finally {
            _current.value = null
        }
    }

    fun respond(pending: PendingPrompt, value: Any?) {
        pending.reply.complete(value)
    }
}
