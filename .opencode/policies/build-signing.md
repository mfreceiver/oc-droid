# Build & Signing Policy

ocdroid 构建、签名、发版流程的**唯一权威来源**。脚本实现见 `scripts/`，操作细节见 `docs/build-apk.md`。

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

## Release 签名

- keystore：`/home/mar/.android/opencode_release.keystore`（**仓库外，永不入库**），alias `release`。
- 凭证从 `local.properties`（gitignored）读取：`release.storeFile / storePassword / keyAlias / keyPassword`。
- 签名配置在 `app/build.gradle.kts` 的 `signingConfigs.release`，`buildTypes.release` 绑定该签名。
- **keystore 与密码务必备份**——丢失则无法以同一身份升级 App。
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
