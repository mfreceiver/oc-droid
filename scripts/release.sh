#!/usr/bin/env bash
# scripts/release.sh — ocdroid 发版唯一入口（打 semver tag）。
# 详见 .opencode/policies/versioning.md 与 docs/build-apk.md §6。
#
# 用法: ./scripts/release.sh <patch|minor|major>
#
# 版本模型（go-around pattern）：版本号不写在任何文件里，唯一来源是 git——
#   versionName = git describe --tags --always --dirty（见 app/build.gradle.kts）
#   versionCode = git rev-list --count HEAD（单调递增，每 commit +1）
# 本脚本只做「给仓库打一个新 semver tag」：
#   1. 校验分支=main、工作区干净（已跟踪文件）
#   2. 质量门禁（./scripts/check.sh）
#   3. 由最新 git tag 推算下一版本（patch|minor|major）
#   4. assembleRelease + archiveReleaseApk（-PreleaseVersion 注入干净 tag 名）
#      → 产物 APK/oc-droid-<version>.apk（versionCode 自动 = commit count）
#   5. 生成 changelog（上个 tag..HEAD 的 conventional commits 分组）
#   6. 创建 annotated tag（注释 = changelog）——不 commit 任何版本文件
#   7. 打印 push / upload 命令（不自动执行——对外发布需人工确认）
#
# 「同版本族重发小修复」：tag 后的小修，直接 commit →
#   ./gradlew assembleRelease archiveReleaseApk   （不带 -PreleaseVersion）
# → APK 自带 versionName=<tag>-N-g<hash>、versionCode 更高，可装升级，无需新 tag。
# 只有里程碑式发版才跑本脚本打新 tag。
#
# 发版前评审 gate 见 .opencode/policies/review-gate.md（是否强制由用户把控）。

set -euo pipefail
source "$(dirname "$0")/env.sh"

TYPE="${1:?用法: release.sh <patch|minor|major>}"
[[ "$TYPE" =~ ^(patch|minor|major)$ ]] || { echo "❌ 类型必须是 patch|minor|major"; exit 1; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

# --- 1. git 前置校验 ---
BRANCH=$(git branch --show-current)
[[ "$BRANCH" == "main" ]] || { echo "❌ 当前分支=$BRANCH，发版必须在 main"; exit 1; }

# 允许 untracked（APK/、local.properties 等），但已跟踪文件不能有改动
if ! git diff --quiet HEAD || ! git diff --cached --quiet; then
  echo "❌ 工作区有未提交的已跟踪改动，请先 commit 或 stash"
  git status --short
  exit 1
fi

# --- 2. 质量门禁 ---
echo "==> 质量门禁：编译 + 单测"
./scripts/check.sh

# --- 3. 由最新 tag 推算下一版本 ---
PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
if [[ -z "$PREV_TAG" ]]; then
  echo "❌ 仓库无任何 tag，无法推断基线版本；请先手动 git tag v0.1.0"
  exit 1
fi
BASE="${PREV_TAG#v}"
IFS='.' read -r MAJOR MINOR PATCH <<<"$BASE"
case "$TYPE" in
  patch) PATCH=$((PATCH+1)) ;;
  minor) MINOR=$((MINOR+1)); PATCH=0 ;;
  major) MAJOR=$((MAJOR+1)); MINOR=0; PATCH=0 ;;
esac
VERSION="$MAJOR.$MINOR.$PATCH"
TAG="v$VERSION"
# 短 hash = release 构建嵌入 versionName + APK 文件名的 commit 锚点（与 gradle
# archiveReleaseApk 一致：二者都取 HEAD 的 git rev-parse --short）。
SHORT=$(git rev-parse --short HEAD)
FULL_VERSION="$VERSION-$SHORT"
echo "==> 版本：$PREV_TAG → $TAG（versionName=$FULL_VERSION）"

# --- 4. release 构建 + 归档（-PreleaseVersion 覆盖 tag 部分；hash 自动来自 HEAD）---
echo "==> assembleRelease + archiveReleaseApk（versionName=$FULL_VERSION）"
./gradlew --no-daemon assembleRelease archiveReleaseApk -PreleaseVersion="$VERSION"

APK_DST="APK/oc-droid-$FULL_VERSION.apk"
[[ -f "$APK_DST" ]] || { echo "❌ 归档产物 $APK_DST 不存在"; exit 1; }
echo "✅ 产物: $APK_DST"

# --- 5. changelog（上个 tag..HEAD 的 conventional commits 分组）---
NOTE_FILE="APK/oc-droid-$FULL_VERSION.md"
CL_RANGE="$PREV_TAG..HEAD"
FEATS=""; FIXES=""; DOCS=""; TESTS=""; REFACTORS=""; PERFS=""; MISC=""
cc_re='^([a-zA-Z]+)(\([^)]*\))?!?:[[:space:]](.+)$'
while IFS= read -r subject; do
  [[ -z "$subject" ]] && continue
  case "$subject" in release:*) continue ;; esac
  if [[ "$subject" =~ $cc_re ]]; then
    ctype="${BASH_REMATCH[1]}"; cdesc="${BASH_REMATCH[3]}"
  else
    ctype="other"; cdesc="$subject"
  fi
  case "$ctype" in
    feat)       FEATS+="- $cdesc"$'\n' ;;
    fix)        FIXES+="- $cdesc"$'\n' ;;
    docs)       DOCS+="- $cdesc"$'\n' ;;
    test|tests) TESTS+="- $cdesc"$'\n' ;;
    refactor)   REFACTORS+="- $cdesc"$'\n' ;;
    perf)       PERFS+="- $cdesc"$'\n' ;;
    chore|ci|build|style|revert) MISC+="- $cdesc"$'\n' ;;
    release)    ;;
    *)          MISC+="- $cdesc"$'\n' ;;
  esac
done < <(git log --no-merges "$CL_RANGE" --pretty=tformat:"%s")

cl_emit() { [[ -z "$2" ]] && return 0; printf '### %s\n\n%s\n' "$1" "$2"; }
{
  printf 'Release v%s\n\n' "$VERSION"
  cl_emit "Features"      "$FEATS"
  cl_emit "Bug Fixes"     "$FIXES"
  cl_emit "Documentation" "$DOCS"
  cl_emit "Tests"         "$TESTS"
  cl_emit "Refactor"      "$REFACTORS"
  cl_emit "Performance"   "$PERFS"
  cl_emit "Miscellaneous" "$MISC"
} > "$NOTE_FILE"
echo "✅ Changelog: $NOTE_FILE（自 $PREV_TAG 以来）"

# --- 6. 创建 annotated tag（不 commit 任何版本文件）---
git tag -a "$TAG" -F "$NOTE_FILE"
echo "✅ Tag 创建: $TAG"

# --- 7. 对外发布（人工执行）---
echo ""
echo "════════════════════════════════════════════════════════════"
echo "✅ 仓库发版准备完成: $VERSION (tag $TAG)"
echo "  APK:   $APK_DST"
echo "  Notes: $NOTE_FILE"
echo ""
echo "确认无误后执行（详见 docs/build-apk.md §6.3）:"
echo "  git push origin main && git push origin $TAG"
echo "  ./scripts/upload-release.sh $VERSION   # curl Gitea API: 建/找 release + 上传 APK + 更新 notes"
echo "════════════════════════════════════════════════════════════"
