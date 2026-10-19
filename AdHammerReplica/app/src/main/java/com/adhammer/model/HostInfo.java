package com.adhammer.model;

import java.util.regex.Pattern;

/**
 * URL / Host 拦截规则（对应原版 HostInfo）。
 *
 * <p>{@code host} 是<b>正则表达式</b>，用 {@code find()} 匹配（不是通配符）。
 * 因此博客里提到的「完整域名 / 通配符 *.domain」写法需要转义成正则，例如：</p>
 * <pre>
 *   googleads.g.doubleclick.net      → 完整域名
 *   .*\.doubleclick\.net             → 对应 *.doubleclick.net
 *   .*ad[s]?\.                        → 含 ad/ads 关键字
 * </pre>
 */
public class HostInfo extends XModel {

    protected static final String TAG = "AdHammer HostInfo --> ";

    /** 1/2 = URL 拦截，3 = Host(DNS) 拦截。 */
    public int at;

    /** 匹配用的正则表达式。 */
    public String host;

    public HostInfo() {
    }

    public HostInfo(int at, String host) {
        this.at = at;
        this.host = host;
    }

    /** 正则 find 匹配。 */
    public boolean matches(String target) {
        try {
            return Pattern.compile(host).matcher(target).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** at=3：Host / DNS 拦截。 */
    public boolean isHostRule() {
        return at == 3;
    }

    /** at=1：URL 拦截。 */
    public boolean isUrlRule() {
        return at == 1;
    }

    /** at=2：URL 拦截（扩展）。 */
    public boolean isUrlRule2() {
        return at == 2;
    }

    @Override
    public String toString() {
        return "HostInfo{at=" + at + ", host='" + host + "'}";
    }
}
