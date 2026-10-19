# AdHammer Replica —— 去广告 LSP 模块复刻版

从设备上已安装的 `me.app.xp.ad.hammer`（AdHammer / 原「大圣净化」）逆向复刻而来。
原作者已停更且失联，本项目在**保持规则格式完全兼容**的前提下重写实现，便于继续扩展。

- 原 APK：`adhammer.apk`（7.1 MB，SHA256 `204d945b...`，从设备 `56da4a86` 拉取）
- 反编译产物：`jadx_out/`（3293 个类）
- 规则规范：**[RULES.md](RULES.md)**

---

## 一、逆向结论：原版是怎么工作的

### 入口

```
assets/xposed_init → com.dahuo.sunflower.none.ui.x.LegacyAdHook
                      └→ AdHook (extends AdNoneHook)
```

`module.prop`：`minApiVersion=101, targetApiVersion=102, staticScope=false`

### 主流程

```
handleLoadPackage
  ├─ android           → 系统级处理（hosts / 防火墙）
  ├─ 模块自身           → isModuleActive 置 true（UI 自检）
  └─ 目标 App（主进程）
       └─ hook Application.onCreate
            ├─ 加载规则 /data/data/<pkg>/.rules/<pkg>.txt  (Gson lenient JSON)
            ├─ 读「悬浮窗/查看布局」开关
            └─ 按规则内容装配各类 Hooker
```

### 类映射表（原版 → 复刻）

| 原版类（混淆后） | 原始类名 | 复刻类 | 职责 |
|---|---|---|---|
| `LegacyAdHook` | — | `HookEntry` | 模块入口 |
| `AbstractC1425` | `AdNoneHook` | `AdHookEngine` | 主控：过滤 / 加载 / 分发 |
| `C1428` | `AdProcessor` | `AdProcessor` | 关闭 / 禁用 / 跳首页 / 拦截 |
| `C1391` | `ActHook` | `ActHook` | Activity 生命周期 + startActivity |
| `p079.ViewOnTouchListenerC3284` | `AllViewsHook` | `AllViewsHook` | **精准 View 匹配与隐藏** |
| `C1410` | `DialogHook` | `DialogHook` | 弹框：禁止 / 取消 / 自动点击 |
| `C3340` / `C3331` | Url / Host Hook | `UrlHook` / `HostHook` | URL / DNS 拦截 |
| `C3309` | EnvParam Hook | `EnvHook` | 设备与版本伪装 |
| `C1399` | `CommonHook` | `CommonHook` | 通知自动消失等 |
| `C3327` | Fragment Hook | `FragmentHook` | Fragment 内的广告 View |
| `AdRulesHelper` | — | `RuleLoader` | 规则加载与规整 |
| `C1417` | `AppRule` | `AppRule` | 规则容器（顶层 JSON） |
| `C1421` / `C1416` | `RuleInfo` / `AdRule` | 同名 | 单条规则 |
| `ViewHookInfo` | — | 同名 | 精准 View 规则 |
| `DlgHookInfo` | — | 同名 | 弹框规则 |
| `C1420` | `HostInfo` | `HostInfo` | URL / Host 规则 |
| `ReplaceInfo` | — | 同名 | 文件替换规则 |
| `C1418` | `EnvParam` | `EnvParam` | 环境伪装参数 |

---

## 二、工程结构

```
AdHammerReplica/
├── app/src/main/java/com/adhammer/
│   ├── HookEntry.java              模块入口
│   ├── core/
│   │   ├── AdHookEngine.java       主控 + 扩展点 FeatureHook
│   │   ├── AdProcessor.java        四种去广告动作的执行器
│   │   ├── RuleLoader.java         规则加载 / 解密 / 规整
│   │   ├── Runtime.java            运行时全局状态
│   │   ├── XposedEx.java           安全 hook 包装（类不存在也不崩）
│   │   ├── Strx / Resx / Jsonx     匹配 / 资源解析 / JSON 工具
│   │   └── Tips / XLog             提示 / 日志
│   ├── model/                      规则数据模型（字段名与线上规则一致）
│   │   ├── AppRule / RuleInfo / AdRule / ActionType
│   │   ├── ViewHookInfo / DlgHookInfo
│   │   └── HostInfo / ReplaceInfo / EnvParam / MethodRule
│   └── hooker/                     各类 Hooker
│       ├── ActHook / AllViewsHook / DialogHook
│       ├── UrlHook / HostHook / EnvHook
│       └── CommonHook / FragmentHook / FileReplaceHook
└── app/src/main/assets/xposed_init
```

