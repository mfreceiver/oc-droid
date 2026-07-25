#!/usr/bin/env bash
# scripts/gitea/query-run.sh — 查某 commit/分支最新 CI run 状态(供 ci-failure-analyzer 解析)。
# 用法: query-run.sh <commit_sha_or_branch> [limit]
# 输出:JSON(workflow_runs 列表,含 status/conclusion/head_sha)。
set -euo pipefail
SHA="${1:?用法: query-run.sh <commit_sha_or_branch> [limit]}"
LIM="${2:-5}"
REPO="mfreceiver/oc-droid"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"
: "${GITEA_TOKEN:?需要 GITEA_TOKEN}"
curl -sf -H "Authorization: token $GITEA_TOKEN" \
  "$GITEA_URL/api/v1/repos/$REPO/actions/runs?sha=$SHA&limit=$LIM"
