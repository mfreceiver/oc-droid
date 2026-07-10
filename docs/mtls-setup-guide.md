# ocdroid mTLS 服务端配置与证书导入指南

> 实操指南：在 opencode 服务器上配置 stunnel mTLS、生成签名证书对、把客户端证书导入 ocdroid App。
> 设计 rationale 见 `docs/mtls-tunnel-plan.md`。本指南面向支持**粘贴 PEM 文本导入**的 ocdroid 版本（v0.6.4 之后，minSdk 34）。

## 0. 架构一句话

```
ocdroid App (OkHttp mTLS)
   │  https + TLS 双向认证（出示客户端证书）
   ▼
stunnel（公网端口，强制 mTLS）── 明文转发 ──▶ opencode serve (127.0.0.1:4096, basic auth 第二层)
   ▲
你的 TCP 隧道（纯 TCP 透传，把 stunnel 端口暴露到公网）
```

- **mTLS**：传输层强认证 + 加密。只有持有「由你的私有 CA 签发的客户端证书」的设备能完成 TLS 握手；**无证书设备在 TLS 握手阶段就被 stunnel 拒绝**（连一个 HTTP 字节都发不出去，拿不到任何 opencode 信息）。
- **basic auth**：应用层第二道闸（mTLS 通过后才校验），纵深防御。
- 客户端证书**仅存于 App 内**（EncryptedSharedPreferences，AndroidKeyStore 加密），**不进系统证书库**。
- App 以**粘贴 PEM 文本**方式导入客户端证书 + CA（无文件、无口令；导入时内部转空口令 PKCS12 存储）。

---

## 1. 前置

- 服务器：`openssl`、`stunnel ≥ 5.x`、`opencode`。
- 一个可从公网访问的 `<HOST>:<PORT>`（经你现有的 **纯 TCP 透传隧道**——必须是纯 TCP，不能在隧道层再加 TLS，否则 TLS-in-TLS 双层封装会被 DPI 识别）。
- App：ocdroid（支持粘贴 PEM 导入的版本）。
- ⚠️ `<HOST>` 必须与 App 里填的 `serverUrl` 主机**完全一致**（域名或 IP）——服务端证书的 SAN 会按它签发，App 严格校验主机名。

---

## 2. 生成签名证书对（私有 CA + 服务端证书 + 共享客户端 PEM）

在服务器上（建议单独目录，如 `/etc/stunnel/`）执行。**`ca-key.pem` 是根私钥，生成后离线保管，切勿泄露。**

### 2.1 变量
```bash
HOST=ocdroid.example.net        # ← 改成 App 实际连接的主机（隧道公网域名；按 IP 连接见 2.3 注）
DAYS=825                        # 证书有效期（天）；CA 用 3650
```

### 2.2 私有 CA
```bash
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
  -keyout ca-key.pem -out ca-cert.pem \
  -subj "/CN=opencode Private CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"
chmod 600 ca-key.pem
```

### 2.3 服务端证书（SAN 必须匹配 `<HOST>`）
```bash
openssl req -newkey rsa:2048 -nodes -keyout server-key.pem -out server-csr.pem -subj "/CN=$HOST"
openssl x509 -req -in server-csr.pem -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out server-cert.pem -days $DAYS \
  -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:$HOST")
chmod 600 server-key.pem
# 按 IP 连接时：subjectAltName=IP:1.2.3.4（勿用 DNS:）
```

### 2.4 共享客户端证书（PEM；多设备共用同一份，App 粘贴文本导入）
```bash
openssl req -newkey rsa:2048 -nodes -keyout client-key.pem -out client-csr.pem -subj "/CN=ocdroid-shared-client"
openssl x509 -req -in client-csr.pem -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out client-cert.pem -days $DAYS \
  -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=clientAuth")
```
> App 直接粘贴 PEM 文本（`client-cert.pem` + `client-key.pem`），导入时内部转成空口令 PKCS12 存储——**无需 p12 文件、无需口令**。
> `client-key.pem` 须为**无口令 PKCS8**（`-----BEGIN PRIVATE KEY-----`，openssl 3 `-nodes` 默认即此）。加密私钥（`ENCRYPTED PRIVATE KEY`）与传统 PKCS1（`RSA/EC PRIVATE KEY`）App 会拒绝并提示。
> 分发保留 `ca-cert.pem` + `client-cert.pem` + `client-key.pem` 三份文本。

