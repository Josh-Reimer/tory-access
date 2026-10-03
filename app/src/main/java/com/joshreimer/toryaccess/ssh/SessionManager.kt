package com.joshreimer.toryaccess.ssh

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.joshreimer.toryaccess.data.AuthMethod
import com.joshreimer.toryaccess.data.Host
import com.joshreimer.toryaccess.data.SshKey
import com.joshreimer.toryaccess.service.HubService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SessionManager(
    private val appContext: Context,
    private val connector: SshConnector,
    private val scope: CoroutineScope,
) {
    private val _sessions = MutableStateFlow<List<SshTerminalSession>>(emptyList())
    val sessions: StateFlow<List<SshTerminalSession>> = _sessions.asStateFlow()

    /** The tab the terminal screen shows; also the target of debug automation. */
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun get(id: String): SshTerminalSession? = _sessions.value.firstOrNull { it.id == id }
    val active: SshTerminalSession? get() = _activeId.value?.let(::get)

    fun open(host: Host): SshTerminalSession {
        HubService.ensureRunning(appContext)
        val s = SshTerminalSession(appContext, host, connector, scope)
        _sessions.value = _sessions.value + s
        _activeId.value = s.id
        s.connect()
        return s
    }

    fun setActive(id: String) {
        if (get(id) != null) _activeId.value = id
    }

    fun close(id: String) {
        val s = get(id) ?: return
        s.dispose()
        val remaining = _sessions.value - s
        _sessions.value = remaining
        if (_activeId.value == id) _activeId.value = remaining.lastOrNull()?.id
    }

    fun closeAll() = _sessions.value.map { it.id }.forEach(::close)

    /**
     * ssh-copy-id over Tor: connects with the host's current (password) auth, appends the
     * public key to ~/.ssh/authorized_keys if absent, and returns the remote output.
     */
    suspend fun deployKey(host: Host, key: SshKey, onStage: (String) -> Unit): String {
        val session = connector.connect(host.copy(auth = AuthMethod.PASSWORD), onStage)
        return withContext(Dispatchers.IO) {
            try {
                onStage("Installing key…")
                val pub = key.publicKey.replace("'", "")
                val cmd = "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
                    "(grep -qxF '$pub' ~/.ssh/authorized_keys || echo '$pub' >> ~/.ssh/authorized_keys) && echo installed"
                val ch = session.openChannel("exec") as ChannelExec
                ch.setCommand(cmd)
                val out = ByteArrayOutputStream()
                ch.outputStream = null
                ch.setErrStream(out, true)
                val input = ch.inputStream
                ch.connect(30_000)
                input.copyTo(out)
                while (!ch.isClosed) delay(50)
                val exit = ch.exitStatus
                ch.disconnect()
                val text = out.toString().trim()
                if (exit != 0) throw java.io.IOException("Remote command failed ($exit): $text")
                text
            } finally {
                session.disconnect()
            }
        }
    }
}
