package com.szgenle.lanbeacon

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

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
        if (ip == null) {
            Log.w(TAG, "no LAN IP detected, will wait for network callback")
        }

        // 2. 启动 HTTP server
        try {
            server = PresenceHttpServer(config.port, config.appName, config.appVersion).also { it.start() }
            Log.i(TAG, "HTTP server started on port=${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            _state.value = BeaconState.Error("HTTP server start failed: ${e.message}", e)
            return
        }

        // 3. 注册 mDNS
        registerMdns()

        // 4. 注册网络变化回调
        registerNetworkCallback()

        // 5. 设置最终状态
        if (ip != null) {
            _state.value = BeaconState.Running(lanIp = ip, port = config.port)
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

    private fun extractIpv4(linkProperties: LinkProperties): String? {
        return linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun registerMdns() {
        val cfg = config ?: return
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = cfg.serviceName
                serviceType = cfg.serviceType
                setPort(cfg.port)
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
                val currentState = _state.value
                val oldIp = (currentState as? BeaconState.Running)?.lanIp
                if (newIp != null && newIp != oldIp) {
                    Log.i(TAG, "WiFi IP changed: $oldIp -> $newIp, rebinding")
                    rebindServer()
                    _state.value = BeaconState.Running(lanIp = newIp, port = config!!.port)
                } else if (newIp != null && currentState is BeaconState.NetworkLost) {
                    // 从 NetworkLost 恢复
                    Log.i(TAG, "WiFi recovered: ip=$newIp")
                    rebindServer()
                    _state.value = BeaconState.Running(lanIp = newIp, port = config!!.port)
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
     * NanoHTTPD 绑定的是 0.0.0.0（handler 层做来源过滤），所以其实只需要重注册 mDNS 即可。
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
            server = PresenceHttpServer(cfg.port, cfg.appName, cfg.appVersion).also { it.start() }
            Log.i(TAG, "HTTP server rebound on port=${cfg.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind HTTP server", e)
        }
        registerMdns()
    }

    companion object {
        private const val TAG = "LanPresenceManager"
    }
}
