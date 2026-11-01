# wurao 架构设计（形态 B：三层收敛）

> 2026-11-01。依据 `decision.md` D1–D5 与 `docs/research-survey.md`。
> 状态：**待评审**。评审通过后才写第一行代码。
> 这份文档先解决一个问题：**三层机制如何不变成三套东西** —— 旧实现正是一步步把多套机制塞进一个模型才崩的。

---

## 一、统一的那个东西：规则模型

三层（渲染前 / 渲染后·全局 / 渲染后·自抓）机制完全不同，能统一的只有**规则的结构与执行接口**，
不是匹配算法。所以模型按「匹配域」切分，规则本身只有一个结构：

```kotlin
Rule(
  domain: Domain,      // 匹配什么：NODE / COMPONENT / SDK / HOST / FILE
  target: String,      // 按 domain 定：选择器串 / 组件名 / 类#方法 / 域名 / 路径
  act: Act,            // 做什么：BLOCK / GOTO / CLICK / LONG_CLICK / BACK / HIDE / REMOVE
  scope: Scope,        // 对谁生效：全局 / 指定 App / 指定组件 / 指定 App 内排除
  timing: Timing,      // 延迟 / 冷却 / 次数上限 / 重置口径
  origin: Origin,      // 来源：订阅(subsId) / 用户 / 内置 —— 决定 id 与去重
  meta: Meta,          // note / snapshot / health
)
```

**id 构成**（决定幂等与去重）：`<origin>:<scopeKey>:<groupKey>:<ruleKey>`，导入时先删同 id 再写。
重复导入不翻倍 —— 这是旧实现做对的地方，保留。

### 1.1 五个匹配域

| domain | target 长什么样 | 触发点 | 属于哪层 |
|---|---|---|---|
| `COMPONENT` | `com.x.ad.SplashActivity` | `startActivity` / 组件解析 | L0 |
| `SDK` | `com.bytedance...TTAdSdk#init`（或 DexKit 查询描述） | 类加载 / 方法调用 | L0 |
| `HOST` | `ads.doubleclick.net` | `InetAddress.getByName` / okhttp / WebView / Cronet | L0 |
| `FILE` | 广告缓存路径 | 文件读写 | L0 |
| `NODE` | GKD 选择器串 | View 树变化 | L1 / L2 |

**关键纪律**：五个域共用 **同一个 Rule 结构、同一套存储、同一套生效判定、同一套健康度与诊断**；
只有「匹配器实现」按域不同。加一个新域 = 加一个匹配器 + 一个触发点，**不动模型、不动存储、不动 UI 框架**。

### 1.2 动作收敛到 7 个（旧实现是 13 个，这是教训）

| act | 语义 | 适用域 |
|---|---|---|
| `BLOCK` | 让它不发生（组件不起来 / 方法不执行 / 请求不出门 / 路径不可读写） | COMPONENT / SDK / HOST / FILE |
| `GOTO` | 拦下并改跳转到指定组件（原版 at=3/7「跳首页」） | COMPONENT |
| `CLICK` | 点击（`performClick()` → 失败退 `dispatchTouchEvent` 坐标） | NODE |
| `LONG_CLICK` | 长按 | NODE |
| `BACK` | 返回键 | NODE / COMPONENT |
| `HIDE` | 隐藏（GONE + 尺寸归零） | NODE |
| `REMOVE` | 从容器摘除 | NODE |

**降级是执行细节，不是枚举**：CLICK 的「先语义点击后退坐标点击」、定位链的抗改版排序，
都不进动作枚举。旧实现把复杂度摊在枚举上，是 bug 的来源之一。

---

## 二、执行链路与三道闸

```
触发（hook 点 / View 树变化）
  │
  ├─① 进程闸：这个 App 没被接管 → 零开销返回
  ├─② App 闸：当前 App 有没有该 domain 的规则（按 pkg 切分，单应用 KB 级）
  ├─③ Domain 闸：按触发点类型只取对应 domain 的规则（组件/SDK/HOST/FILE/NODE 分桶）
  │
  └─ 匹配器求值 → 命中 → 生效判定（scope/例外名单/timing）→ 执行 act
```

