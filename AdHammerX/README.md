# AdHammerX —— 大圣净化 Kotlin 复刻版

原版 `me.app.xp.ad.hammer`（大圣净化）已停更，本项目用 **Kotlin + LSPosed API 102（libxposed）+ miuix** 重写，
保留原版三大子系统（悟空 / 八戒 / 沙僧）与同一套订阅规则格式，并补齐了「规则可编辑」「本地造规则」这两块原版薄弱的能力。

```
构建产物：app/build/outputs/apk/debug/app-debug.apk
包名：com.adhammer.x
```

---

## 1. 技术栈

| 项 | 版本 | 说明 |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | AGP 9 起 Kotlin 内置，**不再需要** `org.jetbrains.kotlin.android` 插件 |
| Gradle | 9.8.1 | wrapper 已配置 |
| Kotlin | 2.4.21（内置） | 编译选项写在 `kotlin { compilerOptions { ... } }`，旧的 `kotlinOptions {}` 已移除 |
| compileSdk / buildTools | 37 / 37.0.0 | libxposed 102 的依赖元数据**强制**要求 compileSdk ≥ 37 |
| minSdk / targetSdk | 26 / 35 | |
| miuix | 0.9.4 | `top.yukonga.miuix.kmp:miuix-ui-android`，MIUI 风格 Compose 组件 |
| Compose | BOM 2026.09.00 | 由 miuix 侧的 compose 版本决定，需与之一致 |
| libxposed | 102.0.0 | `io.github.libxposed:api` / `:service` |
| Gson / OkHttp | 2.11.0 / 4.12.0 | 规则解析 / 订阅、特征库更新、AI 请求 |

> 装完 android-37 后，本地 `platforms/android-37` 是 `android-37.0` 的软链接
> （sdkmanager 建出的目录名是 `android-37.0/37.1`，AGP 只认 `android-37`）。

---

## 2. 与旧 Xposed API 的关键差异

| 旧 API（82/101） | API 102（本项目） |
| --- | --- |
| `assets/xposed_init` + Manifest meta-data | `META-INF/xposed/{module.prop, java_init.list, scope.list}` |
| `IXposedHookLoadPackage` | `XposedModule` → `onPackageReady(PackageReadyParam)` |
| `XposedHelpers.findAndHookMethod` | `hook(Executable).setId().setExceptionMode(PROTECTIVE).intercept { chain -> ... }` |
| `XC_MethodHook` 的 before/after | `Chain`：`proceed()` 继续、直接 return 即拦截、返回值即替换结果 |
| `param.thisObject / args[i]` | `chain.getThisObject() / chain.getArg(i) / chain.getArgs()` |
| `XSharedPreferences` | `getRemotePreferences(group)`（框架负责跨进程同步） |
| 模块 UI 自己判断激活 | `XposedServiceHelper.registerListener` + `XposedService` |

**API 102 在类加载层面禁止访问 `de.robv.android.xposed.*`**，所以 `XposedHelpers` / `XposedBridge` 全部不能用，
反射统一走 `hook/Reflect.kt`。

---

## 3. 目录结构

