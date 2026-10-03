package com.joshreimer.toryaccess.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCodeTest {
    @Test
    fun mapsLikeXterm() {
        assertEquals(3, controlCode('c'.code))   // ^C
        assertEquals(3, controlCode('C'.code))
        assertEquals(0, controlCode(' '.code))   // ^@
        assertEquals(27, controlCode('['.code))  // ESC
        assertEquals(31, controlCode('/'.code))  // ^_ (undo in many shells)
        assertEquals('é'.code, controlCode('é'.code))
    }
}
