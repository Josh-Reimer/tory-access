package com.joshreimer.toryaccess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.joshreimer.toryaccess.AppGraph
import com.joshreimer.toryaccess.ssh.SessionStatus
import com.joshreimer.toryaccess.ssh.SshTerminalSession
import com.joshreimer.toryaccess.terminal.ModifierLatch
import com.joshreimer.toryaccess.terminal.TerminalCanvasView
import com.joshreimer.toryaccess.ui.theme.ToryColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF000000)
private val BarBg = Color(0xFF111117)

@Composable
fun TerminalScreen(graph: AppGraph, nav: Navigator) {
    val sessions by graph.sessions.sessions.collectAsState()
    val activeId by graph.sessions.activeId.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.lastOrNull()
    val latch = remember { ModifierLatch() }
    var view by remember { mutableStateOf<TerminalCanvasView?>(null) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(sessions.isEmpty()) { if (sessions.isEmpty()) nav.pop() }
    if (active == null) return

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) active.paste(text)
    }

    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().background(TermBg).statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // ── tabs
        Row(Modifier.fillMaxWidth().background(BarBg), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to hub", tint = Color.White) }
            ScrollableTabRow(
                selectedTabIndex = sessions.indexOf(active).coerceAtLeast(0),
                containerColor = BarBg,
                contentColor = Color.White,
                edgePadding = 0.dp,
                divider = {},
                indicator = { positions ->
                    val i = sessions.indexOf(active)
                    if (i in positions.indices) {
                        with(TabRowDefaults) {
                            SecondaryIndicator(Modifier.tabIndicatorOffset(positions[i]), color = ToryColors.Onion)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                sessions.forEach { s -> SessionTab(s, selected = s.id == active.id, onSelect = { graph.sessions.setActive(s.id) }, onClose = { graph.sessions.close(s.id) }) }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("terminal_menu")) {
                    Icon(Icons.Outlined.MoreVert, "Menu", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = AutomationTags) {
                    DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; paste() })
                    DropdownMenuItem(text = { Text("Copy screen") }, onClick = { menu = false; copy("screen", active.screenText()) })
                    DropdownMenuItem(text = { Text("Copy scrollback") }, onClick = { menu = false; copy("scrollback", active.transcriptText()) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Bigger text") }, onClick = {
                        graph.settings.update { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(TerminalCanvasView.MAX_SP)) }
                    })
                    DropdownMenuItem(text = { Text("Smaller text") }, onClick = {
                        graph.settings.update { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(TerminalCanvasView.MIN_SP)) }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; active.reconnect() })
                    DropdownMenuItem(text = { Text("Close tab", color = ToryColors.Danger) }, onClick = { menu = false; graph.sessions.close(active.id) })
                }
            }
        }

        // ── terminal
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    TerminalCanvasView(ctx, latch).also { v ->
                        v.onFontSizeChanged = { sp -> graph.settings.update { it.copy(fontSizeSp = sp) } }
                        v.onLongPress = { menu = true }
                        view = v
                    }
                },
                update = { v ->
                    v.setTextSizeSpIfChanged(settings.fontSizeSp)
                    v.keepScreenOn = settings.keepScreenOn
                    if (v.session !== active) {
                        v.session = active
                        v.post { v.showKeyboard() }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp).clipToBounds().testTag("terminal_view"),
            )
            StatusOverlay(active, onReconnect = { active.reconnect() }, onClose = { graph.sessions.close(active.id) })
        }

        // ── extra keys
        ExtraKeys(latch, view, onPaste = ::paste)
    }
}

private fun TerminalCanvasView.setTextSizeSpIfChanged(sp: Float) {
    if (tag != sp) {
        tag = sp
        setTextSizeSp(sp)
    }
}

@Composable
private fun SessionTab(s: SshTerminalSession, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val status by s.status.collectAsState()
    val title by s.title.collectAsState()
    Tab(selected = selected, onClick = onSelect, modifier = Modifier.height(44.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor(status))
            Spacer(Modifier.width(8.dp))
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
                color = if (selected) Color.White else Color(0xFF9A98A6),
                fontSize = 13.sp,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, "Close", Modifier.size(14.dp), tint = Color(0xFF9A98A6))
            }
        }
    }
}

