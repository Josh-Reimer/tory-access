package com.joshreimer.toryaccess.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.joshreimer.toryaccess.data.AuthMethod
import com.joshreimer.toryaccess.data.Host
import com.joshreimer.toryaccess.data.Route
import com.joshreimer.toryaccess.data.SecretBox
import com.joshreimer.toryaccess.graph
import com.joshreimer.toryaccess.ssh.Prompt
import com.joshreimer.toryaccess.ssh.SecretReply
import com.joshreimer.toryaccess.ssh.SessionStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug-build automation surface. Results come back in `am broadcast`'s own output
 * ("Broadcast completed: result=0, data=…"), so a Termux agent can read state without
 * screenshots. Always address it explicitly with -n (implicit broadcasts don't reach
 * manifest receivers). See scripts/device.sh for wrappers.
 *
 *   am broadcast -n <pkg>/com.joshreimer.toryaccess.debug.AutomationReceiver -a tory.DUMP
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = try {
            handle(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "automation ${intent.action} failed", e)
            resultCode = 1
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
        Log.i(TAG, "${intent.action} -> $result")
        resultData = result
    }

    private fun handle(context: Context, intent: Intent): String {
        val g = context.graph
        // 0 = success, 1 = error (Activity.RESULT_OK is -1, which reads like a failure in `am broadcast`).
        resultCode = 0
        return when (intent.action) {
            "tory.DUMP" -> dump(context).toString()

            "tory.ADD_HOST" -> {
                val label = intent.getStringExtra("label") ?: error("--es label required")
                val hostname = intent.getStringExtra("host") ?: error("--es host required")
                val password = intent.getStringExtra("password")
                val keyLabel = intent.getStringExtra("key")
                val key = keyLabel?.let { kl -> g.keys.keys.value.firstOrNull { it.label == kl } ?: error("no key '$kl'") }
                val existing = g.hosts.findByLabel(label)
                val host = (existing ?: Host(label = label, hostname = hostname, username = "root")).copy(
                    hostname = hostname,
                    port = intent.getIntExtra("port", existing?.port ?: 22),
                    username = intent.getStringExtra("user") ?: existing?.username ?: "root",
                    route = if (intent.getStringExtra("route") == "direct") Route.DIRECT else Route.TOR,
                    auth = if (key != null) AuthMethod.KEY else AuthMethod.PASSWORD,
                    keyId = key?.id,
                    password = password?.let { SecretBox.seal(it) } ?: existing?.password,
                    startupCommand = intent.getStringExtra("startup") ?: existing?.startupCommand.orEmpty(),
                )
                g.hosts.upsert(host)
                "ok ${host.id}"
            }

            "tory.REMOVE_HOST" -> {
                val label = intent.getStringExtra("label") ?: error("--es label required")
                val h = g.hosts.findByLabel(label) ?: error("no host '$label'")
                g.hosts.delete(h.id)
                "ok"
            }

            // Type into the active session. --ez enter true appends a carriage return.
            "tory.SEND" -> {
                val s = g.sessions.active ?: error("no active session")
                val text = intent.getStringExtra("text").orEmpty()
                s.send(text + if (intent.getBooleanExtra("enter", false)) "\r" else "")
                "ok"
            }

            // The emulator's visible screen as plain text — the Compose/uiautomator tree can't see it.
            "tory.SCREEN" -> {
                val s = g.sessions.active ?: error("no active session")
                if (intent.getBooleanExtra("scrollback", false)) s.transcriptText() else s.screenText()
            }

            // Answer the dialog that's currently up: --es value yes|no for host keys, or the secret text.
            "tory.ANSWER" -> {
                val pending = g.prompts.current.value ?: error("no prompt pending")
                val value = intent.getStringExtra("value") ?: error("--es value required")
                when (pending.prompt) {
                    is Prompt.HostKey -> g.prompts.respond(pending, value.equals("yes", true))
                    is Prompt.Secret -> g.prompts.respond(pending, SecretReply(value, save = false))
                }
                "ok"
            }

            "tory.NEWNYM" -> {
                g.scope.launch { g.tor.newIdentity() }
                "ok"
            }

            else -> {
                resultCode = 1
                "unknown action ${intent.action}; try tory.DUMP | ADD_HOST | REMOVE_HOST | SEND | SCREEN | ANSWER | NEWNYM"
            }
        }
    }

    private fun dump(context: Context): JsonObject {
        val g = context.graph
        val tor = g.tor.state.value
        val pending = g.prompts.current.value?.prompt
        return buildJsonObject {
            put("tor", buildJsonObject {
                put("mode", g.settings.value.torMode.name)
                put("phase", tor.phase.name)
                put("progress", tor.progress)
                put("socksPort", tor.socksPort)
                put("summary", tor.summary)
            })
            put("prompt", when (pending) {
                null -> JsonPrimitive(null as String?)
                is Prompt.HostKey -> JsonPrimitive("hostkey ${pending.host} ${pending.fingerprint}${if (pending.changed) " CHANGED" else ""}")
                is Prompt.Secret -> JsonPrimitive("secret ${pending.title}: ${pending.message}")
            })
            put("activeSession", g.sessions.activeId.value)
            put("sessions", JsonArray(g.sessions.sessions.value.map { s ->
                buildJsonObject {
                    put("id", s.id)
                    put("host", s.host.label)
                    put("title", s.title.value)
                    put("status", when (val st = s.status.value) {
                        is SessionStatus.Connecting -> "connecting: ${st.stage}"
                        SessionStatus.Connected -> "connected"
                        is SessionStatus.Closed -> "closed: ${st.reason}"
                    })
                }
            }))
            put("hosts", buildJsonArray {
                g.hosts.hosts.value.forEach { h ->
                    add(buildJsonObject {
                        put("label", h.label)
                        put("target", h.display)
                        put("route", h.effectiveRoute.name)
                        put("auth", h.auth.name)
                    })
                }
            })
            put("keys", JsonArray(g.keys.keys.value.map { JsonPrimitive("${it.label} ${it.fingerprint}") }))
        }
    }

    companion object {
        private const val TAG = "ToryAutomation"
    }
}
