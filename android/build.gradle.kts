plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.lanbeacon"
}

dependencies {
    // 极轻量嵌入式 HTTP 服务（局域网在场广播 healthz 端点）
    implementation(libs.nanohttpd)
    implementation(libs.kotlinx.coroutines.core)
}
