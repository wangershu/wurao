#!/bin/bash
# 把规则文件推送到目标应用目录
#
# 用法: ./push_rules.sh <规则文件路径> <目标包名>
# 例:   ./push_rules.sh ./com.sina.weibo.txt com.sina.weibo
#
# 注意：规则文件名必须等于目标包名

set -e

RULE_FILE="$1"
PKG="$2"

if [ -z "$RULE_FILE" ] || [ -z "$PKG" ]; then
    echo "用法: $0 <规则文件路径> <目标包名>"
    exit 1
fi

if [ ! -f "$RULE_FILE" ]; then
    echo "规则文件不存在: $RULE_FILE"
    exit 1
fi

BASENAME=$(basename "$RULE_FILE")
if [ "${BASENAME%.txt}" != "$PKG" ]; then
    echo "警告: 文件名应为 <包名>.txt，当前是 $BASENAME"
    echo "已自动重命名处理"
fi

echo "==> 推送规则到 $PKG"
adb root >/dev/null 2>&1 || echo "（adb root 失败，请确保设备已 root）"
adb shell "mkdir -p /data/data/$PKG/.rules"
adb push "$RULE_FILE" "/data/data/$PKG/.rules/$PKG.txt"
adb shell "chmod 644 /data/data/$PKG/.rules/$PKG.txt"

echo "==> 校验"
adb shell "ls -l /data/data/$PKG/.rules/"

echo "==> 强制停止 $PKG 使其下次启动加载新规则"
adb shell "am force-stop $PKG" 2>/dev/null || true

echo "完成。重新打开 $PKG，然后在 LSPosed 日志中搜索 AdHammer 确认生效。"
