#!/usr/bin/env bash
# scripts/gitea/publish-release.sh — CI 内把 release-check 产物上传到 Gitea Release。
# 用法: publish-release.sh <tag>     (tag 形如 v1.2.3;由 release.yml 调用)
# 环境:GITEA_TOKEN(release 读写权限;release.yml 注入 auto GITHUB_TOKEN)、GITEA_URL。
# 产物目录:artifacts/(APK + AAB + mapping.txt + SHA256SUMS)。
# 注:本机发版仍走 scripts/upload-release.sh(读 tea config);本脚本是 CI 侧对应物。
set -euo pipefail
TAG="${1:?用法: publish-release.sh <tag>}"
REPO="mfreceiver/oc-droid"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"
[[ -n "${GITEA_TOKEN:-}" ]] || { echo "❌ GITEA_TOKEN 未设置"; exit 1; }
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
[[ -d artifacts && "$(ls -A artifacts 2>/dev/null)" ]] || { echo "❌ artifacts/ 为空(先跑 release-check.sh)"; exit 1; }

API="$GITEA_URL/api/v1/repos/$REPO/releases"
AUTH="Authorization: token $GITEA_TOKEN"

# 按 tag 查 release(找不到则建;tag 必须已由 release 事件存在)
RID=$(curl -sf -H "$AUTH" "$API/tags/$TAG" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null || true)
if [[ -z "$RID" ]]; then
  RID=$(curl -sf -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" \
        -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"draft\":false,\"prerelease\":false}" \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
fi
echo "==> release id: $RID"

for f in artifacts/*; do
  [[ -f "$f" ]] || continue
  echo "==> 上传 $(basename "$f")"
  curl -sf -X POST "$API/$RID/assets?name=$(basename "$f")" \
    -H "$AUTH" -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" >/dev/null
done
echo "✅ Release $TAG 产物上传完成 → $GITEA_URL/$REPO/releases/tag/$TAG"
