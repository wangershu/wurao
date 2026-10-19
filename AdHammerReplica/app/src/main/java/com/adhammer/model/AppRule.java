package com.adhammer.model;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个应用的完整规则集（对应原版 AppRule）。
 *
 * <p>存储位置：<code>/data/data/&lt;目标包名&gt;/.rules/&lt;目标包名&gt;.txt</code>（Android 9+），
 * Android 8 及以下落在模块私有目录 <code>app_rules</code> 下。</p>
 *
 * <p>规则文件是 <b>Gson 宽松 JSON</b>，本类即其顶层结构。</p>
 */
public class AppRule implements Serializable {

    private static final String TAG = "AdHammer AppRule --> ";

    /** 目标应用包名。 */
    public String pkg;

    /** 首页 Activity（跳过广告后的落点）。 */
    public String home;

    /** 总开关：false 则整份规则不生效。 */
    public boolean isOpen;

    /** 是否云端默认规则（影响提示展示）。 */
    public boolean isDef;

    /** 去广告成功时的 Toast 提示文案，为空则不提示。 */
    public String adTips;

    // ---------- 环境伪装 / 版本伪装（EnvParam） ----------
    /** 厂商 Build.MANUFACTURER */
    public String mf;
    /** 品牌 Build.BRAND */
    public String bd;
    /** 型号 Build.MODEL */
    public String ml;
    /** Build.PRODUCT */
    public String pt;
    /** 序列号 / device 伪装 */
    public String sl;
    /** device */
    public String dv;
    /** 语言 */
    public String lang;
    /** MAC 地址伪装 */
    public String mac;
    /** WIFI SSID 伪装 */
    public String ss;
    /** 版本伪装 versionCode */
    public int vc;
    /** 版本伪装 versionName */
    public String vn;

    /** 最后一次更新时间戳。 */
    public long lst;

    /** 广告 Activity 规则表：key = Activity 全名（或 xxx.Url / xxx.Replace / xxx.Hook）。 */
    public Map<String, RuleInfo> mAdMap = new HashMap<>();

    /** URL 拦截规则（at=1/2）。 */
    public List<HostInfo> mUrlList = new ArrayList<>();

    /** Host / DNS 拦截规则（at=3）。 */
    public List<HostInfo> mHostList = new ArrayList<>();

    /** 精准 View 规则：key = Activity 全名（或包名）→ ViewHookInfo 列表。 */
    public Map<String, List<ViewHookInfo>> mViewMap = new HashMap<>();

    /** 文件替换规则（内部缓存，由 .Replace 规则解析而来）。 */
    public transient List<ReplaceInfo> mReplaceList = new ArrayList<>();

    /** 弹框规则（内部缓存，由 .Hook 之外的 dlg 规则解析而来）。 */
    public transient List<DlgHookInfo> mDlgList = new ArrayList<>();

    public AppRule() {
    }

    public AppRule(boolean isOpen, boolean isDef, String adTips, String home) {
        this.home = home;
        this.isOpen = isOpen;
        this.isDef = isDef;
        this.adTips = adTips;
    }

    // ---------- 规则分发（对应原版 AppRule.m5717） ----------

    /** 把一条 URL/Host 规则归入对应列表。 */
    public void addHostInfo(HostInfo info) {
        if (info == null) return;
        if (info.isHostRule()) {
            mHostList.add(info);
        } else if (info.isUrlRule() || info.isUrlRule2()) {
            mUrlList.add(info);
        }
    }

    /**
     * 登记一条规则，按类型分流到 mAdMap / mViewMap / mUrlList / mHostList。
     */
    public void addRule(RuleInfo info) {
        if (info == null) return;

        // .Url 规则 → URL / Host 拦截表
        if (info.isUrlKey()) {
            addHostInfo(new HostInfo(info.at, info.ak));
            return;
        }

        // .Replace 规则 → 文件替换表
        if (info.isReplaceKey()) {
            mReplaceList.addAll(ReplaceInfo.parse(info.ak));
            return;
        }

        // .Dialog 规则 → 弹框规则表
        // ak 为空且 at=5 表示「禁止该范围内所有弹框」；否则 ak 是 DlgHookInfo 的 JSON 数组
        if (info.isDialogKey()) {
            if (info.ak != null && !info.ak.isEmpty()) {
                java.util.List<DlgHookInfo> dlgs = DlgHookInfo.fromJson(info.ak);
                if (dlgs != null) mDlgList.addAll(dlgs);
            } else {
                // 无条件禁止弹框：用一条空条件的规则占位
                DlgHookInfo all = new DlgHookInfo();
                all.at = 0;
                mDlgList.add(all);
            }
            return;
        }

        // at=8 → 精准 View 规则
        if (info.isViewRule()) {
            List<ViewHookInfo> views = ViewHookInfo.fromJson(info.ak);
            if (views == null || views.isEmpty()) return;
            mViewMap.put(info.ad, views);
            return;
        }

        mAdMap.put(info.ad, info);
    }

    /** 按 Activity 全名取规则，并绑定首页落点。 */
    public AdRule getAdRule(String actName) {
        RuleInfo info = mAdMap.get(actName);
        if (info == null) return null;
        return new AdRule(home, info);
    }

    /** 应用环境伪装参数。 */
    public void applyEnv(EnvParam env) {
        if (env == null) return;
        this.ss = env.ss;
        this.mac = env.mac;
        this.mf = env.mf;
        this.bd = env.bd;
        this.ml = env.ml;
        this.pt = env.pt;
        this.sl = env.sl;
        this.dv = env.dv;
        this.lang = env.lang;
        if (env.vc > 0) this.vc = env.vc;
        if (!TextUtils.isEmpty(env.vn)) this.vn = env.vn;
    }

    /** 是否有任何 Activity 规则。 */
    public boolean hasRules() {
        return mAdMap != null && !mAdMap.isEmpty();
    }

    @Override
    public String toString() {
        return "AppRule{pkg='" + pkg + "', home='" + home + "', mAdMap=" + mAdMap
                + ", isOpen=" + isOpen + ", adTips='" + adTips + "'}";
    }
}
