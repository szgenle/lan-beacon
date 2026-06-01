package com.szgenle.lanbeacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PresenceHttpServer] 单元测试。
 *
 * 覆盖两个纯函数：
 * - [PresenceHttpServer.isPrivateNetwork]：RFC1918 / link-local / loopback 判定
 * - [PresenceHttpServer.buildHealthzJson]：healthz JSON 格式 + 转义
 */
class PresenceHttpServerTest {

    // ==================== isPrivateNetwork ====================

    @Test
    fun `RFC1918 - 10 dot x - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("10.0.0.1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("10.255.255.255"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("10.1.2.3"))
    }

    @Test
    fun `RFC1918 - 172 dot 16 to 31 - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("172.16.0.1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("172.31.255.255"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("172.20.0.1"))
    }

    @Test
    fun `172 dot 32 and above - should NOT be private`() {
        assertFalse(PresenceHttpServer.isPrivateNetwork("172.32.0.1"))
        assertFalse(PresenceHttpServer.isPrivateNetwork("172.100.0.1"))
    }

    @Test
    fun `RFC1918 - 192 dot 168 - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("192.168.0.1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("192.168.1.100"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("192.168.255.255"))
    }

    @Test
    fun `loopback 127 - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("127.0.0.1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("127.1.2.3"))
    }

    @Test
    fun `link-local 169 dot 254 - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("169.254.0.1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("169.254.255.255"))
    }

    @Test
    fun `public IPs - should NOT be private`() {
        assertFalse(PresenceHttpServer.isPrivateNetwork("8.8.8.8"))
        assertFalse(PresenceHttpServer.isPrivateNetwork("1.1.1.1"))
        assertFalse(PresenceHttpServer.isPrivateNetwork("203.0.113.1"))
        assertFalse(PresenceHttpServer.isPrivateNetwork("100.64.0.1")) // CGNAT, not site-local
    }

    @Test
    fun `null and blank - should NOT be private`() {
        assertFalse(PresenceHttpServer.isPrivateNetwork(null))
        assertFalse(PresenceHttpServer.isPrivateNetwork(""))
        assertFalse(PresenceHttpServer.isPrivateNetwork("   "))
    }

    @Test
    fun `invalid format - should NOT be private`() {
        assertFalse(PresenceHttpServer.isPrivateNetwork("not-an-ip"))
        assertFalse(PresenceHttpServer.isPrivateNetwork("999.999.999.999"))
    }

    // ==================== buildHealthzJson ====================

    @Test
    fun `buildHealthzJson - normal values`() {
        val json = PresenceHttpServer.buildHealthzJson("myapp", "1.2.3", 1700000000000L)
        assertEquals("""{"app":"myapp","version":"1.2.3","ts":1700000000000}""", json)
    }

    @Test
    fun `buildHealthzJson - empty strings`() {
        val json = PresenceHttpServer.buildHealthzJson("", "", 0L)
        assertEquals("""{"app":"","version":"","ts":0}""", json)
    }

    @Test
    fun `buildHealthzJson - escapes double quotes`() {
        val json = PresenceHttpServer.buildHealthzJson("""my"app""", """1.0-"beta"""", 1L)
        assertEquals("""{"app":"my\"app","version":"1.0-\"beta\"","ts":1}""", json)
    }

    @Test
    fun `buildHealthzJson - escapes backslash`() {
        val json = PresenceHttpServer.buildHealthzJson("""my\app""", """1\0""", 2L)
        assertEquals("""{"app":"my\\app","version":"1\\0","ts":2}""", json)
    }

    @Test
    fun `buildHealthzJson - contains valid JSON fields`() {
        val json = PresenceHttpServer.buildHealthzJson("agentpost", "2.0.0", 1700000000000L)
        // 验证能被简单解析（字段存在且顺序正确）
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"app\":\"agentpost\""))
        assertTrue(json.contains("\"version\":\"2.0.0\""))
        assertTrue(json.contains("\"ts\":1700000000000"))
    }
}
