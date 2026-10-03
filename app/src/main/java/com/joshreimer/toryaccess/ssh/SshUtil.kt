package com.joshreimer.toryaccess.ssh

import java.security.MessageDigest
import java.util.Base64

/** OpenSSH-style `SHA256:<unpadded base64>` fingerprint of a public key blob. */
fun sha256Fingerprint(blob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

/** Result of parsing a quick-connect string like `alice@example.onion:2222`. */
data class HostSpec(val username: String, val hostname: String, val port: Int)

fun parseHostSpec(input: String, defaultUser: String = "root"): HostSpec? {
    var s = input.trim().removePrefix("ssh://")
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    val at = s.lastIndexOf('@')
    val user = if (at > 0) s.substring(0, at) else defaultUser
    if (at >= 0) s = s.substring(at + 1)
    val host: String
    var port = 22
    if (s.startsWith("[")) {
        // [ipv6]:port
        val close = s.indexOf(']')
        if (close < 0) return null
        host = s.substring(1, close)
        val rest = s.substring(close + 1)
        if (rest.isNotEmpty()) port = rest.removePrefix(":").toIntOrNull() ?: return null
    } else if (s.count { it == ':' } == 1) {
        host = s.substringBefore(':')
        port = s.substringAfter(':').toIntOrNull() ?: return null
    } else {
        host = s
    }
    if (host.isEmpty() || port !in 1..65535 || user.isEmpty()) return null
    return HostSpec(user, host, port)
}

/** Tor's SOCKS5 ExtendedErrors reply codes (tor-spec / socks-extensions §3). */
private val SOCKS_REPLIES = mapOf(
    1 to "Tor couldn't reach the destination (general failure)",
    2 to "Connection not allowed by Tor's exit policy",
    3 to "Network unreachable",
    4 to "Host unreachable — check the address, or the exit couldn't connect",
    5 to "Connection refused by the server — is sshd listening on that port?",
    6 to "Tor circuit timed out (TTL expired)",
    0xF0 to "Onion service descriptor not found — the .onion is offline or mistyped",
    0xF1 to "Onion service descriptor is invalid",
    0xF2 to "Couldn't reach the onion service's introduction points",
    0xF3 to "Onion service rendezvous failed",
    0xF4 to "Onion service requires client authorization",
    0xF5 to "Onion service client authorization was rejected",
    0xF6 to "That isn't a valid .onion address",
    0xF7 to "Onion service introduction timed out",
)

private val SOCKS_RETURN = Regex("""server returns (-?\d+)""")

/** Turns JSch's terse exception text into something a person can act on. */
fun friendlySshError(t: Throwable): String {
    val msg = generateSequence(t) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
    SOCKS_RETURN.find(msg)?.let { m ->
        val code = m.groupValues[1].toInt() and 0xFF
        return SOCKS_REPLIES[code] ?: "SOCKS proxy refused the connection (code $code)"
    }
    return when {
        msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
            "Authentication failed — check the username, key or password"
        msg.contains("UnknownHostKey", true) -> "Host key not trusted — connection aborted"
        msg.contains("HostKey has been changed", true) || t.javaClass.simpleName == "JSchChangedHostKeyException" ->
            "Host key CHANGED and was not accepted — connection aborted"
        msg.contains("Connection refused", true) && msg.contains("ProxySOCKS5", true) ->
            "Couldn't reach the SOCKS proxy — is Tor/Orbot running?"
        msg.contains("timeout", true) || msg.contains("timed out", true) ->
            "Timed out. Onion services can take 30–60s on a cold circuit; try again."
        msg.contains("invalid privatekey", true) -> "The private key couldn't be read (wrong passphrase?)"
        msg.isBlank() -> t.javaClass.simpleName
        else -> msg
    }
}
