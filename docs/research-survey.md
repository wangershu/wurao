# wurao 重头开始：三份调研与形态建议

> 2026-11-01。目的：**在写第一行代码之前，把「新 wurao 应该是什么」的依据摆出来**。
> 调研对象：原版大圣（`me.app.xp.ad.hammer`）、GKD、AdClose。
> 状态：**待拍板**。拍板后本文档升为形态基线。
> 旧实现（`~/WorkBuddy/2026-10-19-10-54-37/adhammer`）整体保留、只读参考，不继承任何代码。

---

## 零、结论先行

1. **原版大圣真正的底座不是控件树，是「组件名」。** 旧规则库 3655 条里 2453 条（67%）是 `BLOCK_PAGE`（组件/Activity 级拦截，其中 2444 条来自组件表），控件树类只有 289 条（8%）。我们上一轮把力气花在了 8% 上，复杂度却来自那里。

2. **三条路线恰好覆盖三个阶段，互补而非替代**：AdClose 管「渲染前」（SDK 初始化 / 请求），原版管「页面级」（组件拦截 / 跳首页 / 文件替换），GKD 管「渲染后」（控件定位 + 点击）。旧实现的 bug 集中在**把三条路线塞进一个模型**这件事上。

3. **GKD 最该学的不是选择器，是「规模随误伤增长，而不是随应用数增长」**（全局规则 + 例外名单）。旧库停更 21 个月就废了，根因是模型要求有人持续为新应用写规则。

---

## 一、原版大圣：事实清单

### 1.1 它是什么

| 项 | 值 | 来源 |
|---|---|---|
| 包名 / 版本 | `me.app.xp.ad.hammer` / `10.0.7`（versionCode 1000700） | `jadx` 解出的 AndroidManifest.xml |
| SDK | minSdk 27 / targetSdk 27（**很老**，行为是按老 Android 设计的） | 同上 |
| 组件规模 | 46 Activity + 4 Service，无 Receiver/Provider | 同上 |
| 权限 | `BIND_ACCESSIBILITY_SERVICE`（有八戒）、`SYSTEM_ALERT_WINDOW`、`QUERY_ALL_PACKAGES`、`WAKE_LOCK`、存储读写 | 同上 |
| Xposed 入口 | `com.dahuo.sunflower.none.ui.x.LegacyAdHook` | `extract/assets/xposed_init` |

→ **原版本身就是双引擎**：悟空（进程内 hook 屏蔽）+ 八戒（无障碍自动点击）+ 沙僧（hosts 域名）。我们上一轮把八戒的能力搬进进程内（R1），是对原版的增强，不是原版的能力。

### 1.2 规则形态

- 存储：`/data/data/<目标包名>/.rules/<目标包名>.txt`，一份应用一个文件，宽松 JSON（Gson lenient）。存目标应用私有目录以绕过 Android 10+ 存储限制。
- 顶层 `AppRule`：`pkg` / `home`（跳广告后的落点）/ `isOpen` / `isDef` / `adTips` + 环境伪装字段（`mf`/`bd`/`ml`/`pt`/`dv`/`sl`/`lang`/`mac`/`ss`/`vc`/`vn`）+ 四张表 `mAdMap` `mViewMap` `mUrlList` `mHostList`。
- `mAdMap` 的 key 是 **Activity 全名**，另有三种特殊后缀承载别的规则类型：`.Url` / `.Replace` / `.Dialog` / `.Hook`。

**动作 `at` 表（悟空命名空间）**

| at | 行为 | 备注 |
|---|---|---|
| 1 | `finish()` 当前页；`d>0` 时延迟后按返回键 | 会一闪而过 |
| 2 | `setComponentEnabledSetting(DISABLED)` | 可能闪退 |
| 3 / 7 | 启动 `home`/`to` 并 finish 当前页，无转场 | 「移花接木」 |
| 4 | 在 `startActivity` 阶段改写跳转目标 | |
| 5 | 文件替换（同名空文件/夹顶掉广告缓存） | 有路径安全限制 |
| 6 | 私有加密脚本（**死规则**，密钥未公开，旧库 62 条全废） | |
| 8 | 精准 View 隐藏 | 见 1.3 |
| 9 | **防火墙拦截**：在 `startActivity` 阶段把 Intent 掏空 | 原作者推荐，效果最好不闪退 |
| 500–503 | 组件级拦截（Activity/Service/Receiver/Provider） | 旧库最大一块 |

