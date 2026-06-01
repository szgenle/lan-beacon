// 顶层 build 文件。具体插件通过 Convention Plugin 应用，这里只做声明。
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
