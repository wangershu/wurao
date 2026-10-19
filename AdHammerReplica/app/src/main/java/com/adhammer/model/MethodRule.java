package com.adhammer.model;

/**
 * 方法级拦截规则（对应原版 C1419，FragmentHook / 自定义 Hook 消费）。
 *
 * <p>可直接把某个类的某个方法「返回值改掉」或「直接 return」，
 * 用于屏蔽广告 SDK 的开关方法，例如 {@code isAdEnabled()} 强制返回 false。</p>
 */
public class MethodRule extends XModel {

    /** 目标类名（全名）。 */
    public String cn;

    /** 目标方法名。 */
    public String mn;

    /** 宿主类名（Fragment 场景下的外层 Activity/Fragment）。 */
    public String sCn;

    /** 源字段名 / 静态字段名。 */
    public String sFn;

    /** 字段值。 */
    public String sFv;

    /** 返回值类型：void/boolean/int/String/... */
    public String rt;

    /** 强制返回的值（字符串形式，按 rt 转换）。 */
    public String rtt;

    /** 文本条件。 */
    public String t;

    /** 匹配值。 */
    public String v;

    /** 参数签名（可选）。 */
    public String fields;

    /** 坐标 x（手势类扩展用）。 */
    public int x;

    /** 坐标 y（手势类扩展用）。 */
    public int y;

    /** 计数 / 次数。 */
    public int c;

    /** 延迟毫秒。 */
    public long d;

    public static MethodRule[] fromJson(String json) {
        return com.adhammer.core.Jsonx.fromJson(json, MethodRule[].class);
    }
}
