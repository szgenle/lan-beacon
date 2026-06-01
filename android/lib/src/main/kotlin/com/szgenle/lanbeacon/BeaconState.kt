package com.szgenle.lanbeacon

/**
 * 局域网在场广播的运行状态。
 *
 * 集成方通过 [LanPresenceManager.state] 观察状态变化，典型用途：
 * - 在 UI 上显示连接状态图标
 * - 发生错误时弹提示或记日志
 * - 网络断开时提醒用户检查 WiFi
 *
 * 状态流转：
 * ```
 * Idle ──start()──► Starting ──► Running(ip)
 *                       │              │
 *                       ▼              ▼
 *                   Error(e)     NetworkLost ──WiFi恢复──► Running(newIp)
 *                                     │
 *                              连续失败──► Error(e)
 *
 * 任意状态 ──stop()──► Idle
 * ```
 */
sealed class BeaconState {

    /** 未启动 / 已停止。 */
    data object Idle : BeaconState()

    /** 正在启动中（HTTP server + mDNS 注册），尚未完成。 */
    data object Starting : BeaconState()

    /**
     * 正常运行中。
     *
     * @property lanIp 当前设备的 LAN IPv4 地址
     * @property port HTTP 监听端口
     */
    data class Running(
        val lanIp: String,
        val port: Int,
    ) : BeaconState()

    /**
     * WiFi 网络断开，beacon 暂停广播，等待网络恢复后自动重连。
     *
     * HTTP server 保持存活（绑定 0.0.0.0），mDNS 已注销。
     */
    data object NetworkLost : BeaconState()

    /**
     * 发生错误，beacon 无法正常工作。
     *
     * @property message 人类可读的错误描述
     * @property cause 原始异常（可能为 null）
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : BeaconState()
}
