package com.szgenle.lanbeacon.scanner

import kotlinx.serialization.Serializable

/**
 * 发现的设备信息。
 *
 * 对应 Beacon 端 `/v1/healthz` 响应的解析结果。
 *
 * @param ip 设备 IP 地址（IPv4 或 IPv6）
 * @param port 设备 HTTP 端口
 * @param app 应用标识
 * @param version 应用版本号
 * @param ts 响应时刻的 Unix 毫秒时间戳
 * @param meta 元数据键值对（可选）
 */
@Serializable
data class DeviceInfo(
    val ip: String,
    val port: Int,
    val app: String,
    val version: String,
    val ts: Long = 0L,
    val meta: Map<String, String> = emptyMap(),
)
