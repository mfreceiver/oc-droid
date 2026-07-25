#!/usr/bin/env bash
# scripts/ci/release-check.sh — Release 构建校验(.gitea/workflows/release.yml 调用,CI 签名)。
# 干净 CI 容器:clean + lintRelease + testReleaseUnitTest + bundleRelease + assembleRelease + archive。
# 签名:workflow 从 Gitea Secrets 解出 keystore → 写 local.properties → gradle signingConfigs.release 自动用。
# 版本:go-around 模式 git 派生;tag 触发,传 -PreleaseVersion=<tag去v>。
# 产物:artifacts/(APK + AAB + mapping.txt + SHA256SUMS)。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
# tag 触发时 GITHUB_REF=refs/tags/vX.Y.Z;workflow_dispatch 兜底取最近 tag。
TAG="${GITHUB_REF##*/}"
[[ "$TAG" == v* ]] || TAG="$(git describe --tags --abbrev=0 2>/dev/null || echo v0.0.0)"
VERSION="${TAG#v}"
SHORT="$(git rev-parse --short HEAD)"
echo "==> release-check: tag=$TAG version=$VERSION-$SHORT"
./gradlew --no-daemon --stacktrace clean lintRelease testReleaseUnitTest bundleRelease assembleRelease archiveReleaseApk -PreleaseVersion="$VERSION"
echo "==> 归档产物"
mkdir -p artifacts
cp "APK/oc-droid-$VERSION-$SHORT.apk" artifacts/
cp app/build/outputs/bundle/release/app-release.aab "artifacts/oc-droid-$VERSION-$SHORT.aab"
cp app/build/outputs/mapping/release/mapping.txt artifacts/mapping.txt 2>/dev/null || true
( cd artifacts && sha256sum * > SHA256SUMS )
echo "✅ release-check 通过 → artifacts/ 含 APK + AAB + mapping + SHA256SUMS"
