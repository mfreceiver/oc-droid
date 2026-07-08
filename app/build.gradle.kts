import java.util.Base64
import java.util.Properties

// Load .env for integration test credentials (not checked in)
val envFile = rootProject.file(".env")
val env = if (envFile.exists()) {
    envFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
} else emptyMap()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "cn.vectory.ocdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.vectory.ocdroid"
        minSdk = 26
        targetSdk = 34
        versionCode = 46
        versionName = "0.5.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Integration test credentials from .env (dynamic, not in code)
        testInstrumentationRunnerArguments["openCodeServerUrl"] = env["OPENCODE_SERVER_URL"] ?: ""
        testInstrumentationRunnerArguments["openCodeUsername"] = env["OPENCODE_USERNAME"] ?: ""
        testInstrumentationRunnerArguments["openCodePassword"] = env["OPENCODE_PASSWORD"] ?: ""
        // Agent used by integration-UI tests when they send a prompt. Pick one the
        // server can actually run (GET /agent lists them). Optionally override the
        // model (provider + id) so a runnable agent uses a fast/cheap model — e.g.
        // build + deepseek/deepseek-v4-flash — instead of the agent's default.
        testInstrumentationRunnerArguments["openCodeAgent"] = env["OPENCODE_AGENT"] ?: ""
        testInstrumentationRunnerArguments["openCodeModelProvider"] = env["OPENCODE_MODEL_PROVIDER"] ?: ""
        testInstrumentationRunnerArguments["openCodeModelId"] = env["OPENCODE_MODEL_ID"] ?: ""
    }

    // P0-8: load release keystore config once; availability drives both the
    // signingConfig fields and the buildTypes.release signing fallback. When the
    // keystore is absent we deterministically fall back to the debug signing
    // config (instead of leaving SigningConfig.storeFile == null, whose
    // assembleRelease behavior is AGP-version-dependent — some versions emit an
    // unsigned APK, others fail the build).
    val releaseProps = Properties().apply {
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) load(propsFile.inputStream())
    }
    val releaseStoreFilePath = releaseProps.getProperty("release.storeFile")
    val releaseKeystoreAvailable =
        releaseStoreFilePath != null && file(releaseStoreFilePath).exists()

    signingConfigs {
        create("release") {
            // Credentials live in gitignored local.properties (see docs/build-apk.md).
            if (releaseKeystoreAvailable) {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseProps.getProperty("release.storePassword", "")
                keyAlias = releaseProps.getProperty("release.keyAlias", "release")
                keyPassword = releaseProps.getProperty("release.keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // P0-8: explicit deterministic fallback — release uses the real
            // release keystore when configured, otherwise the debug keystore so
            // assembleRelease still produces an installable APK on dev machines
            // / CI without local.properties. (CI that needs a real release
            // signature must provide release.storeFile in local.properties.)
            signingConfig = if (releaseKeystoreAvailable) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("release keystore not configured — falling back to debug signing")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // R-21: Robolectric 需要 Android manifest/resources 在单元测试期可读。
        unitTests {
            isIncludeAndroidResources = true
            // 让 android.util.Log 等 framework stub 在 unit test 中返回默认值
            // 而非抛 RuntimeException("not mocked")，避免 DebugLog 等调用炸测试。
            isReturnDefaultValues = true
        }
    }
    // §bug8 lint 配置：abortOnError=true 保持严格，warningsAsErrors=false 让警告
    // 不阻断构建。HardcodedText 已完成 i18n 迁移，显式启用为 error 强制 0 字面量
    // 回退。MissingTranslation（R18 Phase 4 C-4）：en/zh 已 100% 对齐，重新启用
    // 强制未来 i18n 改动必须同步两侧，避免回归。
    lint {
        abortOnError = true
        warningsAsErrors = false
        // HardcodedText 已迁移完成，启用为 error；MissingTranslation（R18 Phase 4 C-4）
        // en/zh 已 100% 对齐，升级为 error 强制未来 i18n 改动必须同步两侧（maxer Final
        // 终审：此前仅默认 warning，与注释"重新启用强制"不一致）。
        error += setOf("HardcodedText", "MissingTranslation")
    }
}

// R-20 Phase 0：Room schema 导出目录（exportSchema=true 要求 KSP arg）。
// schemas/ 入 git（后续稳定后正式 Migration 用）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// R-22 / R18 Phase 5++ kover 覆盖率门槛（防回归 floor）。
// §R18 Phase 5++ 实测基线（2026-07-06，排除纯 @Composable/主题/Activity 后的
// unit-testable 集合，~995 新单测累计覆盖纯函数/util/拦截器/controllers/
// data.model/AppCore+Actions/Repository/ui.chat 可测 helper 等）：
//   Line 57.9% / Branch 55.1% / Instruction 54.9% / Method 56.4% / Class 56.9%
//
// **排除策略**（gpter Gate-5 路径 A）：filters.excludes 排除 35 个经逐文件审计确认
// 非 unit-testable 的类（纯 @Composable UI / 主题值 val / Activity·Application）。
// 排除标准：类的所有非 Composable helper 均为 `private`（不可直接 JVM 单测）——这类
// helper 若要单测需先提取为 `internal` 纯函数到独立文件（见下方"暂不提取"清单）。
// 混合文件若含 `internal` 可测 helper 则**保留**（helper 计入覆盖；同文件的
// @Composable 未覆盖部分**仍计入分母**——即保留类会因 Composable 未覆盖而拉低该类
// 覆盖率，这是为不误排可测 helper 而接受的代价，非"不惩罚 floor"）。
//
// **80% line 现状**：当前 unit-testable 集合 57.9%。继续纯 unit-test 估计可达 ~62-68%
// （dispatchEffect 22 分支逐 effect 全 mock + 剩余 Repository 边界 + private helper 提取）。
// 真正到 80% 需 androidTest + Compose UI test（ComposeTestRule/HiltAndroidRule）覆盖
// Composable，以及 Robolectric ActivityScenario/通知 shadow 覆盖 framework 代码——
// 列为 R19 独立 epic。详见 verify 块下方"剩余路径"。
kover {
    reports {
        // §R18 Phase 5+ (gpter/momo Gate-5 路径 A): exclude classes that are genuinely
        // NOT unit-testable via testDebugUnitTest, so the coverage floor applies to
        // unit-testable code. Audit categories:
        //   (1) pure @Composable UI — needs ComposeTestRule/androidTest;
        //   (2) theme value files (Color/Fonts/Motion/Dimens/Shape/Type/Theme) —
        //       pure `val`/Typography definitions;
        //   (3) Activity/Application (MainActivity/OpenCodeApp) — framework lifecycle.
        // 排除判定：类内非 Composable helper 全为 `private`（不可直接 JVM 单测）。
        //
        // MIXED 文件分两类处理：
        //  (a) 含 `internal` 可测 helper 的 → **保留**（ChatToolCards/ChatSessionTabStrip/
        //      ChatSubAgentCard/ChatPatchCards/ThinkingCapsule/ChatEmptyState/
        //      ChatRenderUtils/ToolCardClassifier/AgentTone/ChatUiTuning/
        //      ImageAttachmentLoader/PickerProviderFilter）。helper 计入覆盖；同文件
        //      @Composable 未覆盖**仍计入分母**（接受此代价以不误排可测 helper）。
        //  (b) 仅含 `private` helper 的 → 排除（下方"暂不提取"清单标注，R19 可提取为
        //      internal 纯函数后单测）。
        //
        // **暂不提取（R19 可拆为 internal 纯函数单测，~130 行）**：
        //   ChatScreen: currentSessionActivity/bestSessionActivityText/formatStatusFromPart/
        //                formatThinkingFromReasoningText
        //   ChatTextParts: fenceMarkerOf/splitCodeAndProse/codeText/codeFenceLanguage
        //   ChatInputBar: handleComposerSend
        //   ChatContextUsageDialog: formatCount/formatOptionalCount
        //   ChatMessageContent: markerLabelFor
        filters {
            excludes {
                classes = setOf(
                    // (1) pure @Composable UI screens/cards/rows (ui.chat) — 含 private
                    //     helper 的混合文件，按"暂不提取"处理（见上方清单）
                    "cn.vectory.ocdroid.ui.chat.ChatScreenKt",
                    "cn.vectory.ocdroid.ui.chat.ChatMessageContentKt",
                    "cn.vectory.ocdroid.ui.chat.ChatTextPartsKt",
                    // ChatTopBarKt: visiblePickerProviders 已提取到 PickerProviderFilter.kt
                    // （独立文件，保留计入覆盖）；ChatTopBarKt 剩余为纯 @Composable，排除。
                    "cn.vectory.ocdroid.ui.chat.ChatTopBarKt",
                    "cn.vectory.ocdroid.ui.chat.QuestionCardViewKt",
                    "cn.vectory.ocdroid.ui.chat.ChatInputBarKt",
                    "cn.vectory.ocdroid.ui.chat.ChatMessageRowKt",
                    "cn.vectory.ocdroid.ui.chat.ChatReasoningAndTodoKt",
                    "cn.vectory.ocdroid.ui.chat.MultiFilePatchAccordionKt",
                    "cn.vectory.ocdroid.ui.chat.ChatFileCardsKt",
                    "cn.vectory.ocdroid.ui.chat.ChatContextUsageDialogKt",
                    "cn.vectory.ocdroid.ui.chat.ChatContextUsageRingKt",
                    "cn.vectory.ocdroid.ui.chat.ChatServerManagementDialogKt",
                    "cn.vectory.ocdroid.ui.chat.ChatImageAttachmentStripKt",
                    "cn.vectory.ocdroid.ui.chat.ChatPermissionCardKt",
                    "cn.vectory.ocdroid.ui.chat.ChatMessageNavFabKt",
                    "cn.vectory.ocdroid.ui.chat.MetadataMarkerKt",
                    // ui.sessions (Composable)
                    "cn.vectory.ocdroid.ui.sessions.SessionsScreenKt",
                    "cn.vectory.ocdroid.ui.sessions.DirectoryPickerKt",
                    // ui.settings (Composable)
                    "cn.vectory.ocdroid.ui.settings.SettingsScreenKt",
                    "cn.vectory.ocdroid.ui.settings.SettingsSectionsKt",
                    "cn.vectory.ocdroid.ui.settings.ModelManagementSectionKt",
                    "cn.vectory.ocdroid.ui.settings.DebugLogSectionKt",
                    "cn.vectory.ocdroid.ui.settings.HostProfilesManagerScreenKt",
                    "cn.vectory.ocdroid.ui.chat.TodoListPanelKt",
                    // (2) theme value files (pure val / Typography definitions)
                    "cn.vectory.ocdroid.ui.theme.ColorKt",
                    "cn.vectory.ocdroid.ui.theme.FontsKt",
                    "cn.vectory.ocdroid.ui.theme.MotionKt",
                    "cn.vectory.ocdroid.ui.theme.DimensKt",
                    "cn.vectory.ocdroid.ui.theme.ShapeKt",
                    "cn.vectory.ocdroid.ui.theme.TypeKt",
                    "cn.vectory.ocdroid.ui.theme.ThemeKt",
                    "cn.vectory.ocdroid.ui.theme.SemanticColorsKt",
                    // (3) Activity / Application (framework lifecycle) — 已排除，不计入
                    //     下方"剩余路径"清单
                    "cn.vectory.ocdroid.MainActivity",
                    "cn.vectory.ocdroid.OpenCodeApp"
                )
            }
        }
        verify {
            rule("min coverage floor (unit-testable; Composable/theme/framework excluded)") {
                // §R18 Phase 5++ 实测基线（排除后 unit-testable 集合）：
                //   Line 57.9% / Branch 55.1% / Instruction 54.9%（1697 单测）
                // 阈值 = 实测 - 约 3pp 缓冲（防回归）。
                //
                // **80% line 剩余路径**（unit-testable 集合内，未排除的代码）：
                // 剩余未覆盖行主要分布于：(1) AppLifecycleMonitor（134 行，需 Robolectric
                // 通知框架 shadow）；(2) ui.chat 保留混合文件的 @Composable 部分（~1500 行，
                // 需 ComposeTestRule/androidTest）；(3) SessionSyncCoordinator/AppCore
                // dispatchEffect 的剩余分支（需逐 ControllerEffect 全 mock，可纯 unit-test）；
                // (4) 上方"暂不提取"清单的 private helper（~130 行，提取为 internal 后可单测）。
                // 注：MainActivity/OpenCodeApp/Hilt 注入器已在本 filters.excludes 排除，
                // 不在此清单内。继续纯 unit-test 估计可达 ~62-68%；80% 需 androidTest +
                // Compose UI test（R19 epic）。
                // R-19 Sprint 2: floor raised 55/52/52 -> 60/56/52 after P2-2 dispatcher
                // routing tests + #7(b) helper extraction pushed unit-testable to 61.4/57.8.
                minBound(60, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE, kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
                minBound(56, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH, kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
                minBound(52, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.INSTRUCTION, kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
            }
        }
    }
}

// R-22 §bug7: wire koverVerify into the `check` lifecycle task so the coverage
// floor is enforced by any invocation of `./gradlew check` (and CI), not only
// by an explicit `./gradlew koverVerify`. check.sh runs compileDebugKotlin +
// testDebugUnitTest directly (not `check`), so this does not retro-fit kover
// onto the default check.sh path — it closes the gap for `check`/CI callers.
tasks.named("check").configure { dependsOn("koverVerify") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.security.crypto)
    
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // v2 §3.1: code-block syntax highlight (highlightedCodeBlock / highlightedCodeFence).
    implementation(libs.markdown.renderer.code)
    implementation(libs.androidx.compose.material3.windowsizeclass)

    // R-20 Phase 0：加密持久化缓存（SQLCipher via Room SupportFactory）。
    // Room 用 KSP 处理器；SQLCipher 4.16.0 AAR 自带 consumer-rules（见 proguard-rules.pro）。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.sqlcipher.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    // R-01 SSL 行为测试：HandshakeCertificates / HeldCertificate 用来给 MockWebServer
    // 起自签名 HTTPS，验证 allowInsecureConnections=true/false 的双分支行为。
    testImplementation(libs.okhttp.tls)
    // R-21 SettingsManager 单测：Robolectric 提供 AndroidKeyStore shadow，
    // 让真 EncryptedSharedPreferences 可在 JVM unit test 中跑（Tink + MasterKey）。
    testImplementation(libs.robolectric)
    // R-21: ApplicationProvider.getApplicationContext（Robolectric 测试取 Context）
    testImplementation(libs.androidx.test.core)
    // R-20 Phase 0：CacheDatabaseTest 用 Room in-memory（不挂 SupportFactory，
    // Robolectric 跑不了真 SQLCipher native lib）。
    testImplementation(libs.androidx.room.testing)
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
