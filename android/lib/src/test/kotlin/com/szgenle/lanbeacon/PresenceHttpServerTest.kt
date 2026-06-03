package com.szgenle.lanbeacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PresenceHttpServer] 单元测试。
 *
 * 覆盖纯函数：
 * - [PresenceHttpServer.isPrivateNetwork]：RFC1918 / link-local / loopback 判定
 * - [PresenceHttpServer.buildHealthzJson]：healthz JSON 格式 + 转义
 * - [PresenceHttpServer.timeSafeEquals]：常量时间字符串比较
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

    // ==================== isPrivateNetwork IPv6 ====================

    @Test
    fun `IPv6 ULA fd prefix - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("fd12::1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("fd00::1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("fdff:abcd:1234::1"))
    }

    @Test
    fun `IPv6 ULA fc prefix - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("fc00::1"))
    }

    @Test
    fun `IPv6 link-local fe80 - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("fe80::1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("fe80::abcd:1234:5678:9abc"))
    }

    @Test
    fun `IPv6 loopback - should be private`() {
        assertTrue(PresenceHttpServer.isPrivateNetwork("::1"))
    }

    @Test
    fun `IPv6 global unicast - should NOT be private`() {
        assertFalse(PresenceHttpServer.isPrivateNetwork("2001:db8::1"))
        assertFalse(PresenceHttpServer.isPrivateNetwork("2400:cb00:2048:1::6814:155"))
    }

    @Test
    fun `IPv4-mapped IPv6 private - should be private`() {
        // IPv4-mapped IPv6 addresses: Java resolves these to Inet4Address
        assertTrue(PresenceHttpServer.isPrivateNetwork("::ffff:192.168.1.1"))
        assertTrue(PresenceHttpServer.isPrivateNetwork("::ffff:10.0.0.1"))
    }

    @Test
    fun `IPv4-mapped IPv6 public - should NOT be private`() {
        assertFalse(PresenceHttpServer.isPrivateNetwork("::ffff:8.8.8.8"))
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

    // ==================== buildHealthzJson with metadata ====================

    @Test
    fun `buildHealthzJson - empty metadata omits meta field`() {
        val json = PresenceHttpServer.buildHealthzJson("app", "1.0", 100L, emptyMap())
        assertFalse(json.contains("meta"))
        assertEquals("""{"app":"app","version":"1.0","ts":100}""", json)
    }

    @Test
    fun `buildHealthzJson - metadata produces meta object`() {
        val meta = mapOf("name" to "My Phone", "cap" to "sync")
        val json = PresenceHttpServer.buildHealthzJson("app", "1.0", 100L, meta)
        assertTrue(json.contains("\"meta\":"))
        assertTrue(json.contains("\"name\":\"My Phone\""))
        assertTrue(json.contains("\"cap\":\"sync\""))
    }

    @Test
    fun `buildHealthzJson - metadata escapes special characters`() {
        val meta = mapOf("desc" to "has\"quote")
        val json = PresenceHttpServer.buildHealthzJson("app", "1.0", 1L, meta)
        assertTrue(json.contains("\"desc\":\"has\\\"quote\""))
    }

    // ==================== timeSafeEquals ====================

    @Test
    fun `timeSafeEquals - matching strings`() {
        assertTrue(PresenceHttpServer.timeSafeEquals("Bearer my-secret", "Bearer my-secret"))
    }

    @Test
    fun `timeSafeEquals - mismatched strings`() {
        assertFalse(PresenceHttpServer.timeSafeEquals("Bearer correct", "Bearer wrong"))
    }

    @Test
    fun `timeSafeEquals - null actual`() {
        assertFalse(PresenceHttpServer.timeSafeEquals("Bearer token", null))
    }

    @Test
    fun `timeSafeEquals - empty strings`() {
        assertTrue(PresenceHttpServer.timeSafeEquals("", ""))
    }

    @Test
    fun `timeSafeEquals - different lengths`() {
        assertFalse(PresenceHttpServer.timeSafeEquals("short", "much longer string"))
    }
}
