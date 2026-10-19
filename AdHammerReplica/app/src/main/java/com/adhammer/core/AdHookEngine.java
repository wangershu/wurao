package com.adhammer.core;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;

import com.adhammer.hooker.ActHook;
import com.adhammer.hooker.AllViewsHook;
import com.adhammer.hooker.CommonHook;
import com.adhammer.hooker.DialogHook;
import com.adhammer.hooker.EnvHook;
import com.adhammer.hooker.HostHook;
import com.adhammer.hooker.UrlHook;
import com.adhammer.model.AdRule;
import com.adhammer.model.AppRule;
import com.adhammer.model.RuleInfo;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块主控（对应原版 AdNoneHook）。
 *
 * <p>职责：过滤目标进程 → 加载规则 → 按规则内容装配各类 Hooker。</p>
 *
 * <h3>扩展新功能的位置</h3>
 * <p>新增一种去广告手段时，只需：</p>
 * <ol>
 *   <li>写一个 {@code XxxHook}，实现 {@link FeatureHook}</li>
 *   <li>在 {@link #dispatch} 里按规则条件注册它</li>
 * </ol>
 */
public final class AdHookEngine {

    public static final String MODULE_PKG = "com.adhammer.replica";

    /** 已知会崩溃或强对抗的应用，直接跳过。 */
    private static final Set<String> BLACKLIST = new HashSet<>();

    static {
        BLACKLIST.add("com.drakeet.purewriter");
        BLACKLIST.add("com.google.android.youtube");
    }

    /** 不 hook 的系统/桌面/硬件相关包前缀。 */
    private static final String[] SKIP_PREFIX = {
            "com.miui.home", "com.qualcomm", "com.mediatek", "com.android.systemui",
            "com.android.skin", "com.android.providers", "com.android.phone",
            "com.android.camera", "org.omnirom", "com.meizu", "com.lge",
            "com.letv", "com.lenovo"
    };

    private static final AtomicBoolean sLoaded = new AtomicBoolean(false);

    private AdHookEngine() {
    }

    /**
     * 所有子 Hooker 的统一接口，便于后续扩展。
     */
    public interface FeatureHook {
        /** 安装 hook。 */
        void install(Context context, ClassLoader classLoader, String pkg, AppRule rule);
    }

    /** 模块入口调用。 */
    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;

        // 1) 模块自身：把「模块已激活」标记置为 true，供 UI 自检
        if (MODULE_PKG.equals(pkg)) {
            try {
                XposedHelpers.findAndHookMethod(
                        "com.adhammer.ui.MainActivity", lpparam.classLoader,
                        "isModuleActive",
                        de.robv.android.xposed.XC_MethodReplacement.returnConstant(Boolean.TRUE));
            } catch (Throwable ignored) {
                // 尚未集成 UI 时忽略，不影响去广告能力
            }
            return;
        }

        // 2) 系统进程：预留（原版在此处理 hosts / 防火墙相关）
        if ("android".equals(pkg)) return;

        // 3) 只处理主进程（或同名进程）
        if (!lpparam.isFirstApplication && !pkg.equals(lpparam.processName)) return;

        // 4) 过滤
        if (BLACKLIST.contains(pkg)) {
            XLog.d("ignore blacklist app: " + pkg);
            return;
        }
        if (!isTarget(pkg)) return;

        // 5) 挂到 Application.onCreate —— 此时 Context / ClassLoader 都就绪
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.thisObject;
                        ClassLoader cl = ctx.getClassLoader();
                        Runtime.sPkg = pkg;

                        // 同一进程内规则只加载一次
                        if (sLoaded.compareAndSet(false, true)) {
                            String dir = RuleLoader.rulesDir(pkg, MODULE_PKG);
                            AppRule loaded = RuleLoader.load(dir, pkg);
                            if (loaded != null && loaded.pkg == null) loaded.pkg = pkg;
                            Runtime.sAppRule = loaded;
                        }

                        AppRule rule = Runtime.sAppRule;
                        if (rule == null || !rule.isOpen) return;

                        XLog.i("install hooks for " + pkg + " | " + RuleLoader.summary(rule));
                        dispatch(ctx, cl, pkg, rule);
                    } catch (Throwable e) {
                        XLog.e("install hooks failed", e);
                    }
                }
            });
        } catch (Throwable e) {
            XLog.e("hook Application.onCreate failed", e);
        }
    }

    /** 按规则内容装配 Hooker。 */
    private static void dispatch(Context ctx, ClassLoader cl, String pkg, AppRule rule) {
        // ① Activity 级：跳过 / 关闭 / 禁用 / 拦截广告页
        new ActHook().install(ctx, cl, pkg, rule);

        // ② View 级：精准匹配并隐藏广告 View
        if (rule.mViewMap != null && !rule.mViewMap.isEmpty()) {
            new AllViewsHook().install(ctx, cl, pkg, rule);
        }

        // ③ 弹框级：禁止 / 取消 / 自动点击 Dialog
        if (rule.mDlgList != null && !rule.mDlgList.isEmpty()) {
            new DialogHook().install(ctx, cl, pkg, rule);
        }

        // ④ URL 拦截
        if (rule.mUrlList != null && !rule.mUrlList.isEmpty()) {
            new UrlHook().install(ctx, cl, pkg, rule);
        }

        // ⑤ Host / DNS 拦截
        if (rule.mHostList != null && !rule.mHostList.isEmpty()) {
            new HostHook().install(ctx, cl, pkg, rule);
        }

        // ⑥ 文件替换：广告缓存替换成空文件夹
        if (rule.mReplaceList != null && !rule.mReplaceList.isEmpty()) {
            new com.adhammer.hooker.FileReplaceHook().install(ctx, cl, pkg, rule);
        }

        // ⑦ Fragment 内的广告 View
        if (rule.mViewMap != null && !rule.mViewMap.isEmpty()) {
            new com.adhammer.hooker.FragmentHook().install(ctx, cl, pkg, rule);
        }

        // ⑧ 环境 / 版本伪装
        if (hasEnvSpoof(rule)) {
            new EnvHook().install(ctx, cl, pkg, rule);
        }

        // ⑨ 通用：通知自动消失等
        new CommonHook().install(ctx, cl, pkg, rule);

        // ⑩ 组件启停（在应用启动时统一处理 at=1 / at=2）
        applyComponentState(ctx, pkg, rule);
    }

    /** at=1 启用、at=2 禁用广告 Activity 组件（对应原版 C1427 末尾逻辑）。 */
    private static void applyComponentState(Context ctx, String pkg, AppRule rule) {
        if (rule.mAdMap == null || rule.mAdMap.isEmpty()) return;
        PackageManager pm = ctx.getPackageManager();
        for (Map.Entry<String, RuleInfo> entry : rule.mAdMap.entrySet()) {
            RuleInfo info = entry.getValue();
            if (info == null) continue;
            android.content.ComponentName cn = new android.content.ComponentName(pkg, entry.getKey());
            if (info.isCloseAct()) {
                AdProcessor.enableComponent(pm, cn);
            } else if (info.isDisableAct()) {
                AdProcessor.disableComponent(pm, cn);
            }
        }
    }

    private static boolean hasEnvSpoof(AppRule rule) {
        return rule.vc > 0 || (rule.vn != null && !rule.vn.isEmpty())
                || rule.mf != null || rule.bd != null || rule.ml != null
                || rule.pt != null || rule.sl != null || rule.dv != null
                || rule.lang != null || rule.mac != null || rule.ss != null;
    }

    /** 是否是需要 hook 的目标（排除系统/桌面/芯片相关）。 */
    private static boolean isTarget(String pkg) {
        if (pkg == null) return false;
        for (String prefix : SKIP_PREFIX) {
            if (pkg.startsWith(prefix)) return false;
        }
        return true;
    }

    /** 供 ActHook 查询：按 Activity 名取规则。 */
    public static AdRule getAdRule(String actName) {
        AppRule rule = Runtime.sAppRule;
        return rule == null ? null : rule.getAdRule(actName);
    }
}
