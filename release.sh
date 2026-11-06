#!/bin/sh
# 发布构建：产出规范命名的 release APK
#
# 命名规则：wurao-v<versionName>.apk
# versionName = 0.3.1.<当前 HEAD 的 7 位短哈希>（与 build.gradle.kts 一致）
# 先提交再执行本脚本，APK 里的版本号才是发布提交的哈希。
set -e
cd "$(dirname "$0")"
HASH=$(git rev-parse --short=7 HEAD)
VER="0.3.1.$HASH"
./build.sh :app:assembleRelease
OUT="app/build/outputs/apk/release"
cp "$OUT/app-release.apk" "$OUT/wurao-v$VER.apk"
echo "release: $OUT/wurao-v$VER.apk (versionName=$VER)"
