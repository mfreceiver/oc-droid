# OCDroid

OCDroid — 基于 OpenCode 协议的原生 Android 客户端，用于远程连接 AI coding agent、发送指令、监控工作进度、浏览代码变更。

## 功能概述

- **Chat**：发送消息、切换模型和 Agent、查看 AI 回复与工具调用（Markdown 渲染、Patch diff、Todo 列表）
- **Files**：文件树浏览、git 状态标记、代码与 Markdown 预览
- **Settings**：服务器连接配置、Basic Auth 认证、主题切换（Light / Dark / System）
- **平板适配**：手机底部 Tab 导航，平板三栏布局（文件 / 预览 / Chat）

## 环境要求

- Android 8.0+（API 26）
- Android Studio（用于构建）
- 运行中的 OpenCode Server（`opencode serve` 或 `opencode web`）

## 快速开始（局域网）

1. 在电脑上启动 OpenCode：`opencode serve --port 4096`
2. 打开 Android App，进入 Settings，填写服务器地址（如 `http://192.168.x.x:4096`）
3. 点击 Test Connection 验证连接
4. 在 Chat 中创建或选择 Session，开始对话

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

## 构建

```bash
# 设置 JDK（使用 Android Studio 自带的）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# 构建
./gradlew assembleDebug

# 单元测试
./gradlew testDebugUnitTest

# 测试覆盖率
./gradlew koverHtmlReport
# 报告位于 app/build/reports/kover/html/index.html
```

集成测试需要运行中的 OpenCode Server，将 `.env.example` 复制为 `.env` 并填入实际凭证后执行：

```bash
./gradlew connectedDebugAndroidTest
```

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

## License

本项目基于 [grapeot/opencode_android_client](https://github.com/grapeot/opencode_android_client)（MIT）深度改造。
采用 MIT 协议，详见 [LICENSE](./LICENSE)。
