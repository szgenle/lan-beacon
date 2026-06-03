package com.szgenle.lanbeacon.scanner

/**
 * Scanner 运行状态。
 *
 * 集成方通过 [LanBeaconScanner.state] 观察状态变化。
 *
 * 状态流转：
 * ```
 *  [Idle] ──start()──► [Scanning]
 *                          │
 *           healthz 200 命中
 *                          ▼
 *                      [Present(device)]
 *                          │
 *           连续 maxMissCount 次失败
 *                          ▼
 *                       [Lost] ──自动重扫──► [Scanning]
 *
 *  任意状态 ──stop()──► [Idle]
 * ```
 */
sealed class ScannerState {

    /** 未启动 / 已停止。 */
    data object Idle : ScannerState()

    /** 正在进行子网扫描，尚未发现目标设备。 */
    data object Scanning : ScannerState()

    /**
     * 设备在场。
     *
     * @property device 已发现的设备信息
     */
    data class Present(val device: DeviceInfo) : ScannerState()

    /** 设备离场（连续心跳失败超过阈值）。自动回到 [Scanning]。 */
    data object Lost : ScannerState()
}
