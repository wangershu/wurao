package com.adhammer.core;

import android.content.Context;

/**
 * 资源 id 解析（对齐原版 C3358.m11368）。
 *
 * <p>支持两种写法：</p>
 * <ul>
 *   <li>{@code skip_btn} —— 在目标包下解析</li>
 *   <li>{@code com.xxx:id/skip_btn} —— 全限定，跨包解析</li>
 * </ul>
 */
public final class Resx {

    private Resx() {
    }

    public static int resolveId(Context context, String name, String pkg) {
        if (context == null || name == null) return 0;
        try {
            if (name.contains(":id/")) {
                return context.getResources().getIdentifier(name, "id", null);
            }
            return context.getResources().getIdentifier(name, "id", pkg);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 取 View 的资源名（android:id 的 entryName）。 */
    public static String entryNameOf(android.view.View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }
}
