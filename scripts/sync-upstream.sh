#!/usr/bin/env bash
# Sync this fork with upstream (grapeot/opencode_android_client).
#
# Usage:
#   bash scripts/sync-upstream.sh             # 用 merge 合并上游 master
#   bash scripts/sync-upstream.sh --rebase    # 改用 rebase（会强推 origin）
#
# 前提：仓库已配置 upstream remote（见 FORK_SYNC.md）。
set -euo pipefail

MODE="merge"
if [[ "${1:-}" == "--rebase" ]]; then MODE="rebase"; fi

BRANCH="${BASE_BRANCH:-master}"
REMOTE_UPSTREAM="upstream"
REMOTE_ORIGIN="origin"

# 确保 upstream remote 存在
if ! git remote get-url "$REMOTE_UPSTREAM" >/dev/null 2>&1; then
  echo "✗ 缺少 upstream remote，正在添加..."
  git remote add "$REMOTE_UPSTREAM" https://github.com/grapeot/opencode_android_client.git
fi

echo "==> [1/5] fetch upstream（含 tag）"
git fetch "$REMOTE_UPSTREAM" --tags

echo "==> [2/5] checkout $BRANCH"
git checkout "$BRANCH"

echo "==> [3/5] 同步 $BRANCH 与 $REMOTE_UPSTREAM/$BRANCH（$MODE）"
if [[ "$MODE" == "rebase" ]]; then
  git rebase "$REMOTE_UPSTREAM/$BRANCH"
else
  git merge "$REMOTE_UPSTREAM/$BRANCH"
fi

echo "==> [4/5] 推送到 $REMOTE_ORIGIN（含 tag）"
if [[ "$MODE" == "rebase" ]]; then
  git push "$REMOTE_ORIGIN" "$BRANCH" --force-with-lease
else
  git push "$REMOTE_ORIGIN" "$BRANCH"
fi
git push "$REMOTE_ORIGIN" --tags

echo "==> [5/5] 完成校验"
echo "--- 本地领先上游的提交（你的改动）---"
git log "$REMOTE_UPSTREAM/$BRANCH..$BRANCH" --oneline || true
echo "--- 上游领先本地、尚未合并的提交 ---"
git log "$BRANCH..$REMOTE_UPSTREAM/$BRANCH" --oneline || true
echo "✓ 同步完成"
