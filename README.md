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

### 发布包（release）

```bash
./gradlew :app:assembleRelease     # 产物 app/build/outputs/apk/release/app-release.apk
```

签名从这四个变量读（环境变量优先，其次 `-P`）：

| 变量 | 说明 |
|---|---|
| `RELEASE_STORE_FILE` | keystore 路径 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名（`adhammer`） |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

都没配时会自动退回 debug 签名，构建不会失败。

release 构建会开 R8（类名混淆 + 资源收缩），保留规则见 `app/proguard-rules.pro`：
LSPosed 入口类、Gson 模型、Manifest 里的组件都必须保名。

### 版本号

版本号**由 tag 驱动**，不用手改 `build.gradle.kts`：

| 变量 | 来源 | 默认值 |
| --- | --- | --- |
| `VERSION_NAME` | CI 取 tag 去掉 `v`（`v1.2.0` → `1.2.0`） | `1.1.1` |
| `VERSION_CODE` | CI 取 `github.run_number`（保证单调递增） | `2` |

本地想指定：`VERSION_NAME=1.2.0 VERSION_CODE=3 ./gradlew :app:assembleRelease`。

### CI / 发版

- push 到 `main` → `Build Debug APK`
- push tag `v*` → `Release APK`：`assembleRelease` → 上传 artifact → 自动建 GitHub Release，
  附上 APK **和它的 `.sha256` 校验文件**
   （签名 keystore 存在仓库 secrets：`SIGNING_KEYSTORE_BASE64` / `SIGNING_KEYSTORE_PASSWORD` /
   `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`）

发版流程：`git tag vX.Y.Z && git push origin vX.Y.Z` —— 就这一步，版本号自动跟着走。
下载后建议核对校验值：

```bash
sha256sum -c adhammer-vX.Y.Z.apk.sha256
```

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

### 应用详情页：按三个子系统分块

应用管理 → 点开某个应用，规则按「悟空 / 八戒 / 沙僧」三块组织，
对齐原版「拦截模式 → App → 该 App 的规则」这一层级：

| 子系统 | 里面有什么 | 入口 |
| --- | --- | --- |
| 悟空 · 页面与控件拦截 | 通用规则 / URL 拦截 / 精准 View / 组件屏蔽 / 文件替换 | 每类一个入口 → 该类规则列表 → 编辑器（表单 / JSON 双模式） |
| 八戒 · 自动跳过任务 | 订阅同步下来的自动化任务，按 Activity 匹配后顺序点击 / 滑动 | 任务列表 |
| 沙僧 · 域名拦截 | 这个应用专属的域名（手写或 APK 分析器生成）+ 全局域名黑名单 | 此应用的域名 / 全局域名黑名单 |

- 每块右侧的开关 = 「这个应用要不要装这个子系统」，用来排查是哪一块在起作用
- 顶部的大开关 = 整应用白名单（关掉后一条 hook 都不装）
- 订阅里的域名黑名单是**全局**的（对所有应用生效）；「此应用的域名」只对当前应用生效，
  也是 APK 分析器产出域名的落点

存储 key：

