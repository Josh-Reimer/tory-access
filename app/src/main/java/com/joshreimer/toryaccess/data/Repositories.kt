package com.joshreimer.toryaccess.data

import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class HostRepository(dir: File) {
    private val store = JsonStore(File(dir, "hosts.json"), ListSerializer(Host.serializer())) { emptyList() }
    val hosts = store.state

    fun get(id: String): Host? = store.value.firstOrNull { it.id == id }
    fun findByLabel(label: String): Host? =
        store.value.firstOrNull { it.label.equals(label, ignoreCase = true) }

    fun upsert(host: Host) {
        store.update { list ->
            val i = list.indexOfFirst { it.id == host.id }
            if (i < 0) list + host else list.toMutableList().also { it[i] = host }
        }
    }

    fun delete(id: String) = store.update { it.filterNot { h -> h.id == id } }

    fun markConnected(id: String) {
        get(id)?.let { upsert(it.copy(lastConnectedAt = System.currentTimeMillis())) }
    }
}

class KeyRepository(dir: File) {
    private val store = JsonStore(File(dir, "keys.json"), ListSerializer(SshKey.serializer())) { emptyList() }
    val keys = store.state

    fun get(id: String): SshKey? = store.value.firstOrNull { it.id == id }
    fun add(key: SshKey) = store.update { it + key }
    fun delete(id: String) = store.update { it.filterNot { k -> k.id == id } }
}

class KnownHostsRepository(dir: File) {
    private val store = JsonStore(File(dir, "known_hosts.json"), ListSerializer(KnownHost.serializer())) { emptyList() }
    val entries = store.state

    fun find(host: String, type: String): KnownHost? =
        store.value.firstOrNull { it.host == host && it.type == type }

    fun forHost(host: String): List<KnownHost> = store.value.filter { it.host == host }

    /** Adds or replaces the key of this host+type. */
    fun put(entry: KnownHost) = store.update { list ->
        list.filterNot { it.host == entry.host && it.type == entry.type } + entry
    }

    fun remove(host: String, type: String?) {
        store.update { list -> list.filterNot { it.host == host && (type == null || it.type == type) } }
    }
}

class SettingsRepository(dir: File) {
    private val store = JsonStore(File(dir, "settings.json"), Settings.serializer()) { Settings() }
    val settings = store.state
    val value: Settings get() = store.value
    fun update(transform: (Settings) -> Settings) = store.update(transform)
}
