package com.adhammer;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（对应原版 LegacyAdHook）。
 *
 * <p>由 assets/xposed_init 声明，LSPosed 在目标进程启动时加载。</p>
 */
public class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void initZygote(StartupParam startupParam) {
        XLog.i("AdHammer Replica loaded in zygote, modulePath=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            AdHookEngine.handleLoadPackage(lpparam);
        } catch (Throwable e) {
            // 单个应用出错不能影响其他应用
            XLog.e("handleLoadPackage error in " + lpparam.packageName, e);
        }
    }
}
