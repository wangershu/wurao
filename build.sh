#!/bin/sh
# 构建包装脚本 —— 必须用它，不要直接 ./gradlew。
#
# 为什么需要它：在沙箱化的终端里构建时，新建的文件会被打上
# `com.apple.provenance` 扩展属性，而 Gradle 下一次构建想删除自己的旧产物时会失败
# （java.io.IOException: Unable to delete directory ...）。
# 表现是「构建报错但看不到任何编译错误」，极易误判成代码问题。
#
# 顺带关掉 Gradle 9 的 problems-report（它写的 html 同样会被卡住），
# 以及 Kotlin 增量编译（增量备份目录撞同一个问题）。
#
# 用法：./build.sh :app:assembleDebug
set -e
cd "$(dirname "$0")"

# 清掉扩展属性，让 Gradle 能删自己的旧产物
[ -d app/build ] && xattr -cr app/build 2>/dev/null || true
[ -d build ] && xattr -cr build 2>/dev/null || true
[ -d .kotlin ] && xattr -cr .kotlin 2>/dev/null || true

exec ./gradlew --no-problems-report -Pkotlin.incremental=false "$@"