**八戒（无障碍）是另一套 `at`/`ct` 命名空间** —— 悟空 `at=3` 是跳首页，八戒 `at=3` 是坐标点击。混用会执行出完全错误的动作（历史 bug 根源之一）。

### 1.3 精准 View（at=8）到底怎么匹配

定位参数：`v`（入口类名前缀，startsWith）+ `id`（资源名，`;` 分隔多候选）+ `t`（文本，`;` 分隔，`#xx#` 表示全等）+ `pl`（向上走几层父）/ `cl`（向下找几层子）/ `sq`（`getChildAt` 序号）+ 父子的二次校验 `p`/`pId`/`pt`、`c`/`cId`/`ct`。

**表达力上限很低**：没有兄弟关系、没有属性长度、没有逻辑组合、没有「只要叶子节点」。这正是 GKD 选择器碾压它的地方。

命中后动作：`hideTree()` —— 递归整棵子树，宽高归零 + `GONE` + padding 归零 + alpha 0（五重保险）。**只隐藏，不点击**（点击是八戒的事）。

触发时机是**被动 + 主动双保险**：hook `View.setVisibility` / `TextView.setText` / `View.setLayoutParams` / 三个类的构造器；构造时挂 `OnGlobalLayout` 再补一次（覆盖异步填充的广告）。

### 1.4 进程与目标筛选

只处理主进程（`isFirstApplication` 或进程名 == 包名）；`SKIP_PREFIX` 排除 `com.android.systemui`、`com.miui.home`、芯片/ROM 相关包；黑名单（已知崩溃或强对抗）。规则在 `Application.onCreate` 之后装配，**一进程内只加载一次**。

### 1.5 旧规则库的真实构成（`docs/baseline.md` 实测）

| 来源 | 条数 | 构成 |
|---|---:|---|
| 组件表（rp=15） | **2444** | at=500 Activity 1846 · 502 Service 379 · 501 Receiver 102 · 9 防火墙 96 · 503 Provider 21 |
| 通用规则（rp=10） | 287 | at=3 跳首页 117 · at=6 死规则 62 · at=5 弹框 50 · at=4 改写 31 · at=1/2 共 27 |
| 文件（rp=20） | 261 | 空文件夹 146 · 删除 70 · 空文件 45 |
| 精准 View（rp=14） | 242 | at=8 |
| URL/Host（rp=11） | 94 | URL 60 · Host 34 |
| 八戒任务 | 327 个任务 / **519 步** | 点击控件 477（92%）· 坐标 23 · 返回键 9 · 滑动 9 · 命令 1 |

**八戒步骤的定位方式分布暴露了旧模型的脆弱**：按类名 328（63%，**最不稳**，混淆后每发版都变）· 按 ID 87（17%）· 按文本 82（16%）· 无定位 18。63% 押在最不稳的方式上，且没有降级链。

规则年龄：有时间戳的 890 条里 2020 年及更早占 27%；订阅索引 `update_time` 停在 2025-01-22。

---

## 二、GKD：值得吸收什么

详见 `ref/gkd/`（43k★，主仓 94k 行 Kotlin；`gkd-selector` / `gkd-subscription` 已并入主仓）。

**执行链路**：无障碍事件 → 三层节流（事件级合并 100ms、批次级按 key 合并、调度级单活 run）→ `MatchCoordinator` → 选择器匹配 → 动作。它**不做定时轮询**，只有 4 类触发。

**硬上限对冲不确定性**：节点读 500ms 超时 + 4 线程固定池；`MAX_CHILD_SIZE=512` / `MAX_DESCENDANTS_SIZE=4096`；3 个 `LruCache(4096)`；节点过期（有 text 1000ms / 无 text 2000ms）；事件队列容量 64，溢出丢弃并计数。

