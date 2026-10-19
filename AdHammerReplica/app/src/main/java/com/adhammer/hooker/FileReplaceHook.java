package com.adhammer.hooker;

import android.content.Context;

import com.adhammer.core.AdHookEngine;
import com.adhammer.core.XLog;
import com.adhammer.model.AppRule;
import com.adhammer.model.ReplaceInfo;

import java.io.File;

/**
 * 广告文件替换（对应原版的「广告文件替换」能力）。
 *
 * <p>原理：把广告缓存目录 / 文件替换成同名的空文件夹，
 * 让 App 既写不进也读不出，广告素材自然就加载失败了。</p>
 *
 * <p>安全约束见 {@link ReplaceInfo#isDanger}：禁止替换 /data、/data/data 和外置存储根目录。</p>
 */
public class FileReplaceHook implements AdHookEngine.FeatureHook {

    @Override
    public void install(Context context, ClassLoader classLoader, String pkg, AppRule rule) {
        if (rule.mReplaceList == null || rule.mReplaceList.isEmpty()) return;
        for (ReplaceInfo info : rule.mReplaceList) {
            if (info == null || !info.isValid()) continue;
            replace(info.path);
        }
    }

    /**
     * 用同名空文件夹替换目标。
     *
     * @return 替换是否成功
     */
    public static boolean replace(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File target = new File(path);
            if (!target.exists()) {
                // 目标不存在：直接建一个空目录占住位置，让后续写入失败
                return target.mkdirs();
            }
            deleteRecursive(target);
            // 用「空文件夹」占位：App 想写文件时发现同名是目录，写入失败；想读时也读不到内容
            return target.mkdirs();
        } catch (Throwable e) {
            XLog.w("replace failed " + path + " : " + e.getMessage());
            return false;
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) deleteRecursive(child);
                }
            }
            file.delete();
        } catch (Throwable e) {
            XLog.w("delete failed " + file.getAbsolutePath() + " : " + e.getMessage());
        }
    }
}