---

## 3. 配置 stunnel（mTLS）

`/etc/stunnel/stunnel.conf`：
```ini
setuid = stunnel
setgid = stunnel
pid    = /run/stunnel/stunnel.pid

[opencode-mtls]
accept  = 127.0.0.1:<隧道本地端口>     # ← stunnel 监听口（隧道把此口透传到公网 <PORT>）
connect = 127.0.0.1:4096              # opencode，明文

cert = /etc/stunnel/server-cert.pem
key  = /etc/stunnel/server-key.pem

; ── mTLS：强制客户端出示由本 CA 签发的证书 ──
CAfile      = /etc/stunnel/ca-cert.pem
verifyPeer  = yes
requireCert = yes
; 可选收紧：sslVersion = TLSv1.2   （TLSv1.3 兼容性较窄，按 stunnel 版本酌定）
```
- `verifyPeer=yes` + `requireCert=yes` 是 stunnel 5.x 语法；旧版用 `verify = 3`（按你的版本确认）。
- `chmod 600` 所有 `*-key.pem`，stunnel 用户可读 `server-key.pem`。
- 启动：`sudo systemctl restart stunnel`（或 `stunnel /etc/stunnel/stunnel.conf`）。

---

## 4. 启动 opencode（basic auth 第二层）

```bash
export OPENCODE_SERVER_USERNAME=admin
export OPENCODE_SERVER_PASSWORD='<strong-app-layer-password>'
opencode serve --hostname 127.0.0.1 --port 4096   # 仅绑 127.0.0.1，只 stunnel 可达
```

---

## 5. 暴露端口（你的 TCP 隧道）

把 stunnel 的 `accept` 本地端口经你的**纯 TCP 透传隧道**映射到公网 `<HOST>:<PORT>`。App 的 `serverUrl` 即指向 `https://<HOST>:<PORT>`。

> 隧道层**不要**再开 TLS（否则与 stunnel 的 TLS 形成 TLS-in-TLS，可被 DPI 识别）。stunnel 是唯一一次 TLS 终止。

---

## 6. 分发 + 导入 ocdroid App

### 6.1 分发（带外，到每台设备）
把这三样**文本**安全地送到设备（可经加密聊天/邮件正文粘贴，无需传文件）：
- `client-cert.pem`（客户端证书）
- `client-key.pem`（客户端私钥，无口令 PKCS8）
- `ca-cert.pem`（私有 CA 公钥证书）

**在服务器上取得粘贴文本**：SSH 进服务器跑下面两条命令，各自的输出分别粘进 App 的两个框（路径换成你 §2 生成证书的目录，如 `/etc/stunnel/`，或本用户服务的 `~/.config/stunnel/certs/`）。两段都无口令；证书与私钥顺序不限、可整段合并粘贴。

框1「客户端证书+私钥 (PEM)」—— 证书 + 私钥合并输出：
```bash
cat /etc/stunnel/client-cert.pem /etc/stunnel/client-key.pem
```

框2「CA 证书 (PEM)」：
```bash
cat /etc/stunnel/ca-cert.pem
```

### 6.2 App 导入流程（粘贴 PEM 文本）
ocdroid：**设置 → 主机配置 → 新建/编辑 profile**：

1. **服务器地址**：`https://<HOST>:<PORT>`（注意 `https`，指向 stunnel 公网端口）。
2. **Basic Auth**：填 opencode 的用户名 / 密码（第二层）。
3. 打开 **「mTLS 客户端证书」** 开关。
4. **客户端证书+私钥 (PEM)** 文本框：粘贴 `client-cert.pem` 与 `client-key.pem`（两段可合并粘贴，顺序不限，无口令）。App 导入时内部转成空口令 PKCS12 存储。
5. **CA 证书 (PEM)** 文本框：粘贴 `ca-cert.pem`。⚠️ **必粘**：你的服务端证书是私有 CA 签的，导入后 App **只信该 CA**（严格模式，防 MITM）；不粘则 App 走系统/平台 CA，会因不认识你的私有 CA 而握手失败。
6. **保存**。App 即以 mTLS 连接；证书仅存本应用（EncryptedSharedPreferences），不进系统证书库。PEM 解析失败会在对话框顶部红色横幅提示「证书文本无效：…」。

