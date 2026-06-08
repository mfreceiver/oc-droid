# AGENTS.md - opencode_android_client

## Build Environment

终端默认可能找不到 Java，导致 `./gradlew` 失败。使用 Android Studio 自带的 JDK：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# For integration tests (adb)
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
```

**持久化**：在 `~/.zshrc` 中加入上述 `JAVA_HOME` 和 `PATH` 两行，然后 `source ~/.zshrc`。

## Run / Module not found

若 Run 报 "Module not found"：File → Sync Project with Gradle Files；若仍失败，File → Invalidate Caches / Restart。Run 配置使用 module `opencode_client.app`（对应 settings.gradle.kts 的 rootProject.name + `:app`）。

## Test Commands

- Unit tests: `./gradlew testDebugUnitTest`
- Coverage report: `./gradlew koverHtmlReport` → `app/build/reports/kover/html/index.html`
- Integration tests: `./gradlew connectedDebugAndroidTest` (requires .env with OPENCODE_* credentials)

## Device Safety

- Do not run `connectedDebugAndroidTest`, install, or launch debug builds on a physical Android phone unless explicitly asked. Physical devices may contain the user's active app settings and credentials; installing test builds can overwrite them.
- For UI/instrumented tests, use an emulator only. If both emulator and physical devices are connected, target the emulator explicitly with `ANDROID_SERIAL=<emulator-id>` or an equivalent Gradle/adb device selection.
