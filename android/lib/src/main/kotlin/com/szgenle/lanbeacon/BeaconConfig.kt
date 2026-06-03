package com.szgenle.lanbeacon

/**
 * 局域网在场广播的全部配置参数。
 *
 * **所有字段均无默认值**——强制集成方显式传入，避免遗漏关键身份信息后
 * 静默走兜底值、上线排查半天的问题。
 *
 * 使用示例：
 * ```kotlin
 * val config = BeaconConfig(
 *     port = 47821,
 *     appName = "agentpost",
 *     appVersion = BuildConfig.VERSION_NAME,
 *     serviceType = "_agentpost._tcp.",
 *     serviceName = "agentpost-beacon",
 * )
 * lanPresenceManager.start(config)
 * ```
 */
data class BeaconConfig(
    /**
     * HTTP 监听端口。
     *
     * 建议使用 IANA 未注册的高端口（如 47821），避免与常见服务冲突。
     */
    val port: Int,

    /**
     * 应用标识，写入 `/v1/healthz` JSON 响应的 `app` 字段。
     *
     * 桌面端据此区分同一局域网内不同 App 的 beacon。
     */
    val appName: String,

    /**
     * 应用版本号，写入 `/v1/healthz` JSON 响应的 `version` 字段。
     *
     * 方便桌面端做版本兼容判断。
     */
    val appVersion: String,

    /**
     * mDNS 服务类型，格式为 `_<protocol>._tcp.`（末尾带点）。
     *
     * 每个集成应用应使用独立的 serviceType 以避免与其他 beacon 混淆。
     * 例如：`_agentpost._tcp.`、`_myapp._tcp.`
     */
    val serviceType: String,

    /**
     * mDNS 服务实例名（用户可见的名称）。
     *
     * 同一局域网内同一 serviceType 下需唯一；Android NSD 会自动在冲突时追加编号。
     */
    val serviceName: String,

    /**
     * 可选的 Bearer Token 共享密钥。
     *
     * 非 null 时启用鉴权：Beacon 端要求 Scanner 请求携带 `Authorization: Bearer <token>` 头，
     * 缺少或不匹配时返回 401。
     *
     * 为 null（默认）时跳过验证，行为与 v0.1 一致。
     */
    val token: String? = null,

    /**
     * 可选的元数据键值对。
     *
     * 写入 mDNS TXT 记录和 `/v1/healthz` JSON 响应的 `meta` 字段。
     * 键名应为 ASCII 小写，建议不超过 9 字节（参考 RFC 6763 §6.4）。
     *
     * 常见用法：
     * - `"name"` → 设备名称（如 "My Phone"）
     * - `"cap"` → 能力声明（如 "sync,file"）
     *
     * 为空（默认）时不写入任何自定义 TXT 属性，`meta` 字段也不会出现在 JSON 响应中。
     */
    val metadata: Map<String, String> = emptyMap(),
)
