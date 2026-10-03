package com.joshreimer.toryaccess.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.joshreimer.toryaccess.data.Host
import com.joshreimer.toryaccess.data.Route

/**
 * Hands a host to Termux's own OpenSSH via the RUN_COMMAND intent, tunnelled through our
 * SOCKS port with `nc -X 5`. Arguments travel as literal intent extras — never shell-parsed —
 * so nothing here needs quoting.
 *
 * Termux side, one time: `pkg install openssh netcat-openbsd` and
 * `allow-external-apps=true` in ~/.termux/termux.properties.
 */
object TermuxBridge {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PREFIX = "/data/data/com.termux/files/usr"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun sshArgs(host: Host, socksPort: Int?): List<String> = buildList {
        if (host.port != 22) { add("-p"); add(host.port.toString()) }
        if (host.effectiveRoute == Route.TOR && socksPort != null) {
            add("-o"); add("ProxyCommand=nc -X 5 -x 127.0.0.1:$socksPort %h %p")
        }
        add("${host.username}@${host.hostname}")
    }

    /** Copy-pasteable version for a Termux shell or ~/.ssh/config users. */
    fun sshCommandLine(host: Host, socksPort: Int?): String =
        "ssh " + sshArgs(host, socksPort).joinToString(" ") { if (it.contains(' ')) "'$it'" else it }

    fun open(context: Context, host: Host, socksPort: Int?) {
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/ssh")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", sshArgs(host, socksPort).toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        context.startForegroundService(intent)
    }
}
