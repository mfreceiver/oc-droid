#!/usr/bin/env bash
# scripts/check.sh — 改动校验三合一（编译 + detekt + 单测；LSP 不跑单测，故仍须本脚本）
# 详见 AGENTS.md「改动校验」与 .opencode/policies/build-signing.md。
#
# 用法:
#   ./scripts/check.sh           # 编译 + 单测（默认，每次改动必跑）
#   ./scripts/check.sh --lint    # 额外跑 lintDebug
#   ./scripts/check.sh --full    # 编译 + 单测 + 覆盖率 + lint
#
# 本机 opencode 已启用 LSP（编辑后编译/类型诊断秒级回流）；但 LSP 不跑单测，改 Kotlin/资源后仍须跑此脚本（编译 + detekt + 单测）确认。

set -euo pipefail
source "$(dirname "$0")/env.sh"

MODE="${1:-default}"
GRADLE="./gradlew"; [[ "${OC_GRADLE_DAEMON:-0}" == "1" ]] || GRADLE="./gradlew --no-daemon"

echo "==> compileDebugKotlin"
$GRADLE compileDebugKotlin

echo "==> detekt (ocdroid sole-writer encapsulation gate)"
$GRADLE :app:detekt

echo "==> testDebugUnitTest"
$GRADLE testDebugUnitTest

case "$MODE" in
  --lint)
    echo "==> lintDebug"
    $GRADLE lintDebug
    echo "==> detekt (ocdroid sole-writer encapsulation gate)"
    $GRADLE :app:detekt
    ;;
  --full)
    echo "==> lintDebug"
    $GRADLE lintDebug
    echo "==> detekt (ocdroid sole-writer encapsulation gate)"
    $GRADLE :app:detekt
    echo "==> koverVerify（覆盖率门控）"
    $GRADLE koverVerify
    echo "==> koverHtmlReport → app/build/reports/kover/html/index.html"
    $GRADLE koverHtmlReport
    ;;
  default|"")
    ;;
  *)
    echo "用法: check.sh [--lint|--full]"; exit 1 ;;
esac

echo "✅ check.sh 通过"
