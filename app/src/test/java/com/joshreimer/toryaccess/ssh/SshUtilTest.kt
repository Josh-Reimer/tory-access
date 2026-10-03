package com.joshreimer.toryaccess.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshUtilTest {
    @Test
    fun parsesHostSpecs() {
        assertEquals(HostSpec("alice", "abc.onion", 22), parseHostSpec("alice@abc.onion"))
        assertEquals(HostSpec("root", "1.2.3.4", 2222), parseHostSpec("1.2.3.4:2222"))
        assertEquals(HostSpec("bob", "::1", 22), parseHostSpec("bob@[::1]"))
        assertEquals(HostSpec("bob", "2001:db8::1", 2200), parseHostSpec("ssh://bob@[2001:db8::1]:2200"))
        assertEquals(HostSpec("me@corp", "h.example", 22), parseHostSpec("me@corp@h.example"))
        assertNull(parseHostSpec(""))
        assertNull(parseHostSpec("a b"))
        assertNull(parseHostSpec("host:99999"))
        assertNull(parseHostSpec("host:abc"))
    }

    @Test
    fun fingerprintMatchesOpenSshFormat() {
        // ssh-keygen -lf of an all-zero blob is deterministic; we only check the shape here.
        val fp = sha256Fingerprint(ByteArray(51))
        assertTrue(fp.startsWith("SHA256:"))
        assertEquals(7 + 43, fp.length) // 32 bytes → 43 unpadded base64 chars
    }

    @Test
    fun mapsTorExtendedSocksErrors() {
        // JSch reports the reply byte as a signed Java byte: 0xF0 → -16.
        val e = Exception("ProxySOCKS5: server returns -16")
        assertTrue(friendlySshError(e).contains("descriptor not found"))
        assertTrue(friendlySshError(Exception("ProxySOCKS5: server returns 5")).contains("refused"))
        assertTrue(friendlySshError(Exception("Auth fail for methods 'publickey'")).startsWith("Authentication failed"))
    }
}
