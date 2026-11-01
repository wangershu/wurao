# 订阅源实测记录

结论先行：**这些都是导入项，不内置**。内置只有 2 条开屏规则 + 5 个域名 + 少量种子组件名单，
订阅一律由你在设置页粘贴或填网址导入。

## 能用（已实测导入成功）

| 源 | 类型 | 规模 | 实测结果 |
|---|---|---|---|
| `https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5` | GKD 订阅 | 1.1MB / 3189 条 | 采用 3127（98%），跳过 62（都是"没有 matches"），耗时 3.1s，送达 3111 条 |
| `https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/AWAvenue-Ads-Rule.txt` | 广告域名表 | 23KB / 976 行 | 认出域名正常（片段 80 行认出 72 个），送达 DNS 拦截 |

注意：该 GKD 订阅的 name 里写着"已停止维护"，能用但不会再更新。

## 不能用 / 待确认

- `https://dy.940622.xyz/dy_ys10`（大圣）：HTTP 522，源站挂了
- `https://github.com/12ccl/dy_ys88/raw/main/dy_ys88.json`：我这台机器访问 github.com 超时
  （HTTP=000，raw.githubusercontent.com 与 npmmirror 都通），手机端同样没下载成功。
  换 jsDelivr / ghproxy 之类的镜像应该可以，需要时试
- `https://github.com/qq5460168/666`：README 404，没看到规则文件在哪
- `https://github.com/A2O-A1/Collection-of-Advertising-Rules`：**不是规则本身，是规则索引**，
  里面列了 kbsml.com 的 AdGuard 规则地址（adg-kall.txt / adg-kall-dns.txt）
- `https://github.com/Lin-arm/GKD_subscription`：未测

## 导入方式

设置页 → 导入订阅/域名表：
- 粘贴内容，或
- 填网址（后台下载，15s 连接超时 / 25s 读取超时，失败会报具体异常）

反复导入同一份不会翻倍；更新订阅只替换订阅那一份，**不动你自己抓的规则**。

## 已知隐患

3000+ 条规则时，每次页面切换后的匹配要按作用域过滤整份规则再匹配。
目前过滤是字符串比较、很便宜，但**没做按 App 的索引**；真觉得电不够用再优化。
