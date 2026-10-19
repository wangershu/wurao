package com.adhammer.core;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志工具。模块内统一走 XposedBridge.log，便于 LSPosed 日志里检索。
 */
public final class XLog {

    public static final String TAG = "AdHammer";

    /** 调试开关：发布时可关闭以减少开销。 */
    public static boolean DEBUG = true;

    private XLog() {
    }

    public static void d(String msg) {
        if (DEBUG) log("[D] " + msg);
    }

    public static void i(String msg) {
        log("[I] " + msg);
    }

    public static void w(String msg) {
        log("[W] " + msg);
    }

    public static void e(String msg, Throwable t) {
        log("[E] " + msg + (t != null ? " : " + t.getMessage() : ""));
    }

    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable ignored) {
            // 非 Xposed 环境下忽略
        }
    }
}
