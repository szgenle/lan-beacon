package com.szgenle.lanbeacon

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * 局域网在场广播管理器。
 *
 * 整合三个子组件的生命周期：
 * 1. [PresenceHttpServer]：迷你 HTTP 服务，响应 `/v1/healthz`
 * 2. mDNS 服务注册：让桌面端自动发现设备 IP
 * 3. 网络变化监听（ConnectivityManager.NetworkCallback）：WiFi 切换时自动重绑
 *
 * 集成方在前台 Service 中持有本类实例，按开关切换 [start] / [stop]。
 *
 * @see BeaconConfig 全部配置参数（无默认值，强制显式传入）
 */
class LanPresenceManager(private val context: Context) {

    private var server: PresenceHttpServer? = null
    private var config: BeaconConfig? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _state = MutableStateFlow<BeaconState>(BeaconState.Idle)
    /**
     * 当前运行状态，集成方观察此 Flow 即可得知 beacon 的实时情况。
     *
     * @see BeaconState
     */
    val state: StateFlow<BeaconState> = _state.asStateFlow()

    /**
     * 当前设备的 WiFi LAN IPv4 地址，null 表示未连接或无法获取。
     * 这是 [state] 的便捷投影：`Running` 状态时有值，其他状态为 null。
     */
    val currentLanIp: String?
        get() = (_state.value as? BeaconState.Running)?.lanIp

    /**
     * 当前设备的 WiFi LAN IPv6 地址（ULA 或 link-local），null 表示无 IPv6。
     * 这是 [state] 的便捷投影：`Running` 状态时可能有值。
     */
    val currentLanIpv6: String?
        get() = (_state.value as? BeaconState.Running)?.lanIpv6

    /** 是否正在运行（便捷属性，等价于 `state.value is Running`）。 */
    val isRunning: Boolean
        get() = _state.value is BeaconState.Running

