package com.adhammer.core;

import android.app.Activity;

import com.adhammer.model.AppRule;

import java.lang.ref.WeakReference;

/**
 * 模块运行时全局状态（对应原版 AdNoneHook 的静态字段）。
 */
public final class Runtime {

    /** 当前目标应用已加载的规则。 */
    public static volatile AppRule sAppRule;

    /** 当前目标包名。 */
    public static volatile String sPkg;

    /** 当前前台 Activity 弱引用。 */
    public static volatile WeakReference<Activity> sCurAct;

    /** 当前前台 Activity 类名。 */
    public static volatile String sCurActName;

    /** 下一次 hook 是否跳过（用于避免误伤正常页面跳转）。 */
    public static volatile boolean mIsSkipHook = false;

    private Runtime() {
    }

    public static Activity curAct() {
        WeakReference<Activity> ref = sCurAct;
        return ref == null ? null : ref.get();
    }
}
