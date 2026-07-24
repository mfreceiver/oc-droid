# Build & Signing Policy

ocdroid 构建、签名、发版流程的**唯一权威来源**。脚本实现见 `scripts/`，操作细节见 `docs/specs/build-apk.md`。

## 构建环境

环境变量定义在 `scripts/env.sh`（唯一来源），任何脚本 `source env.sh` 即可：

```bash
export JAVA_HOME=/home/mar/android-studio/jbr
export ANDROID_HOME=/home/mar/android-sdk
export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools"
```

- JDK 21（JBR），用项目自带 `./gradlew` wrapper（Gradle 9.3.1），**不要**用系统 gradle。
- 工具链在 `gradle/libs.versions.toml` 锁定（AGP 9.1.0 / Kotlin 2.2.10 / KSP 2.3.6），**勿擅自升级**。
- 工具链与其它本机项目（x-liker / syncplayer）完全隔离。

## 构建模式

| 命令 | 用途 | 产物 |
|---|---|---|
| `./gradlew assembleDebug` | 测试 APK（调试密钥签名） | `app/build/outputs/apk/debug/app-debug.apk` |
| `./gradlew assembleRelease` | 发布 APK（release 密钥签名） | `app/build/outputs/apk/release/app-release.apk` |

- Debug 包**仅用于模拟器测试**，**禁止装到物理手机**（见「设备安全」）。
- Release 包才可分发。

## 改动校验（替代 LSP 自检，必做）

本工作区 opencode 服务端已关闭 LSP，编辑后无编译器自动反馈。每次改 Kotlin/资源后必须：

```bash
./scripts/check.sh          # 编译 + 单测（默认）
./scripts/check.sh --full   # + lint + 覆盖率
```

等价于手动 LSP 自检。详见 AGENTS.md「改动校验」。

- **Daemon（v0.13.5）**：`check.sh` 默认 `./gradlew --no-daemon`（CI / 共享机安全）。本地 dev loop 想复用 daemon，`export OC_GRADLE_DAEMON=1` 后脚本切回 `./gradlew`（复用常驻 daemon 提速迭代）。
- **Gradle 并行 / 缓存（v0.13.5）**：`gradle.properties` 已默认 `org.gradle.parallel=true` + `org.gradle.caching=true`。**`org.gradle.configuration-cache` 故意不开**——`app/build.gradle.kts` 配置期用 `ProcessBuilder("git", …)` 派生 `versionName`/`versionCode`（见 `versioning.md`），与 configuration-cache 不兼容（待迁 `providers.exec`/`ValueSource`）；在 git-versioning 迁移完成前不得手动开启。

## Release 签名

### 密钥库（v0.13.5 新建，破坏性签名变更）

- keystore：`/home/mar/.android/opencode_release.keystore`（**仓库外，永不入库**，mode 600），alias `release`，**RSA-4096 / SHA256withRSA / 有效期 25y**（2026-07-24 → 2051-07-18）。
- 旧 release keystore 的凭证已丢失（`local.properties` 被意外覆盖）；v0.13.5 生成了**全新 release key**（覆盖旧 keystore，旧件备份为 `.replaced-20260724`）。
- **新证书 SHA-256**：`15:6C:58:B7:B1:A4:7B:C3:65:1C:9C:AD:D2:F5:12:FA:AD:01:2B:05:31:B8:13:B2:75:63:B9:50:91:5F:F1:7A`（≠ 旧 release 证书 `8c324d…e3fc`，≠ debug 证书 `5ddf8f…f252`）。
- ⚠️ **破坏性签名变更**：v0.13.5 用新 key 签名，与 v0.13.4 及更早**不签名兼容**。从任何旧版升级的用户**必须先卸载**（否则 Android 拒装 `INSTALL_FAILED_INCONSISTENT_CERTIFICATES`；卸载会清数据——连接设置 / 草稿 / TOFU pin）。此后发版沿用此新 key（正常升级）。
- 签名配置在 `app/build.gradle.kts` 的 `signingConfigs.release`（读 `local.properties` 的 `release.storeFile/storePassword/keyAlias/keyPassword`），`buildTypes.release` 绑定该签名。
- 该破坏性 re-sign 在**隔离的 git worktree（checkout 到 tag v0.13.5）**完成（主线工作树当时有并发无关改动），故发布的 APK 构自干净的 v0.13.5 提交代码，仅签名与最初的（debug 签名）v0.13.5 产物不同。

