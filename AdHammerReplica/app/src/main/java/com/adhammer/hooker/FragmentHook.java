package com.adhammer.hooker;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;
import com.adhammer.core.XposedEx;
import com.adhammer.model.AppRule;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Fragment 层 View 规则支持（对应原版 C3327）。
 *
 * <p>很多 App 的广告长在 Fragment 里而不是 Activity 里，
 * 只 hook Activity 会漏掉。这里在 Fragment 的 View 创建完成后补一次检查。</p>
 */
public class FragmentHook implements AdHookEngine.FeatureHook {

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        if (rule.mViewMap == null || rule.mViewMap.isEmpty()) return;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length > 0 && param.args[0] instanceof View) {
                        AllViewsHook.checkAndHide((View) param.args[0]);
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        // androidx Fragment
        XposedEx.hookByName(classLoader, "androidx.fragment.app.Fragment",
                "onViewCreated", View.class, Bundle.class, hook);
        // 旧 support 库
        XposedEx.hookByName(classLoader, "android.support.v4.app.Fragment",
                "onViewCreated", View.class, Bundle.class, hook);

        XLog.d("FragmentHook installed for " + pkg);
    }
}
