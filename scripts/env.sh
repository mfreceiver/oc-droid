#!/usr/bin/env bash
# scripts/env.sh — ocdroid 构建环境唯一来源（source 使用）
# 任何构建/校验脚本开头 `source "$(dirname "$0")/env.sh"` 即可。
# 不要在别处重复 export JAVA_HOME / ANDROID_HOME。

set -euo pipefail

export JAVA_HOME=/home/mar/android-studio/jbr
export ANDROID_HOME=/home/mar/android-sdk
export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools"

# local.properties 必须存在
if [[ ! -f local.properties ]]; then
  echo "❌ 缺少 local.properties，运行：printf 'sdk.dir=/home/mar/android-sdk\n' > local.properties" >&2
  exit 1
fi