```
app/src/main/
├── kotlin/com/adhammer/
│   ├── hook/                 # 目标进程里跑的代码
│   │   ├── AdHammerModule.kt # 入口：onModuleLoaded / onPackageReady
│   │   ├── WuKong.kt         # 悟空：Activity / View / URL / 组件 / 文件替换
│   │   ├── ViewEngine.kt     # 悟空：ViewRule 精准匹配 + 五重隐藏（支持 ad="*" 全局规则）
│   │   ├── ShaSeng.kt        # 沙僧：InetAddress.getByName → 127.0.0.1
│   │   ├── RuntimeCfg.kt     # hook 侧规则缓存（含首页 Activity）
│   │   ├── FeatureGuard.kt   # 每个特性独立 try/catch，一个挂了不影响其它
│   │   ├── HitLog.kt         # 命中记录环形缓冲（40 条，3s 节流写回）
│   │   ├── HomeConfig.kt     # at=3/4 的「首页」兜底
│   │   └── Reflect.kt        # 替代 XposedHelpers
│   ├── rules/
│   │   ├── model/Rules.kt    # 与订阅 JSON 同名的 Gson 模型（RulePack/RuleItem/ViewRule/Task/Step…）
│   │   ├── RuleStore.kt      # 读写：规则、任务、hosts、首页、日志、合并追加
│   │   ├── Cfg.kt            # 全局开关：暂停 / 悟空 / 八戒 / 沙僧 / 单应用白名单
│   │   ├── Subscriber.kt     # 订阅：下载 → md5 校验 → 解析 → 落库
│   │   ├── RuleGen.kt        # 快照 → 规则（本地启发式）
│   │   └── Jsonx.kt
│   ├── analyzer/             # ★ 本地 APK 分析（不需要大模型）
│   │   ├── AdFeatures.kt     # 广告特征库：内置 + 在线更新 + 手工导入
│   │   └── ApkAnalyzer.kt    # 组件/SDK/资源名/域名 四路证据 → 候选规则
│   ├── float/                # 悬浮窗、快照、自动跳过无障碍服务
│   ├── ai/                   # AI 写规则（OpenAI 兼容 /chat/completions，可选）
│   └── ui/                   # miuix 界面（结构参考 HyperIsland）
│       ├── MainActivity.kt   # Scaffold + 四页底栏 + 路由分发
│       ├── Nav.kt            # 极简路由栈（Dest / Navigator）
│       ├── AppIcons.kt       # 自绘图标（miuix 图集里没有的）
│       ├── Chart.kt          # Canvas 手绘图表（柱状 / 环形 / 排行条）
│       ├── Kit.kt            # MIUI 风格通用组件（列表项、箭头项、统计格、状态卡）
│       ├── ScreenHome.kt     # ① 主页（状态总览）+ ② 应用管理
│       ├── ScreenStats.kt    # ③ 统计（趋势 / 占比 / 排行）
│       ├── ScreenSettings.kt # ④ 设置根页 + 六个二级页
│       ├── ScreenAbout.kt    # 关于（开发者与链接占位）
│       ├── ScreenRules.kt    # 应用详情 / 规则编辑器 / 首页 Activity
│       ├── ScreenMore.kt     # 域名拦截 / 规则工厂 / 拦截记录
│       ├── ScreenTools.kt    # APK 分析器
│       └── UiState.kt        # 状态 + 规则 CRUD + 统计读取
└── resources/META-INF/xposed/  module.prop（minApiVersion=102）/ java_init.list / scope.list（229 个包名）
```

---

## 4. 三大子系统

### 悟空（Xposed 侧，主力）
`WuKong.kt` 装五类 hook：

1. **Activity 级**（ruleType 10）：`Activity.onCreate` / `startActivity` / `startActivityForResult`
   - `at=1` 关闭页面（`d>0` 时延迟按返回键，应对倒计时）
   - `at=2` 禁用组件
   - `at=3` 跳首页 / `at=4` 改写跳转目标（首页在 App 详情页右上角配置）
   - `at=5` Dialog 规则（按类名/资源名/文案匹配，支持禁止、自动取消、自动点击）
2. **View 级**（ruleType 14，`ViewEngine`）：`setVisibility` / `setText` / `setLayoutParams` / `onResume` 四个时机扫描，
   `ViewRule` 支持 `v/s/id/t/ht/pl/p/pId/pt/cl/c/cId/ct/sq`；`ad` 填 `*` 表示对所有页面生效
3. **URL 级**（ruleType 11）：`URL.openConnection` + `WebView.loadUrl`，正则预编译
4. **组件级**（ruleType 15）：`at=500/501/502/503` 用 `PackageManager.setComponentEnabledSetting` 屏蔽四大组件；
   `at=9` 在 `startActivity` 阶段掏空 Intent（防火墙式，效果最好）
5. **文件替换**（ruleType 20）：清空广告缓存目录/文件，带路径安全校验

### 八戒（无障碍）
`float/BaJieService.kt`：按 Activity 匹配订阅里的 `Task`，顺序执行 `Step`（点击/长按/滑动/坐标点击/延时/间隔）。

### 沙僧（DNS）
`ShaSeng.kt`：hook `InetAddress.getByName` / `getAllByName`，命中 hosts 名单就返回 `127.0.0.1`。
不开 VPN、不需要 root；域名可在「沙僧域名」页手工增删。

---

## 5. 规则是怎么流转的（相比原版的改进）

```
订阅 zip ──Subscriber──▶ 本地 SharedPreferences(adhammer_local)
                              │  任何写入后即推送
                              ▼
                     RemotePreferences(adhammer_rules)   ← 框架跨进程同步
                              │
                              ▼
              目标 App 进程：RuntimeCfg 读取 → WuKong/ShaSeng 安装 hook
```

原版把规则写进 `/data/data/<目标App>/.rules/`，需要目标 App 目录可写；
本项目用 API 102 的 `getRemotePreferences`，**不写目标 App 私有目录、不需要 root**。

写入语义分两种：
- **订阅同步 = 整包覆盖**（`putRules`）
- **手工/AI/分析器 = 追加合并**（`mergeRules`，按 `ruleType + name` 归并、`ad + adKey + actionType` 去重）

