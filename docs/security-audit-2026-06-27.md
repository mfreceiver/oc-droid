# 安全审计报告

> **审计项目**：ocdroid（mfreceiver fork）
> **项目版本**：`0.1.20260622`（versionName）
> **审计日期**：2026-06-27
> **审计基线**：上游基点 `v0.1.20260622` @ `ec517f1`；dev HEAD @ `27b4dc0`（dev 领先上游 5 个自定义提交）
> **审计范围**：全量应用代码 —— `app/src/main`（Kotlin 源码）、`AndroidManifest.xml`、`res/xml/network_security_config.xml`、构建/签名配置（`app/build.gradle.kts` 等）、WebView 资源（`assets/web_preview/`）、ProGuard 规则
> **审计方式**：gpter / glmer / kimo 三方并行独立评审，编排者综合去重、交叉印证、统一最终定级
> **后门结论**：三方一致 —— **不存在安全后门**
> **安全姿态评分**：综合约 **6.5 / 10**（glmer 7 / gpter 6.5 / kimo 6）

---

> 注：以下发现内容为审计完成时的原始记录，**未作修改**。后续每次上游同步后的"差异审计"会产出新的带日期报告，本报告作为初始全量基线。

---

## 一、后门结论：三方一致判定 —— 不存在安全后门 ✓✓✓

| 排查项 | 三方一致结论 |
|--------|------|
| 硬编码凭证/token/密钥 | **无**（仅 localhost 与 `space.ai-builders.com` 默认值，均为业务所需） |
| 隐藏数据外发/可疑遥测 | **无**（唯一外部域名 `space.ai-builders.com` 是用户显式配置的语音转写） |
| 证书校验绕过（trustAll / 自定义 TrustManager / HostnameVerifier） | **无**（TLS 走系统默认） |
| release 调试后门 | **无**（Intent 凭证注入被 `BuildConfig.DEBUG` 硬门控，release 死代码） |
| 危险 exported 组件 | **无**（仅 MainActivity launcher + FileProvider 正确配置） |
| SQL 注入 | **无**（项目无数据库） |
| 被篡改依赖 | **未发现证据**（voiceflow-android 来自原作者，仅理论供应链风险） |

**安全姿态评分**：glmer 7/10、gpter 6.5/10、kimo 6/10 → **综合约 6.5/10**。基础安全做得不错（EncryptedSharedPreferences、备份排除凭证、DOMPurify、最小权限、无 trust-all），扣分集中在性能/资源泄漏与网络基线偏宽。

---

## 二、合并发现清单（按统一最终定级，附共识度）

### 🟠 High（真实风险：ANR / OOM / 凭证暴露）

| 编号 | 问题 | 位置 | 共识 | 修复 |
|------|------|------|------|------|
| **H1** | `onCleared()` 在**主线程 `runBlocking`** 关语音会话（WebSocket/音频 IO）→ ANR | `MainViewModel.kt:1094-1102` | **3/3** | 改为 `viewModelScope.launch(NonCancellable)` + 短超时，不在主线程同步等待 |
| **H2** | `HttpImageHolder` 全局静态 Bitmap 缓存**无界无清理** → 长用 OOM | `DataUriImageTransformer.kt:62-91` | **3/3** | 改 `LruCache`（按字节限）+ `onTrimMemory`/切会话清理 |
| **H3** | **主线程同步解码 Base64 大图** + `decodeByteArray` 无 `inSampleSize` → 卡顿/OOM | `FilePreviewPane.kt:319-336`、`ChatMessageContent.kt`、`DataUriImageTransformer.kt:36-60` | 2/3 | 解码移到 `Dispatchers.IO`，先 `inJustDecodeBounds` 探尺寸再采样，设字节/像素上限 |
| **H4** | **网络基线偏宽**：无 scheme 默认拼 `http://` + `*.ts.net` 明文豁免过广 + Basic Auth 可能明文传输 | `network_security_config.xml:14-19`、`OpenCodeRepository.kt:60`、`SSEClient.kt:50` | **3/3** | 默认拼 `https://`；收紧 `ts.net` 明文或用户显式确认；删冗余 `usesCleartextTraffic="true"` |

### 🟡 Medium