@Composable
private fun StatusOverlay(s: SshTerminalSession, onReconnect: () -> Unit, onClose: () -> Unit) {
    val status by s.status.collectAsState()
    val st = status
    if (st is SessionStatus.Connected) return
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).testTag("session_overlay"),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.host.label, style = MaterialTheme.typography.titleMedium)
                Text(s.host.display, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                when (st) {
                    is SessionStatus.Connecting -> {
                        CircularProgressIndicator(color = ToryColors.Onion)
                        Spacer(Modifier.height(12.dp))
                        Text(st.stage, textAlign = TextAlign.Center, modifier = Modifier.testTag("session_stage"))
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onClose) { Text("Cancel") }
                    }
                    is SessionStatus.Closed -> {
                        Text(
                            st.reason, textAlign = TextAlign.Center,
                            color = if (st.error) ToryColors.Danger else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("session_error"),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onClose) { Text("Close") }
                            FilledTonalButton(onClick = onReconnect, modifier = Modifier.testTag("session_reconnect")) { Text("Reconnect") }
                        }
                    }
                    SessionStatus.Connected -> Unit
                }
            }
        }
    }
}

private sealed interface XKey {
    val label: String
    data class Special(override val label: String, val keyCode: Int, val repeat: Boolean = false) : XKey
    data class Chars(override val label: String, val text: String) : XKey
    data object Ctrl : XKey { override val label = "CTRL" }
    data object Alt : XKey { override val label = "ALT" }
}

// Same layout as Termux's default extra-keys rows, so muscle memory carries over.
private val ROWS = listOf(
    listOf(
        XKey.Special("ESC", KeyEvent.KEYCODE_ESCAPE), XKey.Chars("/", "/"), XKey.Chars("-", "-"),
        XKey.Special("HOME", KeyEvent.KEYCODE_MOVE_HOME), XKey.Special("↑", KeyEvent.KEYCODE_DPAD_UP, true),
        XKey.Special("END", KeyEvent.KEYCODE_MOVE_END), XKey.Special("PGUP", KeyEvent.KEYCODE_PAGE_UP, true),
    ),
    listOf(
        XKey.Special("TAB", KeyEvent.KEYCODE_TAB), XKey.Ctrl, XKey.Alt,
        XKey.Special("←", KeyEvent.KEYCODE_DPAD_LEFT, true), XKey.Special("↓", KeyEvent.KEYCODE_DPAD_DOWN, true),
        XKey.Special("→", KeyEvent.KEYCODE_DPAD_RIGHT, true), XKey.Special("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, true),
    ),
)

@Composable
private fun ExtraKeys(latch: ModifierLatch, view: TerminalCanvasView?, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(BarBg).padding(vertical = 2.dp)) {
        ROWS.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().height(40.dp)) {
                row.forEach { key ->
                    val active = (key is XKey.Ctrl && latch.ctrl) || (key is XKey.Alt && latch.alt)
                    KeyCap(
                        label = key.label,
                        active = active,
                        repeat = key is XKey.Special && key.repeat,
                        modifier = Modifier.weight(1f).testTag("xkey_${key.label}"),
                    ) {
                        when (key) {
                            is XKey.Special -> view?.sendSpecialKey(key.keyCode)
                            is XKey.Chars -> view?.sendText(key.text)
                            XKey.Ctrl -> latch.ctrl = !latch.ctrl
                            XKey.Alt -> latch.alt = !latch.alt
                        }
                    }
                }
                if (r == 0) {
                    IconKey(Modifier.weight(1f), onClick = onPaste) { Icon(Icons.Outlined.ContentPaste, "Paste", Modifier.size(18.dp), tint = Color.White) }
                } else {
                    IconKey(Modifier.weight(1f), onClick = { view?.showKeyboard() }) { Icon(Icons.Outlined.Keyboard, "Keyboard", Modifier.size(18.dp), tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean, repeat: Boolean, modifier: Modifier, onPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    // The gesture loop outlives recompositions; always call the latest lambda (the view may
    // not have existed when this key was first composed).
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .padding(2.dp)
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> ToryColors.Onion.copy(alpha = 0.45f)
                    pressed -> Color(0xFF34323D)
                    else -> Color.Transparent
                }
            )
            .pointerInput(label, repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    press()
                    val job = if (repeat) scope.launch {
                        delay(400)
                        while (true) { press(); delay(45) }
                    } else null
                    waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (active) Color.White else Color(0xFFE4E1EC),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun IconKey(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        modifier.padding(2.dp).fillMaxSize().clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); click(); waitForUpOrCancellation() } },
        contentAlignment = Alignment.Center,
    ) { content() }
}
