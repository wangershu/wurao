# 无扰 / wurao

基于 LSPosed（libxposed API 102）的去广告模块，自用为主、不分发。设计上把**耗电**放在第一位：hook 只挂在低频触发点，规则按应用切分后落位到框架远程文件，目标进程不常驻轮询。

## 功能

- **控件隐藏**：抓屏点选控件生成规则，hook 侧按属性选择器或屏幕区域（IoU）隐藏整块 View
- **DNS 拦截**：AdClose 域名表，命中后解析到 `0.0.0.0`；误屏蔽可在域名表里单条移除/恢复
- **开屏自动点按**：内置 GKD 官方订阅的开屏规则集，只在冷启动 / 从后台切回时执行，成功点一次即停
- **跳过提醒**：点按成功弹 Toast，文字可编辑（默认「已跳过」），也可关闭
- **命中记录**：目标进程通过 ContentProvider 回传，今日拦截页按应用显示、可直接停用规则

## 构建

构建一律走 `build.sh`（绕开 macOS provenance 属性导致 Gradle 删不掉旧产物的问题）：

```sh
./build.sh :app:assembleDebug      # 调试包
./build.sh :app:testDebugUnitTest  # 单测
```

环境：Gradle 9.8.1 / AGP 9.4.1 / compileSdk 37 / minSdk 26 / targetSdk 35 / JDK 17。
`local.properties` 需配置本机 SDK 路径，build-tools 固定 37.0.0。

## 版本号与发布包命名

**版本号**：`0.3.1.<发布提交的 7 位短哈希>`，`versionCode = 3`。
`app/build.gradle.kts` 在构建时执行 `git rev-parse --short=7 HEAD` 取值，不硬编码，所以要先提交、再打 release —— APK 里的 `versionName` 就是发布提交的哈希。

**发布包命名**：`wurao-v<versionName>.apk`，例如 `wurao-v0.3.1.e48f4df.apk`。

```sh
./release.sh    # 构建 release 并产出规范命名的 APK
```

`release.sh` 会从当前 HEAD 取短哈希，构建后把 `app-release.apk` 复制成 `wurao-v0.3.1.<hash>.apk`（release 沿用 debug 签名，自用可直接安装）。

**发版流程**：提交代码 → `./release.sh` → `gh release create v<versionName> <规范命名 APK> ...`。
App 内「检查更新」查询 `/releases/latest`，tag 去掉 `v` 后与 `versionName` 比较，一致即显示「已是最新版」。

## 安装与使用

1. 安装模块 APK，在 LSPosed 启用并勾选需要生效的作用域
2. 规则从设置页导入，或开悬浮球（需悬浮窗权限）抓屏生成；抓屏还需要开启无障碍服务
3. LSPosed 未连接时主页会常驻告警，且不允许忽略；仅「框架已连、只差无障碍」时可忽略该提示

## 规则来源

- 开屏点按：GKD 官方订阅 v186 的全局「开屏广告」组 + 各应用专属开屏组（内置 `assets/gkd_splash.tsv`）
- 广告域名：AdClose 开源域名清单

## 架构要点

- 入口 `com.wurao.hook.WuraoModule`，注册在 `assets/xposed_init` 与 `META-INF/xposed/java_init.list`
- 界面 → hook：框架远程文件（`wurao_rules_<pkg>` / `wurao_click_<pkg>` / `wurao_domains` / `cfg.json`）
- hook → 界面：ContentProvider `content://com.wurao.app.hits`
- 远程偏好是目标进程启动时的快照、后续写不刷新，因此**不用**它传规则
- 统一 `Rule` 模型 + `RuleStore` 幂等 upsert，`ruleKey` 用 target hash

## 声明

仅供学习交流与技术研究使用，请勿用于商业或违法用途。因使用本模块产生的任何后果由使用者自行承担。
