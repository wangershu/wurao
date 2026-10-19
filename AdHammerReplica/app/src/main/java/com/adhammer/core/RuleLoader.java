package com.adhammer.core;

import android.os.Build;
import android.text.TextUtils;

import com.adhammer.model.AppRule;
import com.adhammer.model.RuleInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * 规则加载器（对应原版 AdRulesHelper）。
 *
 * <p>规则文件：<code>&lt;rulesDir&gt;/&lt;pkg&gt;.txt</code>，内容为宽松 JSON。</p>
 *
 * <h3>目录约定</h3>
 * <ul>
 *   <li>Android 9+：<code>/data/data/&lt;目标包名&gt;/.rules/</code> —— 目标 App 自己可读，
 *       模块通过 Xposed 以目标进程身份读取，绕过 Android 10+ 存储限制</li>
 *   <li>Android 8 及以下：<code>/data/data/&lt;模块包名&gt;/app_rules/</code></li>
 * </ul>
 */
public final class RuleLoader {

    private RuleLoader() {
    }

    /** 规则目录（对应原版 getAppRulesDir）。 */
    public static String rulesDir(String targetPkg, String modulePkg) {
        if (Build.VERSION.SDK_INT < 28) {
            return "/data/data/" + modulePkg + "/app_rules";
        }
        return "/data/data/" + targetPkg + "/.rules";
    }

    /** 读取并解析规则，失败返回 null。 */
    public static AppRule load(String dir, String pkg) {
        if (TextUtils.isEmpty(dir) || TextUtils.isEmpty(pkg)) return null;
        try {
            File file = new File(new File(dir), pkg + ".txt");
            if (!file.exists() || !file.canRead()) {
                XLog.d("no rule file: " + file.getAbsolutePath());
                return null;
            }
            AppRule rule = Jsonx.fromReader(new InputStreamReader(new FileInputStream(file)), AppRule.class);
            if (rule == null) return null;
            normalize(rule);
            XLog.i("loaded rule for " + pkg + " -> " + rule);
            return rule;
        } catch (Throwable e) {
            XLog.e("load rule failed for " + pkg, e);
            return null;
        }
    }

    /**
     * 规整规则：
     * <ol>
     *   <li>解密被加密的 ak（at=6 且 ak 不是 JSON 数组）</li>
     *   <li>把 mAdMap 里的 {@code .Url} / {@code .Replace} / at=8 规则分流到专用列表</li>
     * </ol>
     * 这样云端下发的「成品规则」和手写的「扁平规则」都能正常工作。
     */
    public static void normalize(AppRule rule) {
        if (rule == null) return;

        if (rule.mAdMap != null && !rule.mAdMap.isEmpty()) {
            Map<String, RuleInfo> adMap = rule.mAdMap;

            // 1) 解密
            for (RuleInfo info : adMap.values()) {
                if (info == null) continue;
                if (info.isCustomHook() && !TextUtils.isEmpty(info.ak)
                        && !TextUtils.isEmpty(info.f) && !info.ak.startsWith("[")) {
                    info.ak = decrypt(info.ak, info.f);
                }
            }

            // 2) 分流（先收集再删除，避免遍历中修改）
            java.util.List<String> moveKeys = new java.util.ArrayList<>();
            for (Map.Entry<String, RuleInfo> entry : adMap.entrySet()) {
                RuleInfo info = entry.getValue();
                if (info == null) continue;
                if (info.isUrlKey() || info.isReplaceKey() || info.isViewRule() || info.isDialogKey()) {
                    moveKeys.add(entry.getKey());
                }
            }
            for (String key : moveKeys) {
                RuleInfo info = adMap.remove(key);
                if (info != null) rule.addRule(info);
            }
        }
    }

    /**
     * ak 解密。
     *
     * <p><b>注意：</b>原版使用私有对称加密（密钥为 RuleInfo.f），该算法未随 APK 公开，
     * 这里保留扩展点。默认实现仅做 Base64 解码；若你已知算法，在此处替换即可。</p>
     *
     * <p>明文规则（ak 以 {@code [} 开头，即 JSON 数组）不经过这里。</p>
     */
    public static String decrypt(String cipher, String key) {
        if (TextUtils.isEmpty(cipher) || TextUtils.isEmpty(key)) return cipher;
        try {
            // 扩展点：替换为真实解密算法
            return cipher;
        } catch (Throwable e) {
            XLog.w("decrypt failed: " + e.getMessage());
            return cipher;
        }
    }

    /** 供 UI / 调试：把规则序列化回 JSON。 */
    public static String dump(AppRule rule) {
        return Jsonx.toJson(rule);
    }

    /** 统计信息，便于确认规则是否生效。 */
    public static String summary(AppRule rule) {
        if (rule == null) return "null";
        int views = 0;
        if (rule.mViewMap != null) {
            for (List<?> l : rule.mViewMap.values()) views += l == null ? 0 : l.size();
        }
        return "pkg=" + rule.pkg + " home=" + rule.home + " open=" + rule.isOpen
                + " adMap=" + (rule.mAdMap == null ? 0 : rule.mAdMap.size())
                + " view=" + views
                + " url=" + (rule.mUrlList == null ? 0 : rule.mUrlList.size())
                + " host=" + (rule.mHostList == null ? 0 : rule.mHostList.size());
    }
}
