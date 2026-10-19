package com.adhammer.hooker;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 环境与版本伪装（对应原版 C3309）。
 *
 * <p>用途：很多 App 会「按机型 / 按版本 / 按地区」下发广告，
 * 把设备指纹改成规则指定的值即可绕过这类判定。</p>
 *
 * <p>伪装字段：Build.MANUFACTURER / BRAND / MODEL / PRODUCT / DEVICE / SERIAL、
 * 语言、MAC、WIFI SSID，以及 getPackageInfo 返回的 versionCode / versionName。</p>
 */
public class EnvHook implements AdHookEngine.FeatureHook {

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        // ① Build 静态字段伪装
        spoof(Build.class, "MANUFACTURER", rule.mf);
        spoof(Build.class, "BRAND", rule.bd);
        spoof(Build.class, "MODEL", rule.ml);
        spoof(Build.class, "PRODUCT", rule.pt);
        spoof(Build.class, "DEVICE", rule.dv);

        // ② 版本伪装：让 App 以为自己是「没有广告的那个版本」
        if (rule.vc > 0 || !TextUtils.isEmpty(rule.vn)) {
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.ApplicationPackageManager", classLoader,
                        "getPackageInfo", String.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object result = param.getResult();
                                    if (!(result instanceof android.content.pm.PackageInfo)) return;
                                    if (!pkg.equals(param.args[0])) return;
                                    android.content.pm.PackageInfo pi =
                                            (android.content.pm.PackageInfo) result;
                                    if (rule.vc > 0) pi.versionCode = rule.vc;
                                    if (!TextUtils.isEmpty(rule.vn)) pi.versionName = rule.vn;
                                } catch (Throwable ignored) {
                                }
                            }
                        });
            } catch (Throwable e) {
                XLog.w("hook getPackageInfo failed: " + e.getMessage());
            }
        }

        // ③ WIFI SSID 伪装（扩展点）
        if (!TextUtils.isEmpty(rule.ss)) {
            try {
                Class<?> wifiInfo = Class.forName("android.net.wifi.WifiInfo", false, classLoader);
                XposedHelpers.findAndHookMethod(wifiInfo, "getSSID", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(rule.ss);
                    }
                });
            } catch (Throwable ignored) {
                // 该 ROM 无此类，忽略
            }
        }

        // ④ MAC 伪装（扩展点）
        if (!TextUtils.isEmpty(rule.mac)) {
            try {
                Class<?> wifiInfo = Class.forName("android.net.wifi.WifiInfo", false, classLoader);
                XposedHelpers.findAndHookMethod(wifiInfo, "getMacAddress", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(rule.mac);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把某个类的 String 型静态字段改成指定值。 */
    private static void spoof(Class<?> clazz, String field, String value) {
        if (TextUtils.isEmpty(value)) return;
        try {
            XposedHelpers.setStaticObjectField(clazz, field, value);
            XLog.d("spoof " + clazz.getSimpleName() + "." + field + " = " + value);
        } catch (Throwable e) {
            XLog.w("spoof " + field + " failed: " + e.getMessage());
        }
    }
}
