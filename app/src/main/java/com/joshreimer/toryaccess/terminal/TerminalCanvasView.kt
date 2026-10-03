package com.joshreimer.toryaccess.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joshreimer.toryaccess.ssh.SshTerminalSession
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Sticky Ctrl/Alt toggled from the extra-keys row; consumed by the next key. Compose-observable. */
class ModifierLatch {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun consumeCtrl(): Boolean = ctrl.also { if (it) ctrl = false }
    fun consumeAlt(): Boolean = alt.also { if (it) alt = false }
}

/**
 * Renders a Termux [TerminalEmulator] and turns soft/hardware keyboard input into bytes for
 * the attached [SshTerminalSession]. A plain View (hosted in Compose via AndroidView) because
 * it needs a real [InputConnection] — Compose text fields fight terminal-style input.
 */
@SuppressLint("ViewConstructor")
class TerminalCanvasView(context: Context, val modifiers: ModifierLatch) : View(context) {

    var onFontSizeChanged: ((Float) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    var session: SshTerminalSession? = null
        set(value) {
            if (field === value) return
            field?.onScreenUpdate = null
            field = value
            topRow = 0
            value?.onScreenUpdate = { onScreenUpdated() }
            updateSize()
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fillPaint = Paint()
    private var cellWidth = 1f
    private var cellHeight = 1
    private var ascent = 0
    private var fontSizeSp = 13f

    /** 0 = bottom of the live screen; negative = scrolled back into the transcript. */
    private var topRow = 0
    private var scrollRemainder = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setTextSizeSp(fontSizeSp)
    }

    fun setTextSizeSp(sp: Float) {
        fontSizeSp = sp.coerceIn(MIN_SP, MAX_SP)
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val fm = textPaint.fontMetricsInt
        ascent = fm.ascent
        cellHeight = ceil(textPaint.fontSpacing).toInt()
        cellWidth = textPaint.measureText("X")
        updateSize()
        invalidate()
    }

    private fun updateSize() {
        val s = session ?: return
        if (width == 0 || height == 0) return
        val cols = max(4, (width / cellWidth).toInt())
        val rows = max(4, height / cellHeight)
        s.resize(cols, rows, ceil(cellWidth).toInt(), cellHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateSize()

    private fun onScreenUpdated() {
        val emu = session?.emulator ?: return
        if (topRow != 0) {
            // Keep the scrolled-back content still while new lines arrive below.
            topRow = max(-emu.screen.activeTranscriptRows, topRow - emu.scrollCounter)
        }
        emu.clearScrollCounter()
        invalidate()
    }

    fun scrollToBottom() {
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    // ---------------------------------------------------------------- rendering

    override fun onDraw(canvas: Canvas) {
        val emu = session?.emulator
        if (emu == null) {
            fillPaint.color = DEFAULT_BG
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            return
        }
        val palette = emu.mColors.mCurrentColors
        val reverse = emu.isReverseVideo
        val defaultBg = palette[if (reverse) TextStyle.COLOR_INDEX_FOREGROUND else TextStyle.COLOR_INDEX_BACKGROUND]
        // drawRect, not drawColor: Compose doesn't clip AndroidView children, so drawColor
        // would paint over the tab bar above us.
        fillPaint.color = defaultBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        val screen = emu.screen
        topRow = topRow.coerceIn(-screen.activeTranscriptRows, 0)
        val cursorRow = emu.cursorRow
        val cursorCol = emu.cursorCol
        val showCursor = emu.shouldCursorBeVisible() && topRow + emu.mRows > cursorRow

        for (vr in 0 until emu.mRows) {
            val row = topRow + vr
            if (row >= emu.mRows) break
            val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val cursorX = if (showCursor && row == cursorRow) cursorCol else -1
            drawLine(canvas, emu, line, vr, cursorX, palette, reverse, defaultBg)
        }
    }

    private fun drawLine(
        canvas: Canvas,
        emu: TerminalEmulator,
        line: com.termux.terminal.TerminalRow,
        visualRow: Int,
        cursorX: Int,
        palette: IntArray,
        reverse: Boolean,
        defaultBg: Int,
    ) {
        val chars = line.mText
        val used = line.spaceUsed
        val columns = emu.mColumns
        var runStyle = 0L
        var runCursor = false
        var runStartCol = 0
        var runStartIdx = 0
        var col = 0
        var idx = 0
        while (col < columns && idx < chars.size) {
            val c = chars[idx]
            val high = Character.isHighSurrogate(c) && idx + 1 < chars.size
            val cp = if (high) Character.toCodePoint(c, chars[idx + 1]) else c.code
            val width = max(1, WcWidth.width(cp))
            val style = line.getStyle(col)
            val inCursor = col == cursorX
            if (col != 0 && (style != runStyle || inCursor != runCursor)) {
                drawRun(canvas, chars, runStartIdx, idx - runStartIdx, runStartCol, col - runStartCol,
                    visualRow, runStyle, runCursor, palette, reverse, defaultBg, emu)
                runStartCol = col
                runStartIdx = idx
            }
            runStyle = style
            runCursor = inCursor
            col += width
            idx += if (high) 2 else 1
            // Combining marks ride along with the preceding base character.
            while (idx < used && WcWidth.width(chars, idx) <= 0) {
                idx += if (Character.isHighSurrogate(chars[idx])) 2 else 1
            }
        }
        drawRun(canvas, chars, runStartIdx, idx - runStartIdx, runStartCol, min(col, columns) - runStartCol,
            visualRow, runStyle, runCursor, palette, reverse, defaultBg, emu)
    }

    private fun drawRun(
        canvas: Canvas,
        chars: CharArray,
        start: Int,
        count: Int,
        startCol: Int,
        runCols: Int,
        visualRow: Int,
        style: Long,
        cursor: Boolean,
        palette: IntArray,
        reverse: Boolean,
        defaultBg: Int,
        emu: TerminalEmulator,
    ) {
        if (runCols <= 0) return
        val effect = TextStyle.decodeEffect(style)
        var fgIndex = TextStyle.decodeForeColor(style)
        val bgIndex = TextStyle.decodeBackColor(style)
        val bold = effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0
        if (bold && fgIndex in 0..7) fgIndex += 8
        var fg = resolveColor(fgIndex, palette)
        var bg = resolveColor(bgIndex, palette)
        if ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) != reverse) {
            val t = fg; fg = bg; bg = t
        }
        val left = startCol * cellWidth
        val right = left + runCols * cellWidth
        val top = (visualRow * cellHeight).toFloat()
        val bottom = top + cellHeight

        val cursorStyle = emu.cursorStyle
        if (cursor && cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            bg = palette[TextStyle.COLOR_INDEX_CURSOR]
            fg = if (isFocused) defaultBg else fg
            if (!isFocused) {
                fillPaint.color = bg
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = 2f
                canvas.drawRect(left + 1, top + 1, right - 1, bottom - 1, fillPaint)
                fillPaint.style = Paint.Style.FILL
                bg = defaultBg
            }
        }
        if (bg != defaultBg) {
            fillPaint.color = bg
            canvas.drawRect(left, top, right, bottom, fillPaint)
        }
        if (cursor && cursorStyle != TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            fillPaint.color = palette[TextStyle.COLOR_INDEX_CURSOR]
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) canvas.drawRect(left, top, left + 3, bottom, fillPaint)
            else canvas.drawRect(left, bottom - 4, right, bottom, fillPaint)
        }

        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0 || count <= 0) return
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
            fg = Color.argb(0xFF, Color.red(fg) * 2 / 3, Color.green(fg) * 2 / 3, Color.blue(fg) * 2 / 3)
        }
        textPaint.color = fg
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.25f else 0f
        textPaint.isUnderlineText = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        textPaint.isStrikeThruText = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0