| 编号 | 问题 | 位置 | 共识 | 修复 |
|------|------|------|------|------|
| **M1** | REST/SSE **共用** OkHttpClient，`readTimeout(0)` → 普通 API 请求可无限挂起 | `OpenCodeRepository.kt:35-56` | 2/3⚠️ | 拆两个 client：REST 有限超时，SSE 单独 `readTimeout(0)` |
| **M2** | `AndroidView(WebView)` **缺 `onRelease`/`destroy()`** → Activity + native 资源泄漏 | `MarkdownWebPreviewPane.kt:153-220` | 1/3 | 加 `onRelease = { it.destroy() }` |
| **M3** | WebView 允许 `<style>` + 远程图片 URL → 元数据外连 / CSS UI 欺骗 | `preview.js:32-97`、`MarkdownWebPreviewPane.kt` | 2/3 | 默认拦截外部资源；`shouldInterceptRequest` 仅放行 asset/data |
| **M4** | release 仍开 OkHttp **BASIC 日志**，URL 含 session/文件路径会入 logcat | `OpenCodeRepository.kt:37-39` | 2/3⚠️ | 仅 `BuildConfig.DEBUG` 启用；release `NONE` |
| **M5** | SSEClient **无限重连、无抖动** → 后台电量/流量风暴 | `SSEClient.kt:37-43` | 1/3 | 加最大重试/超时 + 指数退避抖动；4xx 直接放弃 |
| **M6** | `OpenCodeApp` 静态预热 WebView 永不 `destroy()`/复用 | `OpenCodeApp.kt:16-28` | **3/3** | 移除或真正复用并销毁 |

### 🟢 Low / Info（卫生 / 设计选择 / 理论风险）

- **供应链**（3/3，理论）：`voiceflow-android` 经 JitPack 无 checksum → 建议开 Gradle dependency verification
- **SSH TOFU**（glmer）：首次连接自动信任 fingerprint → 可选加 UI 确认
- **allowBackup="true"**（gpter）：虽排除 sharedpref 但偏宽 → 安全优先可设 false
- **shareImage 临时文件不清理**（kimo）：`cacheDir/shared/` 无限增长
- **Basic Auth 编码**（kimo）：用平台默认编码，非 ASCII 用户名/密码建议显式 `Charsets.UTF_8`
- **AndroidPreviewBridge 缺 ProGuard keep**（kimo）：release 可能致 Markdown 外链点击失效
- **`.env` 凭证写入测试 APK meta-data**（kimo）：CI 构建卫生
- **理论风险**：Markdown 图片路径 workspace 越界（glmer L3）、SSRF 经 markdown 图片（kimo）—— 均取决于服务端访问控制
- **待验证**：`sendAudioChunk` 是否在 Main dispatcher（kimo）

---

## 三、最该优先修复的 Top 5

1. **H1** `onCleared()` 主线程 `runBlocking` → ANR（3/3 一致，最痛）
2. **H2 + H3** 图片：无界缓存 + 主线程解码 → OOM/卡顿（合并修，收益最大）
3. **H4** 网络基线：默认 `https://` + 收紧 `ts.net` + 删冗余明文开关（含 M4 release 日志）
4. **M1** 拆分 REST/SSE OkHttpClient，修普通请求无限挂起
5. **M2** WebView `onRelease` 销毁（顺带 M6 预热 WebView）

---

## 四、三方分歧（透明披露，已据共识定级）

| 议题 | 分歧 | 裁定 |
|------|------|------|
| `onCleared` runBlocking | kimo=Critical / glmer=High / gpter=Medium | **High**（确实能 ANR，但仅在退出/旋转路径，非稳态） |
| `readTimeout(0)` 共用 client | gpter=High / kimo=Low | **Medium**（真实，但需服务端异常/慢连接才触发） |
| release OkHttp 日志 | kimo=High / gpter=Medium / glmer=认为无碍 | **Medium**（glmer 正确指出 BASIC 不记凭证；但 URL 含 session/路径会泄露元数据，二者各对一半） |

---

## 五、结论

**这是一个安全意识明显高于平均水平的项目，没有后门或恶意行为。** 三方审计交叉印证后，无任何 Critical 级安全问题；主要待改进项集中在**稳定性/性能（ANR、OOM、资源泄漏）**和**网络基线收紧**，均为可修复的实现缺陷，不涉及安全底线。
