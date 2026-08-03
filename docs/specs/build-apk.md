> **规范路径**：本文件描述本机构建/签名细节。**生产发版的权威流程是 Gitea CI**（见 [`docs/specs/gitea-cicd.md`](gitea-cicd.md)）；本文件主要用于本地调试与离线场景。

# 本地构建测试 APK 指南

> 本文是本机（Linux）**实测记录**。构建/签名/发版的**权威规则**见 `.opencode/policies/build-signing.md`；版本号规则见 `.opencode/policies/versioning.md`；改动校验与发版入口见 `scripts/check.sh`、`scripts/release.sh`。本文与脚本若有冲突，以脚本和 policy 为准。

---

## 0. 一句话结论

**做测试 APK 不需要任何配置**：设好 JDK/SDK 环境变量，`./gradlew assembleDebug` 即可得到一枚已用调试密钥签名、可直接安装的 `app-debug.apk`。
需要发版/分发才配置 release 签名（见第 3 节）。

---

## 1. 构建环境

### 1.1 工具链版本（本项目实际值）

| 组件 | 版本 | 来源 |
|------|------|------|
| AGP | **9.1.0** | `gradle/libs.versions.toml` |
| Gradle | **9.3.1** | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | **2.2.10** | `gradle/libs.versions.toml` |
| KSP | **2.3.6** | `gradle/libs.versions.toml` |
| JDK | **21**（JBR，Android Studio 内置） | 需要 ≥17，实测 21 可用 |
| compileSdk / minSdk / targetSdk | 35 / 26 / 34 | `app/build.gradle.kts` |

> 注意：JDK 21、android-35 平台、build-tools 35.0.x 本机已具备，可直接用。

### 1.2 环境变量（本机 Linux 实测路径）

终端默认找不到 Java，**每次构建前导出**（或写入 `~/.zshrc` 持久化）。环境变量的**唯一来源**是 `scripts/env.sh`：

```bash
source ./scripts/env.sh          # 等价于:
# export JAVA_HOME=/home/mar/android-studio/jbr
# export ANDROID_HOME=/home/mar/android-sdk
# export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools"
```

### 1.3 `local.properties`（指向 SDK）

项目根目录需有 `local.properties`（已被 `.gitignore` 忽略，不会提交）：

```bash
printf 'sdk.dir=/home/mar/android-sdk\n' > local.properties
```

---

## 2. 构建 Debug / Release APK

```bash
./gradlew assembleDebug        # 测试 APK → app/build/outputs/apk/debug/app-debug.apk（调试密钥签名，可直接装）
./gradlew assembleRelease      # 发布 APK → app/build/outputs/apk/release/app-release.apk（release 密钥签名）
```

- 首次 debug 构建约 10+ 分钟（下载依赖）；release 约 1–2 分钟（依赖缓存后）。
- 加速（v0.13.5 起默认开启）：`gradle.properties` 已设 `org.gradle.parallel=true` + `org.gradle.caching=true`。`org.gradle.configuration-cache` **故意不开**——`app/build.gradle.kts` 在配置期用 `ProcessBuilder("git", …)` 派生 `versionName`/`versionCode`，与 configuration-cache 不兼容（须先迁 `providers.exec`/`ValueSource`，列为 follow-up）。`scripts/check.sh` 的 daemon 默认 `--no-daemon`（CI / 共享机安全）；本地 dev loop 想复用 daemon 设 `OC_GRADLE_DAEMON=1`。

---

## 3. Release 签名（v0.13.5 新 key）

> 权威规则见 `.opencode/policies/build-signing.md`「Release 签名」；本节为本机实测记录。

Release 签名在 `app/build.gradle.kts` 的 `signingConfigs.release` 配置（读 `local.properties` 的 `release.storeFile/storePassword/keyAlias/keyPassword`，`buildTypes.release` 绑定）。

- **v0.13.5 全新 release key**（旧 release keystore 凭证已丢失，`local.properties` 被误覆盖）：
  - keystore `/home/mar/.android/opencode_release.keystore`（**仓库外**，mode 600，旧件备份 `.replaced-20260724`），alias `release`，RSA-4096 / SHA256withRSA / 25y。
  - **新证书 SHA-256**：`15:6C:58:B7:B1:A4:7B:C3:65:1C:9C:AD:D2:F5:12:FA:AD:01:2B:05:31:B8:13:B2:75:63:B9:50:91:5F:F1:7A`。
  - ⚠️ **破坏性签名变更**：v0.13.5 与 v0.13.4 及更早**不签名兼容**。升级用户**必须先卸载**（否则 `INSTALL_FAILED_INCONSISTENT_CERTIFICATES`；卸载清数据）。此后沿用此 key。
  - 该 re-sign 在**隔离 git worktree（checkout v0.13.5）**完成；发布 APK 构自干净 v0.13.5 提交，仅签名不同。

