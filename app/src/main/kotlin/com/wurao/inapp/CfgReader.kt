package com.wurao.inapp

import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject

/**
 * hook 侧读界面写的配置 —— 走框架的**远程文件**（`openRemoteFile`）。
 *
 * 不用 Context，所以在 onPackageReady 里就能读，不必等 Application.onCreate。
 *
 * 规矩：**读失败一律返回空**，调用方按"全关 / 不启用"处理 ——
 * 一个读错就把风险级能力全开是要出事的，反过来最多是这次没生效。
 */
object CfgReader {

    /** 读全部配置（键 → 值）。读不到返回空 map。 */
    fun readAll(iface: XposedInterface): Map<String, String> {
        val pfd = runCatching { iface.openRemoteFile(com.wurao.core.Cfg.CFG_FILE) }.getOrNull()
        if (pfd == null) {
            Log.d("读配置：远程文件不存在（按不启用处理）")
            return emptyMap()
        }
        val text = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure { Log.w("读配置失败: ${it.message}") }.getOrNull()
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(text)
            val out = HashMap<String, String>()
            o.keys().forEach { k -> out[k] = o.optString(k) }
            out
        }.onFailure { Log.w("配置解析失败: ${it.message}") }.getOrDefault(emptyMap())
    }
}
