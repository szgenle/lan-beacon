package com.szgenle.lanbeacon

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * 局域网在场广播 HTTP 服务。
 *
 * 内置 `GET /v1/healthz` 端点，供桌面端周期探测设备是否在同一局域网。
 * 支持可插拔路由：集成方可通过 [BeaconConfig.routes] 注册自定义端点。
 * 安全策略：拒绝非私有网段来源的请求（返回 403）。支持 IPv4 RFC1918 及 IPv6 ULA/link-local。
 *
 * 路由匹配优先级：安全检查 → 内置 healthz → 自定义路由 → 404
 *
 * 生命周期由 [LanPresenceManager] 管理，不要直接 start/stop。
 *
 * @param port HTTP 监听端口
 * @param appName healthz JSON 中的 `app` 字段值（应用标识）
 * @param appVersion healthz JSON 中的 `version` 字段值
 * @param token 可选的 Bearer Token，非 null 时启用鉴权
 * @param metadata 可选的元数据键值对，非空时写入 healthz JSON 的 `meta` 字段
 * @param routes 自定义路由列表，自动继承安全策略
 */
class PresenceHttpServer(
    port: Int,
    private val appName: String,
    private val appVersion: String,
    private val token: String? = null,
    private val metadata: Map<String, String> = emptyMap(),
    private val routes: List<Route> = emptyList(),
) : NanoHTTPD("::", port) {
    // 绑定 IPv6 wildcard（::）实现双栈监听：Android/Linux 上同时接受 IPv4 和 IPv6 连接。

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

        // 路由：内置 GET /v1/healthz（优先匹配）
        if (session.method == Method.GET && session.uri == "/v1/healthz") {
            val json = buildHealthzJson(appName, appVersion, System.currentTimeMillis(), metadata)
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json,
            )
        }

        // 路由：自定义端点
        val matchedRoute = routes.find { route ->
            route.method.equals(session.method.name, ignoreCase = true) && route.path == session.uri
        }
        if (matchedRoute != null) {
            return dispatchCustomRoute(matchedRoute, session)
        }

        // 其他路径一律 404
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not Found",
        )
    }

    /**
     * 将 NanoHTTPD session 转为 [RouteRequest]，调用自定义 handler，再转回 NanoHTTPD Response。
     */
    private fun dispatchCustomRoute(route: Route, session: IHTTPSession): Response {
        return try {
            // 读取请求体（POST/PUT 等）
            val body = if (session.method != Method.GET && session.method != Method.HEAD) {
                val bodyMap = mutableMapOf<String, String>()
                session.parseBody(bodyMap)
                bodyMap["postData"]
            } else {
                null
            }

            val request = RouteRequest(
                uri = session.uri,
                method = session.method.name,
                headers = session.headers,
                queryParams = session.parameters?.mapValues { (_, v) -> v.firstOrNull() ?: "" } ?: emptyMap(),
                body = body,
            )

            val routeResponse = route.handler(request)

            newFixedLengthResponse(
                Response.Status.lookup(routeResponse.status) ?: Response.Status.OK,
                routeResponse.mimeType,
                routeResponse.body,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Custom route error: ${route.method} ${route.path}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Internal Server Error",
            )
        }
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
         * 判断 IP 是否属于私有/本地网段，同时支持 IPv4 与 IPv6：
         *
         * IPv4:
         * - 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16（RFC1918）
         * - 169.254.0.0/16（link-local）
         * - 127.0.0.0/8（loopback）
         *
         * IPv6:
         * - fc00::/7（Unique Local Address, ULA）
         * - fe80::/10（link-local）
         * - ::1（loopback）
         *
         * 注：Java 的 [InetAddress.isSiteLocalAddress] 对 IPv6 仅识别已废弃的 fec0::/10，
         * 不覆盖现代 ULA (fc00::/7)，因此需要 [isUniqueLocalAddress] 额外判断。
         */
        fun isPrivateNetwork(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            return try {
                val addr = InetAddress.getByName(ip)
                addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
                    || isUniqueLocalAddress(addr)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 判断是否为 IPv6 Unique Local Address (fc00::/7)。
         * 实践中几乎只使用 fd00::/8 前缀（随机生成的 ULA）。
         */
        private fun isUniqueLocalAddress(addr: InetAddress): Boolean {
            if (addr is Inet6Address) {
                val firstByte = addr.address[0].toInt() and 0xFF
                return firstByte == 0xFC || firstByte == 0xFD
            }
            return false
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
