# UI 对齐大圣净化（AdHammerX）设计稿

状态：已评审（2026-11-03 逐条对过，意见已并入） | 日期：2026-11-03

> 评审结论（用户逐条确认）：
> 1. **主页**：「常用」只保留**悬浮球 + 抓规则**两项，风格对齐；命中记录**挪出主页**，
>    放进「今日拦截」统计卡的二级页（miuix 风格，每行带停用/恢复）。
>    原「常用」里的全部暂停 → 挪到设置页底部（破坏性操作带确认）；同步 → 并入订阅与规则源页；检测链接 → 砍掉（状态卡本身已表达连接状态）。
> 2. **应用页**：保留现有结构换 miuix 皮肤；筛选用按钮组：**全部 / 只看拦过的 / 只看已在 LSPosed 勾选的**
>   （作用域名单来自 `CfgPush.scope()`）；应用详情二级页同样改 miuix。
> 3. **设置页**：**平铺、不加小分类标题**。四个入口各自进二级页：
>    订阅与规则源（导入 + 我自己的规则合一页）、外观、备份与恢复、关于。
>    全部对齐目标版本对应页面；关于页**完全对齐**（渐变沉浸 hero）。

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
   右侧两个 StatCard（已启用规则 → 跳应用页；今日拦截 → **进命中记录二级页**）。
   —— 现有代码已有这三张卡和正确配色，搬进 `Kit.Page` 的 LazyColumn item 即可。
2. **警告横幅**：框架未接入 / 无障碍未开时显示（现有逻辑保留，样式即黄底卡）。
3. **信息卡**：系统版本 / 应用版本 / Hook 框架 / 规则库 / 设备型号，
   改为 RowGroup 里的 KeyValue 行（现在是自定义 InfoRow）。
4. **常用**（只两项）：悬浮球（SwitchRow）、抓规则（ArrowRow → 跳应用页/抓屏流程）。
   全部暂停挪去设置页底部；同步并入订阅与规则源页；检测链接砍掉。
5. ~~命中记录列表~~ → **挪出主页**，见 3.1.1。

#### 3.1.1 命中记录（新二级页 Hits.kt）
- 入口：主页「今日拦截」统计卡。
- `Kit.DetailPage(title = "今日拦截")`：顶部一句说明（同一条规则反复命中只占一行），
  每条命中一张卡：应用 + 描述 + 选择器 + 时间 + 次数，行尾停用/恢复 TextButton。
- 空态用 `Kit.Empty`（"今天还没拦过任何东西"）。

### 3.2 应用页（AppsScreen → Apps.kt）
- `Kit.Page("应用", subtitle = "N 个应用")`，顶部 miuix 搜索框（圆角填充式）。
- **筛选按钮组**（一行三个胶囊按钮，选中项 primary 高亮）：
  **全部 / 只看拦过的 / 已在 LSPosed 勾选**。
  第三个是新增：名单来自 `CfgPush.scope()`（框架服务已连接时可得；
  未连接时该行置灰并注明"框架未连接"）。
- 每行一张卡：App 图标（现有 AppIcon 保留）+ 名称 + 包名/命中次数 + ArrowRight。
- 包名直输入框收进底部一个 ArrowItem（「手动输入包名」）。

#### 3.2.1 应用详情（AppDetailScreen，二级页同样 miuix 化）
- `Kit.DetailPage(标题 = 应用名)`，顶部 Header 块（图标 + 包名）。
- 三个风险级开关 → Section("风险级（默认全关）") + RowGroup 里三个 SwitchRow。
- 抓屏、自动分析、规则列表 → 各自 Section + 行。文案、逻辑全部保留，只换行样式。

### 3.3 设置（SettingsScreen → Settings.kt）
**平铺一张长列表，不加小分类标题**（用户拍板）。每行一张卡，全部进二级页：

| 行 | 摘要 | 去向 |
|---|---|---|
| 订阅与规则源 | 导入订阅 / 域名表 / 手写规则 | 二级页（见 3.3.1） |
| 备份与恢复 | 导出成文本，粘回来整份恢复 | 二级页（见 3.3.2） |
| 外观 | 主题、悬浮底栏 | 二级页（见 3.3.3） |
| 关于 | 版本、项目信息 | 二级页（见 3.3.4，完全对齐原版） |
| 全部暂停 / 恢复 | 一键停掉所有拦截 | 行内动作 + 确认弹窗（miuix Dialog） |

#### 3.3.1 订阅与规则源（二级页，现有两块表单合一）
- Section("导入")：粘贴订阅内容或域名表 / 填网址，miuix TextField + TextButton
  （导入 / 清空订阅），导入报告不静默（沿用现有 `importSource` 文案）。
- Section("我自己的规则")：包名 + 选择器 + 动作的手写表单 + 现有规则清单
  （每行一条，删除带确认）。「抓屏挑控件更省事」的提示保留为 Note。

#### 3.3.2 备份与恢复（新二级页，对齐原版结构）
对齐目标版 BackupSettingsScreen：
- Section("导出 / 恢复")：说明文字 + 导出（生成文本并复制剪贴板）/ 粘贴恢复两个 TextButton，
  粘贴恢复用一个大 TextField。
- Section("当前数据量")：KeyValue 行（规则条数 / 订阅导入 / 命中记录 / 拦截域名）。

#### 3.3.3 外观（二级页，对齐原版 AppearanceSettingsScreen）
- Section("主题") + RadioGroup：跟随系统 / 浅色 / 深色（现有 themeMode 落地）。
- Section("底栏") + SwitchRow：悬浮底栏（现有 floating_bar 保留）。

#### 3.3.4 关于（完全对齐原版 ScreenAbout）
- 沉浸式渐变 hero：高 60% 屏高，线性渐变底 + 4 团漂移光斑 + 底部渐隐遮罩；
  应用图标 88dp → 应用名 30sp Bold → 版本号；滚动视差与渐隐。
- 列表：开发者 / 项目仓库 / 版本信息（KeyValue）。
- 状态栏沉浸，返回交给系统手势。图标用现有启动图标，文案换成「无扰」。

### 3.4 导航（Nav.kt 新增）
- 引入参考实现 `Navigator`（三页签 + 返回栈 + jump/switch），
  替换掉现有 `tabState` / `openPkg` / `subPage` 三个散装 state。
- 根页切换用 HorizontalPager（页面状态不丢）；二级页整层滑入覆盖。
- **调试直达不变**：`--es tab` / `--es open_pkg` 翻译成 Navigator.switch/go，
  在 Activity 层完成（现有直达全部保留，这是真机验证的唯一手段——
  设备没有 INJECT_EVENTS，点不了屏幕）。新增 `--es dest hits` 直达命中记录页。

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