### 凭证管理（`pass` + `setup-signing.sh`）

- **canonical 凭证存储 = `pass`**（standard unix password manager，GPG 加密）：
  - entries：`ocdroid/release/{store-file, store-password, key-alias, key-password}`。
  - GPG key `E5D94730141E69F6`（uid `ocdroid release signing <ocdroid-release@vectory.local>`），**无 passphrase** 生成（`%no-protection`）以便 headless / 非交互 shell（CI）使用。
  - 🔧 **加固建议（可选 follow-up）**：给 GPG key 加 passphrase + 用 `gpg-agent`/`pinentry`，和/或启用全盘加密——无 passphrase key 的机密性仅靠 `~/.gnupg` 文件权限。
- **plaintext 备份（fallback）**：`$OCROID_RELEASE_CREDS`（默认 `/home/mar/.android/ocdroid_release.creds`，mode 600，仓库外）；仅在 `pass` 不可用时由脚本降级读取。
- **`scripts/setup-signing.sh`（桥接，生成 `local.properties`）**：**优先读 `pass`**（`ocdroid/release/*`），`pass` 不可用则降级读 `$OCROID_RELEASE_CREDS` → 重建 `local.properties`（保留已有 `sdk.dir`，mode 600；末行打印 `source: pass|<creds路径>` 标明实际来源）。
  - 改 `pass` 条目后**直接重跑 `setup-signing.sh`** 即可重建 `local.properties`，无需手动同步 creds 文件。
  ```bash
  ./scripts/setup-signing.sh
  OCROID_RELEASE_CREDS=/path/creds ./scripts/setup-signing.sh   # 覆盖 creds 路径
  ```
- `local.properties` 是**可重建的派生物**（gitignored，仓库内，可能被 repo 操作覆盖）：丢失 / 被覆盖 → 重跑 `setup-signing.sh`，**绝非唯一副本**。

### 备份职责（强制）

丢失 keystore **或** pass store 即再次丢失 release 身份。以下必须**离机备份**：

- keystore 文件：`/home/mar/.android/opencode_release.keystore`。
- `pass` store：`~/.password-store/` **以及** GPG keyring `~/.gnupg/`（私钥 `E5D94730141E69F6`）——缺任一则 pass entries 无法解密。
- （plaintext 冗余 `/home/mar/.android/ocdroid_release.creds` 暂留至 pass 备份确认有效。）

- 新增 App 签名用**独立 key**（一 App 一 key），不要复用 x-liker / syncplayer 的 key。

## 发版唯一入口

```bash
./scripts/release.sh <patch|minor|major>
```

脚本内部依次执行：分支/工作区校验 → 质量门禁 → bump 版本 → assembleRelease → 产物归档 → commit + tag。
`git push` 与 `tea releases create` **不自动执行**（对外发布需人工确认），脚本只打印命令。

## 发布产物约定

- APK 放项目根目录 `APK/`（gitignored，不入库）。
- 命名：`oc-droid-<versionName>.apk`（如 `oc-droid-0.2.4.apk`）。
- 应用名称：**OC Droid**。
- tag：`v<versionName>`（如 `v0.2.4`），指向 main 分支提交。

## Remote

`origin` = `https://git.vectory.cn:18443/mfreceiver/oc-droid.git`。

## 禁止

- 禁止手拼发版命令绕过 `release.sh`。
- 禁止把 debug 包当作发版产物上传。
- 禁止在 release 流程外手改版本号（见 `versioning.md`）。
