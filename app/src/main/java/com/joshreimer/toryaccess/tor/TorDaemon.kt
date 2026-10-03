package com.joshreimer.toryaccess.tor

import android.content.Context
import android.util.Log
import com.joshreimer.toryaccess.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

enum class TorPhase { STOPPED, STARTING, BOOTSTRAPPING, READY, ERROR }

data class TorState(
    val phase: TorPhase = TorPhase.STOPPED,
    val progress: Int = 0,
    val summary: String = "Stopped",
    val socksPort: Int = 0,
    val log: List<String> = emptyList(),
    /** Bridge lines we had to skip because their transport isn't bundled. */
    val skippedBridges: List<String> = emptyList(),
)

class TorUnavailableException(message: String) : IOException(message)

private val BOOTSTRAP = Regex("""Bootstrapped (\d+)% \(([^)]*)\): (.*)""")
private const val LOG_LINES = 300

/**
 * Runs tor-android's `libtor.so` as a child process (it's a real PIE executable shipped
 * as a "native lib" so it lands in nativeLibraryDir, the one place W^X lets us exec from).
 */
class TorDaemon(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(TorState())
    val state: StateFlow<TorState> = _state.asStateFlow()

    private val dataDir = File(context.filesDir, "tor")
    private val torrc = File(dataDir, "torrc")
    private val cookieFile = File(dataDir, "control_auth_cookie")
    private val controlPortFile = File(dataDir, "control_port")
    private val pidFile = File(dataDir, "tor.pid")

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start(settings: Settings) {
        if (isRunning) return
        stopping = false
        dataDir.mkdirs()
        killStale()
        controlPortFile.delete()

        val bridges = Bridges.parse(settings.bridges)
        val socks = if (portFree(settings.socksPort)) settings.socksPort.toString() else "auto"
        torrc.writeText(buildTorrc(socks, bridges.usable))

        _state.value = TorState(
            phase = TorPhase.STARTING,
            summary = "Starting tor",
            skippedBridges = bridges.unsupported,
        )

        val binary = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
        val pb = ProcessBuilder(binary.absolutePath, "-f", torrc.absolutePath)
            .redirectErrorStream(true)
            .directory(dataDir)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        val proc = try {
            pb.start()
        } catch (e: IOException) {
            fail("Couldn't launch tor: ${e.message}")
            return
        }
        process = proc
        Thread({ pump(proc) }, "tor-stdout").apply { isDaemon = true }.start()
    }

    private fun buildTorrc(socks: String, bridges: List<String>) = buildString {
        appendLine("SocksPort 127.0.0.1:$socks ExtendedErrors IsolateDestAddr")
        appendLine("ControlPort 127.0.0.1:auto")
        appendLine("ControlPortWriteToFile ${controlPortFile.absolutePath}")
        appendLine("CookieAuthentication 1")
        appendLine("CookieAuthFile ${cookieFile.absolutePath}")
        appendLine("DataDirectory ${dataDir.absolutePath}")
        appendLine("PidFile ${pidFile.absolutePath}")
        // Tor exits by itself if our app process dies, so a stale daemon never squats the ports.
        appendLine("__OwningControllerProcess ${android.os.Process.myPid()}")
        appendLine("AvoidDiskWrites 1")
        appendLine("ClientOnly 1")
        appendLine("SocksPolicy accept 127.0.0.1")
        appendLine("SocksPolicy reject *")
        appendLine("Log notice stdout")
        if (bridges.isNotEmpty()) {
            appendLine("UseBridges 1")
            bridges.forEach { appendLine("Bridge $it") }
        }
    }

    private fun pump(proc: Process) {
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> onLine(line) }
            }
        } catch (_: IOException) {
        }
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        if (process === proc) process = null
        if (stopping) {
            _state.update { it.copy(phase = TorPhase.STOPPED, progress = 0, summary = "Stopped", socksPort = 0) }
        } else {
            fail("tor exited (code $code)")
        }
    }

    private fun onLine(line: String) {
        Log.d(TAG, line)
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_LINES)) }
        val m = BOOTSTRAP.find(line) ?: return
        val pct = m.groupValues[1].toInt()
        val summary = m.groupValues[3]
        if (pct >= 100) {
            scope.launch(Dispatchers.IO) { onBootstrapped() }
        } else {
            _state.update { it.copy(phase = TorPhase.BOOTSTRAPPING, progress = pct, summary = summary) }
        }
    }

    private fun onBootstrapped() {
        // With `SocksPort auto` we only learn the real port from tor itself.
        val port = runCatching {
            withControl { c ->
                // e.g. "127.0.0.1:9160" (quoted; space-separated if several listeners)
                c.getInfo("net/listeners/socks")
                    ?.let { Regex(""":(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            }
        }.getOrNull()
        if (port == null || port <= 0) {
            fail("Bootstrapped, but couldn't read tor's SOCKS port")
            return
        }
        _state.update { it.copy(phase = TorPhase.READY, progress = 100, summary = "Connected to Tor", socksPort = port) }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(phase = TorPhase.ERROR, summary = msg, socksPort = 0, log = (it.log + "!! $msg").takeLast(LOG_LINES)) }
    }

    @Synchronized
    fun stop() {
        stopping = true
        val p = process ?: run {
            _state.update { it.copy(phase = TorPhase.STOPPED, progress = 0, summary = "Stopped", socksPort = 0) }
            return
        }
        runCatching { withControl { it.command("SIGNAL SHUTDOWN") } }
        p.destroy()
    }

    /** Suspends until tor is READY, returning its SOCKS port. Reports progress via [onProgress]. */
    suspend fun awaitReady(timeoutMs: Long = 180_000, onProgress: (TorState) -> Unit = {}): Int =
        withTimeout(timeoutMs) {
            val s = state.first { s ->
                onProgress(s)
                s.phase == TorPhase.READY || s.phase == TorPhase.ERROR || s.phase == TorPhase.STOPPED
            }
            when (s.phase) {
                TorPhase.READY -> s.socksPort
                TorPhase.STOPPED -> throw TorUnavailableException("Tor is stopped")
                else -> throw TorUnavailableException(s.summary)
            }
        }

    suspend fun newIdentity(): Boolean = withContext(Dispatchers.IO) {
        runCatching { withControl { it.command("SIGNAL NEWNYM").ok } }.getOrDefault(false)
    }

    suspend fun circuits(): List<Circuit> = withContext(Dispatchers.IO) {
        runCatching {
            withControl { c -> c.getInfo("circuit-status")?.let(::parseCircuitStatus) }
        }.getOrNull().orEmpty()
    }

    private fun <T> withControl(block: (TorControlClient) -> T): T {
        val port = controlPortFile.takeIf { it.exists() }?.readText()
            ?.trim()?.substringAfterLast(':')?.toIntOrNull()
            ?: throw IOException("tor control port not published yet")
        return TorControlClient.connect(port, cookieFile).use(block)
    }

    /** A daemon from a previous app process (e.g. after a crash) holds our ports; reap it. */
    private fun killStale() {
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return
        runCatching { android.os.Process.killProcess(pid) }
        pidFile.delete()
    }

    private fun portFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
        true
    } catch (_: IOException) {
        false
    }

    companion object {
        private const val TAG = "ToryTor"
    }
}
