# UI 对齐大圣净化（AdHammerX）设计稿

状态：待评审 | 日期：2026-11-03

## 0. 对齐基准

设计来源不是截图临摹，而是 **AdHammerX 的源码**（github.com/wangershu/wurao，
已克隆到本地参考）。它的 UI 是 miuix 0.9.4 + HyperIsland 版式，正是手机上
「大圣净化」那套观感：可折叠大标题、分组卡片、悬浮胶囊底栏。

**已核实的事实**（不是猜测）：

- 本地 wurao 与 AdHammerX 构建链完全一致：AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.1。
  当年注释里写的「0.9.4 metadata 太新编不过」在现在的 Kotlin 下已不成立；
  `app/libs/` 里三个 0.9.4 aar 已经躺在那里（ui / icons / preference）。
- miuix 0.9.0 的 `TopAppBar` 已带 largeTitle 签名（javap 核实），
  所以即使不升 0.9.4，可折叠大标题也能做。**建议直接切到 0.9.4 aar**，
  与参考实现同版本，抄代码零翻译成本。
- 参考实现的组件层 `Kit.kt`（Page / DetailPage / Section / RowGroup /
  ArrowRow / SwitchRow / KeyValue / Stats / Empty / Note / HeroCard）
  与导航层 `Nav.kt`（三页签 + 返回栈）可以直接搬进本项目，包名同为 `com.wurao.ui`。

## 1. 原则

1. **信息架构不变**：底部三页签（主页 / 应用 / 设置）+ 应用详情二级页 + 设置二级页。
   只换皮，不动层级，不动任何业务逻辑（RuleStore / CfgPush / HitProvider /
   SnapshotService / 调试直达，调用点原样保留）。
2. **视觉全部归一到 miuix**：material3 的 Text / Icon / AlertDialog /
   OutlinedTextField / Checkbox 全部替换为 miuix 等价物；图标从 material-icons
   换成 MiuixIcons。
3. **单文件拆分**：MainActivity.kt 现在 1970 行。顺势拆成
   `ui/Kit.kt`（组件）、`ui/Nav.kt`（导航）、`ui/Home.kt`、`ui/Apps.kt`、
   `ui/Settings.kt`，MainActivity 只留入口、调试直达、业务胶水。
   拆分是这次改动的一部分，不另立步骤。

## 2. 设计规范（抄参考实现，数值已定）

| 项 | 规格 |
|---|---|
| 主题 | `MiuixTheme(controller = ThemeController(...))`，**禁止 `colors=` 写法**（会绕过 typography，数字渲染成豆腐块） |
| 系统栏 | edge-to-edge，状态栏/导航栏透明 |
| 页面骨架 | `Kit.Page`：TopAppBar 同时给 `title` + `largeTitle`（滚动折叠成小标题），LazyColumn，左右 12dp，item 间距 12dp，overScrollVertical |
| 底栏 | 只在根页显示；悬浮胶囊（不 fillMaxWidth）；选中项自绘 `primary.copy(alpha=.14)` 圆角 50 胶囊底 + primary 图标色（miuix 默认选中态几乎不可见，必须自绘） |
| 行规格 | 内边距 18×14dp；行尾箭头 `MiuixIcons.Basic.ArrowRight` 10×16；分组标题 SmallTitle 18×8；「一卡多行」= Card(insideMargin=0) 套 Column |
| 状态卡 | 激活 #36D167 / 底 #DFFAE4；未激活 #FF5A52 / 底 #FFE5E3；警告底 #FFF3D6 / 字 #704D00；水印 = 自绘描边圆 + 勾，右下角 offset(12,16)，外层必须 clip 圆角 16 |
| 二级页 | `Kit.DetailPage`：同骨架 + 左上角返回（MiuixIcons.Back），整层覆盖根页，底色用 colorScheme.surface（**不是 background**，那是卡片白） |

## 3. 页面改造清单

### 3.1 主页（BoardScreen → Home.kt）
结构对齐参考的 ScreenHome：

1. **状态网格**：左大 StatusCard（激活状态 + 版本号 + 对勾水印，1:1 方形），
   右侧两个 StatCard（已启用规则 → 跳应用页；今日拦截 → 弹命中摘要）。
   —— 现有代码已有这三张卡和正确配色，搬进 `Kit.Page` 的 LazyColumn item 即可。
2. **警告横幅**：框架未接入 / 无障碍未开时显示（现有逻辑保留，样式即黄底卡）。
3. **信息卡**：系统版本 / 应用版本 / Hook 框架 / 规则库 / 设备型号，
   改为 RowGroup 里的 KeyValue 行（现在是自定义 InfoRow）。
