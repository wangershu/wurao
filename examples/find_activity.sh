#!/bin/bash
# 抓取目标应用当前栈顶的 Activity 名 —— 写规则第一步
#
# 用法: ./find_activity.sh <目标包名>
# 例:   ./find_activity.sh com.sina.weibo
#
# 操作：先在手机上打开目标 App，停在有广告的那个页面，然后运行本脚本。
# 输出中第一个 "ACTIVITY <包名>/<类名>" 就是广告 Activity 的全名。

PKG="${1:-}"

echo "==> 当前栈顶 Activity"
if [ -n "$PKG" ]; then
    adb shell dumpsys activity activities | grep -m5 "topResumedActivity\|realActivity" | head -5
    echo ""
    echo "==> 筛选 $PKG 相关"
    adb shell dumpsys activity activities | grep -o "${PKG}/[A-Za-z0-9_.$]*" | sort -u
else
    adb shell dumpsys activity activities | grep -m3 "topResumedActivity"
fi

echo ""
echo "==> 也可以持续观察（每 2 秒刷新一次，Ctrl+C 退出）："
echo "    adb shell 'while true; do dumpsys activity activities | grep mResumedActivity; sleep 2; done'"
