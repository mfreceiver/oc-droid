#!/usr/bin/env bash
# scripts/gitea/publish-release.sh — CI 内把 release-check 产物上传到 Gitea Release。
# 用法: publish-release.sh <tag>     (tag 形如 v1.2.3;由 release.yml 调用)
# 环境:GITEA_TOKEN(release 读写权限;release.yml 注入 auto GITHUB_TOKEN)、GITEA_URL。
# 产物目录:artifacts/(APK + mapping.txt + SHA256SUMS)。不生成 AAB —— 项目不上架 Play Store。
# Changelog:增量=上一 tag 到本 tag 之间的 commit(--no-merges);首版=本 tag 最近 50 条。
# 注:CI/CD(release.yml push tag 触发)是发版的主路径——本脚本在 runner 内
# 用 Gitea Secrets 注入的正式 keystore 构建签名包并上传。无本机 fallback;
# 正常流程不手动上传(本机 APK 签名不同,混装会冲突)。
set -euo pipefail
TAG="${1:?用法: publish-release.sh <tag>}"
REPO="mfreceiver/oc-droid"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"
[[ "$TAG" == v* ]] || { echo "❌ TAG='$TAG' 不是 v* 格式;release.yml 应由 tag push 触发,workflow_dispatch 走错路径"; exit 1; }
[[ -n "${GITEA_TOKEN:-}" ]] || { echo "❌ GITEA_TOKEN 未设置"; exit 1; }
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
[[ -d artifacts && "$(ls -A artifacts 2>/dev/null)" ]] || { echo "❌ artifacts/ 为空(先跑 release-check.sh)"; exit 1; }

API="$GITEA_URL/api/v1/repos/$REPO/releases"
AUTH="Authorization: token $GITEA_TOKEN"

# Changelog 生成
# 取 $TAG 父提交上最近的 tag(= 上一版);找不到说明是首版
PREV_TAG=$(git describe --tags --abbrev=0 "${TAG}^" 2>/dev/null || true)
if [[ -n "$PREV_TAG" ]]; then
  RANGE="${PREV_TAG}..${TAG}"
  TITLE="## Changes since ${PREV_TAG}"
else
  RANGE="${TAG}"
  TITLE="## Release snapshot(首次打 tag,最近 50 条 commit)"
fi
# head -50 防止超长 changelog 撑爆 API body;--max-count 避免 pipefail 下 SIGPIPE 吞 git 错误
CHANGELOG=$(git log "$RANGE" --pretty=format:"- %s" --no-merges --max-count=50 2>/dev/null || true)
[[ -z "$CHANGELOG" ]] && CHANGELOG="_(no commits in range)_"
BODY="${TITLE}

${CHANGELOG}"
echo "==> changelog 范围: $RANGE ($(printf '%s\n' "$CHANGELOG" | wc -l) 条 commit)"

# python3 构造 JSON payload(自动转义,避免 shell 拼接被 commit message 中的引号/反斜杠搞坏)
PAYLOAD=$(python3 -c 'import json,sys
print(json.dumps({"tag_name": sys.argv[1], "name": sys.argv[1], "body": sys.argv[2],
                  "draft": False, "prerelease": False}))' "$TAG" "$BODY")

# 按 tag 查 release(找不到则建;tag 必须已由 release 事件存在)
RID=$(curl -sf -H "$AUTH" "$API/tags/$TAG" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null || true)
if [[ -z "$RID" ]]; then
  RID=$(curl -sf -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" \
        -d "$PAYLOAD" \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
else
  # 已存在(可能是 workflow_dispatch 重跑):PATCH 更新 body,保持 idempotent
  curl -sf -X PATCH "$API/$RID" -H "$AUTH" -H "Content-Type: application/json" -d "$PAYLOAD" >/dev/null \
    && echo "==> 已更新 release #$RID 的 body(changelog)"
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