| key | 含义 |
| --- | --- |
| `rules:<pkg>` | 悟空规则（RulePack 列表） |
| `tasks:<pkg>` | 八戒任务 |
| `hosts` | 沙僧全局域名（订阅同步） |
| `hosts:<pkg>` | 沙僧按应用的域名 |
| `cfg:off:<wk\|bj\|ss>:<pkg>` | 按应用关掉某个子系统 |

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
| **悬浮窗 / 快照 / 一键生成规则 UI** | ✅ 复刻 | 悬浮窗「快照 / 取点」→ 规则工厂勾选 → 生成 View 规则 + 八戒步骤 |
| **智能跳过（auto_skip 白名单）** | ✅ 复刻 | 设置 → 自动跳过，名单可增删、可排除，支持「新应用自动加入」 |
| **取消弹框列表 / 应用升级 / 青少年模式** | ✅ 复刻 | 设置 → 弹框拦截，关键词可编辑 |
| **文件替换（一键 / 批量 / 自动检测）** | ✅ 复刻 | 设置 → 文件替换，默认关闭（会真动文件系统） |
| **八戒步骤编辑（增删改、上下移动）** | ✅ 复刻 | 应用详情 → 八戒任务 → 单条任务 → 编辑步骤 |
| **悟空实验室（规则自检）** | ✅ 复刻 | 设置 → 悟空实验室，填 Activity / id 看会命中哪些规则 |
| **沙僧代理（VpnService）** | ⚠️ 部分复刻 | 见下：DNS 级拦截，不做 HTTPS 中间人 |
| **云端规则同步与激活** | ❌ 未复刻 | 与原作者服务器强绑定，作者失联后已不可用 |
| **微信扩展（拆红包 / 自动登录等）** | ❌ 不做 | 涉及账号与资金，风险高于收益 |

### 沙僧代理与原版的差异

原版是「VPN + MITM + 自签名证书」，能按 **URL 路径** 拦广告，代价是要装证书、且会解密 HTTPS。
这一版刻意改成 **DNS 级拦截**：

| | 原版 | 本版 |
| --- | --- | --- |
| 拦截粒度 | 域名 + URL 路径 | 域名（含子域名后缀匹配） |
| 需要证书 | 是（自签名 CA） | 否 |
| 解密 HTTPS | 是 | 否 |
| 命中后 | 返回错误页 / 断开 | 解析到 `0.0.0.0`，连接立刻失败 |
| 覆盖范围 | 所有应用 | 所有应用（可单独排除） |

URL 路径级的活儿交给悟空的 `rp=11`（URL 拦截）。两个沙僧可以同时开着，读的是同一份域名表。

---

## 八、反逆向与 AI 使用限制

完整声明见 **[AI_POLICY.md](AI_POLICY.md)**（机器可读版：`ai.txt`、`llms.txt`，
随包分发版：`AdHammerX/app/src/main/assets/AI_POLICY.txt`，应用内「关于 → AI 与逆向限制」）。

要点：**禁止**任何 AI 系统 / 大语言模型 / 自动化分析 Agent 对本软件做反编译、
反混淆、行为复刻、规则抽取或再实现；**禁止**用于模型训练、微调、蒸馏、RAG 索引或数据集构建。
允许人类出于个人学习、安全研究与兼容性适配阅读源码。

### 已落地的技术措施

| 措施 | 位置 | 作用 |
| --- | --- | --- |
| R8 混淆 + 资源收缩 | `app/build.gradle.kts`（release）+ `app/proguard-rules.pro` | 类名/方法名不可逆，抬高静态分析门槛 |
| 签名校验 | `util/Guard.kt` + `BuildConfig.SIGN_SHA256` | 构建期把证书 SHA-256 写死进包，被重打包重新签名就对不上，直接拒绝启动 |
| 注入 / 调试检测 | `util/Guard.kt` | 扫 `/proc/self/maps` 里的 frida / gadget，查 27042 端口，查 debuggable 与调试器 |
| 禁止备份与迁移 | `AndroidManifest.xml` + `res/xml/data_extraction_rules.xml` | `adb backup` 与换机迁移都导不出规则数据 |
| 发布包 SHA-256 | `.github/workflows/release.yml` | 每个 Release 附 `.sha256`，可核验收到的包没被换过 |

局限：这些只挡「改两下就发出去」的二次分发，挡不住对症下药的逆向 ——
要真拦死得上壳 + 服务端校验，那不是这个体量该做的事。

---

## 九、参考

- 原作者博客：https://hammer-finger.blogspot.com/
  - 《应用教程》—— 各 App 定位与下载
  - 《悟空使用说明》—— 四种去广告手段的取舍
  - 《支持精准匹配方式》—— 精准 View 规则的生成方法
  - 《沙僧使用说明》/《沙僧代理DNS拦截》—— URL 与域名拦截思路
