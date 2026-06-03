package com.szgenle.lanbeacon

/**
 * 自定义 HTTP 路由定义。
 *
 * 集成方通过 [BeaconConfig.routes] 注册自定义端点，所有自定义路由自动继承
 * 库内置的安全策略（RFC1918 IP 过滤 + Token 鉴权）。
 *
 * 使用示例：
 * ```kotlin
 * val pingRoute = Route(
 *     method = "GET",
 *     path = "/v1/ping",
 *     handler = { request -> RouteResponse(body = """{"pong":true}""") },
 * )
 * ```
 *
 * @param method HTTP 方法，大写（如 "GET"、"POST"）
 * @param path 端点路径，必须以 "/" 开头（如 "/v1/ping"）
 * @param handler 请求处理函数，接收 [RouteRequest] 返回 [RouteResponse]
 */
data class Route(
    val method: String,
    val path: String,
    val handler: (request: RouteRequest) -> RouteResponse,
)

/**
 * 自定义路由的请求封装。
 *
 * 库内部将 NanoHTTPD 的 session 转换为此类型传给集成方，避免暴露底层实现细节。
 *
 * @param uri 请求路径（如 "/v1/ping"）
 * @param method HTTP 方法（如 "GET"）
 * @param headers 请求头（键为小写）
 * @param queryParams URL 查询参数
 * @param body 请求体（GET 请求通常为 null）
 */
data class RouteRequest(
    val uri: String,
    val method: String,
    val headers: Map<String, String>,
    val queryParams: Map<String, String>,
    val body: String?,
)

/**
 * 自定义路由的响应封装。
 *
 * @param status HTTP 状态码，默认 200
 * @param mimeType 响应 Content-Type，默认 "application/json"
 * @param body 响应体
 */
data class RouteResponse(
    val status: Int = 200,
    val mimeType: String = "application/json",
    val body: String,
)
