package com.adhammer.model;

import android.R;
import android.app.Dialog;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import com.adhammer.core.Jsonx;
import com.adhammer.core.Resx;
import com.adhammer.core.Strx;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹框（Dialog）规则（对应原版 DlgHookInfo，DialogHook 消费）。
 *
 * <p>匹配条件：{@code id}（资源 id）或 {@code n}（Dialog 类名）或 {@code t}（出现的文字），
 * 三者为空则无条件匹配；动作由 {@code at} 决定。</p>
 */
public class DlgHookInfo extends XModel {

    protected static final String TAG = "AdHammer DlgHookInfo --> ";

    /** 动作：0=禁止弹框，1=自动取消弹框，2=自动点击弹框。 */
    public int at = 0;

    /** 判定用资源 id，多个候选用 ";" 分隔。 */
    public String id;

    /** 判定用文字（Dialog 内出现的文本），多个候选用 ";" 分隔。 */
    public String t;

    /** 判定用 Dialog 类名（equals）。 */
    public String n;

    /** at=2 时要点击的目标文字。 */
    public String clkTxt;

    /** at=2 时要点击的目标资源 id。 */
    public String clkId;

    /** 动作延迟毫秒。 */
    public long d;

    public static List<DlgHookInfo> fromJson(String json) {
        return Jsonx.fromJson(json, new TypeToken<List<DlgHookInfo>>() {
        }.getType());
    }

    /** 0=禁止弹框 / 1=自动取消弹框 / 2=自动点击弹框。 */
    public String describeAction() {
        if (at == 2) return "自动点击弹框";
        return at == 1 ? "自动取消弹框" : "禁止弹框";
    }

    public String describeCondition() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(id)) sb.append("viewId:").append(id);
        if (!TextUtils.isEmpty(t)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("出现文字:").append(t);
        }
        if (!TextUtils.isEmpty(clkTxt)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("点击文字:").append(clkTxt);
        }
        return sb.toString();
    }

    public boolean isAutoClick() {
        return at == 2;
    }

    public boolean isAutoCancel() {
        return at == 1;
    }

    /** 该 Dialog 是否命中本条规则的判定条件。 */
    public boolean matches(Dialog dialog, String pkg) {
        if (dialog == null) return false;
        if (TextUtils.isEmpty(id) && TextUtils.isEmpty(t) && TextUtils.isEmpty(n)) {
            return true;
        }
        boolean hit = false;
        if (!TextUtils.isEmpty(id)) {
            for (String one : id.split(";")) {
                int resId = Resx.resolveId(dialog.getContext(), one, pkg);
                if (resId == 0) continue;
                if (dialog.findViewById(resId) != null) {
                    hit = true;
                    break;
                }
            }
        }
        if (!hit && !TextUtils.isEmpty(n)) {
            hit = TextUtils.equals(n, dialog.getClass().getName());
        }
        if (hit || TextUtils.isEmpty(t)) return hit;
        return findByText(dialog, t).size() > 0;
    }

    /** 在 Dialog 的 DecorView 内按文字查找 View（命中任一候选即返回）。 */
    public ArrayList<View> findByText(Dialog dialog, String texts) {
        ArrayList<View> out = new ArrayList<>();
        View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (decor instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) decor;
            for (String one : texts.split(";")) {
                group.findViewsWithText(out, one, ViewGroup.FIND_VIEWS_WITH_TEXT);
                if (!out.isEmpty()) break;
            }
        }
        return out;
    }

    /** 按 clkId 找到要点击的 View。 */
    public View findClickView(Dialog dialog, String pkg) {
        if (dialog == null || TextUtils.isEmpty(clkId)) return null;
        int resId = Resx.resolveId(dialog.getContext(), clkId, pkg);
        if (resId == 0) {
            // 兼容 android.R.id.button1/2/3
            if (Strx.contains(clkId, "button1")) resId = R.id.button1;
            else if (Strx.contains(clkId, "button2")) resId = R.id.button2;
            else if (Strx.contains(clkId, "button3")) resId = R.id.button3;
        }
        return resId != 0 ? dialog.findViewById(resId) : null;
    }

    @Override
    public String toString() {
        return "DlgHookInfo{at=" + at + ", id='" + id + "', t='" + t + "', clkTxt='" + clkTxt
                + "', clkId='" + clkId + "', d=" + d + '}';
    }
}
