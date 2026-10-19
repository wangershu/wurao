package com.adhammer.model;

import android.content.ComponentName;
import android.text.TextUtils;

/**
 * 运行时广告规则（对应原版 AdRule）：RuleInfo + 已解析好的「首页落点」。
 */
public class AdRule extends RuleInfo {

    /** 最终跳转目标 Activity：优先 RuleInfo.to，其次 AppRule.home。 */
    public String home;

    public AdRule(String appHome, RuleInfo info) {
        this.ad = info.ad;
        this.at = info.at;
        this.ak = info.ak;
        this.d = info.d;
        this.f = info.f;
        this.to = info.to;
        this.m = info.m;
        this.home = TextUtils.isEmpty(info.to) ? appHome : info.to;
    }

    /** 生成跳转用的 ComponentName。 */
    public ComponentName toComponent(String pkg) {
        if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(home)) return null;
        return new ComponentName(pkg, home);
    }
}
