plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 编译已内置，不再单独声明 org.jetbrains.kotlin.android。
    // kotlin-compose 等 S5 做 UI 时再加（现在加会因为缺 compose runtime 直接编译失败）
}

android {
    namespace = "com.wurao"
    // libxposed api 102 的依赖元数据要求 compileSdk >= 37
    compileSdk = 37
    // 必须显式指定：本机 build-tools 36.0.0 那份是残缺的（AGP 默认会去装它然后失败），
    // 37.0.0 是唯一能用的完整版本
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.wurao.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        buildConfig = true
        // compose = true 留到 S5（UI 阶段）再开
    }

    // core 层跑在 JVM 单测里，碰到 android.util.Log 会抛 "not mocked"
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            // libxposed 的模块声明目录必须保留
            merges += "META-INF/xposed/*"
            excludes -= setOf("META-INF/xposed/*")
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(libs.junit)
}
