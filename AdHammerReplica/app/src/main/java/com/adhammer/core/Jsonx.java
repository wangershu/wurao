package com.adhammer.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;

/**
 * Gson 封装。规则文件是宽松 JSON（原版使用 lenient 模式），这里保持一致。
 */
public final class Jsonx {

    private static final Gson GSON = new Gson();

    private Jsonx() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return GSON.fromJson(json, clazz);
        } catch (Throwable e) {
            XLog.w("Jsonx.fromJson failed: " + e.getMessage());
            return null;
        }
    }

    public static <T> T fromJson(String json, Type type) {
        try {
            return GSON.fromJson(json, type);
        } catch (Throwable e) {
            XLog.w("Jsonx.fromJson(type) failed: " + e.getMessage());
            return null;
        }
    }

    /** 从 Reader 以 lenient 模式读取（规则文件可能含非标准 JSON）。 */
    public static <T> T fromReader(Reader reader, Type type) {
        try {
            JsonReader jr = new JsonReader(reader);
            jr.setLenient(true);
            T result = GSON.fromJson(jr, type);
            try {
                jr.close();
            } catch (Throwable ignored) {
            }
            return result;
        } catch (Throwable e) {
            XLog.w("Jsonx.fromReader failed: " + e.getMessage());
            return null;
        }
    }

    public static <T> T fromStream(InputStreamReader reader, TypeToken<T> token) {
        return fromReader(reader, token.getType());
    }

    public static String toJson(Object obj) {
        try {
            return GSON.toJson(obj);
        } catch (Throwable e) {
            return null;
        }
    }
}
