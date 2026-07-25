#!/usr/bin/env bash
# scripts/gitea/claim-issue.sh — Primary 领取 Issue:agent:ready→agent:claimed + 回写分支/基线。
# 用法: claim-issue.sh <issue_number> <branch> <base_commit>
# 幂等:保留其它标签,只移除 ready、补 claimed;重复调用只刷新评论。
set -euo pipefail
NUM="${1:?用法: claim-issue.sh <issue> <branch> <base_commit>}"
BRANCH="${2:?}"
BASE="${3:?}"
REPO="mfreceiver/oc-droid"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"
: "${GITEA_TOKEN:?需要 GITEA_TOKEN(写入 ~/.config/opencode/gitea.env,权限 600)}"
API="$GITEA_URL/api/v1/repos/$REPO/issues/$NUM"
AUTH="Authorization: token $GITEA_TOKEN"

# 标签切换:读当前 → 去 ready → 加 claimed(保留 bug/feature 等其它标签)
LABELS_JSON=$(curl -sf -H "$AUTH" "$API" | python3 -c '
import sys,json
d=json.load(sys.stdin)
ls=[l["name"] for l in d.get("labels",[]) if l["name"]!="agent:ready"]
if "agent:claimed" not in ls: ls.append("agent:claimed")
print(json.dumps(ls))')
curl -sf -X PATCH "$API" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"labels\":$LABELS_JSON}" >/dev/null

# 回写状态评论
BODY="🤖 Primary Agent 已领取。
- 分支: \`$BRANCH\`
- 基线 commit: \`$BASE\`
- 状态: exploring → developing"
PAYLOAD=$(python3 -c 'import json,sys;print(json.dumps({"body":sys.argv[1]}))' "$BODY")
curl -sf -X POST "$API/comments" -H "$AUTH" -H "Content-Type: application/json" -d "$PAYLOAD" >/dev/null
echo "✅ issue #$NUM claimed (branch=$BRANCH)"
