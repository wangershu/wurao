package com.adhammer.hooker;

import android.app.Notification;
import android.content.Context;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;

import com.adhammer.core.XposedEx;
import de.robv.android.xposed.XC_MethodHook;

/**
 * 通用 Hook（对应原版 CommonHook）。
 *
 * <p>当前实现：强制所有通知 setAutoCancel(true)，
 * 让广告推送通知点击后自动消失，不再常驻通知栏。</p>
 *
 * <p>后续新增「全局通用型」去广告手段可以放在这里。</p>
 */
public class CommonHook implements AdHookEngine.FeatureHook {

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        XC_MethodHook autoCancel = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = Boolean.TRUE;
            }
        };
        XposedEx.hook(Notification.Builder.class, "setAutoCancel", boolean.class, autoCancel);
        XposedEx.hookByName(classLoader, "androidx.core.app.NotificationCompat$Builder",
                "setAutoCancel", boolean.class, autoCancel);
        XposedEx.hookByName(classLoader, "android.support.v4.app.NotificationCompat$Builder",
                "setAutoCancel", boolean.class, autoCancel);

        XLog.d("CommonHook installed for " + pkg);
    }
}
