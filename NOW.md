# NOW.md —— 当前进度（最新在前）

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
