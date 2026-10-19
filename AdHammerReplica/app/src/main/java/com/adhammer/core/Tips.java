package com.adhammer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.adhammer.model.AppRule;

/**
 * 去广告成功提示（对应原版 showAdTips）。
 *
 * <p>仅在规则开启且配置了 adTips 时展示，避免打扰。</p>
 */
public final class Tips {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Tips() {
    }

    /** 展示规则里配置的提示文案。 */
    public static void show(Context context) {
        AppRule rule = Runtime.sAppRule;
        if (rule == null || !rule.isOpen) return;
        show(context, rule.adTips);
    }

    public static void show(Context context, String msg) {
        if (context == null || msg == null || msg.isEmpty()) return;
        AppRule rule = Runtime.sAppRule;
        if (rule != null && !rule.isOpen) return;
        try {
            MAIN.post(() -> {
                try {
                    Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
