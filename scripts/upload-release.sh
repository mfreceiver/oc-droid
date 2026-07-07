#!/usr/bin/env bash
# scripts/upload-release.sh — 用 curl Gitea REST API 上传 release APK + notes。
# 替代 tea CLI（tea 语法/超时问题）。详见 docs/build-apk.md §发版上传。
#
# 用法: ./scripts/upload-release.sh <versionName>
# 前提:
#   - tag v<versionName> 已 push（本脚本按 tag 查 release；找不到则创建）
#   - tea config (~/.config/tea/config.yml) 有 Gitea token；或导出 GITEA_TOKEN
#   - APK/oc-droid-<versionName>.apk + APK/oc-droid-<versionName>.md 已由 release.sh 生成
set -euo pipefail

VERSION="${1:?用法: upload-release.sh <versionName>}"
TAG="v$VERSION"
REPO="mfreceiver/oc-droid"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"
APK_FILE="APK/oc-droid-$VERSION.apk"
NOTE_FILE="APK/oc-droid-$VERSION.md"

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

[[ -f "$APK_FILE" ]] || { echo "❌ APK $APK_FILE 不存在（先跑 release.sh）"; exit 1; }
[[ -f "$NOTE_FILE" ]] || { echo "❌ Notes $NOTE_FILE 不存在（先跑 release.sh）"; exit 1; }

# --- token：优先环境变量，否则从 tea config 读 ---
if [[ -n "${GITEA_TOKEN:-}" ]]; then
  : # 用环境变量
else
  TEA_CONFIG="${TEA_CONFIG:-$HOME/.config/tea/config.yml}"
  [[ -f "$TEA_CONFIG" ]] || { echo "❌ 无 GITEA_TOKEN 且 tea config 不存在（$TEA_CONFIG）"; exit 1; }
  GITEA_TOKEN="$(grep 'token:' "$TEA_CONFIG" | head -1 | awk '{print $2}')"
fi
[[ -n "${GITEA_TOKEN:-}" ]] || { echo "❌ Gitea token 为空"; exit 1; }

API="$GITEA_URL/api/v1/repos/$REPO/releases"
AUTH="Authorization: token $GITEA_TOKEN"

# --- 1. 按 tag 精确查找 release ---
# §fix(release-target): Gitea 的 GET /releases 列表端点不支持 ?name= 过滤，
#   旧行为会忽略该参数并取 d[0]，把 APK 挂到错误的 release（如把 0.5.1 挂到 0.5.0）。
#   改用 /releases/tags/{tag} 端点精确按 tag 查；找不到则创建（tag 必须已 push）。
echo "==> 查找 Gitea release by tag $TAG ..."
RID=$(curl -sf -H "$AUTH" "$GITEA_URL/api/v1/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null || true)
if [[ -z "$RID" ]]; then
  echo "==> 未找到 $TAG 对应的 release，创建中（tag 必须已 push）..."
  RID=$(curl -sf -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"draft\":false,\"prerelease\":false}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
fi
echo "   release id: $RID"

# --- 2. 上传 APK 附件 ---
echo "==> 上传 APK $APK_FILE ..."
curl -sf -X POST "$API/$RID/assets?name=$(basename "$APK_FILE")" \
  -H "$AUTH" -H "Content-Type: application/octet-stream" \
  --data-binary "@$APK_FILE" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("   uploaded:",d["name"],"size:",d["size"])'

# --- 3. 更新 release notes（changelog）---
echo "==> 更新 release notes ..."
BODY=$(python3 -c 'import json;print(json.dumps({"body":open("'"$NOTE_FILE"'").read()}))')
curl -sf -X PATCH "$API/$RID" -H "$AUTH" -H "Content-Type: application/json" -d "$BODY" >/dev/null

echo ""
echo "✅ 完成: $TAG（APK + notes 已上传）"
echo "   $GITEA_URL/$REPO/releases/tag/$TAG"
