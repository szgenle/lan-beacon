package com.szgenle.lanbeacon.scanner

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Scanner 配置参数。
 *
 * 仅 [port] 为必填，其余均有合理默认值。
 *
 * @param port 目标 Beacon HTTP 监听端口（必须与 Beacon 端一致）
 * @param targetApp 匹配 healthz 响应的 `app` 字段；为空则接受任何合法 beacon
 * @param token Bearer Token 共享密钥；null 时不发送鉴权头
 * @param heartbeatInterval 心跳轮询间隔
 * @param maxMissCount 连续心跳失败次数阈值，超过则判定离场
 * @param scanInterval 子网扫描重试间隔（设备未在场时周期性重扫）
 * @param scanConcurrency 子网扫描并发请求数
 * @param scanTimeout 扫描探测单次 HTTP 超时
 */
data class ScannerConfig(
    val port: Int,
    val targetApp: String = "",
    val token: String? = null,
    val heartbeatInterval: Duration = 5.seconds,
    val maxMissCount: Int = 3,
    val scanInterval: Duration = 30.seconds,
    val scanConcurrency: Int = 32,
    val scanTimeout: Duration = 1500.milliseconds,
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535, got $port" }
        require(maxMissCount >= 1) { "maxMissCount must be >= 1, got $maxMissCount" }
        require(scanConcurrency >= 1) { "scanConcurrency must be >= 1, got $scanConcurrency" }
    }
}
