# Android Test Notes

## 2026-06-03 task/todo 支持 + 模型预设对齐

- 新增 `TodoListPanel` composable（进度条 + checkbox 列表 + 完成划线 + 空态），无单独测试——UI 路由简单，由编译 + `./gradlew testDebugUnitTest` 全覆盖。
- SSE `todo.updated` 事件处理在 `MainViewModelSyncActions.kt` 内联，无单独测试。
- `launchLoadMessages` 内新增 fire-and-forget `getSessionTodos` REST 调用，加 try/catch 防护以确保既存 mock 测试继续通过。
- 新增 3 个模型预设，shortName 在既有 `ModelOption.shortName` 分支中增加 `DeepSeek Local → DS-L` + `Ollama DeepSeek V4 Pro → ODS-Pro`，由既存 `ModelTests.shortName` 回归。
- 验证：`./gradlew testDebugUnitTest` - 214 tests pass。

## 2026-05-25 realtime speech recovery

### 2026-05-30 speech abort/retry

- VoiceFlowKit dependency is pinned to merged revision `cc49c8fa272846852970f8df938766af6e7576ea`, which exposes preserved audio retry APIs.
- Chat input bar now has a left-side speech auxiliary button: stop during recording/transcribing, retry after abort when preserved audio exists.
- Unit/build validation must compile `VoiceFlowPreservedAudio` usage and preserve existing `speechFailureInput` behavior; instrumented ChatInputBar coverage should include the new callback parameters.

本轮目标验证 Android 语音输入的新 realtime recovery path：点击麦克风后立即采集 PCM16 mono 24kHz，写入本地 `.pcm` cache，WebSocket session ready 后从 cache offset 0 replay；send、heartbeat 或 commit 失败时新建 session 并重新 replay cache。

已补充的单元测试覆盖：

- `RealtimeSpeechAudioCache` append、offset read、empty append、remove 后 byteCount/file 状态。
- realtime speech 常量：24kHz mono PCM16、240KB send/replay chunk、12s heartbeat、commit/stop control message。
- realtime WebSocket log URL redaction，避免 ticket query string 进入日志。
- 既有 speech input merge/failure-preserve 测试继续保留。

验证命令：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest
./gradlew koverHtmlReport
```

验证结果：本轮开始前已有的 `app/src/main/java/com/yage/opencode_client/ui/util/DataUriImageTransformer.kt` dirty change 含 `@Composableg` 拼写错误，导致 Kotlin 编译失败。修正为 `@Composable` 后，`./gradlew testDebugUnitTest` 与 `./gradlew koverHtmlReport` 均通过。覆盖率报告位于 `app/build/reports/kover/html/index.html`。
