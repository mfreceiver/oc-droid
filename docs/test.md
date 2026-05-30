# Android Test Notes

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