- **L0 走 hook 点触发**（组件启动、SDK 方法、DNS、文件读写），**不做遍历**。
- **L1/L2 走 View 树遍历**，用选择器引擎，受节点预算与冷却约束。
- 两者共用上述三道闸与同一个 `Executor` 入口：`Executor.match(domain, ctx)`。

### 2.1 规则怎么送到 hook 进程

沿用旧实现验证过的路子：**远程偏好 + 文件通道，按 App 切分**（`gapp:<pkg>` / `nrules:<pkg>` / `gsub:`）。
Android 10+ 存储限制下这是唯一稳的。**桶里存的必须是最终要执行的那份**，读的人不再拼装
（旧实现踩过「处置名单只存在全局组、应用桶不带它 → 停用只对一半生效」）。

### 2.2 选择器引擎：自己实现，但照 GKD 的编译思路

- 不移植 `gkd-selector` 源码（GPL-3.0 允许使用，但自己写更可控，且旧实现已有基础）。
- **照抄它的三个关键设计**：AST → `IntArray` 字节码（短路跳转）、显式栈回溯（防深树爆栈）、
  失败记忆化（按 nodeKey 记录已确认失败的状态 —— 缺这块深选择器会指数爆炸）。
- **验收口径照它**：解析率用 GKD 真实订阅语料测，目标 ≥95%（旧实现 94.6%）。
- **进程内差异（必须重映射）**：`AccessibilityNodeInfo` 语义 ≠ `View` 语义。
  `visibleToUser` 只能近似为 `isShown() && getGlobalVisibleRect(r) && !r.isEmpty() && alpha > 0.01f`；
  `id/vid` 需从 `view.getId()` 反查资源名；`childCount` 在 View 侧含全部子节点（无障碍侧会过滤不可见/不重要节点）。
  逐个核对清单在进入 S2 前必须写完。

---

## 三、开销预算（进程内，每进程）

| 项 | 目标 |
|---|---|
| hook 进程常驻内存增量 | < 500 KB |
| 开大域名表后 | < 700 KB（域名表分级：969 / 17K / 127K 三档，默认 17K） |
| 模块进程常驻（无障碍关闭时） | 零 |
| 单次匹配参与规则数 | 1~2 条 |
| 单次 View 树遍历 | 有节点预算上限，超限即停并计数上报 |

**不允许**：常驻轮询、WakeLock、跨进程遍历（那是无障碍路线的代价，我们不该付）。

---

## 四、目录与模块边界

```
wurao/
├── app/                    主模块
│   └── src/main/kotlin/com/wurao/
│       ├── core/           纯 JVM，可单测（不依赖 Android framework）
│       │   ├── model/      Rule / Act / Domain / Scope / Timing / Origin
│       │   ├── selector/   选择器：词法 → 语法 → 字节码 → 求值
│       │   ├── matcher/    五个域各自的匹配器
│       │   ├── store/      规则库、按 App 切分、桶的读写
│       │   ├── source/     ★ 可插拔规则源适配器（GKD / 域名表 / 组件表 / 未来新格式）
│       │   ├── health/     命中统计与失效判定
│       │   └── diag/       结构化诊断（为什么没命中）
│       ├── inapp/          跑在目标进程（唯一写 hook 逻辑的地方）
│       │   ├── hook/       各触发点的 hook 装配
│       │   ├── executor/   Executor + 三道闸 + 动作执行
│       │   ├── view/       NodeAdapter 的 View 实现
│       │   └── net/        DNS / okhttp / WebView / Cronet 拦截
│       ├── assist/         无障碍：仅检视（抓控件）+ 兜底
│       └── ui/             Compose 界面
├── docs/                   决策与调研
└── build.sh                绕开 macOS com.apple.provenance 的构建脚本
```