4. **常用**：无障碍服务入口、全部暂停/恢复、抓规则、同步、检测链接
   → 收成一张 RowGroup，ArrowRow / SwitchRow 表达；全部暂停是破坏性操作，
   用 SwitchRow（状态可见）而不是现在的 Button。
5. **命中记录**：分组标题 + 每条一张卡（保留停用/恢复按钮，换成 TextButton 风格）。

### 3.2 应用页（AppsScreen → Apps.kt）
- `Kit.Page("应用")`，顶部 miuix TextField 搜索 +「只看拦过的」SwitchRow。
- 每行一张卡：App 图标（现有 AppIcon 保留）+ 名称 + 包名/命中次数 + ArrowRight。
- 包名直输入框收进一个 ArrowItem（「手动输入包名」→ 二级页或展开区）。

### 3.3 应用详情（AppDetailScreen）
- `Kit.DetailPage(标题 = 应用名)`，顶部 Header 块（图标 + 包名）。
- 三个风险级开关 → Section("风险级（默认全关）") + RowGroup 里三个 SwitchRow。
- 抓屏、自动分析、规则列表 → 各自 Section + 行。文案、逻辑全部保留，只换行样式。

### 3.4 设置（SettingsScreen → Settings.kt）
现在是「可展开行里塞表单」，改成参考实现的分组 + 二级页：

| 分组 | 行 | 去向 |
|---|---|---|
| 规则从哪来 | 导入订阅 / 域名表 | 二级页（粘贴框 + 网址 + 导入报告） |
| 规则从哪来 | 清空订阅规则 | 行内动作 + 确认 |
| 我的规则 | 手写规则 / 规则清单 | 二级页（现有添加表单 + 列表 + 删除确认） |
| 数据 | 备份与恢复 | 二级页（导出文本 / 粘贴恢复） |
| 外观 | 悬浮底栏 | SwitchRow（现有 floating_bar 保留） |
| 关于 | 关于 | 现有 AboutScreen 套 DetailPage |

### 3.5 导航（Nav.kt 新增）
- 引入参考实现 `Navigator`（三页签 + 返回栈 + jump/switch），
  替换掉现有 `tabState` / `openPkg` / `subPage` 三个散装 state。
- 根页切换用 HorizontalPager（页面状态不丢）；二级页整层滑入覆盖。
- **调试直达不变**：`--es tab` / `--es open_pkg` 翻译成 Navigator.switch/go，
  在 Activity 层完成（现有直达全部保留，这是真机验证的唯一手段——
  设备没有 INJECT_EVENTS，点不了屏幕）。

## 4. 依赖改动

`app/build.gradle.kts`：三个 0.9.0 aar 换成 libs 里已有的 0.9.4 aar
（miuix-ui-android-0.9.4.aar / miuix-icons-android-0.9.4.aar /
miuix-preference-android-0.9.4.aar；core/shapes 仍用 0.9.0，
若报缺类再补）。material-icons-extended 保留（AppIcon 等少量处仍在用），
全部替换完后可删。

## 5. 不做

- 不动信息架构、不动业务逻辑、不动 hook 侧任何代码。
- 不做深色模式单独调优（miuix 跟随系统，自带深色）。
- 不引入 navigation-compose（参考实现自己也没用，一个返回栈够用）。
- 不抄 AdHammerX 里本项目没有的功能页（统计独立页、AI 助手等）。

## 6. 风险

| 风险 | 应对 |
|---|---|
| 0.9.4 aar 与 Kotlin 2.4.21 仍编不过 | 退回 0.9.0（已核实 TopAppBar 带 largeTitle），Kit.kt 里个别参数名按 0.9.0 签名微调 |
| Kit.kt 从 0.9.4 项目直搬，个别 API 签名差异 | 编译错逐个对 aar 的 javap 签名改，不改设计 |
| pager + 折叠标题的首帧抖动（标题一进页就是收起态） | Kit.Page 里已有 LaunchedEffect 兜底，直接继承 |
| 验证只能靠截图（无 INJECT_EVENTS） | 每页一个 `--es tab`/`--es dest` 直达，启动 3s 内 screencap，与大圣净化截图逐屏对比 |

## 7. 验收

1. `./build.sh :app:assembleDebug` 编译通过，131 条单测保持全绿（不动 core，不应有红）。
2. 真机装包，三页签 + 应用详情 + 设置二级页各截一张图，
   与大圣净化截图对比：大标题折叠、状态网格、卡片圆角间距、底栏胶囊高亮。
3. 业务回归：规则库条数、命中记录、风险级开关状态与改造前一致
   （改前 `--es export` 存一份，改完对比）。
