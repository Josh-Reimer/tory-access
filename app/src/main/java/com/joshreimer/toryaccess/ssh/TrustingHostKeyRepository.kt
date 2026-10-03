package com.joshreimer.toryaccess.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import com.joshreimer.toryaccess.data.KnownHost
import com.joshreimer.toryaccess.data.KnownHostsRepository
import java.util.Base64

/**
 * Trust-on-first-use known_hosts, but with the user in the loop: unknown keys and changed
 * keys both stop the handshake and ask (showing the SHA256 fingerprint). Used with
 * `StrictHostKeyChecking=yes`, so anything but OK aborts the connection inside JSch.
 */
class TrustingHostKeyRepository(
    private val store: KnownHostsRepository,
    private val gate: PromptGate,
    private val hostLabel: String,
) : HostKeyRepository {

    override fun check(host: String, key: ByteArray): Int {
        val hk = try {
            HostKey(host, key)
        } catch (_: Exception) {
            return HostKeyRepository.NOT_INCLUDED
        }
        val encoded = Base64.getEncoder().encodeToString(key)
        val fingerprint = sha256Fingerprint(key)
        val known = store.find(host, hk.type)
        if (known != null && known.key == encoded) return HostKeyRepository.OK

        val accepted = gate.confirmBlocking(
            Prompt.HostKey(
                hostLabel = hostLabel,
                host = host,
                keyType = hk.type,
                fingerprint = fingerprint,
                previousFingerprint = known?.fingerprint,
            )
        )
        if (!accepted) {
            return if (known != null) HostKeyRepository.CHANGED else HostKeyRepository.NOT_INCLUDED
        }
        store.put(KnownHost(host = host, type = hk.type, key = encoded, fingerprint = fingerprint))
        return HostKeyRepository.OK
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        val blob = Base64.getDecoder().decode(hostkey.key)
        store.put(KnownHost(hostkey.host, hostkey.type, hostkey.key, sha256Fingerprint(blob)))
    }

    override fun remove(host: String, type: String?) = store.remove(host, type)

    override fun remove(host: String, type: String?, key: ByteArray?) = store.remove(host, type)

    override fun getKnownHostsRepositoryID(): String = "tory-access"

    override fun getHostKey(): Array<HostKey> =
        store.entries.value.mapNotNull { it.toHostKey() }.toTypedArray()

    /** JSch uses this to prefer the algorithm we already trust for this host. */
    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        store.entries.value
            .filter { (host == null || it.host == host) && (type == null || it.type == type) }
            .mapNotNull { it.toHostKey() }
            .toTypedArray()

    private fun KnownHost.toHostKey(): HostKey? =
        runCatching { HostKey(host, Base64.getDecoder().decode(key)) }.getOrNull()
}
