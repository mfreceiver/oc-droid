#!/usr/bin/env bash
# scripts/bump-version.sh — 版本号 bump 唯一入口
# 详见 .opencode/policies/versioning.md。
#
# 用法: ./scripts/bump-version.sh <patch|minor|major>
#
# 同时维护 app/build.gradle.kts 的:
#   versionName — 语义化 MAJOR.MINOR.PATCH
#   versionCode — 单调递增整数，每次 +1
#
# 禁止手改 app/build.gradle.kts 的 version* 字段。

set -euo pipefail

TYPE="${1:?用法: bump-version.sh <patch|minor|major>}"
[[ "$TYPE" =~ ^(patch|minor|major)$ ]] || { echo "❌ 类型必须是 patch|minor|major"; exit 1; }

FILE=app/build.gradle.kts
[[ -f "$FILE" ]] || { echo "❌ 找不到 $FILE，请在仓库根目录运行"; exit 1; }

# 解析当前 versionName / versionCode
CUR_NAME=$(awk -F\" '/versionName *= */{print $2; exit}' "$FILE")
CUR_CODE=$(awk -F'= ' '/versionCode *= */{print $2; exit}' "$FILE" | tr -d ' ')
[[ -n "$CUR_NAME" && -n "$CUR_CODE" ]] || { echo "❌ 无法解析 versionName/versionCode（$FILE）"; exit 1; }

# 校验语义化格式 MAJOR.MINOR.PATCH
[[ "$CUR_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "❌ versionName 非 x.y.z 格式: $CUR_NAME"; exit 1; }

IFS=. read -r MA MI PA <<<"$CUR_NAME"
case "$TYPE" in
  patch) PA=$((PA+1)) ;;
  minor) MI=$((MI+1)); PA=0 ;;
  major) MA=$((MA+1)); MI=0; PA=0 ;;
esac
NEW_NAME="$MA.$MI.$PA"
NEW_CODE=$((CUR_CODE+1))

# 原地替换（只替换第一次匹配，避免误伤）
sed -i -E "0,/versionCode *= .*/ s//versionCode = $NEW_CODE/" "$FILE"
sed -i -E "0,/versionName *= \".*\"/ s//versionName = \"$NEW_NAME\"/" "$FILE"

echo "✅ $CUR_NAME ($CUR_CODE) → $NEW_NAME ($NEW_CODE)"