**分层铁律**：`core` 不依赖 Android；`inapp` 是唯一写 hook 的地方；`ui` 只读写远程偏好，不直接操作目标进程。

---

## 五、选型建议（可改，给理由）

| 项 | 建议 | 理由 |
|---|---|---|
| `minSdk` / `targetSdk` | 26 / 35 | 与旧实现一致。模块自身 targetSdk 影响模块进程；hook 到宿主后权限以宿主为准；配置传递靠远程偏好 + 文件通道，不依赖 `XSharedPreferences`（Android 10+ 不可靠） |
| 语言 | Kotlin | 三个参考项目都是 Kotlin |
| 构建 | 必须走 `build.sh` | macOS 沙箱给新文件打 `com.apple.provenance`，Gradle 删不掉自己旧产物，表现为「报错但看不到编译错误」 |
| 选择器 | 自研（照 GKD 思路） | 见 2.2 |
| 无障碍 | 保留，仅检视 + 兜底 | 抓控件**必须**依赖无障碍，这是系统安全边界，Xposed 替代不了 |

---

## 六、阶段拆解（每阶段可验收，有证据才算完）

| 阶段 | 内容 | 验收证据 |
|---|---|---|
| **S0** | 仓库骨架、`build.sh`、单测框架、空壳模块跑通 | `./build.sh :app:assembleDebug` 出包 |
| **S1** | `core`：统一规则模型 + 选择器引擎 + 五个域匹配器 | 单测：GKD 语料解析率 ≥95%；模型不变量有断言 |
| **S2** | `inapp`：进程内执行器 + ViewAdapter + 三道闸 | 真机：网易云音乐/微博开屏「跳过」由**目标进程**打出命中日志 |
| **S3** | L0：组件拦截 + SDK 拦截 + 请求拦截 + 组件表种子数据（2444 条） | 真机：5 个 App 冷启，广告页不再出现；`dumpsys` 无异常 |
| **S4** | 规则源可插拔：GKD 订阅 / 域名表 / 组件表适配器 | 单测：三种源导入幂等、总数守恒、无静默丢失 |
| **S5** | UI + 检视层 + 快照与诊断 | 真机：抓一条规则 → 命中 → 看得到「为什么没命中」 |
| **S6** | 健康度、自检页、开销实测 | 常驻内存 <500KB 实测；SelfTest 全绿 |

**顺序理由**：S1/S2 是「能不能用」，S3 是「效果够不够」（旧库最稳的一块，早见效早验证），
S4 是「将来能不能接新订阅」（你要的可扩展性），S5/S6 收尾。

---

## 七、明确不做（别绕回来）

- 不做第二套规则格式、第二套执行器 —— 五个域的差异只在匹配器。
- **不兼容旧规则格式**（只抽组件表种子数据，D5）。
- 不做「全局规则库」页面 —— 要的是把东西落到 App 里。
- 不做 WakeLock 保活（ROM 清掉的是 Secure Setting，保活无效，实测过）。
- 不做 Socket / BlockGuardOs 层拦截（每次网络读写都变成一次规则匹配，太贵）。
- 不做「应用专属域名」（统计页能查记录就够）。
- **不对外分发**（D4）。要发布先重开 D4。

---

## 八、已知风险（详见 `risk.md`）

1. **Compose / Flutter / RN 塌缩**：View 侧整棵树塌缩成一个 `AndroidComposeView`，
   控件层（L1/L2）覆盖率有硬天花板 → **S2 开工前先做目标 App 技术栈普查**。
2. **进程内 hook 的 Android 16 兼容性**：目标设备是 Android 16 / API 36，隐藏 API 与
   非 SDK 接口限制逐年收紧 → 每个 hook 点必须有「失败也不崩」的兜底与计数上报。
3. **开销 × 进程数**：任何常驻开销都要乘 N（并发被 hook 的进程数）。
4. **法律**：见 D4。
