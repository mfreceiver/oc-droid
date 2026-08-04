#!/usr/bin/env bash
# scripts/release.sh — ocdroid 发版唯一入口（打 semver tag）。
# 详见 .opencode/policies/versioning.md 与 docs/specs/build-apk.md §6。
#
# 用法: ./scripts/release.sh <patch|minor|major> [--allow-dirty] [--ocmar-workflow <slug>]
#
# 版本模型（go-around pattern）：版本号不写在任何文件里，唯一来源是 git——
#   versionName = git describe --tags --always --dirty（见 app/build.gradle.kts）
#   versionCode = git rev-list --count HEAD（单调递增，每 commit +1）
# 本脚本只做「给仓库打一个新 semver tag」：
#   1. 校验分支=main、工作区干净（与 gradle 一致：git status --porcelain 为空；
#      dirty 默认报错，仅 --allow-dirty 才放行，产物名带 -dirty）
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

TYPE=""
ALLOW_DIRTY=0
OCMAR_SLUG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --ocmar-workflow) shift; OCMAR_SLUG="$1" ;;
    patch|minor|major) TYPE="$1" ;;
    *) echo "❌ 未知参数: $1（用法: release.sh <patch|minor|major> [--allow-dirty] [--ocmar-workflow <slug>]）"; exit 1 ;;
  esac
  shift
done
[[ -n "$TYPE" ]] || { echo "用法: release.sh <patch|minor|major> [--allow-dirty] [--ocmar-workflow <slug>]"; exit 1; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

# --- 1. git 前置校验 ---
BRANCH=$(git branch --show-current)
[[ "$BRANCH" == "main" ]] || { echo "❌ 当前分支=$BRANCH，发版必须在 main"; exit 1; }

# 与 gradle 的 dirty 判定完全一致（app/build.gradle.kts:54 用 git status --porcelain）：
# 任何非 gitignored 的改动（含 untracked 文件）都会让 versionName/产物名带 -dirty 后缀。
# 里程碑 tag 必须对应一棵干净树——默认 dirty 直接报错；仅 --allow-dirty 显式放行
# （此时产物名带 -dirty）。gitignored 的 APK/、local.properties 不算 dirty。
DIRTY=""
if [[ -n "$(git status --porcelain)" ]]; then
  if [[ "$ALLOW_DIRTY" -eq 1 ]]; then
    echo "⚠️  --allow-dirty：工作区非干净，产物 versionName 将带 -dirty 后缀"
    git status --short
    DIRTY="-dirty"
  else
    echo "❌ 工作区非干净（含 untracked 文件），里程碑发版要求干净树。"
    echo "   请 commit / stash / 丢弃改动后重试；或确需带 dirty 发版，加 --allow-dirty。"
    git status --short
    exit 1
  fi
fi

# --- 1b. ocmar 交付闭环检查（仅 --ocmar-workflow 模式）---
if [[ -n "$OCMAR_SLUG" ]]; then
  # ocmar release 禁止 dirty——里程碑 tag 必须干净树
  if [[ "$ALLOW_DIRTY" -eq 1 ]]; then
    echo "❌ --ocmar-workflow 与 --allow-dirty 互斥：ocmar 发版要求干净树"
    exit 1
  fi
  OCMAR_STATE=".ocmar/workflows/$OCMAR_SLUG/state.json"
  if [[ ! -f "$OCMAR_STATE" ]]; then
    echo "❌ ocmar state 不存在: $OCMAR_STATE"
    exit 1
  fi
  # 检查 release_ready 字段
  RELEASE_READY=$(python3 -c "
import json, sys
with open('$OCMAR_STATE') as f:
    s = json.load(f)
rr = s.get('release_ready')
print(rr if rr else '')
" 2>/dev/null)
  if [[ -z "$RELEASE_READY" ]]; then
    echo "❌ ocmar workflow '$OCMAR_SLUG' 尚未 mark-release-ready"
    echo "   先完成: ocmar-state <dir> mark-release-ready --report <report-path> --owner <sid>"
    exit 1
  fi
  echo "✅ ocmar 交付闭环: $OCMAR_SLUG release_ready=$RELEASE_READY"
fi

# --- 2. 质量门禁 ---
echo "==> 质量门禁：编译 + 单测 + lint"
./scripts/check.sh --lint

# --- 3. 由最新 tag 推算下一版本 ---
# 取最高 semver release tag（严格 vX.Y.Z，排除 -ci-smoke/-dirty 等非 release tag）
PREV_TAG=$(git tag --list 'v[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V -r | head -1)
if [[ -z "$PREV_TAG" ]]; then
  echo "❌ 仓库无严格 semver tag（vX.Y.Z），无法推断基线版本；请先手动 git tag v0.1.0"
  exit 1
fi
BASE="${PREV_TAG#v}"
IFS='.' read -r MAJOR MINOR PATCH <<<"$BASE"
# Ignore pre-release suffixes on CI/smoke tags (e.g. 0-ci-smoke → 0)
# before arithmetic expansion under `set -u`.
MAJOR="${MAJOR%%-*}"
MINOR="${MINOR%%-*}"
PATCH="${PATCH%%-*}"
case "$TYPE" in
  patch) PATCH=$((PATCH+1)) ;;
  minor) MINOR=$((MINOR+1)); PATCH=0 ;;
  major) MAJOR=$((MAJOR+1)); MINOR=0; PATCH=0 ;;
