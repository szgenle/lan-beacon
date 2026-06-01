pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lan-beacon"

// 当前 Android 端唯一发布产物。模块名固定为 :lib，因为顶层目录已经是 android/，
// 避免出现 :android 让坐标变成 com.github.szgenle:lan-beacon:android:VERSION 这种平台前缀冗余。
include(":lib")
