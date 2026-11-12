package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自建服务器地址的规整与明文放行规则——写错了要么连不上，要么把明文开给公网。 */
class NtfyEndpointTest {

    @Test
    fun emptyFallsBackToDefault() {
        assertEquals(NtfyEndpoint.DEFAULT, NtfyEndpoint.normalize(""))
        assertEquals(NtfyEndpoint.DEFAULT, NtfyEndpoint.normalize("   "))
        assertTrue(NtfyEndpoint.check("").ok)
    }

    @Test
    fun publicHttpsIsAllowed() {
        val check = NtfyEndpoint.check("https://ntfy.example.com")
        assertTrue(check.ok)
        assertEquals("https://ntfy.example.com", check.normalized)
    }

    @Test
    fun trailingSlashIsTrimmed() {
        assertEquals("https://ntfy.sh", NtfyEndpoint.normalize("https://ntfy.sh/"))
    }

    @Test
    fun publicPlainHttpIsRejected() {
        val check = NtfyEndpoint.check("http://example.com")
        assertFalse(check.ok)
        assertTrue(check.reason.orEmpty().contains("局域网"))
    }

    @Test
    fun lanPlainHttpIsAllowed() {
        for (host in listOf("192.168.1.5:8088", "10.0.0.2", "172.16.3.4", "localhost:8080")) {
            val check = NtfyEndpoint.check("http://$host")
            assertTrue("$host 应该放行", check.ok)
        }
    }

    @Test
    fun schemeIsFilledInByHostType() {
        assertEquals("http://192.168.1.5:8088", NtfyEndpoint.normalize("192.168.1.5:8088"))
        assertEquals("https://ntfy.example.com", NtfyEndpoint.normalize("ntfy.example.com"))
    }

    @Test
    fun privateRangesAreRecognised() {
        assertTrue(NtfyEndpoint.isPrivateHost("192.168.31.7"))
        assertTrue(NtfyEndpoint.isPrivateHost("10.1.2.3"))
        assertTrue(NtfyEndpoint.isPrivateHost("172.20.0.1"))
        assertTrue(NtfyEndpoint.isPrivateHost("172.31.255.255"))
        assertTrue(NtfyEndpoint.isPrivateHost("127.0.0.1"))
        assertTrue(NtfyEndpoint.isPrivateHost("bellnote.local"))
        assertTrue(NtfyEndpoint.isPrivateHost("::1"))

        assertFalse(NtfyEndpoint.isPrivateHost("172.32.0.1"))
        assertFalse(NtfyEndpoint.isPrivateHost("8.8.8.8"))
        assertFalse(NtfyEndpoint.isPrivateHost("ntfy.sh"))
    }

    @Test
    fun malformedAddressIsRejected() {
        val check = NtfyEndpoint.check("http://")
        assertFalse(check.ok)
    }
}
