package com.joshreimer.toryaccess.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.joshreimer.toryaccess.data.Host
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

sealed interface SessionStatus {
    data class Connecting(val stage: String) : SessionStatus
    data object Connected : SessionStatus
    data class Closed(val reason: String, val error: Boolean) : SessionStatus
}

/**
 * One interactive shell. Termux's [TerminalEmulator] is the VT/xterm state machine; we feed
 * it bytes from the SSH channel instead of a local pty. All emulator access happens on the
 * main thread; network writes go through a single-thread executor so the UI never blocks.
 */
class SshTerminalSession(
    private val appContext: Context,
    val host: Host,
    private val connector: SshConnector,
    private val scope: CoroutineScope,
) {
    val id: String = UUID.randomUUID().toString()

    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Connecting("Starting…"))
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _title = MutableStateFlow(host.label)
    val title: StateFlow<String> = _title.asStateFlow()

    /** Bumped on every screen change; the view listens via [onScreenUpdate]. */
    var onScreenUpdate: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-write").apply { isDaemon = true } }

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var output: OutputStream? = null
    private var connectJob: Job? = null

    private var cols = 80
    private var rows = 24
    private var cellW = 1
    private var cellH = 1

    private val terminalOutput = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = send(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            _title.value = newTitle?.takeIf { it.isNotBlank() } ?: host.label
        }
        override fun onCopyTextToClipboard(text: String) {
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("ssh: ${host.label}", text))
        }
        // OSC 52 paste request from the *remote* side: deliberately ignored, so a server can't
        // silently read the phone's clipboard.
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() = notifyScreen()
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) = notifyScreen()
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$message") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(TAG, "$message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "terminal", e) }
    }

    val emulator = TerminalEmulator(terminalOutput, cols, rows, cellW, cellH, TRANSCRIPT_ROWS, client)

    fun connect() {
        if (connectJob?.isActive == true) return
        _status.value = SessionStatus.Connecting("Starting…")
        connectJob = scope.launch {
            try {
                val s = connector.connect(
                    host,
                    onStage = { stage -> _status.value = SessionStatus.Connecting(stage) },
                    onCreated = { created -> session = created },
                )
                _status.value = SessionStatus.Connecting("Opening shell…")
                val (w, h) = cols to rows
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color")
                ch.setPtySize(w, h, w * cellW, h * cellH)
                ch.setEnv("COLORTERM", "truecolor")
                val input = ch.inputStream
                // Channel open/pty/shell requests write to the socket: never on the main thread.
                withContext(Dispatchers.IO) { ch.connect(30_000) }
                output = ch.outputStream
                channel = ch
                // The view may have resized while the channel was opening.
                if (cols != w || rows != h) writer.execute { runCatching { ch.setPtySize(cols, rows, cols * cellW, rows * cellH) } }
                _status.value = SessionStatus.Connected
                Thread({ pump(input, ch) }, "ssh-read-${host.label}").apply { isDaemon = true }.start()
                host.startupCommand.takeIf { it.isNotBlank() }?.let { send((it.trimEnd() + "\r").encodeToByteArray()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthCancelledException) {
                close("Cancelled", error = false)
            } catch (e: Exception) {
                Log.w(TAG, "connect ${host.label} failed", e)
                close(friendlySshError(e), error = true)
            }
        }
    }

    private fun pump(input: InputStream, ch: ChannelShell) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val chunk = buf.copyOf(n)
                main.post {
                    emulator.append(chunk, n)
                    notifyScreen()
                }
            }
        } catch (_: IOException) {
        }
        val exit = ch.exitStatus
        main.post {
            // A reconnect may already have replaced this channel; only the live one may close us.
            if (channel === ch && _status.value is SessionStatus.Connected) {
                close(if (exit >= 0) "Remote shell exited ($exit)" else "Connection lost", error = exit != 0)
            }
        }
    }

    fun send(bytes: ByteArray) {
        val out = output ?: return
        writer.execute {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                main.post { if (_status.value is SessionStatus.Connected) close("Connection lost: ${e.message}", true) }
            }
        }
    }

    fun send(text: String) = send(text.encodeToByteArray())

    /** Called on the main thread by the view when its size (in cells) changes. */
    fun resize(columns: Int, rowCount: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (columns == cols && rowCount == rows && cellWidthPx == cellW && cellHeightPx == cellH) return
        cols = columns; rows = rowCount; cellW = cellWidthPx; cellH = cellHeightPx
        emulator.resize(columns, rowCount, cellWidthPx, cellHeightPx)
        notifyScreen()
        val ch = channel ?: return
        writer.execute { runCatching { ch.setPtySize(columns, rowCount, columns * cellWidthPx, rowCount * cellHeightPx) } }
    }

    fun paste(text: String) = emulator.paste(text)

    fun reconnect() {
        teardown()
        main.post {
            val notice = "\r\n\u001b[2m── reconnecting ──\u001b[0m\r\n".encodeToByteArray()
            emulator.append(notice, notice.size)
            notifyScreen()
        }
        connect()
    }

    fun close(reason: String = "Disconnected", error: Boolean = false) {
        teardown()
        _status.value = SessionStatus.Closed(reason, error)
    }

    fun dispose() {
        close()
        writer.shutdown()
    }

    private fun teardown() {
        connectJob?.cancel()
        connectJob = null
        val ch = channel
        val s = session
        channel = null
        session = null
        output = null
        if (ch != null || s != null) {
            Thread { runCatching { ch?.disconnect() }; runCatching { s?.disconnect() } }.start()
        }
    }

    private fun notifyScreen() {
        onScreenUpdate?.invoke()
    }

    /** Plain text of the visible screen, for automation and "copy screen". */
    fun screenText(): String {
        val e = emulator
        return e.screen.getSelectedText(0, 0, e.mColumns, e.mRows - 1).trimEnd()
    }

    fun transcriptText(): String = emulator.screen.transcriptText.trimEnd()

    companion object {
        private const val TAG = "ToryTerminal"
        const val TRANSCRIPT_ROWS = 5000
    }
}
