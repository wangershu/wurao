# map.md —— 路径与入口地图

> 核验日期：2026-11-01（适用于本次重头开始）。

## 本仓库（新代码，唯一可写的地方）

```
~/WorkBuddy/2026-11-01-16-01-07/wurao/
├── decision.md                 用户拍板的决定（D1–D5）
├── risk.md                     已知风险
├── NOW.md                      当前进度
├── docs/
│   ├── research-survey.md      三份调研 + 三个候选形态（已完成）
│   └── architecture.md         架构设计与阶段拆解（待评审）
└── build.sh                    （S0 建立）必须走它构建
```

## 参考（只读，不要在上面开发）

| 用途 | 路径 |
|---|---|
| 旧实现（33k 行，只查不抄） | `~/WorkBuddy/2026-10-19-10-54-37/adhammer/` |
| 旧仓库项目共识基线 | `…/adhammer/AGENTS.md`（§7 已知 bug 模式、§9 待拍板） |
| 旧规则格式规范 | `…/adhammer/RULES.md`（476 行） |
| 旧真机实测 + 被推翻的判断 | `…/adhammer/docs/baseline.md` |
| 旧同类调研（含法律部分） | `…/adhammer/docs/survey-similar-projects.md` |
| 旧库语料（抽种子数据用） | `…/adhammer/sub/unpacked/` |
| 旧反编译产物 | `…/adhammer/jadx_out/`（5086 java，混淆） |
| 上一轮的复刻实现（可读） | `…/adhammer/AdHammerReplica/`（2921 行 Java） |
| 原版 APK + 解出的 Manifest | `~/WorkBuddy/2026-11-01-16-01-07/ref/dasheng_res/` |
| GKD（含 selector / subscription） | `…/ref/gkd/` |
| AdClose | `…/ref/adclose/` |

## 真机

| 项 | 值 |
|---|---|
| 设备 | Redmi 2509FPN0BC，序列号 `56da4a86` |
| 系统 | Android 16（API 36）/ HyperOS V816 |
| 框架 | LSPosed 2.2.1（api 102），作用域：全部应用 |

## 构建环境坑（本机特有，别踩第二次）

| 坑 | 解法 |
|---|---|
| 沙箱终端给新文件打 `com.apple.provenance`，Gradle 删不掉旧产物，表现为「报错但看不到编译错误」 | 一律走 `./build.sh`（清 xattr + 关 problems-report + 关 Kotlin 增量） |
| SDK 路径 | `local.properties` 已配 `sdk.dir=/Users/flynnq/Library/Android/sdk`（**不入库**，换机需重建） |
| 本机 build-tools **36.0.0 那份是残缺的**，AGP 默认会去装它然后失败 | `app/build.gradle.kts` 显式 `buildToolsVersion = "37.0.0"` |
| Compose 插件在 classpath 没有 compose runtime 时会报 `noRuntimeOnClasspathError` | S0–S4 不装 kotlin-compose 插件，S5 做 UI 时再装 |

## 不可越过

- 旧仓库一律只读，**不删、不改、不提交**。
- 参考仓库（`ref/`）不参与本仓库 git。
