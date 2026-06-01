plugins {
    `kotlin-dsl`
}

group = "com.szgenle.lanbeacon.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "lanbeacon.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