**选择器实现**（`gkd-selector`，10k 行）：AST → `IntArray` 字节码（`AND_ENTER/OR_ENTER` 带跳转实现短路）→ 显式栈回溯匹配（无 JVM 栈递归）+ 失败记忆化（按 nodeKey 记录已确认失败的状态）。`NodeAdapter<T>` 抽象让整套匹配器与 Android 无关 —— **进程内只需要实现 View 版适配器**。

**订阅模型**：`rule → group → 全局默认` 的字段继承链（`RuleExecutionParameters`）；规则身份 `subsId/type/appId/groupKey/ruleKey`；全局规则默认对全部应用生效，靠 `apps[].enable:false`（1380 条）例外；`actionCd` 默认 1000ms、`resetMatch` 默认 Activity。

**动作**：`click` = 先试 `clickNode`（仅当 `isClickable`），失败退回 `clickCenter` —— **降级链是一等公民**；动作前双重校验（坐标越界 + 上下文是否仍是当前页/窗口）。

**可复核**：快照属性集与选择器属性集是同一套（`AttrInfo`），并有结构化条件解释输出（`text='跳过' left="关闭" right="跳过" -> false`）+ 有界环形缓冲 + 离线 CLI。

**不能照搬**（无障碍特有）：整条事件输入层、`rootInActiveWindow`、500ms 超时与线程池（进程内无 Binder）、为省 IPC 而存在的三层缓存（进程内 `getChildAt` 是数组索引，应删掉）、`performAction`/`GestureDescription`（进程内改 `performClick()` / `dispatchTouchEvent`）。

**最大天花板（必须正视）**：`AccessibilityNodeInfo` 语义 ≠ `View` 语义。Compose / Flutter / RN 在无障碍侧有语义树，**在 View 侧整个塌缩成一个 `AndroidComposeView`**。若目标 App 大量 Compose，进程内控件方案覆盖率会显著低于 GKD —— 建议动手前先做一次目标 App 技术栈普查。

---

## 三、AdClose：值得吸收什么

详见 `ref/adclose/`（1.0k★，**9401 行**：hook 24% / UI 44% / crash 9% / data 6%）。

**零规则是怎么做到的**：把广告消灭在渲染之前的三层 —— ① SDK 初始化（`TTAdSdk.init` 直接吞掉；其余用 DexKit 按 SDK 内部错误字符串定位 init 方法）；② 四级请求拦截（DNS `InetAddress.getByName` → `HttpURLConnectionImpl` → DexKit 定位 okhttp `RealCall.execute`/拦截器 → `setWebViewClient` 两跳拿到实际 client 再 hook）；③ 环境层（`FLAG_SECURE`、传感器摇一摇、Root/Magisk/Xposed 检测、剪贴板）。完全不碰 View 树。

**最值得抄的三条**：
1. **DexKit 泛化扫射** —— `searchPackages(15 个广告 SDK 包名) + PUBLIC + void 返回 + 方法名白名单(loadAd/show/initialize/…)` → `DO_NOTHING`。不用为每个 SDK 逆向一次。
2. **扁平三元组规则表** `HookInfo(class, methods[], returnValue)` —— 一份数据驱动 `XC_MethodReplacement`，新增 SDK 只加一行。
3. **按 SDK 内部错误字符串定位 init** —— 对付强混淆 SDK 的杀招（错误文案通常不被 R8 移除）。

**代价（它管不了的）**：已渲染的原生广告 View、开屏跳过按钮的自动点击、弹窗关闭、倒计时跳过 —— 恰好是原版/GKD 路线的强项；业务与广告共用域名时会误伤。

**它自己的坑（别照抄）**：`hookMultipleMethods` 按方法名匹配不区分签名（同名重载一起被替换）；`HideVPNStatus` 在 `before` 阶段读 `param.getResult()`（恒为 null，逻辑根本不生效）；URL 规则缓存 4 小时导致新规最长 4h 不生效；`UrlContentProvider` exported 但没用 permission 保护；两个 `ConcurrentHashMap` 只 put 不清理（内存单调增长）。

