# Gitea LAN bypass for CI job containers

> 自包含操作手册：拷走这一份 + 配 1 个站点 Variable，任何 Gitea + act_runner 拓扑都能套用。

## TL;DR

**站点级 Variable `MY_GITEA_LAN_URL` 持 LAN URL，workflow 读它（空时回退公网 URL）**——job 容器走 LAN 直连绕过公网 TLS / 代理抖动，人点链接仍走公网。

---

## 1. 适用场景

- act_runner 跑在 Unraid / 内网，job 容器与 Gitea **同 LAN**
- 公网链路（`https://git.example.com:18443`）在 job 容器内不稳定：`gnutls_handshake() failed: The TLS connection was non-properly terminated`
- Gitea 有 LAN 监听端口（HTTP，无 TLS），如 `192.168.3.252:11330`

## 2. 为什么用站点 Variable 而非硬编码

| 维度 | 硬编码 `192.168.3.252:11330` | 站点 Variable `MY_GITEA_LAN_URL` |
|---|---|---|
| 换 IP / 迁 Gitea | 改 N 处 workflow + push | 改 1 处 Variable |
| 多 repo 复用 | 每 repo 单独改 | 站点级，全实例继承 |
| 离开 LAN（外出/换网） | workflow 仍硬指 LAN → 失败 | 删 Variable → workflow 自动回退公网 |
| 安全审计 | IP 进 git 历史 | IP 只在站点设置里 |

**命名约束**：Gitea 保留 `GITEA_*` / `GITHUB_*` 前缀给系统注入项（如自动 `secrets.GITHUB_TOKEN`）。自定义 Variable **必须避开**这两个前缀，否则可能被覆盖或注入失败。本方案叫 `MY_GITEA_LAN_URL`。

## 3. 前置 4 条（缺一不可）

1. **Gitea LAN 直监听 HTTP**——Unraid Gitea 容器模板里 `Target=3000 → HostPort=11330`，WebUI 走 HTTP。如果 Gitea 强制 HTTPS-only，此方案不适用。
2. **runner 可达 LAN**——act_runner 容器与 Gitea 容器在同 Docker network，或同 Unraid host（bridge 互通）。
3. **token 跨 hostname 有效**——`${{ github.token }}` 是 Gitea 实例签发，对实例的所有 hostname（公网 + LAN）都有效。**只需确认两边是同一 Gitea 实例**（同 DB、同 user 表）。
4. **ROOT_URL 不影响 API 路由**——Gitea 的 `ROOT_URL` 配置只决定**响应里返回的下载/克隆 URL**（让人点的链接），不影响**实际 API 路由**。所以即使 `ROOT_URL=https://git.example.com:18443`，用 `http://192.168.3.252:11330/api/v1/...` 调 API 也能正常工作。**副作用**：API 响应里的 `clone_url` 等字段仍指公网——只影响读取这些字段的客户端，不影响直接用 URL 调 API 的脚本。

## 4. 配置 3 步

### 步骤 ① 建站点 Variable

Gitea Web UI：**站点管理 → 管理设置 → 工作流 → 变量 → 添加变量**

| 字段 | 值 |
|---|---|
| Name | `MY_GITEA_LAN_URL` |
| Value | `http://192.168.3.252:11330`（替换为你的 Gitea LAN 地址） |

⚠️ 不要加 trailing slash；不要带 path。纯 `scheme://host:port`。

### 步骤 ② workflow 改 Checkout + 上传（同一套 URL）

**Checkout step**（关键：scheme/host 拆拼，避开 `://` 漏冒号 bug）：

```yaml
- name: Checkout(优先 LAN,空时回退公网)
  env:
    TOKEN: ${{ github.token }}
    GITEA_LAN_URL: ${{ vars.MY_GITEA_LAN_URL }}   # 站点 Variable;空则用公网
  run: |
    set -e
    GITEA_URL="${GITEA_LAN_URL:-https://git.example.com:18443}"   # 回退公网
    # credentials 文件格式: scheme://user:pass@host:port
    # 用 shell 参数扩展拆 scheme / host,避免字符串拼接漏冒号(本项目第一版就漏过,
    # 生成 'http//x-access-token:...' 导致 git 不认 scheme)
    SCHEME="${GITEA_URL%%://*}"
    HOST="${GITEA_URL#*://}"
    printf '%s://x-access-token:%s@%s\n' "$SCHEME" "$TOKEN" "$HOST" > ~/.git-credentials
    chmod 600 ~/.git-credentials
    git config --global credential.helper store
    git config --global advice.detachedHead false
    git clone "$GITEA_URL/<owner>/<repo>.git" .
    git fetch --tags --force
    git checkout -q "${{ github.sha }}"
```

**上传产物 step**（`publish-release.sh` 已支持 `GITEA_URL` env 注入）：

```yaml
- name: 上传产物到 Gitea Release
  env:
    GITEA_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    GITEA_URL: ${{ vars.MY_GITEA_LAN_URL }}   # 空 → publish-release.sh 内置默认(公网)
    TAG: ${{ github.ref_name }}
  run: ./scripts/gitea/publish-release.sh "$TAG"
```

### 步骤 ③ 人点链接保持公网（IM 通知等）

**不要**把通知里的链接也换成 LAN URL——人不在 LAN 时点不开。

