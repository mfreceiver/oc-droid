#!/usr/bin/env bash
# scripts/ci/remote-check.sh — 远端分支校验(.gitea/workflows/branch-check.yml 调用)。
# 在干净 CI 容器中跑:lintDebug + testDebugUnitTest + assembleDebug。
# JDK/SDK 环境由 Gitea Actions workflow 注入,不 source 本机 env.sh。
# 产物归档到 artifacts/(Debug APK)供 workflow 上传。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
echo "==> remote-check: detekt + lintDebug + testDebugUnitTest + assembleDebug"
./gradlew --no-daemon --stacktrace :app:detekt lintDebug testDebugUnitTest assembleDebug
echo "==> 归档 Debug APK"
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk "artifacts/oc-droid-debug-$(git rev-parse --short HEAD).apk"
echo "✅ remote-check 通过"
