package com.adhammer.hooker;

import android.content.Context;
import android.text.TextUtils;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;
import com.adhammer.model.HostInfo;

import java.net.InetAddress;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Host / DNS 拦截（对应原版 HostHook）。
 *
 * <p>原理：在域名解析环节命中规则就返回一个不可达地址（127.0.0.1），
 * 让广告请求在本地就失败。对应博客《沙僧代理DNS拦截》里描述的思路，
 * 但这里是 Xposed 进程内实现，不需要开 VPN。</p>
 *
 * <p>规则 {@code host} 字段是<b>正则表达式</b>。</p>
 */
public class HostHook implements AdHookEngine.FeatureHook {

    private static AppRule sRule;

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        sRule = rule;

        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String host = (String) param.args[0];
                                if (isBlocked(host)) {
                                    XLog.d("block host: " + host);
                                    param.setResult(InetAddress.getByName("127.0.0.1"));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable e) {
            XLog.w("hook InetAddress.getByName failed: " + e.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String host = (String) param.args[0];
                                if (isBlocked(host)) {
                                    XLog.d("block host(all): " + host);
                                    param.setResult(new InetAddress[]{
                                            InetAddress.getByName("127.0.0.1")});
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable e) {
            XLog.w("hook InetAddress.getAllByName failed: " + e.getMessage());
        }
    }

    /** 是否命中 Host 拦截规则。 */
    public static boolean isBlocked(String host) {
        if (TextUtils.isEmpty(host) || sRule == null || sRule.mHostList == null) return false;
        for (HostInfo info : sRule.mHostList) {
            if (info != null && info.matches(host)) return true;
        }
        return false;
    }
}
