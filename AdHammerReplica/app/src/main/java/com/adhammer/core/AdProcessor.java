package com.adhammer.core;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.adhammer.model.ActionType;
import com.adhammer.model.AdRule;

import java.lang.ref.WeakReference;

/**
 * 广告动作执行器（对应原版 AdProcessor）。
 *
 * <p>把规则动作翻译成具体的 Android 调用。四个核心手段与《悟空使用说明》一一对应：</p>
 * <ul>
 *   <li><b>关闭活动页</b>（at=1）：finish 当前页；d&gt;0 时延迟后按返回键</li>
 *   <li><b>禁用活动页</b>（at=2）：把组件设为 DISABLED，不再被启动</li>
 *   <li><b>跳过到首页</b>（at=3/7）：直接启动首页并 finish 当前页，无转场动画</li>
 *   <li><b>防火墙拦截</b>（at=9）：把 Intent 清空，启动行为胎死腹中</li>
 * </ul>
 */
public final class AdProcessor {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AdProcessor() {
    }

    // ---------- 组件启停 ----------

    /** 禁用组件（COMPONENT_ENABLED_STATE_DISABLED）。 */
    public static void disableComponent(PackageManager pm, ComponentName cn) {
        if (pm == null || cn == null) return;
        try {
            if (pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return;
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            XLog.d("disabled component: " + cn.getClassName());
        } catch (Throwable e) {
            XLog.w("disableComponent failed: " + e.getMessage());
        }
    }

    /** 启用组件（COMPONENT_ENABLED_STATE_ENABLED）。 */
    public static void enableComponent(PackageManager pm, ComponentName cn) {
        if (pm == null || cn == null) return;
        try {
            if (pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return;
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            XLog.d("enabled component: " + cn.getClassName());
        } catch (Throwable e) {
            XLog.w("enableComponent failed: " + e.getMessage());
        }
    }

    /** 禁用当前 Activity 自身组件。 */
    public static void disableSelf(Activity act) {
        try {
            disableComponent(act.getPackageManager(), new ComponentName(act, act.getClass()));
        } catch (Throwable ignored) {
        }
    }

    // ---------- 页面动作 ----------

    /** 立即关闭页面：finish + 去掉转场动画。 */
    public static void finishNow(Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            act.setResult(Activity.RESULT_OK);
            act.finish();
            act.overridePendingTransition(0, 0);
            Tips.show(act);
        } catch (Throwable e) {
            XLog.w("finishNow failed: " + e.getMessage());
        }
    }

    /** 延迟后模拟返回键（给「广告倒计时结束才允许返回」的场景用）。 */
    public static void finishDelayed(Activity act, long delayMs) {
        if (act == null) return;
        final WeakReference<Activity> ref = new WeakReference<>(act);
        MAIN.postDelayed(() -> {
            Activity a = ref.get();
            if (a == null) return;
            try {
                a.onBackPressed();
                Tips.show(a);
            } catch (Throwable ignored) {
            }
        }, delayMs);
    }

    /** 跳转到首页并关闭当前广告页（移花接木）。 */
    public static void jumpHome(Activity act, ComponentName home) {
        if (act == null || home == null || act.isFinishing()) return;
        try {
            Intent intent = new Intent();
            Intent origin = act.getIntent();
            if (origin != null) {
                // 携带 data 的外部跳转不改写，避免破坏正常业务逻辑
                if (origin.getData() != null) {
                    XLog.d("skip jumpHome, intent has data");
                    return;
                }
                if (origin.getExtras() != null) intent.putExtras(origin.getExtras());
            }
            intent.setComponent(home);
            act.startActivity(intent);
            Tips.show(act);
            act.setResult(Activity.RESULT_OK);
            act.finish();
            act.overridePendingTransition(0, 0);
        } catch (Throwable e) {
            XLog.w("jumpHome failed: " + e.getMessage());
        }
    }

    // ---------- 规则分发 ----------

    /** Activity.onCreate 命中广告页时执行（对应原版 m5767）。 */
    public static void onAdActivityCreated(Activity act, AdRule rule) {
        if (act == null || rule == null) return;
        switch (rule.at) {
            case ActionType.CLOSE:
                // at=1 关闭活动页
                if (rule.d > 0) finishDelayed(act, rule.d);
                else finishNow(act);
                break;
            case ActionType.DISABLE:
                // at=2 禁用活动页
                disableSelf(act);
                break;
            case ActionType.SKIP:
            case ActionType.REPLACE:
                // at=3/7 跳过 / 替换为首页
                jumpHome(act, rule.toComponent(act.getPackageName()));
                break;
            case ActionType.BLOCK:
                // at=9 防火墙拦截：已在 startActivity 阶段拦掉，这里兜底关闭
                finishNow(act);
                break;
            default:
                XLog.d("unhandled action on create: at=" + rule.at);
                break;
        }
    }

    /** startActivity 命中广告页时改写 Intent（对应原版 m5768）。 */
    public static void onAdActivityStarting(Intent intent, AdRule rule, String pkg) {
        if (intent == null || rule == null) return;
        switch (rule.at) {
            case ActionType.SKIP:
            case ActionType.REPLACE:
            case ActionType.RETARGET: {
                ComponentName home = rule.toComponent(pkg);
                if (home != null) {
                    intent.setComponent(home);
                    XLog.d("retarget -> " + home.getClassName());
                }
                break;
            }
            case ActionType.CLOSE:
            case ActionType.BLOCK: {
                // 拦截：把 Intent 掏空，调用方 startActivity 后不会真正跳转
                intent.setData(null);
                intent.setComponent(null);
                if (intent.getExtras() != null) intent.getExtras().clear();
                intent.setAction(null);
                XLog.d("blocked start: " + rule.ad);
                break;
            }
            default:
                break;
        }
    }
}
