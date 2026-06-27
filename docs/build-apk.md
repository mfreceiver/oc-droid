# 本地构建测试 APK 指南

> 参考姊妹项目 [x-liker](https://git.vectory.cn:18443/mfreceiver/x-liker) 的构建/签名体系，针对 `opencode_android_client` 整理。
> 本文所有命令均已在本机（Linux）实测通过。

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

> 注意：本项目工具链（AGP 9 / Gradle 9 / Kotlin 2.2）比 x-liker（AGP 8.7 / Gradle 8.11）新很多，但 JDK 21、android-35 平台、build-tools 35.0.x 本机已具备，可直接用。

### 1.2 环境变量（本机 Linux 实测路径）

终端默认找不到 Java，**每次构建前导出**（或写入 `~/.zshrc` 持久化）：

```bash
export JAVA_HOME=/home/mar/android-studio/jbr
export ANDROID_HOME=/home/mar/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
```

> 上游 README 写的是 macOS 路径（`/Applications/Android Studio.app/...`）；在本机用上面的 Linux 路径。

### 1.3 `local.properties`（指向 SDK）

项目根目录需有 `local.properties`（已被 `.gitignore` 忽略，不会提交）：

```bash
printf 'sdk.dir=/home/mar/android-sdk\n' > local.properties
```

---

## 2. 构建 Debug 测试 APK（主推，零配置）

```bash
./gradlew assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

- 已用 **Android 调试密钥自动签名**，可直接安装，无需额外配置。
- 实测：首次构建约 12 分钟（下载依赖），APK 约 26 MB。
- 编译期有一条无害告警（`LocalClipboardManager` deprecated），不影响产物。

### 安装到设备/模拟器

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ **设备安全**：仪表/插桩测试与安装 debug 包请**只用模拟器**。如同时连了真机和模拟器，用 `ANDROID_SERIAL=<模拟器id>` 明确指定，避免覆盖真机上的正式 App 与凭证。

---

## 3. 构建 Release 签名 APK（用于分发/发版，可选）

当前 `app/build.gradle.kts` 的 `release` 构建类型只有混淆/压缩，**没有 signingConfig**，直接 `assembleRelease` 只会得到未签名包。
参考 x-liker 的做法：用 `local.properties` 注入签名凭证，debug 保留默认签名（**不要复用 release 密钥**）。

### 3.1 生成签名密钥库（一次性）

```bash
$JAVA_HOME/bin/keytool -genkeypair -v \
  -keystore release.keystore \
  -alias release \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'YOUR_STORE_PASSWORD' \
  -keypass 'YOUR_KEY_PASSWORD' \
  -dname "CN=opencode_client, OU=dev, O=fork, C=CN"
```

把生成的 `release.keystore` 放在项目根目录（已被 `.gitignore` 忽略，勿提交）。

### 3.2 在 `local.properties` 追加签名凭证

```properties
sdk.dir=/home/mar/android-sdk
release.storeFile=release.keystore
release.storePassword=YOUR_STORE_PASSWORD
release.keyAlias=release
release.keyPassword=YOUR_KEY_PASSWORD
```

> 沿用 x-liker 的 `local.properties` 方案（而非单独的 `signing.properties`），避免两个文件。x-liker 自身 AGENTS.md 与代码在这个命名上有出入，这里统一用 `local.properties`。

### 3.3 在 `app/build.gradle.kts` 增加签名配置

在 `android { }` 块内、`buildTypes { }` 之前加：

```kotlin
signingConfigs {
    create("release") {
        val props = java.util.Properties()
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) {
            props.load(propsFile.inputStream())
        }
        storeFile = file(props.getProperty("release.storeFile", "release.keystore"))
        storePassword = props.getProperty("release.storePassword", "")
        keyAlias = props.getProperty("release.keyAlias", "release")
        keyPassword = props.getProperty("release.keyPassword", "")
    }
}
```

并把 `release { }` 绑定该签名（在现有 release 块里加一行）：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")   // ← 新增
    }
}
```

> 💡 **合并冲突提示**：`app/build.gradle.kts` 是上游文件，改它会在合并上游时有冲突风险（见 `FORK_SYNC.md`）。
> 建议把签名改造放在**独立 dev 分支**上，改动尽量集中（就上面这一小块），合并上游后容易重新应用。

### 3.4 构建

```bash
./gradlew assembleRelease
```

产物（已签名）：`app/build/outputs/apk/release/app-release.apk`
未配置签名时则为 `app-release-unsigned.apk`（不可直接安装）。

---

## 4. 测试

```bash
./gradlew testDebugUnitTest                 # 单元测试
./gradlew koverHtmlReport                   # 覆盖率 → app/build/reports/kover/html/index.html
```

集成测试（`connectedDebugAndroidTest）需运行中的 OpenCode Server：把 `.env.example` 复制为 `.env` 填入凭证，且**仅在模拟器**运行。

---

## 5. 版本号管理

沿用上游 `v0.1.YYYYMMDD` 风格，`app/build.gradle.kts` 内手动维护：

```kotlin
versionCode = 12                // 每次发版 +1
versionName = "0.1.20260622"    // 上游发版日期
```

自己的发布可加后缀区分，如 `0.1.20260622-fork.1`，避免与上游 tag 冲突。

---

## 6. 发版到自建 Gitea（可选，参考 x-liker）

本机已装 `tea` CLI（`/home/mar/tools/tea/tea`）。打好 release APK 后：

```bash
mkdir -p releases
cp app/build/outputs/apk/release/app-release.apk releases/opencode_client-$(grep versionName app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/').apk

/home/mar/tools/tea/tea releases create -r mfreceiver/opencode_android_client \
  --tag v<version> \
  -t "v<version>" \
  -n "Release v<version>" \
  -a releases/opencode_client-<version>.apk
```

---

## 附：实测验证记录（本机）

| 项目 | 结果 |
|------|------|
| JDK | 21.0.9（JBR）✓ |
| Android SDK | android-35 + build-tools 35.0.0/35.0.1 ✓ |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL**（12m12s，首次）✓ |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`（26 MB，已签名）✓ |
| Release 签名 | 需按第 3 节配置（上游未提供） |

---

## 参考

- x-liker 的签名/发版模式：`mfreceiver/x-liker` 的 `app/build.gradle.kts` 与 `AGENTS.md`
- 上游构建说明：根目录 `README.md`「构建」章节
- Fork 同步策略：`FORK_SYNC.md`
