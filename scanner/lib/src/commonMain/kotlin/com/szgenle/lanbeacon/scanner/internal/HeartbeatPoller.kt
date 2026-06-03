package com.szgenle.lanbeacon.scanner.internal

import com.szgenle.lanbeacon.scanner.DeviceInfo
import com.szgenle.lanbeacon.scanner.ScannerConfig
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * 心跳轮询器：定时探测已发现设备，计数 miss，触发 present/lost 状态转换。
 */
internal class HeartbeatPoller(private val config: ScannerConfig) {

    sealed class Result {
        data class Success(val device: DeviceInfo) : Result()
        data object Lost : Result()
    }

    private var client: HttpClient? = null

    private fun getClient(): HttpClient {
        return client ?: HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = config.heartbeatInterval.inWholeMilliseconds
                connectTimeoutMillis = 3000L
            }
        }.also { client = it }
    }

    /**
     * 开始对指定设备进行心跳轮询。
     *
     * 返回一个 Flow，每次心跳产出 [Result.Success] 或 [Result.Lost]。
     * Lost 只产出一次，之后 flow 结束。
     */
    fun poll(ip: String, port: Int): Flow<Result> = flow {
        var missCount = 0
        val httpClient = getClient()
        val url = SubnetScanner.buildUrl(ip, port)

        while (true) {
            delay(config.heartbeatInterval)

            val device = probeOnce(httpClient, url, ip, port)
            if (device != null) {
                missCount = 0
                emit(Result.Success(device))
            } else {
                missCount++
                if (missCount >= config.maxMissCount) {
                    emit(Result.Lost)
                    return@flow
                }
            }
        }
    }

    private suspend fun probeOnce(client: HttpClient, url: String, ip: String, port: Int): DeviceInfo? {
        return try {
            val response = client.get(url) {
                config.token?.let { token ->
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            if (response.status != HttpStatusCode.OK) return null

            val body = response.bodyAsText()
            parseHealthzResponse(body, ip, port)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHealthzResponse(json: String, ip: String, port: Int): DeviceInfo? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val app = obj["app"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: return null

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
}
