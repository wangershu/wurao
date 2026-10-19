package com.adhammer.model;

import android.text.TextUtils;

import com.adhammer.core.Jsonx;
import com.google.gson.reflect.TypeToken;

import java.io.Serializable;
import java.util.List;

/**
 * 精准 View 匹配规则（对应原版 ViewHookInfo，AllViewsHook 消费）。
 *
 * <p>解决「跳过按钮既没文字也没 ID」的场景：通过
 * <b>类名前缀 + 资源 id + 文本 + 父层级 + 子层级</b> 定位到最底层 View 后隐藏它。</p>
 *
 * <p>定位思路（见 {@link com.adhammer.hooker.AllViewsHook}）：</p>
 * <ol>
 *   <li>命中入口 View：类名前缀 {@code v}（startsWith）且开关 {@code s} 为 true</li>
 *   <li>校验入口 View 自身：{@code id}（资源名）+ {@code t}（文本）</li>
 *   <li>按 {@code pl} 向上回溯父容器，或按 {@code cl} 向下找子 View，或按 {@code sq} 指定子序号</li>
 *   <li>父/子还可再用 p/pId/pt、c/cId/ct 二次校验</li>
 *   <li>最终目标调用 hide()：宽高归零 + GONE + padding 归零 + alpha 0</li>
 * </ol>
 */
public class ViewHookInfo extends XModel implements Serializable {

    protected static final String TAG = "AdHammer ViewHookInfo --> ";

    /** 入口 View 的类名前缀（startsWith 匹配），如 "android.widget.TextView"。 */
    public String v;

    /** 规则开关，false 表示停用该条。 */
    public boolean s = true;

    /** 入口 View 的 resourceEntryName，多个候选用 ";" 分隔；支持 "pkg:id/name" 全限定。 */
    public String id;

    /** 入口 View 的文本，多个候选用 ";" 分隔；用 "#文本#" 表示全等匹配，否则为包含匹配。 */
    public String t;

    /** hook 类型：1=常规（支持父/子回溯），2/3=其他分支。 */
    public int ht;

    /** parent level：向上回溯的父层数，0 表示不回溯。 */
    public int pl;

    /** 父容器类名（包含匹配）。 */
    public String p;

    /** 父容器资源 id。 */
    public String pId;

    /** 父容器文本。 */
    public String pt;

    /** child level：向下查找的子层数，0 表示不查找。 */
    public int cl;

    /** 子 View 类名（包含匹配）。 */
    public String c;

    /** 子 View 资源 id。 */
    public String cId;

    /** 子 View 文本。 */
    public String ct;

    /** 子 View 序号（ViewGroup.getChildAt），多个用 ";" 分隔，如 "0;2"。 */
    public String sq;

    /** 备注。 */
    public String m;

    public ViewHookInfo() {
    }

    public ViewHookInfo(String v) {
        this.v = v;
    }

    /** 解析 ak 字段里的 ViewHookInfo JSON 数组。 */
    public static List<ViewHookInfo> fromJson(String json) {
        return Jsonx.fromJson(json, new TypeToken<List<ViewHookInfo>>() {
        }.getType());
    }

    // ---------- ht 判定 ----------

    public boolean isHookType1() {
        return ht == 1;
    }

    public boolean isHookType2() {
        return ht == 2;
    }

    public boolean isHookType3() {
        return ht == 3;
    }

    public String describeView() {
        if (TextUtils.isEmpty(v)) return "";
        return "view 名称:\n" + v;
    }

    public String describeId() {
        if (TextUtils.isEmpty(id)) return "";
        return "viewId:" + id;
    }

    @Override
    public String toString() {
        return "{p='" + p + "', pl=" + pl + ", v='" + v + "', c='" + c + "', cl=" + cl + '}';
    }
}
