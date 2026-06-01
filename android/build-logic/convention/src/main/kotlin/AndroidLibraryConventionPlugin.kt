import com.android.build.gradle.LibraryExtension
import com.szgenle.lanbeacon.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register

/**
 * 通用 Android Library 模块（不含 Compose）。
 *
 * 自动应用：
 * - com.android.library
 * - org.jetbrains.kotlin.android
 * - maven-publish（配置 release AAR 发布）
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
                apply("maven-publish")
            }

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                defaultConfig.consumerProguardFiles("consumer-rules.pro")

                // maven-publish 需要至少一个 variant 产出可发布组件
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                    }
                }
            }

            // afterEvaluate 确保 AGP 的 component 已注册
            afterEvaluate {
                extensions.configure<PublishingExtension> {
                    publications {
                        register<MavenPublication>("release") {
                            from(components["release"])

                            // JitPack 构建时环境变量 JITPACK=true，此时 groupId 必须匹配
                            // JitPack 多模块约定 (com.github.User.Repo)；
                            // 本地 / composite build 时使用自定义 group。
                            groupId = if (System.getenv("JITPACK") == "true") {
                                "com.github.szgenle.lan-beacon"
                            } else {
                                "com.szgenle.lanbeacon"
                            }
                            artifactId = project.name  // "lib"
                            version = findProperty("lanbeacon.version")?.toString() ?: "0.1.0-SNAPSHOT"

                            pom {
                                name.set("lan-beacon")
                                description.set("Zero-config LAN presence broadcasting library for Android")
                                url.set("https://github.com/szgenle/lan-beacon")
                                licenses {
                                    license {
                                        name.set("The Apache License, Version 2.0")
                                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