```yaml
- name: 通知企业微信
  if: always()
  env:
    WECOM_WEBHOOK: ${{ vars.WECOM_WEBHOOK }}
    TAG: ${{ github.ref_name }}
    OUTCOME: ${{ job.status }}
  run: |
    # BASE 故意硬编码公网 URL,因为人(手机/外网)需要点开
    BASE="https://git.example.com:18443/<owner>/<repo>"
    ./scripts/gitea/notify-wecom.sh "$WECOM_WEBHOOK" "$OUTCOME" "$TAG" \
      "$BASE/releases/tag/$TAG" "$BASE/actions"
```

## 5. 踩坑提示

### a. `printf` 格式串漏 `://` 的冒号

第一版写：
```bash
printf 'http//x-access-token:%s@host\n' "$TOKEN"
#  ↑ 漏了冒号 → 生成 'http//...' → git 报 'scheme not recognized'
```

修复：用参数扩展 `%%://` / `#*://` 拆 scheme/host，`://` 写在 printf 格式串字面里（不易写错）。

### b. Variable 命名避开保留前缀

- ❌ `GITEA_LAN_URL`（`GITEA_*` 保留）
- ❌ `GITHUB_LAN_URL`（`GITHUB_*` 保留）
- ✅ `MY_GITEA_LAN_URL` / `OUR_GITEA_LAN` / 任意非保留前缀

### c. workflow_dispatch 调试也会读 vars

手动触发 workflow 时，`${{ vars.MY_GITEA_LAN_URL }}` 同样读取站点 Variable。**调试时不需要改 workflow**，直接改 Variable 即可。

### d. 公网 URL 不要带 path

Variable 值：`http://192.168.3.252:11330` ✅  
不要写：`http://192.168.3.252:11330/mfreceiver/oc-droid` ❌  
workflow 在 `$GITEA_URL` 后面拼 `/<owner>/<repo>.git`，Variable 只管到端口。

## 6. 安全 / 回退 / 验证

### 安全

- LAN URL 走 HTTP，token 在 git URL / credential 文件中明文。**仅在 LAN 可信时启用**。
- token 是 Gitea Actions 自动签发的临时 token（同 `secrets.GITHUB_TOKEN`），job 结束自动失效。
- LAN 抓包可见 token → 仅在物理隔离的 LAN 启用，不要在共享 WiFi / 公网咖啡店启用。

### 回退（4 种）

| 触发 | 效果 |
|---|---|
| 删除站点 Variable | `${{ vars.MY_GITEA_LAN_URL }}` 为空 → workflow 用硬编码公网 URL 回退 |
| Variable 值改成公网 URL | 显式让 workflow 走公网（验证公网可达性时用） |
| Variable 值改成错误 URL | checkout 失败（fail-fast，比静默错更安全） |
| workflow 删掉 `GITEA_LAN_URL` env 行 | 完全脱离 Variable，纯硬编码（不推荐） |

### 验证

**本地 bash 插值 dry-run**（在 dev host 跑，验证 printf 拼出的 URL 格式）：

```bash
GITEA_LAN_URL="http://192.168.3.252:11330"   # 模拟 Variable
GITEA_URL="${GITEA_LAN_URL:-https://git.example.com:18443}"
SCHEME="${GITEA_URL%%://*}"
HOST="${GITEA_URL#*://}"
TOKEN="FAKE_TOKEN_FOR_DRY_RUN"
printf '%s://x-access-token:%s@%s\n' "$SCHEME" "$TOKEN" "$HOST"
# 期望输出: http://x-access-token:FAKE_TOKEN_FOR_DRY_RUN@192.168.3.252:11330
# 如果输出 'http//...' 或缺冒号 → 拼接逻辑错
```

**YAML 校验**：

```bash
python3 -c "import yaml; yaml.safe_load(open('.gitea/workflows/integration-check.yml'))" && echo OK
```

**实跑排查清单**（job 失败时按序检查）：

1. job log 第一行 `docker create image=...` 确认镜像对
2. checkout step 日志看 `git clone <URL>` —— URL 是 LAN 还是公网？
   - LAN URL（`192.168...`） → Variable 读取成功
   - 公网 URL → Variable 为空，走了回退路径
3. 若 checkout 失败：进 job 容器 `curl -sI <URL>/api/v1/version`，看 HTTP 是否可达
4. 若 publish-release 失败：看日志里 `==> changelog` 后调的 API URL 是哪个
5. 若 IM 通知链接点不开：检查 BASE 是否被误改成 LAN URL

## 7. 范围外（runner job-polling 不在此方案内）

act_runner 启动时通过 `--instance https://git.example.com:18443` 注册到 Gitea，长轮询拉 job。这条链路 **是宿主侧 act_runner → Gitea**，与 workflow 内的 checkout/API **完全正交**：

- runner 注册 URL 不需要改（如果它在工作，就别动）
- 本方案只影响 **job 容器内的 git/curl 操作**
- 如果 runner 自身拉不到 job（注册 URL 不通），那是另一个问题，不在本文档范围

---

## 附：本仓库当前实现位置

- workflows 3 个（branch-check / integration-check / release）：checkout 用站点 Variable，回退公网
- `scripts/gitea/publish-release.sh`：内置 `GITEA_URL=${GITEA_URL:-https://git.vectory.cn:18443}` 默认公网，workflow env 注入覆盖
- IM 通知 `BASE` 硬编码公网 URL（人点链接，不归 Variable 管）
