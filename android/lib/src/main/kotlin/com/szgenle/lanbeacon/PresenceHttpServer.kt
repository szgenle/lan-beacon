package com.szgenle.lanbeacon

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.InetAddress
import java.security.MessageDigest

/**
 * 局域网在场广播 HTTP 服务。
 *
 * 极简实现：仅暴露 `GET /v1/healthz` 端点，供桌面端周期探测设备是否在同一局域网。
 * 安全策略：拒绝非 RFC 1918 私有网段来源的请求（返回 403）。
 *
 * 生命周期由 [LanPresenceManager] 管理，不要直接 start/stop。
 *
 * @param port HTTP 监听端口
 * @param appName healthz JSON 中的 `app` 字段值（应用标识）
 * @param appVersion healthz JSON 中的 `version` 字段值
 * @param token 可选的 Bearer Token，非 null 时启用鉴权
 * @param metadata 可选的元数据键值对，非空时写入 healthz JSON 的 `meta` 字段
 */
class PresenceHttpServer(
    port: Int,
    private val appName: String,
    private val appVersion: String,
    private val token: String? = null,
    private val metadata: Map<String, String> = emptyMap(),
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        // 安全过滤：仅允许 RFC1918 / link-local 来源
        val remoteIp = session.remoteIpAddress
        if (!isPrivateNetwork(remoteIp)) {
            Log.w(TAG, "Rejected non-RFC1918 request from $remoteIp")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Forbidden",
            )
        }

        // Token 鉴权：配置了 token 时要求请求携带匹配的 Authorization 头
        if (token != null) {
            val authHeader = session.headers["authorization"] // NanoHTTPD headers are lowercase
            val expected = "Bearer $token"
            if (!timeSafeEquals(expected, authHeader)) {
                Log.w(TAG, "Rejected request with invalid/missing token from $remoteIp")
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_PLAINTEXT,
                    "Unauthorized",
                )
            }
        }

        // 路由：仅 GET /v1/healthz
        if (session.method == Method.GET && session.uri == "/v1/healthz") {
            val json = buildHealthzJson(appName, appVersion, System.currentTimeMillis(), metadata)
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json,
            )
        }

        // 其他路径一律 404
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not Found",
        )
    }

    companion object {
        private const val TAG = "PresenceHttpServer"

        /**
         * 构造 `/v1/healthz` 的 JSON 响应体。
         *
         * 仅做转义最小化处理：因为 [appName] / [appVersion] 由集成方在 [BeaconConfig] 中传入，
         * 双引号和反斜杠会被转义，避免破坏 JSON 结构。
         *
         * 输出 schema：
         * ```
         * {"app":"<appName>","version":"<appVersion>","ts":<unixMillis>}
         * {"app":"<appName>","version":"<appVersion>","ts":<unixMillis>,"meta":{...}}
         * ```
         */
        internal fun buildHealthzJson(
            appName: String,
            appVersion: String,
            ts: Long,
            metadata: Map<String, String> = emptyMap(),
        ): String {
            val base = """"app":"${escape(appName)}","version":"${escape(appVersion)}","ts":$ts"""
            if (metadata.isEmpty()) {
                return "{$base}"
            }
            val metaEntries = metadata.entries.joinToString(",") { (k, v) ->
                "\"${escape(k)}\":\"${escape(v)}\""
            }
            return "{$base,\"meta\":{$metaEntries}}"
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        /**
         * 判断 IP 是否属于 RFC1918 / link-local 私有网段：
         * - 10.0.0.0/8
         * - 172.16.0.0/12
         * - 192.168.0.0/16
         * - 169.254.0.0/16（link-local）
         * - 127.0.0.0/8（loopback，方便本机调试）
         */
        fun isPrivateNetwork(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            return try {
                val addr = InetAddress.getByName(ip)
                addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 常量时间字符串比较，防止时序侧信道攻击。
         *
         * 无论字符串是否匹配、以及不匹配的位置在哪，执行时间恒定。
         */
        internal fun timeSafeEquals(expected: String, actual: String?): Boolean {
            if (actual == null) return false
            val expectedBytes = expected.toByteArray(Charsets.UTF_8)
            val actualBytes = actual.toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(expectedBytes, actualBytes)
        }
    }
}
