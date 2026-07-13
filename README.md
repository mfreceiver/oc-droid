# OCDroid

OCDroid — 基于 OpenCode 协议的原生 Android 客户端，用于远程连接 AI coding agent、发送指令、监控工作进度、浏览代码变更。

## 功能概述

- **Chat**：发送消息、切换模型和 Agent、查看 AI 回复与工具调用（Markdown 渲染、Patch diff、Todo 列表）
- **会话导航**：Sessions 标签 = 所有项目所有根会话的**扁平历史**（按时间倒序、全量加载、无分页）；Chat 顶部 Tab 条快速切换「打开的」根会话（workdir hash 色高亮、未读/待问标记、可关闭 Tab），标题点击打开 SessionPickerSheet（全部 / 搜索 / 归档）；子 agent 会话在父会话内导航
- **Files**：项目工作区——首屏「已连接的项目」列表（项目为**独立实体**：空项目 / 全归档项目不消失，仅显式断开才移除），每项目进入文件树浏览、git 状态标记、代码与 Markdown 预览；支持 `files?workdir=` 深链直达
- **Settings**：服务器连接配置、Basic Auth 认证、主题切换（Light / Dark / System）
- **平板适配**：手机底部 Tab 导航，平板三栏布局（文件 / 预览 / Chat）

## 环境要求

- Android 8.0+（API 26）
- Android Studio（用于构建）
- 运行中的 OpenCode Server（`opencode serve` 或 `opencode web`）

## 远程访问

默认为局域网使用。远程访问推荐以下方案：

### HTTPS + 公网服务器

将 OpenCode 部署在公网服务器上，使用 HTTPS 加密：

1. 服务器上运行 OpenCode，配置 TLS
2. App Settings 中填写 `https://your-server.com:4096`
3. 配置 Basic Auth 用户名和密码

### Tailscale

通过 Tailscale 组网，Android 设备和 OpenCode 服务器处于同一 tailnet：

1. 两端安装并登录 Tailscale
2. App Settings 中填写 Tailscale MagicDNS 地址（如 `http://your-machine.tail*****.ts.net:4096`）

App 已对 `*.ts.net` 域名配置 HTTP 豁免，无需额外设置。

### mTLS 双向认证（stunnel）

通过 stunnel 在公网入口强制 mTLS：只有持有「由你的私有 CA 签发的客户端证书」的设备能完成 TLS 握手——**无证书设备在握手阶段即被拒**（一个 HTTP 字节都发不出去）。mTLS 之上再叠 basic auth 作第二层。完整服务端配置（CA/服务端/客户端证书生成、stunnel 配置、轮换）见 [`docs/mtls-setup-guide.md`](./docs/mtls-setup-guide.md)。

App（v0.6.6+）以**剪贴板粘贴 base64** 导入：「客户端证书」槽粘「无口令 PKCS12 的 base64」，「CA 证书」槽粘「CA 证书 DER 的 base64」。在服务器上（生成证书的目录，如 `/etc/stunnel/`）各跑一条命令，复制输出粘进 App 对应槽：

```bash
# 槽1「客户端证书」—— cert + key 打包成无口令 PKCS12，再单行 base64
# ⚠️ 必须 -legacy -descert：Android 内置 PKCS12 提供者（BouncyCastle）不支持
#    OpenSSL 3+ 默认的 AES/PBES2 加密；-legacy -descert 产出 3DES/SHA1（Android 可读）
openssl pkcs12 -export -legacy -descert -inkey client-key.pem -in client-cert.pem -passout pass: | base64 -w0

# 槽2「CA 证书」—— CA 证书 DER → 单行 base64
openssl x509 -in ca-cert.pem -outform DER | base64 -w0
```

> **为什么必须 `-legacy -descert`**：Android 的 PKCS12 提供者是冻结的旧版 BouncyCastle（只认 3DES/SHA1），Conscrypt 不提供 PKCS12-PBES2；连 API 35 也读不了 OpenSSL 3+ 默认的 AES p12（报「PKCS12 无效或口令错误」）。`-legacy -descert` 让证书袋和密钥袋都用 3DES（`-descert` 很关键——单 `-legacy` 的证书袋是 RC2，部分设备仍读不了）。主机 JDK 能读 AES p12，别用它验证。
> macOS 的 `base64` 无 `-w0`，改用 `base64 | tr -d '\n'`。App 会自动处理粘贴通道的常见篡改：去除换行/空白/PEM 头，并**还原 URL 编码**（`%2B`→`+`、`%2F`→`/`、`%3D`→`=`，常见于网页终端/聊天复制）——从 SSH 网页终端等复制即便被 URL 编码也能正常导入。

## 构建

> 构建命令、签名、发版的**权威说明**见 [`docs/build-apk.md`](./docs/build-apk.md)；规则见 `.opencode/policies/build-signing.md`。本节只给速查。

```bash
source ./scripts/env.sh          # 导出本机 JDK/SDK 环境（终端默认找不到 Java）
./gradlew assembleDebug          # 测试 APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # 发布 APK → app/build/outputs/apk/release/app-release.apk（需配置 release 签名）
./gradlew testDebugUnitTest      # 单元测试
./gradlew koverHtmlReport        # 覆盖率 → app/build/reports/kover/html/index.html
```

发版走单一入口（内部已做：质量门禁 → bump 版本 → 构建 → tag）：

```bash
./scripts/release.sh patch       # patch | minor | major
```

集成测试（`connectedDebugAndroidTest`）需要运行中的 OpenCode Server：将 `.env.example` 复制为 `.env` 并填入 `OPENCODE_*` 凭证，且**仅在模拟器**运行（详见 [`AGENTS.md`](./AGENTS.md)「设备安全」）。

## 项目结构

```
app/src/main/java/cn/vectory/ocdroid/
├── data/
│   ├── api/          # REST API 接口、SSE 客户端
│   ├── model/        # 数据模型（Session、Message、File 等）
│   └── repository/   # 数据仓库层
├── di/               # Hilt 依赖注入
├── ui/
│   ├── chat/         # Chat 页面
│   ├── files/        # Files 页面
│   ├── session/      # Session 列表与树形展示
│   ├── settings/     # Settings 页面
│   └── theme/        # 主题、颜色、字体
└── util/             # SettingsManager 等工具类
```

## 技术栈

- Jetpack Compose + Material 3
- OkHttp + Retrofit（网络）
- Kotlin Serialization（JSON）
- Hilt（依赖注入）
- EncryptedSharedPreferences（安全存储）
- Kover（测试覆盖率）

## 兼容的 OpenCode 服务端版本

当前适配 **OpenCode Server [v1.17.12](https://github.com/anomalyco/opencode/releases)**。

## License

本项目基于 [grapeot/opencode_android_client](https://github.com/grapeot/opencode_android_client)（MIT）深度改造。
采用 MIT 协议，详见 [LICENSE](./LICENSE)。
