#!/usr/bin/env bash
# scripts/release.sh — ocdroid 发版唯一入口
# 详见 docs/build-apk.md §6 与 .opencode/policies/build-signing.md。
#
# 用法: ./scripts/release.sh <patch|minor|major>
#
# 流程（每步都有强约束，agent 无法跳过）:
#   1. 校验分支=main、工作区干净
#   2. 质量门禁（编译 + 单测，等价 check.sh default）
#   3. bump 版本号（调用 bump-version.sh）
#   4. assembleRelease（自动读 local.properties 签名配置）
#   5. 产物归档到 APK/oc-droid-<versionName>.apk
#   6. commit + tag
#   7. 打印 push / tea 命令（不自动执行——对外发布需人工确认）
#
# 发版前评审 gate（结构化评审产物）见 .opencode/policies/review-gate.md。
# 本脚本不强制阻断于评审产物，是否强制评审由用户在发版前自行把控。

set -euo pipefail
source "$(dirname "$0")/env.sh"

TYPE="${1:?用法: release.sh <patch|minor|major>}"
[[ "$TYPE" =~ ^(patch|minor|major)$ ]] || { echo "❌ 类型必须是 patch|minor|major"; exit 1; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

# --- 1. git 前置校验 ---
BRANCH=$(git branch --show-current)
[[ "$BRANCH" == "main" ]] || { echo "❌ 当前分支=$BRANCH，发版必须在 main"; exit 1; }

# 工作区允许存在 untracked（APK/、local.properties 等），但不能有已跟踪文件的改动
if ! git diff --quiet HEAD || ! git diff --cached --quiet; then
  echo "❌ 工作区有未提交的已跟踪改动，请先 commit 或 stash"
  git status --short
  exit 1
fi

# --- 2. 质量门禁 ---
echo "==> 质量门禁：编译 + 单测"
./scripts/check.sh

# --- 3. bump 版本号 ---
echo "==> bump 版本号（$TYPE）"
./scripts/bump-version.sh "$TYPE"
VERSION=$(awk -F\" '/versionName *= */{print $2; exit}' app/build.gradle.kts)

# --- 4. release 构建 ---
echo "==> assembleRelease（versionName=$VERSION）"
./gradlew --no-daemon assembleRelease

APK_SRC=app/build/outputs/apk/release/app-release.apk
[[ -f "$APK_SRC" ]] || { echo "❌ 构建产物 $APK_SRC 不存在"; exit 1; }

# --- 5. 产物归档 ---
mkdir -p APK
APK_DST="APK/oc-droid-$VERSION.apk"
cp "$APK_SRC" "$APK_DST"
echo "✅ 产物: $APK_DST"

# --- 6. commit + tag ---
git add app/build.gradle.kts
git commit -m "release: $VERSION

versionName: $VERSION
versionCode: $(awk -F'= ' '/versionCode *= */{print $2; exit}' app/build.gradle.kts | tr -d ' ')"

TAG="v$VERSION"
git tag -a "$TAG" -m "Release $VERSION"

# --- 7. 对外发布命令（人工执行） ---
echo ""
echo "════════════════════════════════════════════════════════════"
echo "✅ 发版准备完成: $VERSION (tag $TAG)"
echo ""
echo "确认无误后执行（push + Gitea Release）:"
echo "  git push origin main && git push origin $TAG"
echo "  /home/mar/tools/tea/tea releases create -r mfreceiver/oc-droid \\"
echo "    --tag $TAG -t $TAG -n \"Release $TAG\" \\"
echo "    -a \"$APK_DST\""
echo "════════════════════════════════════════════════════════════"
