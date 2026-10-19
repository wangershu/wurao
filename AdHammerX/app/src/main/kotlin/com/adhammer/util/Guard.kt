package com.adhammer.util

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Debug
import com.adhammer.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * 完整性校验 / 反篡改。
 *
 * 做的三件事：
 * 1. **防重打包**：运行期取安装包的签名证书 SHA-256，和构建期写进 BuildConfig 的比对。
 *    别人把 APK 解开改完再用自己的 key 重新签名，这里就对不上。
 * 2. **防动态注入**：扫 /proc/self/maps 里有没有 frida / gadget 这类注入 so，
 *    再看 frida-server 的默认端口 27042 有没有在监听。
 * 3. **防调试**：release 包不该带 debuggable 标记，也不该被调试器挂上。
 *
 * 只防「随手改两下」的二次分发，防不了对症下药的逆向 —— 真要拦死得上壳 + 服务端校验。
 * debug 构建（BuildConfig.GUARD_ON = false）整段跳过，不影响自己调试。
 */
object Guard {

    /** 校验通过返回 true；不通过会弹提示并结束页面，返回 false */
    fun enforce(activity: Activity): Boolean {
        if (!BuildConfig.GUARD_ON) return true
        val problems = check(activity)
        if (problems.isEmpty()) return true

        problems.forEach { HLog.w("guard: $it") }
        runCatching {
            android.app.AlertDialog.Builder(activity)
                .setTitle("完整性校验未通过")
                .setMessage(
                    problems.joinToString("\n") + "\n\n" +
                        "这不是官方构建包，或正在被注入调试。\n" +
                        "请从项目 Release 页下载原包安装。"
                )
                .setCancelable(false)
                .setPositiveButton("退出") { _, _ -> activity.finishAndRemoveTask() }
                .show()
        }.onFailure {
            // 弹窗都拉不起来就直接退出，别让改过的包跑起来
            activity.finishAndRemoveTask()
        }
        return false
    }

    /** 返回所有不通过的原因；空列表 = 正常 */
    fun check(context: Context): List<String> {
        if (!BuildConfig.GUARD_ON) return emptyList()
        val out = ArrayList<String>()

        // 1. 签名比对
        val expected = BuildConfig.SIGN_SHA256
        if (expected.isNotBlank()) {
            val actual = signatureHashes(context)
            if (actual.isEmpty()) {
                out.add("· 读不到安装包签名")
            } else if (expected !in actual) {
                out.add("· 签名与官方构建不一致（可能被重新打包）")
            }
        }

        // 2. 注入痕迹
        if (looksInjected()) {
            out.add("· 检测到注入框架或调试端口（frida / gadget）")
        }

        // 3. debuggable / 调试器
        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            out.add("· 安装包被标记为可调试（release 包不该有）")
        }
        if (Debug.isDebuggerConnected()) {
            out.add("· 正被调试器附加")
        }

        return out
    }

    /** 当前安装包的所有签名证书 SHA-256 */
    private fun signatureHashes(context: Context): List<String> {
        val pm = context.packageManager
        val pkg = context.packageName
        val sigs: Array<Signature>? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        }.getOrNull()
        return sigs?.mapNotNull { sig ->
            runCatching {
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
            }.getOrNull()
        } ?: emptyList()
    }

    /** maps 里的注入 so + frida 默认端口 */
    private fun looksInjected(): Boolean {
        val marks = arrayOf("frida", "linjector", "gadget", "substrate", "xhook")
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrNull()
        if (maps != null && marks.any { maps.contains(it, ignoreCase = true) }) return true

        // 27042 = 0x69A2，frida-server 默认监听端口
        val tcp = runCatching { File("/proc/net/tcp").readText() }.getOrNull()
        if (tcp != null && tcp.contains(":69A2", ignoreCase = true)) return true
        val tcp6 = runCatching { File("/proc/net/tcp6").readText() }.getOrNull()
        if (tcp6 != null && tcp6.contains(":69A2", ignoreCase = true)) return true

        return false
    }
}
