package com.joshreimer.toryaccess.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorControlTest {
    private fun reader(vararg lines: String): () -> String? {
        val it = lines.iterator()
        return { if (it.hasNext()) it.next() else null }
    }

    @Test
    fun singleLineGetInfoConsumesTrailingOk() {
        val read = reader("250-ip-to-country/1.2.3.4=de", "250 OK", "250 OK")
        val r = readControlReply(read)
        assertTrue(r.ok)
        assertEquals("de", r.value("ip-to-country/1.2.3.4"))
        // The next command must see its own reply, not the leftover "250 OK" of this one.
        assertEquals(listOf("OK"), readControlReply(read).lines)
        assertNull(read())
    }

    @Test
    fun dataBlockIsCollectedUntilDot() {
        val read = reader(
            "250+circuit-status=",
            "1 BUILT \$AAA~a,\$BBB~b,\$CCC~c PURPOSE=GENERAL",
            "2 EXTENDED \$DDD~d PURPOSE=HS_CLIENT_REND",
            ".",
            "250 OK",
        )
        val r = readControlReply(read)
        assertTrue(r.ok)
        val body = r.value("circuit-status")!!
        assertEquals(2, body.lines().filter { it.isNotBlank() }.size)
        assertNull(read())
    }

    @Test
    fun errorReplyHasNoTrailingOk() {
        val read = reader("551 No GeoIP data", "250 OK")
        val r = readControlReply(read)
        assertFalse(r.ok)
        assertEquals(551, r.status)
        assertEquals("250 OK", read()) // untouched: belongs to the next command
    }

    @Test
    fun parsesCircuitPathsAndPurpose() {
        val c = parseCircuitStatus(
            "\n1 BUILT \$AAAA~guard,\$BBBB~middle,\$CCCC~exit BUILD_FLAGS=NEED_CAPACITY PURPOSE=CONFLUX_LINKED TIME_CREATED=x\n" +
                "7 BUILT \$DDDD=g2,\$EEEE~m2,\$FFFF~rp PURPOSE=HS_CLIENT_REND HS_STATE=HSCR_JOINED"
        )
        assertEquals(2, c.size)
        assertEquals(listOf("guard", "middle", "exit"), c[0].hops.map { it.nickname })
        assertEquals("CONFLUX_LINKED", c[0].purpose)
        assertEquals("DDDD", c[1].hops[0].fingerprint)
        assertEquals("g2", c[1].hops[0].nickname)
        assertEquals("HS_CLIENT_REND", c[1].purpose)
    }
}
