package com.joshreimer.toryaccess.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.joshreimer.toryaccess.data.AuthMethod
import com.joshreimer.toryaccess.data.Host
import com.joshreimer.toryaccess.data.HostRepository
import com.joshreimer.toryaccess.data.KeyRepository
import com.joshreimer.toryaccess.data.KnownHostsRepository
import com.joshreimer.toryaccess.data.Route
import com.joshreimer.toryaccess.data.SecretBox
import com.joshreimer.toryaccess.data.SettingsRepository
import com.joshreimer.toryaccess.data.TorMode
import com.joshreimer.toryaccess.tor.TorDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthCancelledException : IOException("Cancelled")

/** Builds an authenticated JSch [Session] for a [Host], routed through Tor unless told otherwise. */
class SshConnector(
    private val tor: TorDaemon,
    private val settings: SettingsRepository,
    private val hosts: HostRepository,
    private val keys: KeyRepository,
    private val knownHosts: KnownHostsRepository,
    private val gate: PromptGate,
    private val ensureTorStarted: () -> Unit,
) {
    /**
     * [onCreated] receives the session *before* the (blocking, uncancellable) handshake, so a
     * caller that gets cancelled can still `disconnect()` it — which also aborts the handshake.
     */
    suspend fun connect(
        host: Host,
        onStage: (String) -> Unit,
        onCreated: (Session) -> Unit = {},
    ): Session = withContext(Dispatchers.IO) {
        val proxy = resolveProxy(host, onStage)

        val jsch = JSch()
        jsch.hostKeyRepository = TrustingHostKeyRepository(knownHosts, gate, host.label)

        var password: String? = null
        when (host.auth) {
            AuthMethod.KEY -> addIdentity(jsch, host)
            AuthMethod.PASSWORD -> password = host.password?.let(SecretBox::openString) ?: askPassword(host)
        }

        val session = jsch.getSession(host.username, host.hostname.trim(), host.port)
        proxy?.let(session::setProxy)
        password?.let(session::setPassword)
        session.userInfo = GateUserInfo(gate, host, password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            if (host.auth == AuthMethod.KEY) "publickey,keyboard-interactive" else "password,keyboard-interactive",
        )
        // Tor links are slow and high-latency; compression is a clear win for interactive use.
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "6")
        session.serverAliveInterval = 30_000
        session.serverAliveCountMax = 4
        onCreated(session)

        onStage(
            when {
                proxy == null -> "Connecting directly to ${host.hostname}…"
                host.isOnion -> "Building rendezvous circuit to the onion service…"
                else -> "Opening Tor circuit to ${host.hostname}…"
            }
        )
        session.connect(if (proxy == null) 30_000 else 120_000)
        hosts.markConnected(host.id)
        session
    }

    private suspend fun resolveProxy(host: Host, onStage: (String) -> Unit): Proxy? {
        if (host.effectiveRoute == Route.DIRECT) return null
        val s = settings.value
        return when (s.torMode) {
            TorMode.EXTERNAL -> {
                onStage("Using external SOCKS proxy ${s.externalSocksHost}:${s.externalSocksPort}")
                ProxySOCKS5(s.externalSocksHost, s.externalSocksPort)
            }
            TorMode.EMBEDDED -> {
                withContext(Dispatchers.Main) { ensureTorStarted() }
                val port = tor.awaitReady { st -> onStage("Waiting for Tor — ${st.progress}% · ${st.summary}") }
                ProxySOCKS5("127.0.0.1", port)
            }
        }
    }

    private fun addIdentity(jsch: JSch, host: Host) {
        val key = host.keyId?.let(keys::get)
            ?: throw IOException("No SSH key is selected for ${host.label} — edit the host")
        val prv = SecretBox.open(key.privateKey)
        var passphrase = key.passphrase?.let(SecretBox::open)
        if (passphrase == null && KeyPair.load(jsch, prv, null).isEncrypted) {
            val reply = gate.secretBlocking(Prompt.Secret("Key passphrase", "Unlock “${key.label}”"))
                ?: throw AuthCancelledException()
            passphrase = reply.text.encodeToByteArray()
        }
        jsch.addIdentity(key.label, prv, null, passphrase)
    }

    private fun askPassword(host: Host): String {
        val reply = gate.secretBlocking(
            Prompt.Secret("Password", "${host.display}\nvia ${if (host.effectiveRoute == Route.TOR) "Tor" else "direct connection"}", offerSave = true)
        ) ?: throw AuthCancelledException()
        if (reply.save && hosts.get(host.id) != null) {
            hosts.upsert(host.copy(password = SecretBox.seal(reply.text)))
        }
        return reply.text
    }
}

/** Answers JSch's auth callbacks: retries & keyboard-interactive (2FA) go to the UI. */
private class GateUserInfo(
    private val gate: PromptGate,
    private val host: Host,
    private var password: String?,
) : UserInfo, UIKeyboardInteractive {
    private var usedPasswordForKbdInt = false

    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = password

    override fun promptPassword(message: String?): Boolean {
        val reply = gate.secretBlocking(Prompt.Secret("Password", message ?: host.display)) ?: return false
        password = reply.text
        return true
    }

    override fun promptPassphrase(message: String?): Boolean = false

    // Host keys are handled by TrustingHostKeyRepository, never by a yes/no here.
    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) {}

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<String>,
        echo: BooleanArray,
    ): Array<String>? {
        val answers = arrayOfNulls<String>(prompt.size)
        for (i in prompt.indices) {
            val pw = password
            answers[i] = if (!echo[i] && pw != null && !usedPasswordForKbdInt && prompt[i].contains("password", true)) {
                usedPasswordForKbdInt = true
                pw
            } else {
                val msg = listOfNotNull(instruction?.takeIf { it.isNotBlank() }, prompt[i]).joinToString("\n")
                gate.secretBlocking(Prompt.Secret(name?.takeIf { it.isNotBlank() } ?: host.label, msg, echo = echo[i]))
                    ?.text ?: return null
            }
        }
        @Suppress("UNCHECKED_CAST")
        return answers as Array<String>
    }
}
