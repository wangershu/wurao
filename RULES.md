# AdHammer 规则规范

> 本文档基于对 `me.app.xp.ad.hammer`（AdHammer / 原「大圣净化」）APK 的逆向分析整理，
> 并结合原作者博客（hammer-finger.blogspot.com）的使用说明补全语义。
> 字段名与原版规则 JSON **完全一致**，因此历史规则文件可以直接复用。

---

## 一、规则文件放在哪

| Android 版本 | 路径 |
|---|---|
| 9.0 (API 28) 及以上 | `/data/data/<目标包名>/.rules/<目标包名>.txt` |
| 8.1 及以下 | `/data/data/<模块包名>/app_rules/<目标包名>.txt` |

- 文件名必须等于**目标应用的包名**，例如微博就是 `com.sina.weibo.txt`
- 内容是 **宽松 JSON**（Gson lenient 模式），容错性较好
- 放在目标应用自己的私有目录下，模块以目标进程身份读取，绕过 Android 10+ 的存储限制

---

## 二、顶层结构（AppRule）

一份规则文件 = 一个 App 的全部规则。

```json
{
  "pkg": "com.sina.weibo",
  "home": "com.sina.weibo.MainActivity",
  "isOpen": true,
  "isDef": false,
  "adTips": "已为你跳过广告",

  "mAdMap":   { },
  "mViewMap": { },
  "mUrlList": [ ],
  "mHostList": [ ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `pkg` | String | 目标应用包名 |
| `home` | String | **首页 Activity**。跳过广告后的落点，非常重要 |
| `isOpen` | boolean | 总开关。`false` 时整份规则不生效，也不弹提示 |
| `isDef` | boolean | 是否云端默认规则（影响提示样式） |
| `adTips` | String | 去广告成功时的 Toast 文案，留空则不提示 |
| `mAdMap` | Map | Activity 级规则表，**主键是 Activity 全名**（见第三节） |
| `mViewMap` | Map | 精准 View 规则表，主键是 Activity 全名或包名 |
| `mUrlList` | Array | URL 拦截规则 |
| `mHostList` | Array | Host / DNS 拦截规则 |

### 环境伪装字段（可选）

很多 App 按「机型 / 版本 / 地区」下发广告，伪装成另一台设备即可绕过。

| 字段 | 伪装目标 |
|---|---|
| `mf` | `Build.MANUFACTURER` 厂商 |
| `bd` | `Build.BRAND` 品牌 |
| `ml` | `Build.MODEL` 型号 |
| `pt` | `Build.PRODUCT` |
| `dv` | `Build.DEVICE` |
| `sl` | 序列号 |
| `lang` | 语言 |
| `mac` | WIFI MAC |
| `ss` | WIFI SSID |
| `vc` | `versionCode` 版本伪装 |
| `vn` | `versionName` 版本伪装 |

---

## 三、Activity 级规则（mAdMap）——核心

### 3.1 基本形式

```json
"mAdMap": {
  "com.sina.weibo.ad.SplashAdActivity": {
    "at": 9,
    "d": 0,
    "to": "",
    "f": "",
    "m": "启动页广告"
  }
}
```

| 字段 | 说明 |
|---|---|
| **key** | 广告 Activity 的**完整类名**（可用 `adb shell dumpsys activity` 或悬浮窗获取） |
| `at` | **动作类型**，见下表，这是规则的灵魂 |
| `d` | 延迟毫秒。`at=1` 时，`d>0` 表示延迟后再按返回键（应对「倒计时结束才能跳过」） |
| `to` | 跳转目标 Activity。**留空则用顶层 `home`** |
| `f` | 解密密钥（仅 `at=6` 用） |
| `m` | 备注，不影响执行 |

### 3.2 动作类型 `at` 全表

| at | 名称 | 实际行为 | 适用场景 | 副作用 |
|---|---|---|---|---|
| **1** | 关闭活动页 | `finish()` 当前页；`d>0` 时延迟后模拟返回键 | 独立的广告页，关掉不影响使用 | **会有一闪而过** |
| **2** | 禁用活动页 | 把该组件设为 `DISABLED`，以后根本起不来 | 独立广告页 | **可能引起闪退** |
| **3** | 跳过到首页 | 启动 `home`/`to` 并 `finish` 当前页，无转场动画 | 启动页 → 广告 → 首页 | 外部跳转也可能被带到首页 |
| **4** | 改写跳转目标 | 在 `startActivity` 阶段把目标改成首页 | 跳转型广告 | 同上 |
| **5** | 文件替换 | 用同名空文件/夹替换广告缓存 | 广告素材已下载到本地 | 需批量替换 |
| **6** | 自定义 Hook | 执行 `ak` 里的脚本 | 高级场景 | 需密钥 |
| **7** | 替换为首页（移花接木） | 同 3，由系统完成跳转 | 同 3 | 部分容器（太极）不支持 |
| **8** | 精准 View 规则 | `ak` 为 ViewHookInfo 数组，隐藏指定控件 | **按钮没文字也没 id** | 无 |
| **9** | 防火墙拦截 | 在 `startActivity` 阶段把 Intent 掏空 | 独立广告页 | **效果最好，不闪不崩** |
| 100 | 保留位 | — | — | — |
| 500–503 | 扩展拦截 | 同 9 | — | — |

> **选型口诀**（来自原作者说明）：
> 广告页是独立页面 → 优先用 **9**（防火墙拦截），效果最好不闪退；
> 若是 启动页→广告→首页 的套路 → 用 **3** 或 **7**；
> 广告直接长在首页上 → 用 **8**（精准 View）；
> 只有 1/2/9 都不好使时才考虑 1 和 2。

### 3.3 特殊主键后缀

主键（key）除了普通 Activity 名，还支持三种后缀，用来承载其他类型的规则：

| 后缀 | 含义 | `ak` 里放什么 |
|---|---|---|
| `.Url` | URL / Host 拦截 | 按 `at` 决定：1/2 → URL 列表，3 → Host 列表 |
| `.Replace` | 文件替换 | 路径字符串，或 ReplaceInfo 的 JSON 数组 |
| `.Dialog` | 弹框规则 | DlgHookInfo 的 JSON 数组；**留空 + `at=5`** 表示禁止全部弹框 |
| `.Hook` | 自定义 Hook | 脚本（可加密，密钥在 `f`） |

示例：

```json
"mAdMap": {
  "com.sina.weibo.ad.SplashAdActivity": { "at": 9 },
  "com.sina.weibo.Url":     { "at": 3, "ak": ".*\\.doubleclick\\.net" },
  "com.sina.weibo.Dialog":  { "at": 5, "ak": "[{\"at\":0,\"t\":\"领取奖励\"}]" },
  "com.sina.weibo.Hook":    { "at": 6, "ak": "<脚本>", "f": "<密钥>" }
}
```

---

## 四、精准 View 规则（mViewMap / at=8）

### 4.1 解决什么问题

越来越多的「跳过」按钮**既没有文字也没有 id**，无法通过常规方式定位。
精准匹配通过 **类名前缀 + 资源名 + 文本 + 父层级 + 子层级 + 子序号** 组合定位到最底层的 View，然后把它压成 0×0 并 `GONE`。

### 4.2 结构

```json
"mViewMap": {
  "com.sina.weibo.ad.SplashAdActivity": [
    {
      "v": "android.widget.TextView",
      "s": true,
      "id": "tv_skip;skip_btn",
      "t": "跳过;skip",
      "ht": 1,
      "pl": 2,
      "p": "android.widget.FrameLayout",
      "pId": "ad_container",
      "pt": "",
      "cl": 1,
      "c": "android.widget.ImageView",
      "cId": "",
      "ct": "",
      "sq": "0",
      "m": "跳过按钮"
    }
  ]
}
```

### 4.3 字段详解

**入口定位（先找到「候选」View）**

| 字段 | 说明 |
|---|---|
| `v` | **必填**。入口 View 的类名前缀，`startsWith` 匹配，如 `android.widget.TextView` |
| `s` | 规则开关，`false` 停用该条（默认 `true`） |
| `id` | 入口 View 的资源名（`resourceEntryName`），多个候选用 `;` 分隔 |
| `t` | 入口 View 的文本，多个候选用 `;` 分隔；**包含匹配**，写成 `#跳过#` 表示全等 |
| `ht` | Hook 类型：`1` = 常规（支持父/子回溯），`2`/`3` = 其他分支 |