esac
VERSION="$MAJOR.$MINOR.$PATCH"
TAG="v$VERSION"
# 单调性校验：候选版本必须 > 最高已有 tag（防回退如 v0.14.1 → v0.0.1）
HIGHEST_TAG="$PREV_TAG"
HIGHEST_BASE="${HIGHEST_TAG#v}"
HIGHEST_MAJOR=""; HIGHEST_MINOR=""; HIGHEST_PATCH=""
IFS='.' read -r HIGHEST_MAJOR HIGHEST_MINOR HIGHEST_PATCH <<<"$HIGHEST_BASE"
if (( MAJOR < HIGHEST_MAJOR || (MAJOR == HIGHEST_MAJOR && (MINOR < HIGHEST_MINOR || (MINOR == HIGHEST_MINOR && PATCH <= HIGHEST_PATCH))) )); then
  echo "❌ 候选版本 $TAG <= 最高已有 tag $HIGHEST_TAG（版本回退）"
  echo "   当前最高: $HIGHEST_TAG → 候选: $TAG"
  echo "   请确认 bump 类型（patch/minor/major）或检查是否误选了低版本 tag 作为基线"
  exit 1
fi
# 短 hash = release 构建嵌入 versionName + APK 文件名的 commit 锚点（与 gradle
# archiveReleaseApk 一致：二者都取 HEAD 的 git rev-parse --short）。
SHORT=$(git rev-parse --short HEAD)
FULL_VERSION="$VERSION-$SHORT$DIRTY"
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

# --- 7. 对外发布（push tag → CI/CD 自动构建签名包 + 上传）---
echo ""
echo "════════════════════════════════════════════════════════════"
echo "✅ 仓库发版准备完成: $VERSION (tag $TAG)"
echo "  APK:   $APK_DST（本机归档，仅用于模拟器冒烟）"
echo "  Notes: $NOTE_FILE"
echo ""
echo "push tag 后 CI/CD 自动完成（.gitea/workflows/release.yml）:"
echo "  git push origin main && git push origin $TAG"
echo ""
echo "  CI/CD 自动: 签名构建(release keystore from Gitea Secrets)"
echo "            → 上传 APK + SHA256SUMS"
echo "            → 更新 release notes → 企微通知"
echo ""
echo "  ⚠️  不要手动上传本机 APK — CI/CD 已自动上传签名包，"
echo "      手动上传的本地 APK 签名不同，混装会签名冲突。"
echo "════════════════════════════════════════════════════════════"
