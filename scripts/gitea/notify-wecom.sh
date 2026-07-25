#!/usr/bin/env bash
# scripts/gitea/notify-wecom.sh — 发版结果通知企业微信群机器人（markdown）。
# 由 release.yml 末尾以 if: always() 调用；也可本机手动测试。
#
# 用法:
#   notify-wecom.sh <webhook_url> <outcome> <tag> [release_url] [actions_url]
#   outcome: success | failure | cancelled | test
# best-effort: WeCom 不可达时只警告，不影响发版结果（release 产物已上传）。
set -euo pipefail
WEBHOOK="${1:?用法: notify-wecom.sh <webhook_url> <outcome> <tag> [release_url] [actions_url]}"
OUTCOME="${2:?}"
TAG="${3:?}"
REL_URL="${4:-}"
ACT_URL="${5:-}"

# JSON 字符串转义（POSIX awk，CI 镜像无 python3/jq）：转义 \ 与 "，行间插入字面 \n。
json_escape() {
  awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if(NR>1)printf "\\n"; printf "%s",$0}'
}

case "$OUTCOME" in
  success)
    TITLE="## ✅ ocdroid $TAG 发布成功"
    if [[ -n "$REL_URL" ]]; then
      BODY="> 下载 / Changelog: [$TAG]($REL_URL)"
    else
      BODY="> Release: $TAG"
    fi
    ;;
  test)
    TITLE="## 🔧 ocdroid 通知配置测试"
    BODY="> WeCom webhook 已接入 release.yml，发版完成/失败将自动通知本群。"
    ;;
  *)
    TITLE="## ❌ ocdroid $TAG 发版失败（$OUTCOME）"
    if [[ -n "$ACT_URL" ]]; then
      BODY="> 查看日志: [Actions]($ACT_URL)"
    else
      BODY="> Release: $TAG"
    fi
    ;;
esac

CONTENT="$(printf '%s\n\n%s\n' "$TITLE" "$BODY" | json_escape)"
PAYLOAD="{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"$CONTENT\"}}"

curl -sf -X POST "$WEBHOOK" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD" \
  && echo "✅ WeCom 通知已发送（outcome=$OUTCOME）" \
  || echo "⚠️ WeCom 通知发送失败（不影响发版结果）"
