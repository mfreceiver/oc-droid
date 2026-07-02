#!/usr/bin/env bash
# scripts/check.sh — 改动校验三合一（替代 LSP 自检）
# 详见 AGENTS.md「改动校验」与 .opencode/policies/build-signing.md。
#
# 用法:
#   ./scripts/check.sh           # 编译 + 单测（默认，每次改动必跑）
#   ./scripts/check.sh --lint    # 额外跑 lintDebug
#   ./scripts/check.sh --full    # 编译 + 单测 + 覆盖率 + lint
#
# 本工作区 opencode 服务端已关闭 LSP，编辑后必须主动跑此脚本确认无编译/测试错误。

set -euo pipefail
source "$(dirname "$0")/env.sh"

MODE="${1:-default}"
GRADLE="./gradlew --no-daemon"

echo "==> compileDebugKotlin"
$GRADLE compileDebugKotlin

echo "==> testDebugUnitTest"
$GRADLE testDebugUnitTest

case "$MODE" in
  --lint)
    echo "==> lintDebug"
    $GRADLE lintDebug
    ;;
  --full)
    echo "==> lintDebug"
    $GRADLE lintDebug
    echo "==> koverHtmlReport → app/build/reports/kover/html/index.html"
    $GRADLE koverHtmlReport
    ;;
  default|"")
    ;;
  *)
    echo "用法: check.sh [--lint|--full]"; exit 1 ;;
esac

echo "✅ check.sh 通过"
