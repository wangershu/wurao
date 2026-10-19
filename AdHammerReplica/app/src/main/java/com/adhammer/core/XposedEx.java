package com.adhammer.core;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed hook 的安全包装。
 *
 * <p>目标 App 里不一定存在 androidx / support 等类，直接 hook 会抛异常并中断整个安装流程。
 * 这里统一吞掉异常，保证「能装多少装多少」，也让新增 hook 点时不必层层 try-catch。</p>
 */
public final class XposedEx {

    private XposedEx() {
    }

    /** hook 一个已加载的类。 */
    public static void hook(Class<?> clazz, String method, Object... argsAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, argsAndCallback);
        } catch (Throwable e) {
            XLog.w("hook failed " + clazz.getName() + "#" + method + " : " + e.getMessage());
        }
    }

    /** 按类名 + ClassLoader hook（类不存在时静默跳过）。 */
    public static void hookByName(ClassLoader cl, String className, String method,
                                  Object... argsAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, method, argsAndCallback);
        } catch (Throwable e) {
            XLog.d("skip hook " + className + "#" + method + " (not present)");
        }
    }

    /** 无参方法的便捷形式。 */
    public static void hookNoArgs(Class<?> clazz, String method, XC_MethodHook callback) {
        hook(clazz, method, callback);
    }
}