**目标定位（从入口 View 走到真正要隐藏的那个）**

| 字段 | 说明 |
|---|---|
| `pl` | **parent level**：向上走几层父容器，`0` 表示不回溯 |
| `p` / `pId` / `pt` | 父容器的 类名 / 资源名 / 文本（二次校验，留空则不校验） |
| `cl` | **child level**：向下找几层子 View，`0` 表示不查找 |
| `c` / `cId` / `ct` | 子 View 的 类名 / 资源名 / 文本 |
| `sq` | **子序号**：`ViewGroup.getChildAt(index)`，多个用 `;` 分隔，如 `"0;2"` |

**执行动作**：命中后递归整棵子树执行 —— 宽高归零 + 边距归零 + `GONE` + padding 归零 + `alpha=0`（五重保险，既不占位也不闪现）。

### 4.4 匹配流程

```
View 出现（构造 / setText / setVisibility / setLayoutParams / 布局完成）
   │
   ├─① 类名前缀命中 v，且 s=true        → 拿到 ViewHookInfo
   ├─② 校验资源名 id（; 分隔任一命中）
   ├─③ 校验文本 t（包含匹配，或 #xx# 全等）
   ├─④ 按 pl 向上找父 → 用 p/pId/pt 校验
   │     或按 cl 向下找子 → 用 c/cId/ct 校验
   ├─⑤ 按 sq 取指定序号的子 View
   └─⑥ 递归隐藏整棵子树
```

