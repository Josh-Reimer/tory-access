package com.joshreimer.toryaccess.tor

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** One complete control-port reply: final status code plus every key/data line. */
data class ControlReply(val status: Int, val lines: List<String>) {
    val ok: Boolean get() = status == 250
    val message: String get() = lines.lastOrNull().orEmpty()

    /** For `GETINFO key` replies: the value of `key=...` (single- or multi-line). */
    fun value(key: String): String? {
        val idx = lines.indexOfFirst { it.startsWith("$key=") }
        return if (idx < 0) null else lines[idx].removePrefix("$key=")
    }
}

/**
 * Reads exactly one reply off the control socket, following control-spec §2.3:
 *   `250-...` mid line, `250+key=` data block terminated by a lone `.`, `250 ...` end line.
 * Always consuming through the end line is what keeps the socket in sync — the classic bug
 * is reading only the `250-key=value` line and leaving `250 OK` for the next command.
 */
fun readControlReply(readLine: () -> String?): ControlReply {
    val lines = mutableListOf<String>()
    while (true) {
        val line = readLine() ?: throw java.io.EOFException("control port closed")
        if (line.length < 4) throw java.io.IOException("malformed control reply: '$line'")
        val code = line.substring(0, 3).toIntOrNull()
            ?: throw java.io.IOException("malformed control reply: '$line'")
        val sep = line[3]
        val rest = line.substring(4)
        when (sep) {
            ' ' -> {
                lines += rest
                return ControlReply(code, lines)
            }
            '-' -> lines += rest
            '+' -> {
                val data = StringBuilder(rest)
                while (true) {
                    val d = readLine() ?: throw java.io.EOFException("control port closed in data block")
                    if (d == ".") break
                    // Leading-dot escaping per spec.
                    data.append('\n').append(if (d.startsWith("..")) d.substring(1) else d)
                }
                lines += data.toString()
            }
            else -> throw java.io.IOException("malformed control reply: '$line'")
        }
    }
}

data class CircuitHop(val fingerprint: String, val nickname: String)

data class Circuit(
    val id: String,
    val status: String,
    val hops: List<CircuitHop>,
    val purpose: String,
    val flags: Map<String, String>,
)

/** Parses the body of `GETINFO circuit-status`. */
fun parseCircuitStatus(body: String): List<Circuit> = body.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("circuit-status=") }
    .mapNotNull { line ->
        val parts = line.split(' ')
        if (parts.size < 2) return@mapNotNull null
        val id = parts[0]
        val status = parts[1]
        var path = emptyList<CircuitHop>()
        val kv = mutableMapOf<String, String>()
        for (p in parts.drop(2)) {
            // Path first: hops may be written `$FP=nick`, which would otherwise look like key=value.
            if (p.startsWith("$")) {
                path = p.split(',').map { hop ->
                    val clean = hop.removePrefix("$")
                    val sep = clean.indexOfAny(charArrayOf('~', '='))
                    if (sep < 0) CircuitHop(clean, "") else CircuitHop(clean.substring(0, sep), clean.substring(sep + 1))
                }
            } else {
                val eq = p.indexOf('=')
                if (eq > 0) kv[p.substring(0, eq)] = p.substring(eq + 1)
            }
        }
        Circuit(id, status, path, kv["PURPOSE"] ?: "", kv)
    }
    .toList()

/** Minimal authenticated control-port client (cookie auth). Not thread-safe; use per call. */
class TorControlClient private constructor(private val socket: Socket) : Closeable {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
    private val out: OutputStream = socket.getOutputStream()

    fun command(cmd: String): ControlReply {
        out.write("$cmd\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
        return readControlReply { reader.readLine() }
    }

    fun getInfo(key: String): String? {
        val r = command("GETINFO $key")
        return if (r.ok) r.value(key) else null
    }

    override fun close() {
        runCatching { command("QUIT") }
        runCatching { socket.close() }
    }

    companion object {
        fun connect(port: Int, cookieFile: File): TorControlClient {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 15_000
            val client = TorControlClient(socket)
            val cookieHex = cookieFile.readBytes().joinToString("") { "%02X".format(it) }
            val auth = client.command("AUTHENTICATE $cookieHex")
            if (!auth.ok) {
                client.close()
                throw java.io.IOException("Tor control auth failed: ${auth.status} ${auth.message}")
            }
            return client
        }
    }
}
