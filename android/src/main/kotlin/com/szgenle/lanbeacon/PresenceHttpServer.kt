package com.szgenle.lanbeacon

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.InetAddress

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
 */
class PresenceHttpServer(
    port: Int,
    private val appName: String,
    private val appVersion: String,
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

        // 路由：仅 GET /v1/healthz
        if (session.method == Method.GET && session.uri == "/v1/healthz") {
            val json = """{"app":"$appName","version":"$appVersion","ts":${System.currentTimeMillis()}}"""
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
    }
}
