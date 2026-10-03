package com.joshreimer.toryaccess.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** How an SSH connection leaves the device. `.onion` hosts are always forced through Tor. */
@Serializable
enum class Route { TOR, DIRECT }

@Serializable
enum class AuthMethod { KEY, PASSWORD }

/** Ciphertext produced by [SecretBox]; base64 IV + AES-GCM payload. */
@Serializable
data class Sealed(val iv: String, val data: String)

@Serializable
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: AuthMethod = AuthMethod.KEY,
    val keyId: String? = null,
    val password: Sealed? = null,
    val route: Route = Route.TOR,
    val group: String = "",
    val startupCommand: String = "",
    val lastConnectedAt: Long = 0,
) {
    val isOnion: Boolean get() = hostname.trim().lowercase().endsWith(".onion")
    val effectiveRoute: Route get() = if (isOnion) Route.TOR else route
    val display: String get() = buildString {
        append(username).append('@').append(hostname)
        if (port != 22) append(':').append(port)
    }
}

@Serializable
data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val type: String,
    val publicKey: String,
    val fingerprint: String,
    val privateKey: Sealed,
    val passphrase: Sealed? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One trusted server key. [host] is JSch's host form: `name` or `[name]:port`. */
@Serializable
data class KnownHost(
    val host: String,
    val type: String,
    val key: String,
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
enum class TorMode { EMBEDDED, EXTERNAL }

@Serializable
data class Settings(
    val torMode: TorMode = TorMode.EMBEDDED,
    val socksPort: Int = 9160,
    val externalSocksHost: String = "127.0.0.1",
    val externalSocksPort: Int = 9050,
    val autoStartTor: Boolean = true,
    /** Raw bridge lines, one per line, with or without a leading `Bridge `. */
    val bridges: String = "",
    val fontSizeSp: Float = 13f,
    val keepScreenOn: Boolean = true,
)
