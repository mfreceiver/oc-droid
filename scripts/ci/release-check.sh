#!/usr/bin/env bash
# scripts/ci/release-check.sh — Release 构建校验(.gitea/workflows/release.yml 调用,CI 签名)。
# 干净 CI 容器:clean + lintRelease + testDebugUnitTest + assembleRelease + archive。
# 注:用 testDebugUnitTest 而非 testReleaseUnitTest —— AGP 9.x 默认不为 release 变体生成单测 task
# (项目未显式开启 android.buildTypes.release.unitTests);test 代码与变体无关,debug 足够 CI 验证。
# 签名:workflow 从 Gitea Secrets 解出 keystore → 写 local.properties → gradle signingConfigs.release 自动用。
# 版本:go-around 模式 git 派生;tag 触发,传 -PreleaseVersion=<tag去v>。
# 产物:artifacts/(APK + SHA256SUMS)。不生成 AAB —— 项目不上架 Play Store。
# mapping.txt:R8 混淆仍会在 app/build/outputs/mapping/release/ 生成(供本机反混淆崩栈),
#              但不再拷入 artifacts/、不作为 release 资产上传(用户不需要)。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
# tag 触发时 GITHUB_REF=refs/tags/vX.Y.Z;workflow_dispatch 兜底取最近 tag。
TAG="${GITHUB_REF##*/}"
[[ "$TAG" == v* ]] || TAG="$(git describe --tags --abbrev=0 2>/dev/null || echo v0.0.0)"
VERSION="${TAG#v}"
SHORT="$(git rev-parse --short HEAD)"
echo "==> release-check: tag=$TAG version=$VERSION-$SHORT"
./gradlew --no-daemon --stacktrace clean :app:detekt lintRelease testDebugUnitTest assembleRelease archiveReleaseApk -PreleaseVersion="$VERSION"
echo "==> 归档产物"
mkdir -p artifacts
cp "APK/oc-droid-$VERSION-$SHORT.apk" artifacts/
( cd artifacts && sha256sum * > SHA256SUMS )
echo "✅ release-check 通过 → artifacts/ 含 APK + SHA256SUMS"
