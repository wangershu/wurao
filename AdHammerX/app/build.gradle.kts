plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起 Kotlin 已内置，不再需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
}

/** 签名参数：环境变量优先，其次 -P 属性 */
fun signProp(name: String): String? =
    System.getenv(name) ?: (project.findProperty(name) as String?)

android {
    namespace = "com.adhammer"
    // libxposed api 102 的依赖元数据要求 compileSdk >= 37
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.adhammer.x"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // 发布签名：四个参数优先读环境变量（CI 用 env 注入仓库 secrets），
    // 没有就退到 -P 属性；两者都没配时用 debug 签名，保证 assembleRelease 不直接失败
    signingConfigs {
        create("release") {
            val ks = signProp("RELEASE_STORE_FILE")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = signProp("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = signProp("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = signProp("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            // 模块里有 xposed 的声明文件与反射路径，先不开混淆
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasKs = !signProp("RELEASE_STORE_FILE").isNullOrBlank()
            signingConfig = if (hasKs) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
}

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // LSPosed API 102 的模块声明目录必须保留
            merges += "META-INF/xposed/*"
            excludes -= setOf("META-INF/xposed/*")
        }
    }
}

dependencies {
    // ---------- LSPosed 新版 API 102（libxposed）----------
    // 与旧的 de.robv.android.xposed 完全不兼容：不能再用 XposedHelpers / XposedBridge
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // ---------- UI：miuix（MIUI 风格 Compose 组件库）----------
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.compose.lifecycle.runtime)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.icons.extended)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)

    // ---------- 基础 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.okhttp)
}
