package com.adhammer.model;

import android.text.TextUtils;

import java.io.Serializable;

/**
 * 单条规则（对应原版 RuleInfo）。
 *
 * <p>一条规则 = 「对某个广告 Activity（ad）执行某种动作（at）」。</p>
 *
 * <p>字段与线上规则 JSON <b>严格同名</b>，保证旧规则文件可直接复用。</p>
 */
public class RuleInfo implements Serializable {

    protected static final String TAG = "AdHammer RuleInfo --> ";

    /** 广告 Activity 全名（规则主键）。后缀约定：{@code .Url} = URL 规则，{@code .Replace} = 文件替换规则，{@code .Hook} = 自定义 Hook。 */
    public String ad;

    /**
     * 规则值。含义由 at 决定：
     * <ul>
     *   <li>at=8：ViewHookInfo 的 JSON 数组</li>
     *   <li>at=6：自定义 Hook 脚本（可加密，配合 f 解密）</li>
     *   <li>ad 以 .Url 结尾：URL/Host 规则内容</li>
     * </ul>
     */
    public String ak;

    /** 动作类型 action type，见 {@link ActionType}。 */
    public int at;

    /** 延迟毫秒数（d&gt;0 时延迟执行关闭动作）。 */
    public long d;

    /** 加解密密钥（at=6 时用于解密 ak）。 */
    public String f;

    /** 备注。 */
    public String m;

    /** 跳转目标 Activity（优先于 AppRule.home 作为「首页」）。 */
    public String to;

    public RuleInfo(String ad, String ak, int at, long d) {
        this.ad = ad;
        this.ak = ak;
        this.at = at;
        this.d = d;
    }

    public RuleInfo() {
        this.at = 0;
        this.d = 0L;
    }

    // ---------- 语义判定（与原版一一对应） ----------

    /** at 属于「跳过 / 替换到首页」类（3 或 7）。 */
    public boolean isSkipToHome() {
        return at == ActionType.SKIP || at == ActionType.REPLACE;
    }

    /** at=1：关闭活动页。 */
    public boolean isCloseAct() {
        return at == ActionType.CLOSE;
    }

    /** at=2：禁用活动页组件。 */
    public boolean isDisableAct() {
        return at == ActionType.DISABLE;
    }

    /** at=6：需要解密 ak 的自定义 Hook。 */
    public boolean isCustomHook() {
        return at == ActionType.CUSTOM_HOOK;
    }

    /** at=8：精准 View 匹配规则（ak 是 ViewHookInfo JSON 数组）。 */
    public boolean isViewRule() {
        return at == ActionType.VIEW;
    }

    /** at=5：文件替换。 */
    public boolean isReplace() {
        return at == ActionType.REPLACE_FILE;
    }

    /** at=9 或 500~503：防火墙式拦截。 */
    public boolean isBlock() {
        if (at == ActionType.BLOCK) return true;
        return at >= 500 && at <= 503;
    }

    /** at=100：特殊标记位。 */
    public boolean isFlag100() {
        return at == 100;
    }

    /** at=4 或 7：需要改写跳转目标。 */
    public boolean isRetarget() {
        return at == 4 || at == ActionType.REPLACE;
    }

    /** 非「特殊/保留」段的普通规则（at&lt;100 或 block 段）。 */
    public boolean isCommonRule() {
        return at < 100 || isBlock();
    }

    /** 该规则是一个 URL / Host 拦截规则（ad 以 .Url 结尾）。 */
    public boolean isUrlKey() {
        return !TextUtils.isEmpty(ad) && ad.endsWith(SUFFIX_URL);
    }

    /** 该规则是一个文件替换规则（ad 以 .Replace 结尾）。 */
    public boolean isReplaceKey() {
        return !TextUtils.isEmpty(ad) && ad.endsWith(SUFFIX_REPLACE);
    }

    /** 该规则是自定义 Hook（ad 以 .Hook 结尾）。 */
    public boolean isHookKey() {
        return !TextUtils.isEmpty(ad) && ad.endsWith(SUFFIX_HOOK);
    }

    /** 该规则是弹框规则（ad 以 .Dialog 结尾，键为「包名.Dialog」或「Activity.Dialog」）。 */
    public boolean isDialogKey() {
        return !TextUtils.isEmpty(ad) && ad.endsWith(SUFFIX_DIALOG);
    }

    public static final String SUFFIX_URL = ".Url";
    public static final String SUFFIX_REPLACE = ".Replace";
    public static final String SUFFIX_HOOK = ".Hook";
    public static final String SUFFIX_DIALOG = ".Dialog";

    @Override
    public String toString() {
        return "RuleInfo{ad='" + ad + "', to=" + to + ", f='" + f + "', ak='" + ak + "', at=" + at + ", d=" + d + '}';
    }
}