> 导入后若 mTLS 材料有问题（如开启了 mTLS 但未粘客户端证书），App 会在对话框顶部红色横幅提示。

---

## 7. 验证

### 7.1 服务端命令行验证（openssl s_client）
```bash
# ① 有客户端证书 → 握手成功（看 "Verify return code: 0"）
openssl s_client -connect <HOST>:<PORT> \
  -cert client-cert.pem -key client-key.pem -CAfile ca-cert.pem \
  -servername <HOST> </dev/null 2>&1 | grep -E "Verify|subject|issuer"

# ② 无客户端证书 → stunnel 在握手阶段拒绝（fatal alert / 连接断开）
openssl s_client -connect <HOST>:<PORT> -CAfile ca-cert.pem </dev/null 2>&1 | tail -5
```
预期：① 成功；② 失败（这正是「无证书设备被拒」的保证——它在 HTTP 之前就被挡下）。

### 7.2 App 端验证
- 持证书设备：连 REST / 流式（SSE）/ markdown 图片均正常。
- 无证书 / 错证书设备：连接被拒（TLS 握手失败，看不到任何 opencode 内容）。

---

## 8. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| App 连接失败、TLS 握手错 | 服务端证书 SAN 与 `serverUrl` 主机不匹配 | 重签服务端证书，SAN 用 `DNS:<HOST>`（IP 连接用 `IP:`）|
| App 提示不信任服务端证书 | 未导入 CA，或导入了错误的 CA | 导入签发服务端证书的那个 `ca-cert.pem` |
| App「证书文本无效：…」 | 粘贴的 PEM 缺证书/缺私钥/私钥非无口令 PKCS8（加密或传统格式）/ 多份私钥 | 用 openssl 生成无口令 PKCS8（`openssl ... -nodes`），核对含 `BEGIN CERTIFICATE` 与 `BEGIN PRIVATE KEY` 各一块；横幅会显示具体原因 |
| 有证书但仍被 stunnel 拒 | 客户端证书非本 CA 签发 / 已过期 | 用 `ca-cert.pem` 重签客户端证书；检查 `days` |
| stunnel 启动失败 | `verifyPeer`/`requireCert` 语法（旧版 `verify=3`）/ 文件权限 | 按 stunnel 版本调整指令；`chmod 600` keys |
| 切换/重导证书后旧连接未更新 | App 在主机切换/冷启/手动改 URL 时才 reconfigure | 保存后稍候或重启 App（已修复 live reconfigure，但极端情况重启兜底）|

---

## 9. 轮换 / 吊销

本方案是**共享客户端证书**（所有设备同一私钥），故：

- **无单设备吊销**。某设备失控 → **整体轮换**：
  1. 重新生成 CA + 服务端证书 + 客户端 PEM（§2，建议同时换 `HOST` 不变、证书换新）。
  2. 替换 stunnel 的 `ca-cert.pem`/`server-cert.*`，`systemctl restart stunnel`。
  3. 把新 `client-cert.pem` + `client-key.pem` + `ca-cert.pem` 带外分发到**所有**设备，App 内重新粘贴导入（旧证书自动失效）。
  4. ⚠️ **同步更换 opencode basic auth 密码**（失控设备可能已读取 basic auth）。
- 证书过期前（`days` 到期）按同流程轮换。

---

## 10. 安全要点速查

- ✅ 客户端证书**粘贴 PEM 文本**导入（无口令 PKCS8），App 内部转空口令 PKCS12 存储；不依赖文件、不传口令。
- ✅ 证书仅存 App 内（EncryptedSharedPreferences / AndroidKeyStore 加密），**不进系统证书库**、不需 root。
- ✅ 导入 CA → App **只信你的私有 CA**（严格模式，防 WebPKI MITM）。
- ✅ 主机名严格校验（服务端证书 SAN 必须匹配 `serverUrl` 主机，未被禁用）。
- ✅ `ca-key.pem` 离线保管；所有 `*-key.pem` `chmod 600`。
- ✅ mTLS（传输层）+ basic auth（应用层）双层。
- ⚠️ 共享证书：一处泄漏全盘失效，靠整体轮换（含 basic auth）。
- ⚠️ 隧道保持纯 TCP，stunnel 单次 TLS 终止（避免 TLS-in-TLS）。
