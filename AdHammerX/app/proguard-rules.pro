# R8 规则。
#
# 目标：类名/方法名混淆，抬高静态逆向的门槛；同时不能弄坏三样东西 ——
#   1. LSPosed 入口类（META-INF/xposed/java_init.list 里按全名声明，改名就装不上 hook）
#   2. Gson 模型（字段名跟订阅源 JSON 一一对应，改了就解析不出来）
#   3. Manifest 里声明的组件（无障碍服务 / 悬浮窗服务）

-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# ---------- 1. LSPosed 入口：必须保持全名 ----------
-keep class com.adhammer.hook.AdHammerModule { *; }
-keep class com.adhammer.hook.** { *; }
-keep class io.github.libxposed.** { *; }

# ---------- 2. Gson 模型：字段名与订阅源同名，一个都不能改 ----------
-keep class com.adhammer.rules.model.** { *; }
-keepclassmembers class com.adhammer.rules.model.** {
    <fields>;
    <methods>;
}

# ---------- 3. Manifest 声明的组件 ----------
-keep class com.adhammer.ui.MainActivity { *; }
-keep class com.adhammer.float.** { *; }

# ---------- 4. 反射兜底 ----------
# Reflect.kt 只查 Android 框架的类（android.* / java.*），不涉及自身类名；
# 但规则存储的 key 与配置常量是拼字符串用的，整类留下更稳。
-keep class com.adhammer.rules.RuleStore { *; }
-keep class com.adhammer.rules.Cfg { *; }
-keep class com.adhammer.util.** { *; }

# ---------- 5. 三方库 ----------
-keep class top.yukonga.miuix.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.gson.**
-dontwarn org.jetbrains.annotations.**
-dontwarn androidx.compose.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ---------- 6. 资源收缩 ----------
# assets 与 META-INF 属于 Java 资源，AGP 的资源收缩不会动它们；
# 需要保住的其他资源在 res/values/keep.xml 里用 tools:keep 声明。
