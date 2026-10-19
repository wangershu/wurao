package com.adhammer.hooker;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.Runtime;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;
import com.adhammer.model.DlgHookInfo;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 弹框 Hook（对应原版 DialogHook）。
 *
 * <p>规则键为 <code>&lt;包名&gt;.Dialog</code> 或 <code>&lt;Activity 名&gt;.Dialog</code>，
 * 取值 {@code ak} 为 DlgHookInfo 的 JSON 数组。</p>
 *
 * <p>三种动作：禁止弹框（at=0）/ 自动取消（at=1）/ 自动点击（at=2）。</p>
 */
public class DialogHook implements AdHookEngine.FeatureHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static List<DlgHookInfo> sList;
    private static String sPkg;

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        sList = rule.mDlgList;
        sPkg = pkg;

        XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Dialog dialog = (Dialog) param.thisObject;
                if (dialog == null) return;
                // 跳过模块自身的弹框
                String name = dialog.getClass().getName();
                if (name != null && name.contains("com.adhammer")) return;

                DlgHookInfo hit = match(dialog);
                if (hit == null) return;

                XLog.d("hit dialog rule: " + hit.describeAction() + " | " + hit.describeCondition());

                if (hit.isAutoClick()) {
                    // at=2 自动点击：先 show，再点目标按钮
                    return;
                }
                // at=0 禁止弹框 / at=1 自动取消：直接吞掉 show()
                long delay = hit.d;
                if (delay > 0) {
                    param.setResult(null);
                    MAIN.postDelayed(dialog::dismiss, delay);
                } else {
                    param.setResult(null);
                    safeDismiss(dialog);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Dialog dialog = (Dialog) param.thisObject;
                if (dialog == null) return;
                String name = dialog.getClass().getName();
                if (name != null && name.contains("com.adhammer")) return;

                DlgHookInfo hit = match(dialog);
                if (hit == null || !hit.isAutoClick()) return;

                // at=2：延迟后点击目标 View
                MAIN.postDelayed(() -> {
                    try {
                        View target = null;
                        if (hit.clkTxt != null && !hit.clkTxt.isEmpty()) {
                            List<View> views = hit.findByText(dialog, hit.clkTxt);
                            if (!views.isEmpty()) target = views.get(0);
                        }
                        if (target == null) target = hit.findClickView(dialog, sPkg);
                        if (target != null) {
                            target.performClick();
                            XLog.d("auto click dialog view");
                        }
                    } catch (Throwable e) {
                        XLog.w("auto click failed: " + e.getMessage());
                    }
                }, hit.d > 0 ? hit.d : 0);
            }
        });
    }

    /** 找到第一条命中的弹框规则。 */
    private static DlgHookInfo match(Dialog dialog) {
        if (sList == null || sList.isEmpty()) return null;
        AppRule rule = Runtime.sAppRule;
        if (rule == null || !rule.isOpen) return null;

        // 优先用「当前 Activity.Dialog」，其次「包名.Dialog」
        String actName = Runtime.sCurActName;
        for (DlgHookInfo info : sList) {
            if (info == null) continue;
            if (info.matches(dialog, sPkg)) return info;
        }
        return null;
    }

    private static void safeDismiss(Dialog dialog) {
        try {
            dialog.dismiss();
        } catch (Throwable e) {
            XLog.w("dismiss failed: " + e.getMessage());
        }
    }
}
