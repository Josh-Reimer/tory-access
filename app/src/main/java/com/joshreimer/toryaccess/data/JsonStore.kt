package com.joshreimer.toryaccess.data

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * A tiny file-backed state holder. Files are small (hosts, keys, settings), so we load
 * synchronously once and write the whole document atomically on every change.
 * Plain JSON (no Room/KSP) keeps the build simple enough to run inside Termux.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: () -> T,
) {
    private val atomic = AtomicFile(file)
    private val _state = MutableStateFlow(load() ?: default())
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    private fun load(): T? = try {
        if (!file.exists()) null
        else StoreJson.decodeFromString(serializer, atomic.readFully().decodeToString())
    } catch (e: Exception) {
        Log.e("JsonStore", "Couldn't read ${file.name}; starting fresh", e)
        null
    }

    @Synchronized
    fun update(transform: (T) -> T): T {
        val next = transform(_state.value)
        val out = atomic.startWrite()
        try {
            out.write(StoreJson.encodeToString(serializer, next).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
        _state.value = next
        return next
    }
}
