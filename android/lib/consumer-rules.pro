# ==============================================================================
# lan-beacon consumer rules
# 这些规则会通过 AGP 的 consumer proguard 机制自动合并到集成方的 R8 配置中。
# ==============================================================================

# --- NanoHTTPD ---
# NanoHTTPD 通过反射创建 session 内部类，且可能被 R8 认为无引用而移除。
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# --- lan-beacon 公开 API ---
# sealed class 及其子类不能被混淆（集成方通过 when 匹配）
-keep class com.szgenle.lanbeacon.BeaconState { *; }
-keep class com.szgenle.lanbeacon.BeaconState$* { *; }
-keep class com.szgenle.lanbeacon.BeaconConfig { *; }

# BeaconService 是抽象类，子类由集成方定义（Manifest 里注册），但基类方法不能被移除
-keep class com.szgenle.lanbeacon.BeaconService { *; }

# LanPresenceManager 是集成入口
-keep class com.szgenle.lanbeacon.LanPresenceManager { *; }

# PresenceHttpServer 继承 NanoHTTPD，通过多态调用
-keep class com.szgenle.lanbeacon.PresenceHttpServer { *; }