所以订阅更新不会冲掉你手写的规则，反之亦然。

---

## 6. 造规则的四种方式

| 方式 | 是否需要联网 | 适合场景 |
| --- | --- | --- |
| 订阅同步 | 是 | 拿现成规则，覆盖面最大 |
| 手工编辑 / 新增 | 否 | 修订阅里不对的那一条、补自家 App |
| 悬浮窗快照 → 规则工厂 | 否（AI 模式要） | 页面上就有一个「跳过」按钮，但不知道它叫什么 |
| **APK 分析器** | **否** | 想把某个 App 的广告组件一次性挖出来 |

### 6.1 手工编辑（原版就有的能力，已复刻）

规则页 → 选应用 → 六个分类 Tab（通用/URL/View/组件/文件/任务）：

- 每条规则右侧**开关**可单独启停，点条目进编辑器，右侧垃圾桶删除（或长按）
- 编辑器有两种模式：**表单**（动作 / 主键 ad / 规则值 adKey / 延时 / 备注 / 启用）和 **JSON**（整条规则直接改，适合从别处抄）
- 动作下拉按规则类型给出合法选项，并带填写提示（比如 View 类的 adKey 直接给出模板）
- 右上角「🏠」配首页 Activity：`at=3`（跳过到首页）/ `at=4`（改写跳转）需要知道跳到哪，订阅里没这个字段

### 6.2 APK 分析器（本项目新增，核心）

**不需要大模型。** 广告 SDK 是个有限集合，把「它们的类名前缀 / 常用资源名 / 上报域名」沉淀成特征库，
分析 APK 时只做匹配，得到的是**可解释**的候选：

| 证据 | 手段 | 产出 |
| --- | --- | --- |
| 组件清单 | `PackageManager` 取 Activity/Receiver/Service/Provider | Activity → `at=9` 拦截启动；其余 → 500/501/502/503 禁用 |
| 广告 SDK | `DexFile` 枚举类名，匹配 SDK 包名前缀 | 置信度 85~92，默认勾选 |
| 资源名 | `getIdentifier` 探测 `skip / close / ad_container …` 是否存在 | `at=8` View 规则（`pl=1` 连带隐藏父容器） |
| 域名 | 扫 dex 字符串常量，找广告/统计上报域名 | 沙僧 hosts 条目 |

安全策略：**启动页（launcher）永不进候选**；名字像主界面（`Main/Home/…`）又没命中 SDK 的一律跳过；
Activity 默认只给「拦截启动」而不是「禁用组件」，避免把 App 搞崩。

用法：工具页 → APK 分析器 → 填包名（或「选择已安装应用」/「选 APK」从文件挑）→ 开始分析 → 勾选 → 保存。

### 6.3 特征库维护（规则要长期维护，维护的其实是它）

`assets/ad_features.json`，结构：

```json
{
  "version": "2026.10.1",
  "sdks": [{ "id": "pangle", "name": "穿山甲", "prefixes": ["com.bytedance.sdk.openadsdk"] }],
  "activityWords": ["Ad", "Splash", "Interstitial"],
  "safeWords": ["Main", "Home", "Launcher"],
  "resWords": ["skip", "close", "ad_container"],
  "domainWords": ["ad.", "gdt", "pangle"],
  "domainExclude": ["googleapis", "weixin"]
}
```

设置页可以：在线更新（填一个 JSON 地址，和规则订阅同一套更新机制）、粘贴 JSON 导入、恢复内置。
新增一个广告 SDK = 往 `sdks` 里加一条，不用改代码。

### 6.4 AI（可选增强）

AI 只是「更好的启发式」：把**同一份快照**喂给模型，让它判断哪个布局是广告，返回**同样 schema** 的 JSON。
没填 Key 完全不影响其它三种方式。配置在设置页（OpenAI 兼容接口即可）。

---

## 7. 界面（MIUI 风格，miuix · 结构参考 HyperIsland）

底部**四个**页面，二级页面走路由栈（`ui/Nav.kt`），返回键自动出栈：

| Tab | 作用 | 关键内容 |
| --- | --- | --- |
| **主页** | 状态总览 | 框架接入检查（4 项）+ 三个子系统状态 + 今日/近 7 天/累计 + 悬浮窗 + 同步 + 最近拦截 |
| **应用** | 按 App 控制净化 | 全局（域名拦截 / APK 分析器 / 规则工厂）+ 应用列表（**逐 App 开关** + 箭头进详情改规则） |
| **统计** | 拦截了什么 | 近 7 天柱状图 + 类型占比环图 + 应用排行 + 明细入口 |
| **设置** | 模块自身配置 | 六个二级页（见下）+ 关于页入口 |

