package com.szgenle.lanbeacon.scanner

import com.szgenle.lanbeacon.scanner.internal.HeartbeatPoller
import com.szgenle.lanbeacon.scanner.internal.SubnetScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 局域网 Beacon 设备发现器。
 *
 * 通过子网 HTTP 扫描发现运行 beacon 的设备，发现后定时 HTTP 轮询确认在场。
 *
 * 使用示例：
 * ```kotlin
 * val scanner = LanBeaconScanner(ScannerConfig(port = 47821, targetApp = "agentpost"))
 * scanner.start()
 *
 * scanner.state.collect { state ->
 *     when (state) {
 *         is ScannerState.Present -> println("Found: ${state.device.ip}")
 *         is ScannerState.Lost -> println("Device lost")
 *         else -> {}
 *     }
 * }
 * ```
 *
 * @param config Scanner 配置参数
 * @param scope 协程作用域，默认使用 [Dispatchers.Default]
 */
class LanBeaconScanner(
    private val config: ScannerConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)

    /** 当前运行状态。 */
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var heartbeatJob: Job? = null

    private val subnetScanner = SubnetScanner(config)
    private val heartbeatPoller = HeartbeatPoller(config)

    /**
     * 启动扫描。如果当前已在运行则忽略。
     */
    fun start() {
        if (_state.value != ScannerState.Idle) return
        startScanning()
    }

    /**
     * 停止扫描，释放资源。状态回到 [ScannerState.Idle]。
     */
    fun stop() {
        scanJob?.cancel()
        heartbeatJob?.cancel()
        scanJob = null
        heartbeatJob = null
        subnetScanner.close()
        heartbeatPoller.close()
        _state.value = ScannerState.Idle
    }

    /**
     * 强制立即启动一次子网扫描（中断当前心跳）。
     */
    fun scanNow() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        startScanning()
    }

    /**
     * 手动指定目标设备（跳过扫描发现阶段）。
     *
     * 适用于用户已知 IP 的场景。支持 IPv4 和 IPv6 地址。
     *
     * @param ip 目标设备 IP
     * @param port 目标端口，默认使用 config 中的 port
     */
    fun setTarget(ip: String, port: Int = config.port) {
        scanJob?.cancel()
        scanJob = null
        startHeartbeat(ip, port)
    }

    private fun startScanning() {
        scanJob?.cancel()
        _state.value = ScannerState.Scanning

        scanJob = scope.launch {
            while (isActive) {
                val device = subnetScanner.scan()
                if (device != null) {
                    _state.value = ScannerState.Present(device)
                    startHeartbeat(device.ip, device.port)
                    return@launch
                }
                // 未找到，等待 scanInterval 后重试
                delay(config.scanInterval)
            }
        }
    }

    private fun startHeartbeat(ip: String, port: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            heartbeatPoller.poll(ip, port).collect { result ->
                when (result) {
                    is HeartbeatPoller.Result.Success -> {
                        _state.value = ScannerState.Present(result.device)
                    }
                    is HeartbeatPoller.Result.Lost -> {
                        _state.value = ScannerState.Lost
                        // 短暂停留在 Lost 状态后自动重扫
                        delay(500)
                        startScanning()
                    }
                }
            }
        }
    }
}
