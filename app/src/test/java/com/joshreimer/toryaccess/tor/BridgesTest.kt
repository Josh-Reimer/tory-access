package com.joshreimer.toryaccess.tor

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgesTest {
    @Test
    fun splitsVanillaFromPluggableTransports() {
        val p = Bridges.parse(
            """
            # comment
            Bridge 203.0.113.5:443 4352E58420E68F5E40BF7C74FADDCCD9D1349413
            198.51.100.7:9001
            obfs4 192.0.2.1:80 0123456789ABCDEF0123456789ABCDEF01234567 cert=abc iat-mode=0
            [2001:db8::1]:443
            snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72
            """.trimIndent()
        )
        assertEquals(3, p.usable.size)
        assertEquals("203.0.113.5:443 4352E58420E68F5E40BF7C74FADDCCD9D1349413", p.usable[0])
        assertEquals(2, p.unsupported.size)
    }
}
