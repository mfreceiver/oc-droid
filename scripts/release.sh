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
#   6. 生成 changelog（从上个 tag..HEAD 的 conventional commits 分组）→ APK/oc-droid-<versionName>.md
#   7. commit + tag（tag 注释与 Gitea release note 都用该 changelog）
#   8. 打印 push / tea 命令（不自动执行——对外发布需人工确认）
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

# --- 6. 生成 changelog（从上个 tag..HEAD 的 conventional commits 分组）---
NOTE_FILE="APK/oc-droid-$VERSION.md"
PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
CL_RANGE=""
[[ -n "$PREV_TAG" ]] && CL_RANGE="$PREV_TAG..HEAD"

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
done < <(git log --no-merges ${CL_RANGE:+$CL_RANGE} --pretty=tformat:"%s")

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
echo "✅ Changelog: $NOTE_FILE（自 ${PREV_TAG:-<root>} 以来）"

# --- 7. commit + tag ---
git add app/build.gradle.kts
git commit -m "release: $VERSION

versionName: $VERSION
versionCode: $(awk -F'= ' '/versionCode *= */{print $2; exit}' app/build.gradle.kts | tr -d ' ')"

TAG="v$VERSION"
git tag -a "$TAG" -F "$NOTE_FILE"

# --- 8. 对外发布（人工执行：git push + curl Gitea API） ---
#    不再依赖 tea CLI（语法/超时问题）。改用原生 git push + curl Gitea REST API。
#    详见 docs/build-apk.md §发版上传。
echo ""
echo "════════════════════════════════════════════════════════════"
echo "✅ 发版准备完成: $VERSION (tag $TAG)"
echo "  APK:   $APK_DST"
echo "  Notes: $NOTE_FILE"
echo ""
echo "确认无误后执行（详见 docs/build-apk.md §发版上传）:"
echo "  git push origin main && git push origin $TAG"
echo "  ./scripts/upload-release.sh $VERSION   # curl Gitea API: 建release + 上传APK + 更新notes"
echo "════════════════════════════════════════════════════════════"
