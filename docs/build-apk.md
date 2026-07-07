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
- 加速：在 `gradle.properties` 加 `org.gradle.configuration-cache=true`、`org.gradle.caching=true`、`org.gradle.parallel=true`。

---

## 3. Release 签名（已配置）

Release 签名已在 `app/build.gradle.kts` 配置完毕：

- keystore：`/home/mar/.android/opencode_release.keystore`（**仓库外**，永不入库），alias `release`。
- 凭证从 `local.properties`（gitignored）读取：`release.storeFile / storePassword / keyAlias / keyPassword`。
- `signingConfigs.release` 读取上述凭证，`buildTypes.release` 绑定该签名。

> 若要在新机器上重建签名环境：用 `$JAVA_HOME/bin/keytool -genkeypair` 生成独立 keystore（**一 App 一 key**，勿复用其它项目），把 `release.*` 凭证写入 `local.properties`，并在 `signingConfigs.release` 指向它。**keystore 与密码务必备份**——丢失则无法以同一身份升级 App。

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

集成测试（`connectedDebugAndroidTest`）需运行中的 OpenCode Server：把 `.env.example` 复制为 `.env` 填入凭证，且**仅在模拟器**运行（详见 `AGENTS.md` 设备安全规定）。

---

## 5. 版本号管理

权威规则见 `.opencode/policies/versioning.md`，脚本入口 `scripts/bump-version.sh`（**禁止手改** `app/build.gradle.kts` 的 `version*` 字段）：

```bash
./scripts/bump-version.sh patch   # patch | minor | major
```

`app/build.gradle.kts` 维护两个字段（`versionCode` 单调递增 +1，`versionName` 语义化 MAJOR.MINOR.PATCH）：

```kotlin
versionCode = 9
versionName = "0.2.4"     // 当前版本
```

---

## 6. 发版产物与 Gitea Release

### 6.1 发版流程（单一入口）

发版走 `scripts/release.sh`（详见 `.opencode/policies/build-signing.md`），内部已依次执行：

1. 分支=main、工作区干净校验。
2. 质量门禁：`scripts/check.sh`（编译 + 单测全绿）。
3. bump 版本号：`scripts/bump-version.sh`。
4. `./gradlew assembleRelease` 产出签名 APK。
5. 产物归档到 `APK/oc-droid-<versionName>.apk`。
6. **生成 changelog**：从上个 tag 到 HEAD 的 conventional commits 按 `feat/fix/docs/test/...` 分组，写入 `APK/oc-droid-<versionName>.md`。
7. `commit` + 打 tag `v<versionName>`（tag 注释用该 changelog）。

```bash
./scripts/release.sh patch       # patch | minor | major
```

`git push` 与 Gitea release 上传 **不自动执行**（对外发布需人工确认），脚本会打印命令。

> 发版前的多 agent 评审（如 `glmer` + `gpter`）按评审意见修订；评审产物按 `.opencode/policies/review-gate.md` 归档到 `.opencode/runs/reviews/`。

### 6.2 发布产物约定

所有发布的 APK 放到**项目根目录的 `APK/` 文件夹**（已被 `.gitignore` 忽略，不入库），按 **`oc-droid-<versionName>.apk`** 命名：

```
APK/
└── oc-droid-0.2.3.apk
```

一键产出（从构建产物拷贝并命名）：

```bash
mkdir -p APK
VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\([^"]*\)".*/\1/')
cp app/build/outputs/apk/release/app-release.apk "APK/oc-droid-$VERSION.apk"
```

### 6.3 发版到自建 Gitea（git push + curl REST API）

不再依赖 `tea` CLI（曾遇 flag 语法错位 + 大 APK 上传超时）。改用**原生 git push** + **curl Gitea REST API**，封装在 `scripts/upload-release.sh`。

**前提**：Gitea token 可从两处取（脚本自动）：① 环境变量 `GITEA_TOKEN`；② 本机 `~/.config/tea/config.yml`（tea 登录残留，仅读 token，不调 tea）。

```bash
VERSION="0.5.0"   # versionName，与 release.sh bump 出的一致
TAG="v$VERSION"

# 1) push main + tag（Gitea 收到 tag 后自动建 release 占位）
git push origin main && git push origin "$TAG"

# 2) 上传 APK + 更新 release notes（建/找 release → POST assets → PATCH body）
./scripts/upload-release.sh "$VERSION"
```

`upload-release.sh` 内部三步（纯 curl，可单独复用）：

```bash
GITEA_TOKEN="$(grep 'token:' ~/.config/tea/config.yml | head -1 | awk '{print $2}')"
API="https://git.vectory.cn:18443/api/v1/repos/mfreceiver/oc-droid/releases"
# 找 release id（Gitea 自动建的）
RID=$(curl -s -H "Authorization: token $GITEA_TOKEN" "$API?name=$VERSION" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
# 上传 APK 附件
curl -X POST "$API/$RID/assets?name=oc-droid-$VERSION.apk" \
  -H "Authorization: token $GITEA_TOKEN" -H "Content-Type: application/octet-stream" \
  --data-binary @"APK/oc-droid-$VERSION.apk"
# 更新 release notes
curl -X PATCH "$API/$RID" -H "Authorization: token $GITEA_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$(python3 -c 'import json;print(json.dumps({"body":open("APK/oc-droid-VERSION.md").read()}))')"
```

- `main` 分支为开发主线，tag 打在 `main` 的发布提交上。
- 应用名称为 **OC Droid**；`origin` = `https://git.vectory.cn:18443/mfreceiver/oc-droid.git`。
- 若 `tea` 仍可用作 fallback，旧命令 `/home/mar/tools/tea/tea releases create -r mfreceiver/oc-droid --tag $TAG -t $TAG -f APK/oc-droid-$VERSION.md -a APK/oc-droid-$VERSION.apk`（注意 `releases delete` 不接 `--tag`，要用 release id）。

---

## 附：本机环境实测记录

| 项目 | 结果 |
|------|------|
| JDK | 21.0.9（JBR）✓ |
| Android SDK | android-35 + build-tools 35.0.0/35.0.1 ✓ |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL**（首次约 10+ 分钟）✓ |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`（约 26 MB，调试密钥签名）✓ |
| Release 签名 | 已配置（`signingConfigs.release` 读 `local.properties`），`assembleRelease` 通过 ✓ |
| Release APK | `APK/oc-droid-0.2.3.apk`（约 4.1 MB，release 密钥签名）✓ |
| 服务端 | OpenCode Server v1.17.12（本机 `0.0.0.0:4096`）✓ |

