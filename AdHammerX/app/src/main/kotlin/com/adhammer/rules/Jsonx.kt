package com.adhammer.rules

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.adhammer.util.HLog

/** JSON 工具：订阅与规则都是宽松 JSON，统一在这里解析。 */
object Jsonx {

    /** public：inline 函数会把实现内联到调用方，被访问的成员必须同为 public */
    val gson = Gson()

    fun toJson(obj: Any?): String? = runCatching { gson.toJson(obj) }.getOrNull()

    inline fun <reified T> fromJson(json: String?): T? =
        runCatching { gson.fromJson(json, T::class.java) }.getOrElse { null }

    /** 解析 JSON 数组（ViewRule / DialogRule / MethodRule 的 adKey 都是数组） */
    inline fun <reified T> fromJsonArray(json: String?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type) ?: emptyList()
        }.getOrElse {
            HLog.w("parse array failed: ${it.message}")
            emptyList()
        }
    }
}