### 凭证来源（`pass` + `setup-signing.sh`）

凭证**不再只存在 `local.properties`**：

- canonical 存储 = **`pass`**（GPG 加密），entries `ocdroid/release/{store-file,store-password,key-alias,key-password}`，GPG key `E5D94730141E69F6`（无 passphrase，headless 友好）。
- `scripts/setup-signing.sh` **优先读 `pass`**（`ocdroid/release/*`），`pass` 不可用则降级读仓库外凭证（`$OCROID_RELEASE_CREDS`，默认 `/home/mar/.android/ocdroid_release.creds`，mode 600）→ 重建 `local.properties`（保留 `sdk.dir`，末行打印 `source: pass|<creds>`）。**`local.properties` 是可重建的派生物**——丢失即重跑 `./scripts/setup-signing.sh`。
- 备份清单 + 加固建议见 policy「备份职责」段（keystore + `~/.password-store/` + `~/.gnupg/` 三者离机备份）。

> 新机器重建签名环境：用 `$JAVA_HOME/bin/keytool -genkeypair` 生成**独立** keystore（一 App 一 key，勿复用其它项目），把 `release.*` 存入 `pass`（`pass init <gpg-id>` 后 `pass insert ocdroid/release/<key>`）或写入 creds 文件 → `setup-signing.sh` 生成 `local.properties`。

`./gradlew assembleRelease` 直接产出已签名的 `app-release.apk`。

---

## 4. 测试 / 改动校验

改动校验的**权威说明**见 `.opencode/policies/build-signing.md`「改动校验」，脚本入口 `scripts/check.sh`：

```bash
./scripts/check.sh             # 编译 + 单测（默认，每次改动必跑）
./scripts/check.sh --lint      # + lintDebug
./scripts/check.sh --full      # + lint + 覆盖率
# 等价于：./gradlew compileDebugKotlin && testDebugUnitTest [&& lintDebug [&& koverHtmlReport]]
```

> **Daemon（v0.13.5）**：`check.sh` 默认 `./gradlew --no-daemon`（CI / 共享机安全，无残留 daemon 进程）。本地 dev loop 想复用 daemon 提速迭代，`export OC_GRADLE_DAEMON=1` 后再跑——脚本会切回 `./gradlew`（复用常驻 daemon）。

集成测试（`connectedDebugAndroidTest`）需运行中的 OpenCode Server：把 `.env.example` 复制为 `.env` 填入凭证，且**仅在模拟器**运行（详见 `AGENTS.md` 设备安全规定）。

---

## 5. 版本号管理

权威规则见 `.opencode/policies/versioning.md`。ocdroid 采用 **go-around 模式**：**不在源码里写版本号**，`app/build.gradle.kts` 的 `versionCode`/`versionName` 在 Gradle 配置期由 git 派生——

| 字段 | 来源 | 形态 |
|---|---|---|
| `versionName` | `<nearest-tag>-<short-hash>[-dirty]`（tag 去 `v` 前缀，hash = `git rev-parse --short`） | `0.8.2-5f5f243`（始终带 commit 锚点）/ `-dirty`（脏树）/ `dev`（非 git） |
| `versionCode` | `git rev-list --count HEAD` | 单调递增整数，每 commit +1 |

`app/build.gradle.kts` 里**没有**硬编码的 `versionCode = N` / `versionName = "x.y.z"`——那两行是 `versionCode = gitVersionCode` / `versionName = gitVersionName`。**禁止手改**（手写会被下次构建的派生值取代）。

里程碑发版（新 semver tag）走 `release.sh`；tag 后的小修复直接重建即可（见 §6.1）。

---

## 6. 发版产物与 Gitea Release

### 6.1 发版流程

ocdroid 有两类发版（详见 `.opencode/policies/versioning.md`）：

**A. 里程碑发版**（新 semver，单一入口 `scripts/release.sh`）：

```bash
./scripts/release.sh patch   # patch | minor | major
```

