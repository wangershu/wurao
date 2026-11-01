# 订阅源实测记录

结论先行：**这些都是导入项，不内置**。内置只有 2 条开屏规则 + 5 个域名 + 少量种子组件名单，
订阅一律由你在设置页粘贴或填网址导入。

## 能用（已实测导入成功）

| 源 | 类型 | 规模 | 实测结果 |
|---|---|---|---|
| `https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5` | GKD 订阅 | 1.1MB / 3189 条 | 采用 3127（98%），跳过 62（都是"没有 matches"），耗时 3.1s，送达 3111 条 |
| `https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/AWAvenue-Ads-Rule.txt` | 广告域名表 | 23KB / 976 行 | 认出域名正常（片段 80 行认出 72 个），送达 DNS 拦截 |
| `https://raw.githubusercontent.com/qq5460168/666/master/AdClose.txt` | 广告域名表 | 414KB / **17305 行** | **17304 条全进**（已去重），库里 28548 条、送达 25016 条域名 |

注意：该 GKD 订阅的 name 里写着"已停止维护"，能用但不会再更新。

## 大圣（dy_ys88）—— 旧格式，需在电脑上转换

`https://github.com/12ccl/dy_ys88/raw/main/dy_ys88.zip`（80KB，解压出多个 txt）：
`sha_seng_hosts.txt`（域名）、`wu_kong_block_component.txt`（组件）、`wu_kong_rules.txt`（规则）、
`ba_jie_tasks.txt`（多步骤脚本）等。

**手机访问不到 GitHub，这个只能在电脑上处理**：

```bash
python3 tools/import_dasheng.py dy_ys88.zip -o dasheng_rules.txt
```

转出结果：域名 1789、组件 454、弹窗文案点击 9，**去重后 2214 条**。

不映射的部分（我们的模型没有对应语义）：
- `ba_jie_tasks.txt` 多步骤自动化脚本
- `ba_jie_file_replace.txt` / `wu_kong_replaces.txt` 文件与内容替换
- `ba_jie_auto_skip.txt` 包名白名单（需人工决定）
- `wu_kong_block_view.txt` 按 View 拦截

转换产物是**数据不是代码**：旧格式的解析只存在于 tools/ 里，不进 APK。

导入到手机（手机访问不到 GitHub，用 adb）：
- **大表首选**：`adb push 文件 /data/local/tmp/xxx` 再
  `adb shell run-as com.wurao.app cp /data/local/tmp/xxx /data/data/com.wurao.app/files/`，
  然后 `--es import_file xxx`（一次读完，不用分片，且走后台线程）
- 小表：粘贴内容，或 `--es import_sub_b64`（每片 ≤ ~50KB 字符）
- 网址：`--es import_url`（后台下载，15s 连接 / 25s 读取超时）

## 不能用 / 待确认

- `https://dy.940622.xyz/dy_ys10`（大圣）：HTTP 522，源站挂了
- `https://github.com/12ccl/dy_ys88/raw/main/dy_ys88.json`：实际给的是 **.zip**（见上一节），
  在电脑上下载解压后转换即可
- `https://github.com/qq5460168/666`：README 404，没看到规则文件在哪
- `https://github.com/A2O-A1/Collection-of-Advertising-Rules`：**不是规则本身，是规则索引**，
  里面列了 kbsml.com 的 AdGuard 规则地址（adg-kall.txt / adg-kall-dns.txt）
- `https://github.com/Lin-arm/GKD_subscription`：未测

## 踩过的坑（别再犯）

1. **认订阅类型不能靠 `contains("apps")`**。域名里常有 `apps.xxx.com`，
   含这个词的整份会被误判成 GKD 订阅拿去当 JSON5 解析，然后**静默报废**。
   表现为"导入了但只进了一半"（17305 只进 7827），且不报错。
   判据改成"去掉注释后首行是否以 `{` 开头"。
2. **导入/推送绝不能在主线程**。几万条规则时编码+写配置+推文件是好几秒的活，
   实测把模块卡成 ANR（dropbox 里留着记录）。现在导入走 `runInBackground`、
   推送走 `pushRulesAsync`。
3. 分片 base64 每片别超过 ~50KB 字符（adb 传不动更大的）。

## 导入方式

设置页 → 导入订阅/域名表：
- 粘贴内容，或
- 填网址（后台下载，15s 连接超时 / 25s 读取超时，失败会报具体异常）

反复导入同一份不会翻倍；更新订阅只替换订阅那一份，**不动你自己抓的规则**。

## 已知隐患

3000+ 条规则时，每次页面切换后的匹配要按作用域过滤整份规则再匹配。
目前过滤是字符串比较、很便宜，但**没做按 App 的索引**；真觉得电不够用再优化。
