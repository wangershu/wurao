import com.android.build.api.dsl.SigningConfig
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起 Kotlin 已内置，不再需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
}

/** 签名参数：环境变量优先，其次 -P 属性 */
fun signProp(name: String): String? =
    System.getenv(name) ?: (project.findProperty(name) as String?)

/**
 * 版本号：CI 从 tag 注入（VERSION_NAME=v1.2.0 去掉 v、VERSION_CODE=run_number），
 * 本地不传就用这里的默认值。别再手改 —— tag 就是版本。
 */
fun vProp(name: String, default: String): String =
    System.getenv(name) ?: (project.findProperty(name) as String?) ?: default

/**
 * 签名证书的 SHA-256。
 *
 * 构建期算出来写进 BuildConfig，运行期 [com.adhammer.util.Guard] 拿它和
 * 实际安装包的签名比对 —— 被别人重打包重新签名就对不上。
 * 任何 keystore 都自适应，换签名不用改代码。
 */
fun certSha256(cfg: SigningConfig): String? = runCatching {
    val file = cfg.storeFile ?: return@runCatching null
    val pw = (cfg.storePassword ?: "").toCharArray()
    // keytool 默认可能是 JKS 也可能是 PKCS12，两种都试
    val ks = listOf("JKS", "PKCS12").firstNotNullOfOrNull { type ->
        runCatching {
            KeyStore.getInstance(type).apply { file.inputStream().use { load(it, pw) } }
        }.getOrNull()
    } ?: return@runCatching null
    val cert = ks.getCertificate(cfg.keyAlias) ?: return@runCatching null
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
}.getOrNull()

android {
    namespace = "com.adhammer"
    // libxposed api 102 的依赖元数据要求 compileSdk >= 37
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.adhammer.x"
        minSdk = 26
        targetSdk = 35
        versionCode = vProp("VERSION_CODE", "2").toInt()
        versionName = vProp("VERSION_NAME", "1.1.1")
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
            // R8：类名/方法名混淆 + 资源收缩。
            // 入口类、Gson 模型、服务类都在 proguard-rules.pro 里显式保留，
            // 反射只用来查 Android 框架的类，不受混淆影响。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasKs = !signProp("RELEASE_STORE_FILE").isNullOrBlank()
            val used = if (hasKs) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            signingConfig = used
            buildConfigField("boolean", "GUARD_ON", "true")
            buildConfigField("String", "SIGN_SHA256", "\"${certSha256(used) ?: ""}\"")
        }
        debug {
            // 自己调试时不做完整性校验（debug 签名每次都可能变）
            buildConfigField("boolean", "GUARD_ON", "false")
            buildConfigField("String", "SIGN_SHA256", "\"\"")
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
