package com.szgenle.lanbeacon

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 局域网在场广播前台 Service 基类。
 *
 * 集成方只需：
 * 1. 继承 [BeaconService]
 * 2. 实现 [provideConfig] 返回 [BeaconConfig]
 * 3. 实现 [buildNotification] 返回前台通知
 * 4. 在 AndroidManifest 中注册子类 Service（foregroundServiceType="connectedDevice"）
 *
 * 启停方式：
 * ```kotlin
 * // 启动
 * val intent = Intent(context, MyBeaconService::class.java)
 * ContextCompat.startForegroundService(context, intent)
 *
 * // 停止
 * context.stopService(Intent(context, MyBeaconService::class.java))
 * ```
 *
 * 生命周期：
 * - [onCreate]：创建 [LanPresenceManager] 实例
 * - [onStartCommand]：启动前台通知 + beacon 广播
 * - [onDestroy]：停止 beacon + 释放资源
 *
 * 集成方可通过 [state] 观察运行状态变化。
 */
abstract class BeaconService : Service() {

    private var manager: LanPresenceManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * beacon 运行状态。集成方可在 Activity/Fragment 中 bindService 后获取此引用观察状态。
     * 或者直接用 [LanPresenceManager] 的 companion 静态引用（如果选择提供的话）。
     */
    val state: StateFlow<BeaconState>
        get() = manager?.state ?: throw IllegalStateException("Service not yet created")

    // ==================== 子类必须实现 ====================

    /**
     * 提供 beacon 配置。在 [onStartCommand] 时调用。
     *
     * 典型实现：
     * ```kotlin
     * override fun provideConfig() = BeaconConfig(
     *     port = 47821,
     *     appName = "agentpost",
     *     appVersion = BuildConfig.VERSION_NAME,
     *     serviceType = "_agentpost._tcp.",
     *     serviceName = "agentpost-beacon",
     * )
     * ```
     */
    abstract fun provideConfig(): BeaconConfig

    /**
     * 构建前台通知。在 [onStartCommand] 时调用。
     *
     * 集成方需要：
     * 1. 创建 NotificationChannel（Android O+）
     * 2. 返回一个低优先级 ongoing Notification
     *
     * @param state 当前 beacon 状态，可据此显示不同文案
     */
    abstract fun buildNotification(state: BeaconState): Notification

    /**
     * 前台通知 ID。默认 47821，子类可覆盖避免与 app 内其他通知冲突。
     */
    open val notificationId: Int = 47821

    // ==================== 可选覆盖 ====================

    /**
     * 状态变化时的回调。默认实现会更新前台通知内容。
     * 子类可覆盖做额外处理（如发送广播、更新 Widget 等）。
     */
    open fun onStateChanged(newState: BeaconState) {
        // 更新通知
        val notification = buildNotification(newState)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 需要指定 foregroundServiceType
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update foreground notification", e)
        }
    }

    // ==================== Service 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        manager = LanPresenceManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mgr = manager ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        // 启动前台
        val config = provideConfig()
        val notification = buildNotification(BeaconState.Starting)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 启动 beacon
        mgr.start(config)

        // 观察状态变化 → 更新通知
        serviceScope.launch {
            mgr.state.collect { newState ->
                onStateChanged(newState)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        manager?.stop()
        manager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BeaconService"
    }
}