---

## 三、构建

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

./gradlew :app:assembleDebug
```

本机没有 `gradle` 命令时，可直接用缓存的分发：

```bash
~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 四、安装与使用

1. 安装 APK（当前是 debug 签名）
2. 在 **LSPosed 管理器 → 模块** 中勾选它
3. 在模块的作用域里勾选要处理的应用（推荐作用域已写在 `res/values/strings.xml` 的 `xposed_scope`）
4. 把规则文件推到目标应用目录（需要 root）：

```bash
adb root
adb shell mkdir -p /data/data/<目标包名>/.rules
adb push <目标包名>.txt /data/data/<目标包名>/.rules/<目标包名>.txt
adb shell chmod 644 /data/data/<目标包名>/.rules/<目标包名>.txt
```

5. 强制停止目标应用后重新打开，在 LSPosed 日志里搜 `AdHammer` 确认：

```
AdHammer [I] loaded rule for com.sina.weibo -> AppRule{...}
AdHammer [I] install hooks for com.sina.weibo | pkg=... adMap=3 view=1 ...
AdHammer [D] hit ad activity: com.xxx.AdActivity at=9
```

---

## 五、如何写规则

见 **[RULES.md](RULES.md)**，包含：

- 规则文件位置与顶层结构
- `at` 动作类型全表（1 关闭 / 2 禁用 / 3 跳首页 / 9 防火墙拦截 / 8 精准 View …）
- 精准 View 规则的 12 个字段详解与匹配流程
- 弹框规则、URL/Host 拦截（**注意 host 是正则不是通配符**）、文件替换
- 制作规则的操作步骤与排错清单

---

## 六、扩展新功能

新增一种去广告手段只需两步：

**1. 写一个 Hooker**

```java
public class MyHook implements AdHookEngine.FeatureHook {
    @Override
    public void install(Context ctx, ClassLoader cl, String pkg, AppRule rule) {
        // 用 XposedEx.hook(...) 安装你的 hook
    }
}
```

**2. 在 `AdHookEngine.dispatch()` 里注册**

```java
if (/* 你的规则条件 */) {
    new MyHook().install(ctx, cl, pkg, rule);
}
```

如果涉及新的规则字段，往 `model/` 下加字段即可 —— Gson 会自动反序列化，
**已有规则文件不会因为新增字段而失效**。

建议：hook 一律走 `XposedEx`，它会在类不存在时静默跳过，避免目标 App 启动崩溃。

---

## 七、与原版的差异（未复刻部分）

| 能力 | 状态 | 说明 |
|---|---|---|
| Activity 级四种去广告动作（at 1/2/3/7/9） | ✅ 完整复刻 | |
| 精准 View 匹配隐藏（at=8） | ✅ 完整复刻 | 含父/子层级、子序号、文本与资源名校验 |
| 弹框规则（.Dialog） | ✅ 完整复刻 | |
| URL / Host 拦截 | ✅ 复刻 | URL 走 openConnection + WebView，Host 走 InetAddress |
| 环境 / 版本伪装 | ✅ 复刻 | Build 字段 + getPackageInfo |
| 文件替换 | ✅ 复刻 | 空文件夹占位 |
| Fragment 内广告 | ✅ 复刻 | |
| **ak 解密（at=6）** | ⚠️ 未还原 | 原版用私有对称算法（密钥 `f`），未公开。`RuleLoader.decrypt()` 已留扩展点，明文规则不受影响 |
| **自定义 Hook 脚本引擎（at=6）** | ⚠️ 未复刻 | 原版会动态加载脚本/dex 执行任意 hook |
| **悬浮窗 / 快照 / 一键生成规则 UI** | ❌ 未复刻 | 原版是付费 App 的交互部分；当前版本需手动编写规则 JSON |
| **云端规则同步与激活** | ❌ 未复刻 | 与原作者服务器强绑定，作者失联后已不可用 |
| **智能跳过（auto_skip.txt 3083 个包白名单）** | ❌ 未复刻 | 该文件只是包名列表，逻辑在 UI 侧；如需可自行实现 |

---

## 八、参考

- 原作者博客：https://hammer-finger.blogspot.com/
  - 《应用教程》—— 各 App 定位与下载
  - 《悟空使用说明》—— 四种去广告手段的取舍
  - 《支持精准匹配方式》—— 精准 View 规则的生成方法
  - 《沙僧使用说明》/《沙僧代理DNS拦截》—— URL 与域名拦截思路
