package com.adhammer.hooker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.AdProcessor;
import com.adhammer.core.Resx;
import com.adhammer.core.Runtime;
import com.adhammer.core.Strx;
import com.adhammer.core.XLog;
import com.adhammer.model.AdRule;
import com.adhammer.model.AppRule;
import com.adhammer.model.RuleInfo;
import com.adhammer.model.ViewHookInfo;

import java.lang.ref.WeakReference;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Activity 层 Hook（对应原版 ActHook）。
 *
 * <h3>三个拦截点</h3>
 * <ol>
 *   <li><b>onCreate</b>：广告页已经打开 —— 关闭 / 禁用 / 跳首页</li>
 *   <li><b>startActivity / startActivityForResult</b>：广告页正要打开 —— 改写目标或拦截</li>
 *   <li><b>onStart / onResume</b>：页面可见 —— 按 View 规则隐藏广告控件</li>
 * </ol>
 */
public class ActHook implements AdHookEngine.FeatureHook {

    private String pkg;

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        this.pkg = pkg;

        // ① 广告页已创建
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object thiz = param.thisObject;
                if (!(thiz instanceof Activity)) return;
                Activity act = (Activity) thiz;
                Runtime.sCurActName = act.getClass().getName();
                Runtime.sCurAct = new WeakReference<>(act);

                AdRule adRule = AdHookEngine.getAdRule(Runtime.sCurActName);
                if (adRule != null) {
                    XLog.d("hit ad activity: " + Runtime.sCurActName + " at=" + adRule.at);
                    AdProcessor.onAdActivityCreated(act, adRule);
                }
            }
        });

        // ② 广告页正要被启动
        XC_MethodHook startHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Intent intent = null;
                    for (Object arg : param.args) {
                        if (arg instanceof Intent) {
                            intent = (Intent) arg;
                            break;
                        }
                    }
                    if (intent == null) return;
                    ComponentName cn = intent.getComponent();
                    if (cn == null) return;

                    AdRule adRule = AdHookEngine.getAdRule(cn.getClassName());
                    if (adRule == null || !needIntercept(adRule)) return;
                    // 外部唤起（带 data）不拦截，避免破坏正常分享/跳转
                    if (intent.getData() != null) return;

                    XLog.d("intercept start: " + cn.getClassName() + " at=" + adRule.at);
                    AdProcessor.onAdActivityStarting(intent, adRule, pkg);
                } catch (Throwable e) {
                    XLog.w("startActivity hook error: " + e.getMessage());
                }
            }
        };

        XposedHelpers.findAndHookMethod(ContextWrapper.class, "startActivity", Intent.class, startHook);
        XposedHelpers.findAndHookMethod(ContextWrapper.class, "startActivity", Intent.class, Bundle.class, startHook);
        XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                Intent.class, int.class, startHook);
        XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                Intent.class, int.class, Bundle.class, startHook);

        // ③ 页面可见时按 View 规则处理（按 id 精确定位）
        XC_MethodHook viewHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handleViews(param.thisObject);
            }
        };
        XposedHelpers.findAndHookMethod(Activity.class, "onStart", viewHook);
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", viewHook);
    }

    /** 哪些动作需要在 startActivity 阶段就改写/拦截 Intent。 */
    private static boolean needIntercept(AdRule rule) {
        int at = rule.at;
        return at == com.adhammer.model.ActionType.SKIP
                || at == com.adhammer.model.ActionType.REPLACE
                || at == com.adhammer.model.ActionType.RETARGET
                || at == com.adhammer.model.ActionType.CLOSE
                || at == com.adhammer.model.ActionType.BLOCK;
    }

    /**
     * onStart / onResume 时，按 ViewHookInfo.id 精确定位并隐藏。
     * 对应原版 ActHook.m5658。
     */
    private void handleViews(Object thiz) {
        AppRule rule = Runtime.sAppRule;
        if (rule == null || rule.mViewMap == null || !(thiz instanceof Activity)) return;

        Activity act = (Activity) thiz;
        List<ViewHookInfo> list = rule.mViewMap.get(act.getClass().getName());
        if (list == null || list.isEmpty()) return;

        for (ViewHookInfo info : list) {
            if (TextUtils.isEmpty(info.id)) continue;
            for (String idName : info.id.split(";")) {
                int resId = Resx.resolveId(act, idName, pkg);
                if (resId == 0) continue;
                View view = act.findViewById(resId);
                if (view == null) continue;

                // 文本条件：为空直接通过，否则任一候选命中
                boolean pass = TextUtils.isEmpty(info.t);
                if (!pass) {
                    String text = AllViewsHook.textOf(view);
                    if (!TextUtils.isEmpty(text)) {
                        for (String t : info.t.split(";")) {
                            if (Strx.contains(text, t)) {
                                pass = true;
                                break;
                            }
                        }
                    }
                }
                if (pass) {
                    XLog.d("hide view by id: " + idName + " in " + act.getClass().getSimpleName());
                    AllViewsHook.resolveAndHide(info, view, pkg);
                }
            }
        }
    }
}