### 4.5 制作精准规则的步骤（对应原作者说明）

1. 开启**悬浮窗**，它一行行显示的就是当前 Activity 名 —— 确定目标页面
2. 点**快照 / 查看布局**（需要激活），把当前页面元素解析出来
3. 展开到**最底层的那个 View**（展开后的最后一个 View），这样才准确
4. 点**锤子**图标，自动跳转到规则保存页
5. 点击保存

> 关键：**要选最底层的 View**，选到外层容器会误伤整个页面。

---

## 五、弹框规则（.Dialog）

```json
"com.sina.weibo.Dialog": {
  "at": 5,
  "ak": "[{\"at\":2,\"t\":\"领取奖励\",\"clkTxt\":\"关闭\",\"d\":500}]"
}
```

`ak` 是 DlgHookInfo 数组：

| 字段 | 说明 |
|---|---|
| `at` | **0** = 禁止弹框 / **1** = 自动取消 / **2** = 自动点击 |
| `id` | 判定用资源 id，多个候选用 `;` 分隔 |
| `t` | 判定用文字（Dialog 内出现的文本），多个候选用 `;` 分隔 |
| `n` | 判定用 Dialog 类名（全等） |
| `clkTxt` | `at=2` 时要点击的目标文字 |
| `clkId` | `at=2` 时要点击的目标资源 id |
| `d` | 延迟毫秒 |

**匹配逻辑**：`id` 命中 **或** `n` 命中 **或** `t` 命中；三者都留空则**无条件匹配**（慎用）。

---

## 六、URL / Host 拦截（.Url）

```json
"com.sina.weibo.Url": { "at": 3, "ak": ".*\\.doubleclick\\.net" }
```

| `at` | 含义 | 拦截位置 |
|---|---|---|
| 1 / 2 | URL 拦截 | `URL.openConnection`、`WebView.loadUrl` |
| 3 | Host / DNS 拦截 | `InetAddress.getByName` → 返回 127.0.0.1 |

