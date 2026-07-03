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
        versionCode = 19
        versionName = "0.2.14"

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

    signingConfigs {
        create("release") {
            // Credentials live in gitignored local.properties (see docs/build-apk.md).
            val props = Properties()
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
}

// R-22 kover 覆盖率门槛（防回归用）：阈值按 koverHtmlReport 实测基线设的略低值，
// 仅供 `./gradlew koverVerify` 主动调用检查，**未** 接入 `check` 强制阻塞。
// 实测基线（添加 R-21 测试前的本机运行）：
//   Class 43.1% / Method 26.8% / Branch 23.7% / Line 25.4% / Instruction 22.8%
// 所设阈值 = 实测 - 约 1pp 缓冲，目的为"防无声下滑"而非"强制提升"。
// 注：kover 0.9.x API 改用 reports { verify { rule { minBound(...) } } }，
// 不再是旧版的 currentProject { verification { lineCoverage } }。
kover {
    reports {
        verify {
            rule("min coverage floor") {
                minBound(25, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE, kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
                minBound(23, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH, kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
                minBound(22, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.INSTRUCTION, kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE)
            }
        }
    }
}

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
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
