package com.adhammer.model;

/**
 * 环境伪装参数（对应原版 EnvParam）。
 *
 * <p>用于把 App 读到的设备信息 / 版本号改成规则指定的值，绕过「老版本有广告、
 * 新版本无广告」或「特定机型才下发广告」的判定。</p>
 */
public class EnvParam extends XModel {

    /** Build.BRAND */
    public String bd;
    /** device */
    public String dv;
    /** 语言 */
    public String lang;
    /** MAC */
    public String mac;
    /** Build.MANUFACTURER */
    public String mf;
    /** Build.MODEL */
    public String ml;
    /** Build.PRODUCT */
    public String pt;
    /** serial */
    public String sl;
    /** WIFI SSID */
    public String ss;
    /** versionCode */
    public int vc;
    /** versionName */
    public String vn;

    public static EnvParam fromJson(String json) {
        return com.adhammer.core.Jsonx.fromJson(json, EnvParam.class);
    }
}
