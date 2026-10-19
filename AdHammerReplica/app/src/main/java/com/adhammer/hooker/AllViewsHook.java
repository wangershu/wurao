package com.adhammer.hooker;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.Resx;
import com.adhammer.core.Runtime;
import com.adhammer.core.Strx;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;
import com.adhammer.model.ViewHookInfo;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 精准 View 匹配与隐藏（对应原版 AllViewsHook）。
 *
 * <h3>解决什么问题</h3>
 * <p>越来越多的「跳过」按钮既没有文字也没有 id，无法用常规方式定位。
 * 本类通过 <b>类名前缀 + 资源名 + 文本 + 父层级 + 子层级 + 子序号</b>
 * 组合定位到最底层 View，然后把它的宽高压成 0 并置为 GONE。</p>
 *
 * <h3>匹配流程</h3>
 * <ol>
 *   <li>入口 View 命中：类名前缀 {@code v}（startsWith）且开关 {@code s}</li>
 *   <li>入口校验：{@code id}（资源名）+ {@code t}（文本）</li>
 *   <li>定位真正目标：按 {@code pl} 向上找父 / 按 {@code cl} 向下找子 / 按 {@code sq} 指定子序号</li>
 *   <li>父、子还能各自用 p/pId/pt、c/cId/ct 二次校验</li>
 *   <li>执行隐藏：递归整棵子树，宽高归零 + GONE + padding 归零 + alpha 0</li>
 * </ol>
 *
 * <h3>触发时机（被动 + 主动双保险）</h3>
 * <ul>
 *   <li>View 被构造、setVisibility、setText、setLayoutParams 时立刻检查</li>
 *   <li>布局完成（OnGlobalLayout）时再检查一次，覆盖异步填充的广告</li>
 * </ul>
 */
public class AllViewsHook implements AdHookEngine.FeatureHook {

    private static AppRule sRule;
    private static String sPkg;

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        sRule = rule;
        sPkg = pkg;

