package com.szgenle.lanbeacon.scanner.internal

import com.szgenle.lanbeacon.scanner.DeviceInfo
import com.szgenle.lanbeacon.scanner.ScannerConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/**
 * 子网扫描器：遍历本机 /24 子网所有 IP，并发探测 HTTP 端点。
 */
internal class SubnetScanner(private val config: ScannerConfig) {

    private var client: HttpClient? = null

    private fun getClient(): HttpClient {
        return client ?: HttpClient {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = config.scanTimeout.inWholeMilliseconds
                connectTimeoutMillis = config.scanTimeout.inWholeMilliseconds
            }
        }.also { client = it }
    }

    /**
     * 执行一轮子网扫描。
     *
     * @return 首个匹配的设备信息，未找到返回 null
     */
    suspend fun scan(): DeviceInfo? {
        val prefixes = getLocalSubnetPrefixes()
        if (prefixes.isEmpty()) return null

        // 生成所有候选 IP
        val candidates = prefixes.flatMap { prefix ->
            (1..254).map { "$prefix$it" }
        }

        val semaphore = Semaphore(config.scanConcurrency)
        val httpClient = getClient()

        return coroutineScope {
            val deferred = candidates.map { ip ->
                async {
                    semaphore.withPermit {
                        probeHost(httpClient, ip)
                    }
                }
            }

            // 逐个检查结果，找到第一个非 null 就取消剩余
            var found: DeviceInfo? = null
            for (d in deferred) {
                if (found != null) {
                    d.cancel()
                    continue
                }
                val result = try {
                    d.await()
                } catch (_: Exception) {
                    null
                }
                if (result != null) {
                    found = result
                    // 取消剩余任务
                    deferred.forEach { it.cancel() }
                }
            }
            found
        }
    }

    private suspend fun probeHost(client: HttpClient, ip: String): DeviceInfo? {
        return try {
            val url = buildUrl(ip, config.port)
            val response = client.get(url) {
                config.token?.let { token ->
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            if (response.status != HttpStatusCode.OK) return null

            val body = response.bodyAsText()
            parseHealthzResponse(body, ip, config.port)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHealthzResponse(json: String, ip: String, port: Int): DeviceInfo? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val app = obj["app"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: return null

            // 匹配 targetApp（为空则接受所有）
            if (config.targetApp.isNotEmpty() && app != config.targetApp) return null

            val ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: 0L
            val meta = obj["meta"]?.jsonObject?.let { metaObj ->
                metaObj.entries.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
            } ?: emptyMap()

            DeviceInfo(ip = ip, port = port, app = app, version = version, ts = ts, meta = meta)
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client?.close()
        client = null
    }

    companion object {
        /**
         * 构建 healthz URL，IPv6 地址用方括号包裹。
         */
        internal fun buildUrl(ip: String, port: Int): String {
            return if (":" in ip) {
                "http://[$ip]:$port/v1/healthz"
            } else {
                "http://$ip:$port/v1/healthz"
            }
        }
    }
}