内部依次：

1. 校验分支=main、工作区干净（已跟踪文件）。
2. 质量门禁：`scripts/check.sh`。
3. 由最新 git tag 推算下一版本（patch|minor|major）。
4. `./gradlew assembleRelease archiveReleaseApk -PreleaseVersion=<tag>` → 产物 `APK/oc-droid-<tag>-<hash>.apk`（versionName 自动带 commit 锚点，versionCode = commit count）。
5. 生成 changelog（上个 tag..HEAD 的 conventional commits 分组）→ `APK/oc-droid-<tag>-<hash>.md`。
6. 创建 annotated tag `v<tag>`（注释 = changelog）。**不 commit 任何版本文件**（无版本文件可 commit）。

`git push` 与 Gitea release 上传 **不自动执行**，脚本会打印命令。

**B. 同族小修复**（不加新 tag）——go-around 模式的核心收益：

```bash
# 修完 bug、commit 之后：
./gradlew assembleRelease archiveReleaseApk
# → APK/oc-droid-<tag>-<hash>.apk（versionName 自带 commit 锚点，versionCode 更高 → 可装升级）
```

无需 bump semver，重建即得可追溯、可升级的 APK。

> 发版前的多 agent 评审按 `.opencode/policies/review-gate.md` 归档到 `.opencode/runs/reviews/`。

### 6.2 发布产物约定

所有发布的 APK 放到**项目根目录的 `APK/` 文件夹**（已被 `.gitignore` 忽略，不入库），按 **`oc-droid-<versionName>.apk`** 命名（`<versionName>` = git 派生值）：

```
APK/
├── oc-droid-0.8.2-3b3f662.apk        ← 里程碑发版（release.sh；hash = tag 提交）
└── oc-droid-0.8.2-40a0be2.apk        ← 同族小修复（直接重建；hash = 修复提交）
```

归档由 gradle `archiveReleaseApk` task 自动命名（`release.sh` 与手动重建都走它），无需手 grep `versionName`。

### 6.3 发版到自建 Gitea（push tag → CI/CD 自动构建签名包 + 上传）

**主流程**：push tag 后 `.gitea/workflows/release.yml` 自动完成签名构建 + 上传 + 企微通知。Agent / 开发者**不需要手动上传 APK**。

```bash
TAG="v0.14.5"

# 1) release.sh 本地构建 + 打 tag（版本由 git 派生，仅用于本地冒烟）
./scripts/release.sh patch

# 2) push main + tag → CI/CD 触发
git push origin main && git push origin "$TAG"
# ⇒ CI/CD 自动：签名构建(release keystore from Gitea Secrets)
#   → 上传 APK + AAB + mapping.txt + SHA256SUMS
#   → 更新 release notes → 企微通知
```

**⚠️ 不要手动上传本机 APK**——CI/CD 已用 Gitea Secrets 里的正式签名 keystore 构建并上传。手动上传的本机 APK 签名不同，用户混装会签名冲突。如果 release 页面出现两份 APK（一份 7 字符 hash = 本机，一份 8 字符 hash = CI/CD），删除本机那份。

- `main` 分支为开发主线，tag 打在 `main` 的发布提交上。
- 应用名称为 **OC Droid**；`origin` = `https://git.vectory.cn:18443/mfreceiver/oc-droid.git`。
- CI/CD runner 用 `scripts/gitea/publish-release.sh`（CI 侧上传脚本，注入 `${{ secrets.GITHUB_TOKEN }}`）。

---

## 附：本机环境实测记录

| 项目 | 结果 |
|------|------|
| JDK | 21.0.9（JBR）✓ |
| Android SDK | android-35 + build-tools 35.0.0/35.0.1 ✓ |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL**（首次约 10+ 分钟）✓ |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`（约 26 MB，调试密钥签名）✓ |
| Release 签名 | 已配置（`signingConfigs.release` 读 `local.properties`，凭证经 `pass` + `setup-signing.sh` 注入）；**v0.13.5 新 key**（cert SHA-256 `15:6C:58:B7…:F1:7A`）✓ |
| Release APK | `APK/oc-droid-0.13.5-7aa1daf.apk`（约 12 MB，新 release 密钥签名；apksigner 验签通过）✓ ⚠️ 与 v0.13.4 及更早不签名兼容 |
| 服务端 | OpenCode Server v1.17.12（本机 `0.0.0.0:4096`）✓ |