底栏图标用 miuix 官方图标集 `MiuixIcons`（`Home` / `GridView` / `Settings` / `Info`），
只有「统计」那个柱状图是自己画的（图集里没有，`ui/AppIcons.kt`）。

统一规范（照 MIUI 设置页）：`SmallTitle` 分组、列表项用 `BasicComponent` / `ArrowPreference`
（主标题 + 副标题 + 右侧开关/箭头）、触控区 ≥48dp、颜色一律取 `MiuixTheme.colorScheme`，不硬编码色值。

### 7.1 设置的分级子页

```
设置
├── 净化行为          三个子系统开关（页面净化 / 自动跳过 / 域名拦截）+ 总暂停
├── 订阅与规则源      订阅地址、广告特征库、AI 接口
├── 外观              主题（跟随系统/浅色/深色）、悬浮底栏
├── 高级              作用域申请、权限状态、框架信息
├── 备份与恢复        导出 JSON / 导入 JSON / 清日志 / 清统计
├── 使用帮助          启用步骤、规则来源、排错
└── 关于              版本、开发者（占位）、项目链接（占位）、第三方库
```

### 7.2 三个子系统的新名字

内部字段仍沿用订阅 JSON 的 `ruleType`（10/11/14/15/20/1111），只是**界面不再叫悟空/八戒/沙僧**：

| 界面名称 | 原代号 | 做什么 |
| --- | --- | --- |
| 页面净化 | 悟空 | Activity / URL / View / 组件 / 文件替换 |
| 自动跳过 | 八戒 | 无障碍自动点击任务 |
| 域名拦截 | 沙僧 | 广告域名解析到 127.0.0.1 |

### 7.3 开关体系（`rules/Cfg.kt`）

全部写在远程偏好 `cfg:*` 里，hook 进程、无障碍服务、模块 UI 三方读同一份：

| key | 含义 |
| --- | --- |
| `cfg:pause` | 总暂停：一条 hook 都不装 |
| `cfg:wukong` / `cfg:bajie` / `cfg:shaseng` | 三个子系统分别开关 |
| `cfg:app:<pkg>` | 单应用白名单：这个 App 完全不拦 |

hook 在目标进程启动时才安装，所以**改开关要重启目标 App** 才生效。

### 7.4 统计与日志

- 每次命中走 `hook/HitLog.kt` → `StatsStore.bump()`，累计到 `stat:total` / `stat:day:<yyyyMMdd>` /
  `stat:kind:<kind>` / `stat:app:<pkg>`，所以图表能长期累计，不只看最近 40 条
- 明细另存 `log:recent`（环形缓冲 40 条，3 秒节流写回），统计页和主页都能进
- 图表是 `ui/Chart.kt` 里用 Canvas 画的，没引第三方图表库，配色跟着主题走

### 7.5 图标

自适应图标（`mipmap-anydpi-v26/ic_launcher.xml`）：蓝色渐变底 + 白色斜置锤子，
另附 `ic_launcher_monochrome` 支持 Android 13+ 主题图标；应用内 `ic_hammer.xml` 用于关于页。

---

## 8. 构建与安装

```bash
cd adhammer/AdHammerX
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装完后在 LSPosed 管理器里启用模块并勾选作用域（`scope.list` 已按订阅里真实出现的 229 个包名生成），重启目标 App 生效。

> 环境要求：JDK 17、Android SDK platform 37 + build-tools 37.0.0。
> 若本地没有 37：`sdkmanager --install "platforms;android-37.0" "build-tools;37.0.0"`。

---

## 9. 后续可扩展点

- `MethodRule`（`ht=97`）：模型已建好，`WuKong` 里还没接方法级 hook，可按 `cn/mn/rt/rtt` 装上
- 八戒步骤编辑：目前任务只能看，不能改（订阅带过来的够用，自造的场景还少）
- 规则冲突自检：同一个 View 被多条规则命中时给出提示
- 特征库做成可订阅的独立仓库：`AdFeatures.fetchUpdate` 已经留好口子

---

## 10. 已知限制

- `at=6`（自定义 Hook / 加密脚本）未实现：原版脚本格式未完全逆向
- `teenagers`、`pro` 等 `app_config.txt` 字段只解析不使用
- 沙僧是进程内 DNS，系统级（其它进程 / 原生层）请求拦不到
- APK 分析器对未安装 APK 读不到资源表（`getResourcesForApplication` 限制），资源名探测只在已安装应用上生效
- 未做混淆，debug 包约 21MB（含全部 Compose + miuix）
