package com.joshreimer.toryaccess.tor

/**
 * Splits user-entered bridge lines into ones the bundled tor can use directly (vanilla
 * `IP:port [fingerprint]` bridges) and ones needing a pluggable transport we don't ship.
 * Unsupported lines are surfaced in the UI rather than silently dropped — a bridge the
 * user is relying on vanishing without a word is a dead end they can't diagnose.
 */
object Bridges {
    data class Parsed(val usable: List<String>, val unsupported: List<String>)

    private val ADDRESS = Regex("""^(\[[0-9a-fA-F:]+]|\d{1,3}(\.\d{1,3}){3}):\d{1,5}(\s+[0-9A-Fa-f]{40})?$""")

    fun parse(raw: String): Parsed {
        val usable = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        raw.lineSequence()
            .map { it.trim().removePrefix("Bridge ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line -> if (ADDRESS.matches(line)) usable += line else unsupported += line }
        return Parsed(usable, unsupported)
    }
}