**注意**：公开仓库里 `SDKAds.HOOK_INFOS` 与 `AppAds.appHooks` 被作者清空成 `com.example.ClassName` 占位符（README 明写「此部分内容并不开放」）。**真正值钱的 SDK 拦截表在公开渠道拿不到，必须自己攒。**

---

## 四、三个候选形态（待拍板）

### 形态 A：进程内执行的 GKD
规则模型 / 订阅格式 / 动作语义全部对齐 GKD，只把执行层从无障碍换成进程内 hook（`NodeAdapter` 的 View 实现）。
- 得：复杂度最低；直接吃 GKD 生态；只做点击类动作，法律边界最干净。
- 失：丢掉原版最稳的组件级拦截（旧库 67%）；Compose/Flutter 场景下 View 树塌缩会让覆盖率低于 GKD 本体；旧 3655 条规则不兼容。

### 形态 B：三层收敛，统一到一个执行器（推荐）
- **L0 渲染前**：SDK 初始化拦截 + 四级请求拦截 + 组件/Activity 级拦截（吸 AdClose + 原版组件表）。**零规则或极少规则，抗改版。**
- **L1 渲染后·全局**：两条开屏选择器 + 例外名单（吸 GKD 模型），默认全开，规模随误伤增长。
- **L2 渲染后·用户自抓**：检视层抓控件 → 生成带快照的规则，定位链按抗改版能力排序。
- 三层共用**一个规则模型、一个执行器（进程内）**；无障碍只用于检视与兜底。
- 得：保留原版最强的一块 + 吃到 GKD 生态 + 拿到 AdClose 的抗改版能力；规则规模不随应用数增长。
- 失：三层仍是三种机制，**必须靠「统一规则模型」压住复杂度** —— 这正是旧实现崩掉的地方，需要最严格的边界纪律。

### 形态 C：只做 L0（渲染前为主）
以 SDK/请求/组件拦截为主，控件层只做最小补充。
- 得：最抗改版（SDK 类名几年不变）、规则最少、最省电。
- 失：管不了「跳过按钮」「弹窗关闭」这类交互型需求，而这正是用户感知最强的部分。

---

## 五、待拍板（除形态外还有 4 条）

1. **法律边界**：2023 年同类软件收律师函，「拦精灵」二审被判不正当竞争（赔 9 万）。法院划的线是：**替用户点「跳过」可以讨论，直接让广告不显示（屏蔽/摘除/断网）被否定**。形态 B/C 的 L0 整体落在后者一侧；是否公开发布决定要不要收敛到只做点击。
2. **`targetSdk` 定在哪**：原版是 27（能用很多老 API），旧实现是 35。targetSdk 直接决定 `XSharedPreferences`、`QUERY_ALL_PACKAGES`、存储访问的可用性。
3. **旧 3655 条规则要不要兼容**：迁移器 + 旧格式是纯负担还是资产（只有组件表那一块 2444 条是真正稳定的）。
4. **无障碍兜底留不留**：留则约 2000 行 + 一整条测试路径；不留则检视层（抓控件）也没了 —— 而抓控件**必须依赖无障碍**，这是系统安全边界。

---

## 六、证据索引

```
原版  ~/WorkBuddy/2026-10-19-10-54-37/adhammer/adhammer.apk        （v10.0.7，SHA256 204d945b…）
      …/extract/assets/{xposed_init,auto_skip.txt,tngs.txt}
      …/jadx_out/sources/{me/app/xp/ad,me/app/xad,com/dahuo/sunflower}/   5086 个 java（混淆）
      …/AdHammerReplica/…/com/adhammer/{core,hooker,model}/        2921 行，上一轮的复刻（可读）
      …/RULES.md                                                   476 行，旧规则格式规范
      …/docs/baseline.md                                           真机实测 + 被推翻的判断
      …/docs/survey-similar-projects.md                            上一轮同类调研（含法律部分）
本次  ref/dasheng_res/resources/AndroidManifest.xml                 原版 Manifest（jadx 解）
      ref/gkd/…                                                    GKD 主仓（含 selector/subscription）
      ref/adclose/…                                                AdClose
```
