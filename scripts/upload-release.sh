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

VERSION="${1:?用法: upload-release.sh <versionName>}"  # 基础版本，如 0.8.2（不含 hash）
TAG="v$VERSION"
REPO="mfreceiver/oc-droid"
GITEA_URL="${GITEA_URL:-https://git.vectory.cn:18443}"

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# APK 文件名带 commit 短 hash（与 release.sh 一致）：oc-droid-<ver>-<shorthash>.apk。
# 从 tag 反解 commit 短 hash（annotated tag 用 ^{commit} 解引用到提交）。
SHORT=$(git rev-parse --short "$TAG^{commit}" 2>/dev/null || true)
[[ -n "$SHORT" ]] || { echo "❌ 无法解析 $TAG 的 commit（tag 未创建或未 fetch？）"; exit 1; }
FULL_VERSION="$VERSION-$SHORT"
APK_FILE="APK/oc-droid-$FULL_VERSION.apk"
NOTE_FILE="APK/oc-droid-$FULL_VERSION.md"

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

# §fix(release-target): Gitea 的 GET /releases 列表端点不支持 ?name= 过滤，
#   旧行为会忽略该参数并取 d[0]，把 APK 挂到错误的 release（如把 0.5.1 挂到 0.5.0）。
#   改用 /releases/tags/{tag} 端点精确按 tag 查；找不到则创建（tag 必须已 push）。
#
# 辅助：按 tag 查 release id（GET by-tag 端点可靠；找不到返回空）。
release_id_by_tag() {
  curl -sf -H "$AUTH" "$GITEA_URL/api/v1/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null || true
}
# 辅助：取 release 的【真实存储】created_at 年份。
# §fix(gitea-epoch-created_at): POST 响应里的 created_at 恒为 epoch（Gitea 序列化
#   bug，不可信）；必须用 GET by-tag 读 DB 真实值。
release_created_year() {
  curl -sf -H "$AUTH" "$GITEA_URL/api/v1/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
    | python3 -c 'import sys,json;print((json.load(sys.stdin).get("created_at") or "")[:4])' 2>/dev/null || true
}
# 辅助：POST 创建 release，输出新 id。
create_release() {
  curl -sf -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"draft\":false,\"prerelease\":false}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'
}

# --- 1. 按 tag 精确查找 release ---
echo "==> 查找 Gitea release by tag $TAG ..."
RID=$(release_id_by_tag)
if [[ -z "$RID" ]]; then
  echo "==> 未找到 $TAG 对应的 release，创建中（tag 必须已 push）..."
  RID=$(create_release)
fi
echo "   release id: $RID"

# §fix(gitea-epoch-created_at): Gitea 有个 bug —— tag 刚 push、还没被索引时建
#   release，created_at 会被写成 1970-01-01（epoch），release 在页面上按
#   created_at 倒序时沉到最底（v0.11.1 曾因此排到 v0.11.0 下方）。POST/PATCH 都
#   无法改 created_at（字段被服务端忽略）。唯一可靠修法：删 release + 删远端
#   tag + 重新 push（强制 Gitea 重新索引 tag 的 tagger date）+ 重建 release，
#   新 release 的 created_at 会取 tagger date。best-effort：失败只告警不中断。
YEAR=$(release_created_year)
if [[ "$YEAR" == "1970" ]]; then
  echo "⚠️  release created_at 卡在 epoch（Gitea 未及时索引 tag），执行自愈（删 release + 重新索引 tag + 重建）..."
  if curl -sf -X DELETE "$API/$RID" -H "$AUTH" >/dev/null; then
    TAG_SHA=$(git rev-parse "$TAG" 2>/dev/null || true)
    if [[ -n "$TAG_SHA" ]] && git push origin --delete "$TAG" >/dev/null 2>&1; then
      sleep 2
      git push origin "$TAG" >/dev/null 2>&1 || true
      # 轮询直到 Gitea 能解析该 tag 的 tagger date（索引完成）
      for _ in $(seq 1 10); do
        TD=$(curl -sf -H "$AUTH" "$GITEA_URL/api/v1/repos/$REPO/git/tags/$TAG_SHA" 2>/dev/null \
          | python3 -c 'import sys,json;print(json.load(sys.stdin)["tagger"]["date"])' 2>/dev/null || true)
        [[ -n "$TD" ]] && break
        sleep 2
      done
      RID=$(create_release)
      NEW_YEAR=$(release_created_year)
      echo "   自愈完成：新 release id=$RID created_at 年份=$NEW_YEAR（tagger date=$TD）"
    else
      echo "   ⚠️ 删/重 push tag 失败，跳过自愈（手动处理：Gitea UI 重建 release）"
      RID=$(release_id_by_tag || true)
    fi
  else
    echo "   ⚠️ 删旧 release 失败，跳过自愈"
  fi
fi

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