        // ① View 可见性变化
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (((Integer) param.args[0]) == View.GONE) return;
                checkAndHide((View) param.thisObject);
            }
        });

        // ② 文本被填充（广告文案常常异步 setText）
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                checkAndHide((View) param.thisObject);
            }
        });

        // ③ 布局参数被设置
        XposedHelpers.findAndHookMethod(View.class, "setLayoutParams", ViewGroup.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) checkAndHide((View) param.thisObject);
                    }
                });

        // ④ 控件被创建的瞬间 + 布局完成回调
        XC_MethodHook ctorHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object thiz = param.thisObject;
                if (thiz instanceof View) {
                    View view = (View) thiz;
                    if (matchEntry(view.getClass().getName()) != null) {
                        view.getViewTreeObserver().addOnGlobalLayoutListener(
                                new ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        if (!checkAndHide(view)) {
                                            try {
                                                view.getViewTreeObserver()
                                                        .removeOnGlobalLayoutListener(this);
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }
                                });
                    }
                }
            }
        };
        hookAllConstructors(ViewGroup.class, ctorHook);
        hookAllConstructors(ImageView.class, ctorHook);
        hookAllConstructors(TextView.class, ctorHook);
    }

    private static void hookAllConstructors(Class<?> clazz, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllConstructors(clazz, hook);
        } catch (Throwable e) {
            XLog.w("hook ctor failed " + clazz.getName() + ": " + e.getMessage());
        }
    }

    // ==================== 对外：供 ActHook 主动调用 ====================

    /** 解析规则并最终隐藏目标 View。 */
    public static boolean resolveAndHide(ViewHookInfo info, View view, String pkg) {
        if (info == null) return true;
        if (view == null) return false;

        boolean ok;
        if (info.isHookType1()) {
            // ht=1：先校验父链
            if (info.pl > 0) {
                View parent = walkUp(view, info.pl);
                ok = parent != null && matchClass(parent, info.p, info.pId, info.pt);
            } else {
                ok = true;
            }
            if (!ok) return false;
            // 再校验子层
            if (info.cl > 0 && view instanceof ViewGroup && !TextUtils.isEmpty(info.c)) {
                ok = findChildStrict(info, view, 1);
            }
            if (ok) return hideBySeq(info, view);
        } else if (info.pl > 0) {
            View parent = walkUp(view, info.pl);
            if (parent != null && matchClass(parent, info.p, info.pId, info.pt)) {
                return hideBySeq(info, view);
            }
        } else {
            if (info.cl <= 0) return hideBySeq(info, view);
            if (view instanceof ViewGroup && !TextUtils.isEmpty(info.c)) {
                return findChildLoose(info, view, 0);
            }
        }
        return false;
    }

    /** 被动检查：某个 View 出现时判断是否要隐藏。 */
    public static boolean checkAndHide(View view) {
        if (view == null) return false;
        String name = view.getClass().getName();
        ViewHookInfo info = matchEntry(name);
        if (info == null) info = matchEntry(normalizeClassName(view));
        if (info == null) return false;
        if (!checkCondition(info.id, info.t, view)) return false;
        XLog.d("match view rule: " + info.v);
        return resolveAndHide(info, view, sPkg);
    }

    /** 取 View 的显示文本（非 TextView 返回空）。 */
    public static String textOf(View view) {
        try {
            return view instanceof TextView ? Strx.trim(((TextView) view).getText().toString()) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==================== 内部实现 ====================

    /** 按类名前缀找匹配的规则（先按包名 key，再按当前 Activity 类名 key）。 */
    private static ViewHookInfo matchEntry(String className) {
        if (TextUtils.isEmpty(className) || sRule == null || sRule.mViewMap == null) return null;
        java.lang.ref.WeakReference<android.app.Activity> ref = Runtime.sCurAct;
        String actName = (ref != null && ref.get() != null) ? ref.get().getClass().getName() : null;

        for (String key : new String[]{sPkg, actName}) {
            if (key == null) continue;
            List<ViewHookInfo> list = sRule.mViewMap.get(key);
            if (list == null || list.isEmpty()) continue;
            for (ViewHookInfo info : list) {
                if (info != null && info.s && !TextUtils.isEmpty(info.v)
                        && className.startsWith(info.v)) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * 校验 View 的类名族：命中类名后，再用资源名与文本二次确认。
     * 对应原版 m11260。
     */
    private static boolean matchClass(View view, String className, String idCond, String textCond) {
        if (TextUtils.isEmpty(className)) return true;
        if (view == null) return false;
        if (Strx.contains(view.getClass().getName(), className)) {
            return checkCondition(idCond, textCond, view);
        }
        String family = normalizeClassName(view);
        if (Strx.contains(family, className) && checkCondition(idCond, textCond, view)) {
            return true;
        }
        return false;
    }

    /**
     * 校验资源名 + 文本条件。对应原版 m11263。
     * 两个条件都为空时视为通过。
     */
    private static boolean checkCondition(String idCond, String textCond, View view) {
        if (view == null) return false;

        boolean pass = TextUtils.isEmpty(idCond);
        if (!pass) {
            String resName = Resx.entryNameOf(view);
            pass = !TextUtils.isEmpty(resName) && Strx.matchResName(resName, idCond);
        }
        if (!pass) return false;

        if (TextUtils.isEmpty(textCond)) return true;
        String text = textOf(view);
        return Strx.matchText(text, textCond);
    }

    /** 向上走 level 层父容器。 */
    private static View walkUp(View view, int level) {
        View cur = view;
        for (int i = 0; i < level; i++) {
            if (cur instanceof AbsListView) return null;
            Object parent = cur.getParent();
            if (parent instanceof ViewGroup) {
                cur = (ViewGroup) parent;
            } else if (parent instanceof View) {
                return (View) parent;
            } else {
                return null;
            }
        }
        return cur;
    }

    /** 子层查找（严格：必须达到指定层深）。对应原版 m11262。 */
    private static boolean findChildStrict(ViewHookInfo info, View view, int depth) {
        if (view == null || info.cl < depth) return false;
        if (info.cl == depth) {
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (matchClass(group.getChildAt(i), info.c, info.cId, info.ct)) return true;
                }
            }
            return false;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findChildStrict(info, group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** 子层查找（宽松：从当前层逐层下探）。对应原版 m11261。 */
    private static boolean findChildLoose(ViewHookInfo info, View view, int depth) {
        if (view == null || depth > info.cl) return false;
        if (depth == info.cl && matchClass(view, info.c, info.cId, info.ct)) {
            return hideBySeq(info, view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findChildLoose(info, group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /**
     * 按 sq（子序号）定位最终目标并隐藏。对应原版 m11256。
     * sq 为空 → 隐藏 view 本身及其整棵子树。
     */
    private static boolean hideBySeq(ViewHookInfo info, View view) {
        if (TextUtils.isEmpty(info.sq)) {
            hideTree(view);
            return true;
        }
        if (!(view instanceof ViewGroup)) {
            hideTree(view);
            return true;
        }
        ViewGroup group = (ViewGroup) view;
        int count = group.getChildCount();
        boolean done = false;
        for (String seq : info.sq.split(";")) {
            int idx = parseInt(seq, -1);
            if (idx >= 0 && idx < count) {
                hideTree(group.getChildAt(idx));
                done = true;
            }
        }
        return done;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    /** 递归隐藏整棵子树。对应原版 m11264。 */
    private static void hideTree(View view) {
        if (view == null) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideTree(group.getChildAt(i));
            }
        }
        hide(view);
    }

    /**
     * 真正的隐藏动作。对应原版 m11266。
     * 宽高归零 + 边距归零 + GONE + padding 归零 + 完全透明，
     * 五重保险，确保广告既不占位也不闪现。
     */
    private static void hide(View view) {
        try {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && (lp.height > 0 || lp.width > 0)) {
                lp.height = 0;
                lp.width = 0;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
                }
                view.setLayoutParams(lp);
            }
            view.setVisibility(View.GONE);
            view.setPadding(0, 0, 0, 0);
            view.setAlpha(0f);
        } catch (Throwable e) {
            XLog.w("hide view failed: " + e.getMessage());
        }
    }

    /**
     * 归一化类名：把常见的系统控件和自定义控件都收敛到可比对的族名。
     * 对应原版 m11258。
     */
    private static String normalizeClassName(View view) {
        if (view instanceof WebView) return WebView.class.getName();
        if (view instanceof ViewGroup) {
            if (view instanceof LinearLayout) return LinearLayout.class.getName();
            if (view instanceof RelativeLayout) return RelativeLayout.class.getName();
            if (view instanceof FrameLayout) return FrameLayout.class.getName();
        } else {
            if (view instanceof ImageButton) return ImageButton.class.getName();
            if (view instanceof ImageView) return ImageView.class.getName();
            if (view instanceof Button) return Button.class.getName();
            if (view instanceof TextView) return TextView.class.getName();
        }
        try {
            Class<?> superClass = view.getClass().getSuperclass();
            return superClass != null ? superClass.getName() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
