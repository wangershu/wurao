package com.adhammer.model;

import android.os.Environment;
import android.text.TextUtils;

import com.adhammer.core.Jsonx;
import com.adhammer.core.Strx;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 广告文件替换规则（对应原版 ReplaceInfo）。
 *
 * <p>原理：把广告缓存目录替换成同名的空文件/文件夹，让 App 无法写入也无法读取广告。</p>
 *
 * <p>出于安全考虑，禁止替换 {@code /data}、{@code /data/data} 与外置存储根目录。</p>
 */
public class ReplaceInfo extends XModel {

    protected static final String TAG = "AdHammer ReplaceInfo --> ";

    /** 合法路径形态：(^\.|^/|^[a-zA-Z])?/.+(/$)? */
    static Pattern PATTERN = Pattern.compile("(^\\.|^/|^[a-zA-Z])?/.+(/$)?");

    /** 1 = 替换生效。 */
    public int at;

    /** 目标路径。 */
    public String path;

    public ReplaceInfo() {
        this.at = 1;
    }

    public ReplaceInfo(int at, String path) {
        this.at = at;
        this.path = path;
    }

    public static ReplaceInfo single(String path) {
        if (TextUtils.isEmpty(path) || !matchPattern(path) || isDanger(path)) return null;
        return new ReplaceInfo(1, path);
    }

    /** 兼容两种写法：JSON 数组，或单条路径字符串。 */
    public static ArrayList<ReplaceInfo> parse(String raw) {
        ArrayList<ReplaceInfo> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        List<ReplaceInfo> list = Jsonx.fromJson(raw, new TypeToken<List<ReplaceInfo>>() {
        }.getType());
        if (list == null || list.isEmpty()) {
            ReplaceInfo one = single(raw);
            if (one != null) out.add(one);
        } else {
            for (ReplaceInfo info : list) {
                if (info != null && !TextUtils.isEmpty(info.path)
                        && matchPattern(info.path) && !isDanger(info.path)) {
                    out.add(info);
                }
            }
        }
        return out;
    }

    public static boolean matchPattern(String path) {
        return PATTERN.matcher(path).matches();
    }

    /** 危险路径保护：不得替换 /data、/data/data、外置存储根。 */
    public static boolean isDanger(String path) {
        if (TextUtils.isEmpty(path)) return true;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (TextUtils.isEmpty(p)) return true;
        if ("/data".equals(p) || "/data/data".equals(p)) return true;
        try {
            String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (!TextUtils.isEmpty(ext) && (ext.equals(p) || ext.contains(p))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    public boolean isValid() {
        return at > 0 && !TextUtils.isEmpty(path) && matchPattern(path) && !isDanger(path);
    }

    @Override
    public String toString() {
        return "ReplaceInfo{at=" + at + ", path='" + path + "'}";
    }
}