    /**
     * 启动 HTTP 服务 + mDNS 注册 + 网络监听。
     *
     * @param config 全部配置参数，无默认值，集成方必须显式构造。
     * @see BeaconConfig
     */
    fun start(config: BeaconConfig) {
        if (_state.value !is BeaconState.Idle) {
            Log.w(TAG, "not idle (current=${_state.value}), skip start")
            return
        }
        this.config = config
        _state.value = BeaconState.Starting
        Log.i(TAG, "starting LAN presence on port=${config.port}")

        // 1. 获取当前 LAN IP
        val ip = detectLanIp()
        val ipv6 = detectLanIpv6()
        if (ip == null) {
            Log.w(TAG, "no LAN IP detected, will wait for network callback")
        }
        if (ipv6 != null) {
            Log.i(TAG, "IPv6 address detected: $ipv6")
        }

        // 2. 启动 HTTP server
        try {
            server = PresenceHttpServer(config.port, config.appName, config.appVersion, config.token, config.metadata, config.routes).also { it.start() }
            Log.i(TAG, "HTTP server started on port=${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            _state.value = BeaconState.Error("HTTP server start failed: ${e.message}", e)
            return
        }

        // 3. 注册 mDNS
        registerMdns()

        // 4. 获取 WiFi Lock（防止息屏后 WiFi 断开）
        acquireWifiLock()

        // 5. 注册网络变化回调
        registerNetworkCallback()

        // 6. 设置最终状态
        if (ip != null) {
            _state.value = BeaconState.Running(lanIp = ip, lanIpv6 = ipv6, port = config.port)
        } else {
            _state.value = BeaconState.NetworkLost
        }
    }

    /** 停止所有子组件，释放资源。可在任何状态下调用。 */
    fun stop() {
        Log.i(TAG, "stopping LAN presence")

        // 1. 注销网络回调
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null

        // 2. 注销 mDNS
        unregisterMdns()

        // 3. 停止 HTTP server
        runCatching { server?.stop() }
        server = null

        // 4. 释放 WiFi Lock
        releaseWifiLock()

        _state.value = BeaconState.Idle
    }

    // ==================== 内部实现 ====================

    private fun detectLanIp(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return extractIpv4(linkProps)
    }

    private fun detectLanIpv6(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return extractIpv6(linkProps)
    }

    private fun extractIpv4(linkProperties: LinkProperties): String? {
        return linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    /**
     * 提取首个可用的 IPv6 地址，优先级：ULA (fd00::/8) > link-local (fe80::/10)。
     * 返回纯地址字符串（不含 scope-id 后缀）。
     */
    private fun extractIpv6(linkProperties: LinkProperties): String? {
        val ipv6Addrs = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet6Address>()
            .filter { !it.isLoopbackAddress }

        // 优先选 ULA (fc00::/7)
        val ula = ipv6Addrs.firstOrNull {
            val first = it.address[0].toInt() and 0xFF
            first == 0xFD || first == 0xFC
        }
        if (ula != null) return ula.hostAddress?.substringBefore('%')

        // 其次选 link-local (fe80::/10)
        val linkLocal = ipv6Addrs.firstOrNull { it.isLinkLocalAddress }
        return linkLocal?.hostAddress?.substringBefore('%')
    }

    private fun registerMdns() {
        val cfg = config ?: return
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = cfg.serviceName
                serviceType = cfg.serviceType
                setPort(cfg.port)
                // TXT records: protocol version + custom metadata
                setAttribute("v", "1")
                for ((key, value) in cfg.metadata) {
                    setAttribute(key, value)
                }
            }
            nsdManager = (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also { nsd ->
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {
                        Log.i(TAG, "mDNS registered: ${info.serviceName}")
                    }

                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS registration failed: errorCode=$errorCode")
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo) {
                        Log.i(TAG, "mDNS unregistered: ${info.serviceName}")
                    }

                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS unregistration failed: errorCode=$errorCode")
                    }
                }
                registrationListener = listener
                nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration error", e)
        }
    }

    private fun unregisterMdns() {
        registrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null
        nsdManager = null
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val newIp = extractIpv4(linkProperties)
                val newIpv6 = extractIpv6(linkProperties)
                val currentState = _state.value
                val oldIp = (currentState as? BeaconState.Running)?.lanIp
                val oldIpv6 = (currentState as? BeaconState.Running)?.lanIpv6
                if (newIp != null && (newIp != oldIp || newIpv6 != oldIpv6)) {
                    Log.i(TAG, "WiFi IP changed: $oldIp -> $newIp (v6: $oldIpv6 -> $newIpv6), rebinding")
                    rebindServer()
                    _state.value = BeaconState.Running(lanIp = newIp, lanIpv6 = newIpv6, port = config!!.port)
                } else if (newIp != null && currentState is BeaconState.NetworkLost) {
                    // 从 NetworkLost 恢复
                    Log.i(TAG, "WiFi recovered: ip=$newIp, ipv6=$newIpv6")
                    rebindServer()
                    _state.value = BeaconState.Running(lanIp = newIp, lanIpv6 = newIpv6, port = config!!.port)
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "WiFi network lost")
                _state.value = BeaconState.NetworkLost
                unregisterMdns()
                // server 保持运行（绑定 0.0.0.0）：WiFi 恢复后会自动触发 onLinkPropertiesChanged
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    /**
     * WiFi 切换后重启 HTTP server + 重注册 mDNS。
     * NanoHTTPD 绑定的是 ::(双栈 wildcard，handler 层做来源过滤），所以其实只需要重注册 mDNS 即可。
     * 但为安全起见，完整重启一遍。
     */
    private fun rebindServer() {
        val cfg = config ?: return
        // 停旧
        runCatching { server?.stop() }
        server = null
        unregisterMdns()

        // 起新
        try {
            server = PresenceHttpServer(cfg.port, cfg.appName, cfg.appVersion, cfg.token, cfg.metadata, cfg.routes).also { it.start() }
            Log.i(TAG, "HTTP server rebound on port=${cfg.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind HTTP server", e)
        }
        registerMdns()
    }

    /**
     * 获取 WiFi Lock，防止设备息屏后 WiFi 进入低功耗模式导致 beacon 失联。
     *
     * - API 29+：使用 WIFI_MODE_FULL_LOW_LATENCY（最佳延迟）
     * - API 28-：使用 WIFI_MODE_FULL_HIGH_PERF（保持高性能）
     */
    private fun acquireWifiLock() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return

            @Suppress("DEPRECATION")
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }

            wifiLock = wifiManager.createWifiLock(lockType, "LanBeacon::WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WiFi lock acquired (mode=${
                if (lockType == WifiManager.WIFI_MODE_FULL_LOW_LATENCY) "LOW_LATENCY" else "HIGH_PERF"
            })")
        } catch (e: Exception) {
            // 非致命：获取失败时 beacon 仍然工作，只是息屏后可能断连
            Log.w(TAG, "Failed to acquire WiFi lock (non-fatal)", e)
        }
    }

    /** 释放 WiFi Lock。安全调用——未持有时不会抛异常。 */
    private fun releaseWifiLock() {
        wifiLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "WiFi lock released")
            }
        }
        wifiLock = null
    }

    companion object {
        private const val TAG = "LanPresenceManager"
    }
}
