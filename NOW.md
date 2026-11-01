# NOW.md —— 当前进度（最新在前）

## 2026-11-01 · 真机验证通过：模块在美团进程内跑起来了（80 条单测全绿）

**真机证据（美团进程 PID 21950，不是模块进程）**
```
LSPosedFramework: (com.sankuai.meituan)[com.wurao.app,Wurao,...] 无扰已加载: framework=LSPosed ver=2.2.1 api=102
LSPosedFramework: (com.sankuai.meituan)[com.wurao.app,Wurao,...] 已装配: com.sankuai.meituan
```
→ libxposed api 102 的装配链路在 Android 16 上跑通（S2 最大的未知已消除）。

**真实界面验证（adb uiautomator dump 美团首页，168 节点，已成为测试资源）**
纠正了"隐藏视频入口"这条需求的两个想当然写法：
1. 底栏 tab 的文字在 **`content-desc`** 而不是 `text`（tab 是 `android.view.View`，不是 TextView）
   → 按 `[text="视频"]` 写规则根本找不到底栏；
2. 首页内容区**也有**一个 `text="视频"` 的功能入口 → 只按文本匹配会误伤它。

**因此隐藏视频入口的正确规则是：**
```
[vid="bottom_tab_block"] << [desc="视频"]     // 先锁底栏容器，再找 desc
```
（`bottom_tab_block` 是底栏容器的资源 id，实测存在。）

**还没验到的**：真实广告命中（美团这次启动没出开屏广告，日志里只有加载与装配）。
下次验证：强停后多启几次 / 换个时间点，或等 S6 界面做好后手动触发。

---

## 2026-11-01 · S2/S3/S4 完成，APK 已装手机，**已激活**

**已完成（74 条单测全绿，包 982KB 已 adb install）**
- **S2** 进程内执行器：libxposed 102 入口 + 三道闸 + View 适配器 + 动作（`ec73bc1`）
- **S3** 订阅导入：JSON5 解析 + GKD 订阅 → 统一规则 + 域名表 + 规则库（`a470728`）
- **S4** L0 风险级：组件 / DNS / SDK 三道拦截，默认关、按 App 开（`bc05ce8`）

**当前唯一阻塞：模块还没在 LSPosed 里启用**（这一步只能在手机界面上点，我做不了）
- 启用后我能自己完成验证：adb logcat 抓 `Wurao` tag、am start 启动美团/懂车帝、看命中日志。
- 首次验证只看**安全级**（内置开屏跳过规则）—— L0 默认关，等界面做好再开。

**验证通过后的下一步**
- S5 命中记录与排错（按 App 记、下次启动前不重复、从记录跳规则）
- S4b 自动分析候选器（原型已在 `tools/`）
- S6 UI（沿用旧版三个页签，需要装 compose 插件）
- S7 检视层、S8 自检与开销实测

---

## 2026-11-01 · S1b 完成：选择器引擎（解析 + 求值），46 条单测全绿

**已完成**
- `core/selector/Ast.kt`：段 / 关系 / 比较运算符 / `@` 目标标记。
- `core/selector/SelectorParser.kt`：手写词法 + 递归下降，语法错误带位置抛出。
- `core/selector/NodeAdapter.kt`：匹配器与节点来源解耦（无障碍版 / 进程内 View 版各实现一份）。
- `core/selector/SelectorMatcher.kt`：显式栈 DFS + 失败记忆化 + 五种关系 + 按 `@` 返回目标。
- 提交 `78edc87`（解析）、`0e42b47`（求值）。**单测 46 条 / 0 failures**。

**顺带确认的事实**
- GKD 源码注释：`A > B` 表示 A 是 B 的祖先（B 是 A 往上找的结果）。隐藏整个入口的写法是
  `[text="视频"] > @LinearLayout`，不是反过来。
- 隐藏整个入口（美团底栏视频标签）**可行，已纳入本版**：美团底栏是原生 View，动作就是 `HIDE`。
  验收：真机底栏剩四个入口、其余 tab 正常、App 不崩。

**下一步：S2 进程内执行器**
- libxposed api 102 装配 hook（**没有 XposedHelpers/XposedBridge 可用**，与老 API 完全不同）。
- 三道闸 + View 版 NodeAdapter + 动作执行（点击先 `performClick()` 失败退坐标）。
- 真机验收：美团开屏「跳过」由**目标进程**打出命中日志；正常按钮零误触。

**S2 时需要你配合的一次操作**
打开美团停在首页，我用 `uiautomator dump` 看一眼底栏的真实结构（类名/资源 id），
确认隐藏入口那条规则怎么写最稳 —— 比我现在猜类名靠谱。

---

## 2026-11-01 · S1a 完成：统一规则模型（16 条单测全绿）

**已完成**
- `core/model/Domain.kt`：五个匹配域 COMPONENT / SDK / HOST / FILE / NODE。
- `core/model/Rule.kt`：统一 Rule 结构 + Act 七动作（带 allowed 矩阵）+ Scope（全局+例外名单）
  + Timing + Origin + validate()。
- `app/src/test/.../RuleTest.kt`：**16 tests / 0 failures**（id 幂等、动作×域矩阵、
  不静默失败、全局规则与例外名单）。
- 提交 `eb4f364`。S0 骨架 `98dbbac`（构建链路跑通，APK 出包）。

**下一步：S1b 选择器引擎**（core/selector）
- 词法 → 语法 → 字节码（IntArray + 短路跳转）→ 显式栈回溯求值 + 失败记忆化。
- 照 GKD 的三个关键设计，不移植其源码。
- 验收：GKD 真实订阅语料解析率 ≥95%（旧实现 94.6%）。

**S2 前置（还没做）**
- R1 要求的**目标 App 技术栈普查**：主力目标 App 的开屏/信息流是不是 Compose/Flutter 写的
  —— 决定控件层投入多少。开工 S2 前必须做完。

**待决定**
- 无（架构已按 S0→S1→S2→S3 推进）。

---

## 2026-11-01 · 阶段一完成：调研 + 形态拍板 + 架构草案（已评审通过）

**已完成**
- 起点：新仓库 `wurao/` 从零写，旧 adhammer 仓库只读保留（D1）。
- 三份调研完成：原版大圣（APK + jadx + 复刻实现 + 规则库实测）、GKD、AdClose。
  产出 `docs/research-survey.md`。
- 拍板：形态 B 三层收敛（D3）、自用不分发（D4）、旧库不迁格式只抽组件表种子（D5）。
- 架构草案完成：`docs/architecture.md`（统一规则模型 / 五个匹配域 / 动作收敛到 7 个 /
  三道闸 / 开销预算 / S0–S6 阶段拆解 / 明确不做）。项目基础文件 `map.md` `risk.md` `decision.md` 就位。

**当前阻塞**
- `docs/architecture.md` **待用户评审**。评审通过前不写业务代码。

**下一步（评审通过后）**
1. S0：仓库骨架 + `build.sh` + 空壳模块跑通。
2. S2 前置：目标 App 技术栈普查（R1，决定控件层投入多少）。

**待决定（评审时一并看）**
- `minSdk/targetSdk` 26/35、Kotlin、构建脚本（架构 §5 给了建议值，可改）。
- 第一阶段是否先做 L0（见效快）还是先做内核（S1/S2）—— 架构里给的是 S1→S2→S3。
