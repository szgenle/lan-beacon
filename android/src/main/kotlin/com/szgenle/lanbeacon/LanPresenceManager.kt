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
 * 2. mDNS 服务注册：让桌面端自动发现设备 IP（默认 `_lanbeacon._tcp.`，集成方应覆盖为自己的 `_<app>._tcp.`）
 * 3. 网络变化监听（ConnectivityManager.NetworkCallback）：WiFi 切换时自动重绑
 *
 * 集成方在前台 Service 中持有本类实例，按开关切换 [start] / [stop]。
 */
class LanPresenceManager(private val context: Context) {

    private var server: PresenceHttpServer? = null
    private var port: Int = DEFAULT_PORT
    private var appName: String = DEFAULT_APP_NAME
    private var serviceType: String = DEFAULT_MDNS_SERVICE_TYPE
    private var serviceName: String = DEFAULT_MDNS_SERVICE_NAME
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
     * @param port 监听端口，默认 47821
     * @param appName 应用标识，写入 healthz JSON 的 `app` 字段
     * @param appVersion 应用版本号，写入 healthz JSON 的 `version` 字段
     * @param serviceType mDNS 服务类型，需以下划线开头（如 `_lanbeacon._tcp.`、`_myapp._tcp.`）
     * @param serviceName mDNS 服务实例名（用户可见）
     */
    fun start(
        port: Int = DEFAULT_PORT,
        appName: String = DEFAULT_APP_NAME,
        appVersion: String,
        serviceType: String = DEFAULT_MDNS_SERVICE_TYPE,
        serviceName: String = DEFAULT_MDNS_SERVICE_NAME,
    ) {
        if (_isRunning.value) {
            Log.w(TAG, "already running, skip start")
            return
        }
        this.port = port
        this.appName = appName
        this.serviceType = serviceType
        this.serviceName = serviceName
        Log.i(TAG, "starting LAN presence on port=$port")

        // 1. 获取当前 LAN IP
        val ip = detectLanIp()
        _currentLanIp.value = ip

        // 2. 启动 HTTP server
        try {
            server = PresenceHttpServer(port, appName, appVersion).also { it.start() }
            Log.i(TAG, "HTTP server started on port=$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            return
        }

        // 3. 注册 mDNS
        registerMdns(port)

        // 4. 注册网络变化回调
        registerNetworkCallback(appName, appVersion)

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

    private fun registerMdns(port: Int) {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = this@LanPresenceManager.serviceName
                serviceType = this@LanPresenceManager.serviceType
                setPort(port)
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

    private fun registerNetworkCallback(appName: String, appVersion: String) {
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
                    rebindServer(appName, appVersion)
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
    private fun rebindServer(appName: String, appVersion: String) {
        // 停旧
        runCatching { server?.stop() }
        server = null
        unregisterMdns()

        // 起新
        try {
            server = PresenceHttpServer(port, appName, appVersion).also { it.start() }
            Log.i(TAG, "HTTP server rebound on port=$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind HTTP server", e)
        }
        registerMdns(port)
    }

    companion object {
        private const val TAG = "LanPresenceManager"

        /** 默认监听端口。 */
        const val DEFAULT_PORT = 47821

        /** 默认应用标识，写入 healthz JSON 的 `app` 字段。集成方应传入自己的 app 名。 */
        const val DEFAULT_APP_NAME = "lanbeacon"

        /** 默认 mDNS 服务类型。集成方应传入自己的 `_<app>._tcp.`。 */
        const val DEFAULT_MDNS_SERVICE_TYPE = "_lanbeacon._tcp."

        /** 默认 mDNS 服务实例名。集成方应传入自己的实例名。 */
        const val DEFAULT_MDNS_SERVICE_NAME = "lanbeacon"
    }
}
