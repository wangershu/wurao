package com.adhammer.hooker;

import android.content.Context;
import android.text.TextUtils;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;
import com.adhammer.model.HostInfo;

import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * URL 拦截（对应原版 UrlHook）。
 *
 * <p>原理：在网络层拦截请求，命中广告 URL 规则的连接直接断开。
 * 与 DNS 拦截（{@link HostHook}）互补 —— URL 拦截精确到路径，DNS 拦截精确到域名。</p>
 *
 * <p>规则 {@code host} 字段是<b>正则表达式</b>，用 find() 匹配。</p>
 */
public class UrlHook implements AdHookEngine.FeatureHook {

    private static AppRule sRule;

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        sRule = rule;

        // ① HttpURLConnection 通道：openConnection 时判定，命中则断开
        try {
            XposedHelpers.findAndHookMethod(URL.class, "openConnection", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        URL url = (URL) param.thisObject;
                        String text = url.toString();
                        if (isBlocked(text)) {
                            XLog.d("block url: " + text);
                            java.net.URLConnection conn = (java.net.URLConnection) param.getResult();
                            if (conn instanceof HttpURLConnection) {
                                ((HttpURLConnection) conn).disconnect();
                            }
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable e) {
            XLog.w("hook URL.openConnection failed: " + e.getMessage());
        }

        // ② WebView 通道：加载广告页面时拦截
        try {
            Class<?> webView = Class.forName("android.webkit.WebView", false, classLoader);
            XposedHelpers.findAndHookMethod(webView, "loadUrl", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String url = (String) param.args[0];
                    if (!TextUtils.isEmpty(url) && isBlocked(url)) {
                        XLog.d("block webview url: " + url);
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable e) {
            XLog.w("hook WebView.loadUrl failed: " + e.getMessage());
        }
    }

    /** 是否命中 URL 拦截规则。 */
    public static boolean isBlocked(String url) {
        if (TextUtils.isEmpty(url) || sRule == null || sRule.mUrlList == null) return false;
        for (HostInfo info : sRule.mUrlList) {
            if (info != null && info.matches(url)) return true;
        }
        return false;
    }
}
