plugins {
    alias(libs.plugins.lanbeacon.android.library)
}

android {
    namespace = "com.szgenle.lanbeacon"

    testOptions {
        // 单元测试中调用 android.util.Log 等 framework API 时返回默认值，不抛 RuntimeException
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 极轻量嵌入式 HTTP 服务（局域网在场广播 healthz 端点）
    implementation(libs.nanohttpd)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests
    testImplementation(libs.junit)
}