        val baseline = top - ascent
        val measured = textPaint.measureText(chars, start, count)
        val expected = runCols * cellWidth
        if (measured > 0 && abs(measured - expected) > 0.5f) {
            // Glyphs from fallback fonts (CJK, emoji, box drawing) aren't exactly cell-sized.
            canvas.save()
            canvas.scale(expected / measured, 1f, left, 0f)
            canvas.drawText(chars, start, count, left, baseline, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(chars, start, count, left, baseline, textPaint)
        }
    }

    private fun resolveColor(c: Int, palette: IntArray): Int =
        if (c and 0xFF000000.toInt() == 0xFF000000.toInt()) c else palette[c]

    // ---------------------------------------------------------------- touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            scrollRemainder += dy
            val lines = (scrollRemainder / cellHeight).toInt()
            if (lines == 0) return true
            scrollRemainder -= lines * cellHeight
            scrollLines(lines)
            return true
        }
    })

    private var scaling = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (fontSizeSp * detector.scaleFactor).coerceIn(MIN_SP, MAX_SP)
            if (abs(next - fontSizeSp) >= 0.5f) {
                setTextSizeSp(next)
                onFontSizeChanged?.invoke(fontSizeSp)
                return true
            }
            return false
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    })

    /** Positive = towards newer output. In full-screen apps (vim, less, htop) we send keys instead. */
    private fun scrollLines(lines: Int) {
        val s = session ?: return
        val emu = s.emulator
        if (emu.isMouseTrackingActive) {
            val button = if (lines < 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
            repeat(abs(lines)) { emu.sendMouseEvent(button, 1, 1, true) }
        } else if (emu.isAlternateBufferActive) {
            val key = if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            val code = KeyHandler.getCode(key, 0, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode) ?: return
            repeat(abs(lines)) { s.send(code) }
        } else {
            topRow = (topRow + lines).coerceIn(-emu.screen.activeTranscriptRows, 0)
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------------------------------------------------------- keyboard

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password: no autocorrect/predictions mangling what goes to the shell.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flushEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flushEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // IMEs delete via this instead of key events once they have text context.
                repeat(beforeLength) { sendSpecialKey(KeyEvent.KEYCODE_DEL) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)

            private fun flushEditable() {
                val e = editable ?: return
                if (e.isNotEmpty()) {
                    sendText(e.toString())
                    e.clear()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.isSystem
        ) return super.onKeyDown(keyCode, event)

        val ctrl = event.isCtrlPressed || modifiers.consumeCtrl()
        val alt = event.isAltPressed || modifiers.consumeAlt()
        var keyMod = 0
        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK

        val emu = s.emulator
        KeyHandler.getCode(keyCode, keyMod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)?.let {
            s.send(it)
            scrollToBottom()
            return true
        }
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val unicode = event.getUnicodeChar(meta)
        if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return super.onKeyDown(keyCode, event)
        inputCodePoint(unicode, ctrl, alt)
        return true
    }

    /** Text from the IME (or the extra-keys row). Sticky modifiers apply to the first char. */
    fun sendText(text: String) {
        var first = true
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) cp = '\r'.code
            val ctrl = first && modifiers.consumeCtrl()
            val alt = first && modifiers.consumeAlt()
            inputCodePoint(cp, ctrl, alt)
            first = false
        }
    }

    fun sendSpecialKey(keyCode: Int) {
        val s = session ?: return
        var keyMod = 0
        if (modifiers.consumeCtrl()) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (modifiers.consumeAlt()) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        val emu = s.emulator
        KeyHandler.getCode(keyCode, keyMod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
            ?.let { s.send(it) }
        scrollToBottom()
    }

    private fun inputCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean) {
        val s = session ?: return
        var cp = codePoint
        if (ctrl) cp = controlCode(cp)
        val out = StringBuilder()
        if (alt) out.append('\u001b')
        out.appendCodePoint(cp)
        s.send(out.toString())
        scrollToBottom()
    }

    companion object {
        const val MIN_SP = 6f
        const val MAX_SP = 36f
        private val DEFAULT_BG = Color.BLACK
    }
}

/** Ctrl+<char> → C0 control code, matching xterm (incl. the Ctrl+2..8 aliases). */
fun controlCode(cp: Int): Int = when (cp) {
    in 'a'.code..'z'.code -> cp - 'a'.code + 1
    in 'A'.code..'Z'.code -> cp - 'A'.code + 1
    ' '.code, '2'.code, '@'.code -> 0
    '['.code, '3'.code -> 27
    '\\'.code, '4'.code -> 28
    ']'.code, '5'.code -> 29
    '^'.code, '6'.code -> 30
    '_'.code, '-'.code, '7'.code, '/'.code -> 31
    '8'.code, '?'.code -> 127
    else -> cp
}
