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

    private val _currentLanIp = MutableStateFlow<String?>(null)
    /** 当前设备的 WiFi LAN IPv4 地址，null 表示未连接或无法获取。 */
    val currentLanIp: StateFlow<String?> = _currentLanIp.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * 启动 HTTP 服务 + mDNS 注册 + 网络监听。
     *
     * @param config 全部配置参数，无默认值，集成方必须显式构造。
     * @see BeaconConfig
     */
    fun start(config: BeaconConfig) {
        if (_isRunning.value) {
            Log.w(TAG, "already running, skip start")
            return
        }
        this.config = config
        Log.i(TAG, "starting LAN presence on port=${config.port}")

        // 1. 获取当前 LAN IP
        val ip = detectLanIp()
        _currentLanIp.value = ip

        // 2. 启动 HTTP server
        try {
            server = PresenceHttpServer(config.port, config.appName, config.appVersion).also { it.start() }
            Log.i(TAG, "HTTP server started on port=${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            return
        }

        // 3. 注册 mDNS
        registerMdns()

        // 4. 注册网络变化回调
        registerNetworkCallback()

        _isRunning.value = true
    }

    /** 停止所有子组件，释放资源。 */
    fun stop() {
        Log.i(TAG, "stopping LAN presence")
        _isRunning.value = false

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

        _currentLanIp.value = null
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
                val oldIp = _currentLanIp.value
                if (newIp != oldIp) {
                    Log.i(TAG, "WiFi IP changed: $oldIp -> $newIp, rebinding")
                    _currentLanIp.value = newIp
                    rebindServer()
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "WiFi network lost")
                _currentLanIp.value = null
                // server 保持运行：WiFi 恢复后 IP 更新会触发 rebind
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
