package com.adhammer.core;

import android.text.TextUtils;

/**
 * 字符串匹配工具（对齐原版 C5762 的语义）。
 *
 * <p>规则里多处支持「多个候选用 ; 分隔」以及「#文本# 表示全等匹配」，
 * 统一在这里实现，便于后续扩展新的匹配模式（如正则）。</p>
 */
public final class Strx {

    private Strx() {
    }

    /** haystack 是否包含 needle。 */
    public static boolean contains(CharSequence haystack, CharSequence needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toString().contains(needle.toString());
    }

    /** 忽略大小写相等（用于资源名比对）。 */
    public static boolean equalsIgnoreCase(CharSequence a, CharSequence b) {
        if (a == null || b == null) return false;
        return a.toString().equalsIgnoreCase(b.toString());
    }

    public static boolean startsWith(String text, String prefix) {
        return text != null && prefix != null && text.startsWith(prefix);
    }

    /** 拆分为候选数组；空白串返回空数组。 */
    public static String[] split(String text, String sep) {
        if (TextUtils.isEmpty(text)) return new String[0];
        return text.split(sep);
    }

    public static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * 文本条件匹配，支持三种写法：
     * <ul>
     *   <li>{@code #精确文本#} —— 全等（忽略大小写）</li>
     *   <li>{@code a;b;c} —— 多个候选任一命中</li>
     *   <li>其它 —— 包含匹配</li>
     * </ul>
     */
    public static boolean matchText(String viewText, String condition) {
        if (TextUtils.isEmpty(condition)) return true;
        if (TextUtils.isEmpty(viewText)) {
            // 无文本时，只有条件写成 "##" 才算命中（表示匹配空文本）
            return contains(condition, "##");
        }
        if (contains(condition, ";")) {
            for (String one : condition.split(";")) {
                if (matchSingle(viewText, one)) return true;
            }
            return false;
        }
        return matchSingle(viewText, condition);
    }

    private static boolean matchSingle(String viewText, String one) {
        if (contains(one, "#") && one.length() > 1) {
            // #xxx# → 全等
            return equalsIgnoreCase("#" + viewText + "#", one);
        }
        return contains(viewText, one);
    }

    /** 资源名条件匹配（多个候选用 ; 分隔，忽略大小写相等）。 */
    public static boolean matchResName(String resName, String condition) {
        if (TextUtils.isEmpty(condition)) return true;
        if (TextUtils.isEmpty(resName)) return false;
        if (contains(condition, ";")) {
            for (String one : condition.split(";")) {
                if (equalsIgnoreCase(resName, one)) return true;
            }
            return false;
        }
        return equalsIgnoreCase(resName, condition);
    }
}
