#!/usr/bin/env bash
# scripts/ci/local-check.sh — 本机快速校验(Primary 开发期,几十秒级)。
# 比通用的 scripts/check.sh 更聚焦:只编译受影响 Kotlin + JVM 单测,不跑 lint/assemble。
# 本机/远端/Release 三档边界见 docs/specs/gitea-cicd.md。
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
source ./scripts/env.sh
GRADLE="./gradlew"; [[ "${OC_GRADLE_DAEMON:-0}" == "1" ]] || GRADLE="./gradlew --no-daemon"
echo "==> local-check: compileDebugKotlin + testDebugUnitTest"
$GRADLE compileDebugKotlin testDebugUnitTest
echo "✅ local-check 通过"
