package com.adhammer.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 反射工具 —— 替代 API 102 中被禁用的 XposedHelpers。
 *
 * 全部走标准反射，支持按参数个数定位方法（应对 App 改版导致的签名变化）。
 */
object Reflect {

    /** 按类名查找（先当前 CL，再 Boot CL）。 */
    fun findClass(name: String, cl: ClassLoader?): Class<*>? {
        return try {
            Class.forName(name, false, cl)
        } catch (e: Throwable) {
            try {
                Class.forName(name)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    /** 精确匹配方法。 */
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        return try {
            val m = clazz.getDeclaredMethod(name, *paramTypes)
            m.isAccessible = true
            m
        } catch (e: Throwable) {
            try {
                val m = clazz.getMethod(name, *paramTypes)
                m.isAccessible = true
                m
            } catch (e2: Throwable) {
                null
            }
        }
    }

    /** 按方法名 + 参数个数定位（签名变化时更稳）。 */
    fun findMethodByArity(clazz: Class<*>, name: String, arity: Int): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == arity
            }?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        return null
    }

    fun findConstructor(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        return try {
            val c = clazz.getDeclaredConstructor(*paramTypes)
            c.isAccessible = true
            c
        } catch (e: Throwable) {
            null
        }
    }

    fun callMethod(target: Any?, method: Method?, vararg args: Any?): Any? {
        if (method == null) return null
        return try {
            method.invoke(target, *args)
        } catch (e: Throwable) {
            null
        }
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (e: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    fun getObjectField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            findField(target.javaClass, name)?.get(target)
        } catch (e: Throwable) {
            null
        }
    }

    fun setObjectField(target: Any?, name: String, value: Any?) {
        if (target == null) return
        runCatching { findField(target.javaClass, name)?.set(target, value) }
    }

    fun getStaticObjectField(clazz: Class<*>, name: String): Any? {
        return try {
            findField(clazz, name)?.get(null)
        } catch (e: Throwable) {
            null
        }
    }

    fun setStaticObjectField(clazz: Class<*>, name: String, value: Any?) {
        val f = findField(clazz, name) ?: return
        runCatching {
            // final 字段需要去掉 final 修饰
            if (Modifier.isFinal(f.modifiers)) {
                val modifiersField = Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(f, f.modifiers and Modifier.FINAL.inv())
            }
            f.set(null, value)
        }
    }

    fun getIntField(target: Any?, name: String): Int =
        (getObjectField(target, name) as? Number)?.toInt() ?: 0

    fun getBooleanField(target: Any?, name: String): Boolean =
        (getObjectField(target, name) as? Boolean) ?: false
}
