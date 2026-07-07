#!/usr/bin/env bash
# scripts/upload-release.sh — 用 curl Gitea REST API 上传 release APK + notes。
# 替代 tea CLI（tea 语法/超时问题）。详见 docs/build-apk.md §发版上传。
#
# 用法: ./scripts/upload-release.sh <versionName>
# 前提:
#   - tag v<versionName> 已 push（Gitea 收到 tag 后自动建 release）
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

# --- 1. 查找 release（Gitea 收到 tag push 后通常自动建）---
echo "==> 查找 Gitea release $TAG ..."
RID=$(curl -sf -H "$AUTH" "$API?name=$VERSION" 2>/dev/null \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["id"] if d else "")' 2>/dev/null || true)
if [[ -z "$RID" ]]; then
  echo "==> Gitea 未自动建 release，创建中 ..."
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