### ⚠️ 重要：host 字段是正则表达式，不是通配符

原作者博客里写的 `*.doubleclick.net` 在**实现层面是正则 `find()` 匹配**，需要转义：

| 博客写法 | 实际要填的正则 |
|---|---|
| `googleads.g.doubleclick.net`（完整域名） | `googleads\.g\.doubleclick\.net` |
| `*.doubleclick.net` | `.*\.doubleclick\.net` |
| `*.g.doubleclick.net` | `.*\.g\.doubleclick\.net` |
| 含 ad/ads 关键字 | `.*ad[s]?\.` |

**制作规则**：开启悬浮窗 → 勾选「显示 Url」→ 观察所有域名查询 → 黄色（含 ad/ads）很可能就是广告 → 加进规则。
抓不到的可以用 HttpCanary / VNET / AdGuard 辅助。

---

## 七、文件替换（.Replace）

```json
"com.sina.weibo.Replace": { "at": 5, "ak": "/sdcard/Android/data/com.sina.weibo/cache/ad/" }
```

原理：把广告缓存目录替换成同名的空文件/夹，让 App 既写不进也读不出。

**安全限制**（不得替换）：
- `/data`、`/data/data`
- 外置存储根目录

路径需匹配 `(^\.|^/|^[a-zA-Z])?/.+(/$)?`。

---

## 八、完整示例（微博启动广告）

```json
{
  "pkg": "com.sina.weibo",
  "home": "com.sina.weibo.MainTabActivity",
  "isOpen": true,
  "isDef": false,
  "adTips": "已跳过开屏广告",

  "mAdMap": {
    "com.sina.weibo.ad.AdSplashActivity": { "at": 9, "d": 0, "m": "开屏广告-防火墙拦截" },
    "com.sina.weibo.feed.FeedAdActivity": { "at": 1, "d": 300, "m": "信息流广告-延迟关闭" },
    "com.sina.weibo.Url": { "at": 3, "ak": ".*\\.doubleclick\\.net" },
    "com.sina.weibo.Dialog": { "at": 5, "ak": "[{\"at\":0,\"t\":\"领取奖励\"}]" }
  },

  "mViewMap": {
    "com.sina.weibo.MainTabActivity": [
      {
        "v": "android.widget.TextView",
        "s": true,
        "t": "跳过",
        "ht": 1,
        "pl": 0,
        "cl": 0,
        "m": "首页悬浮广告的跳过按钮"
      }
    ]
  },

  "mUrlList": [
    { "at": 1, "host": ".*ad[s]?\\..*\\.weibo\\.cn" }
  ],

  "mHostList": [
    { "at": 3, "host": ".*\\.doubleclick\\.net" }
  ]
}
```

---

## 九、调试与排错

| 现象 | 排查方向 |
|---|---|
| 完全没生效 | ① 规则文件路径/文件名是否为目标包名 ② `isOpen` 是否为 true ③ LSPosed 里是否勾选了该应用 ④ 看 LSPosed 日志里有没有 `loaded rule for <pkg>` |
| 一闪而过 | 用了 `at=1`，改成 `at=9` |
| 应用闪退 | 用了 `at=2`（禁用组件），改成 `at=9` 或 `at=3` |
| 误伤正常页面 | `home` 填错，或 View 规则的 `v` 前缀太宽泛；把 `pl`/`cl` 收紧、加 `id`/`t` 条件 |
| 网络不通 | Host 规则正则写太宽，误拦了业务域名 |
| 广告还在但按钮没了 | 说明 View 被隐藏了，但广告容器还在；往上调 `pl` 隐藏整个容器 |

日志关键字（LSPosed 日志中搜索 `AdHammer`）：
- `loaded rule for` —— 规则加载成功
- `install hooks for` —— 各 Hooker 装配完成
- `hit ad activity` —— 命中广告页
- `intercept start` —— 拦截了跳转
- `block url` / `block host` —— 网络拦截生效
- `match view rule` —— 命中精准 View 规则
