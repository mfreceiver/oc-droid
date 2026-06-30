# R-20 RFC: 统一错误处理策略

**状态**: Draft  
**日期**: 2026-06-30  
**关联**: R-14 (runSuspendCatching), R-16 (拆 ViewModel), R-21 (SSEClient/SettingsManager 测试)

---

## 1. 现状盘点

当前仓库错误处理 4 种风格混用，无统一策略文档。以下盘点所有包含 `catch`/`runCatching`/`onFailure`/`getOrNull` 的位置（排除纯代码模式匹配如 `groupValues?.getOrNull(1)`）。

### 盘点表

| # | 文件:行 | 当前代码 | 风格 | 分类 | 协程上下文? | 应改造为 |
|---|---------|----------|------|------|-------------|----------|
| **P0 — 吞 CancellationException** | | | | | | |
| 1 | `SSEClient.kt:199` | `catch (_: Throwable)` | 1 | ✅ 正确 — 独立启动的子协程，隔离崩溃 | 是(launch) | 不变（隔离型，注释标注即可） |
| 2 | `MainViewModelSessionActions.kt:423` | `try { ... } catch (_: Exception) {}` | 2 | **P0 缺陷** | 是(launch) | M1: 改为 `catch (e: Exception) { if (e is CancellationException) throw e; /* 无操作 */ }` |
| 3 | `MainViewModel.kt:1357-1359` | `catch (e: Exception) { if (e is CancellationException) throw e; ... }` | 2 | ✅ 正确 — 已 rethrow CE（编排者 2026-06-30 终审核实，原盘点描述失准，见 §1.1） | 是(launch) | 不变 — 已正确处理 |
| 4 | `MainViewModelSyncActions.kt:364` | `catch (_: Exception) { return }` | 2 | **P0 缺陷** | 否(纯解析) | M1: 加注释标注「非协程上下文，无需 rethrow CE」 |
| **P1 — Log.w + return 静默丢弃** | | | | | | |
| 5 | `MainViewModel.kt:1921-1923` loadPendingPermissions | `onFailure { Log.w(...) }` | 3 | UserAction | 否 | M1: 改为 `_state.error = ...` 用户可见 |
| 6 | `MainViewModel.kt:1933-1935` loadPendingQuestions | `onFailure { Log.w(...) }` | 3 | UserAction | 否 | M1: 同 #5 |
| 7 | `MainViewModel.kt:1947-1949` replyQuestion | `onFailure { Log.w(...); onError() }` | 3 | UserAction | 是(launch) | M1: `_state.error = ...` |
| 8 | `MainViewModel.kt:1962-1964` rejectQuestion | `onFailure { Log.w(...) }` | 3 | UserAction | 是(launch) | M1: `_state.error = ...` |
| **P2 — runCatching.getOrNull() 静默丢弃** | | | | | | |
| 9 | `MainViewModel.kt:750` openSessionFromDeepLink | `runCatching { …getOrNull() }.getOrNull()` | 4 | ProgressiveEnhancement | 是(launch, withContext) | ✅ 已有 `_state.error` 下游兜底; 保留 + 注释 |
| 10 | `MainViewModel.kt:1385` openSubAgent | `runCatching { …getOrNull() }.getOrNull()` | 4 | ProgressiveEnhancement | 是(launch) | ✅ 已有 `_state.error` 下游; 注释 |
| 11 | `MainViewModelSupport.kt:57` parseSessionCreatedEvent | `runCatching { ... }.getOrNull()` | 4 | ✅ 正确 — SSE Payload 解析丢失事件后续补 | 否 | ✅ 不变 — 非协程; 注释标注 Parse 分类 |
| 12 | `MainViewModelSupport.kt:66` parseSessionUpdatedEvent | 同上 | 4 | ✅ 正确 — 同上 | 否 | ✅ 不变 |
| 13 | `MainViewModelSupport.kt:151` parseSessionStatusEvent | 同上 | 4 | ✅ 正确 — 同上 | 否 | ✅ 不变 |
| 14 | `MainViewModelSupport.kt:178` parseQuestionAskedEvent | 同上 | 4 | ✅ 正确 — 同上 | 否 | ✅ 不变 |
| 15 | `MainViewModelSyncActions.kt:220` message.updated SSE | `runCatching { ... }.getOrNull()` | 4 | ✅ 正确 — SSE Payload 解析丢失事件后续补 | 否 | ✅ 不变 |
| 16 | `MainViewModelSessionActions.kt:484` probeLatestMessageId | `repository.xxx().getOrNull()` | 4 | ✅ 正确 — catchUp 探针逻辑依赖 null | 否 | ✅ 不变 (Result.getOrNull 是合理用法) |
| 17 | `SSEClient.kt:172` sanitizedLogUrl | `runCatching { parse }.getOrNull() ?: "<redacted>"` | 4 | ✅ 正确 — 日志安全降级 | 否 | ✅ 不变 |
| 18 | `OpenCodeRepository.kt:114` httpCache | `runCatching { Cache(...) }.getOrNull()` | 4 | ✅ 正确 — 缓存降级 | 否(属性初始化) | ✅ 不变 |
| **P3 — SSE Payload 解析静默丢弃** | | | | | | |
| 19 | `SSEClient.kt:151` | `catch (_: Exception) {}` | 2 | ProgressiveEnhancement | **不确定** (callback 线程，非协程作用域) | ✅ 不变 — OkHttp EventSourceListener 非协程上下文; 注释标注 |
| 20 | `MarkdownWebPreviewPane.kt:242` | `runCatching { JSONObject(msg) }.getOrNull() ?: return` | 4 | ✅ 正确 — 解析失败静默 | 否(浏览器回调) | ✅ 不变 |
| **P4 — 静默 try/catch 正确范式** | | | | | | |
| 21 | `DebugLog.kt:65` | `runCatching { android.util.Log.* }` | 4 | ✅ 正确 — 日志本身不能抛异常 | 否 | ✅ 不变 |
| 22 | `CrashLogger.kt:33-36` | `try { writeCrash } catch (_: Throwable) {}` | 2 | ✅ 正确 — 崩溃记录器不能遮盖原崩溃 | 否 | ✅ 不变 |
| 23 | `CrashLogger.kt:53` | `catch (_: Throwable) { "unknown" }` | 2 | ✅ 正确 — 非协程上下文，降级展示 | 否 | ✅ 不变 |
| 24 | `CrashLogger.kt:77` | `runCatching { it.delete() }` | 4 | ✅ 正确 — 文件清理降级 | 否 | ✅ 不变 |
| 25 | `TrafficLogger.kt:90` | `catch (ex: Exception) { Log.e(...) }` | 2 | ✅ 正确 — 流量日志写入失败不阻断请求 | 否 | ✅ 不变 |
| 26 | `MainActivity.kt` ON_START catch-up | - | N/A | N/A | N/A | 非错误处理代码 |
| 27 | `AppLifecycleMonitor.kt:187-194` | `runCatching { ... }.onFailure { Log.w(...) }` | 4/3 | CancelSilently | 是(suspend) | M1: `runSuspendCatching` + 静默（后台轮询） |
| 28 | `AppLifecycleMonitor.kt:251,266` | `runCatching { notificationManager.notify(...) }` | 4 | ✅ 正确 — 通知降级 | 否 | ✅ 不变 |
| 29 | `AppLifecycleMonitor.kt:311-328` | `runCatching { createChannels }.onFailure { Log.w }` | 4 | ✅ 正确 — 渠道创建降级 | 否 | ✅ 不变 |
| 30 | `SettingsManager.kt:140,161,184,198,207,235,244` | `catch (e: Exception) { Log.w + default }` | 2 | ✅ 正确 — 持久化解析降级 | 否 | ✅ 不变; M3 统一使用 `DebugLog.w` |
| 31 | `FilePreviewPane.kt:328` | `catch (_: IllegalArgumentException) {}` | 2 | ✅ 正确 — Data URI 解码降级 | 否 | ✅ 不变 |
| 32 | `MarkdownWebPreviewPane.kt:114` | `catch (e: Exception) { ... }` | 2 | ✅ 正确 — 图片解析降级有 fallback | 否 | ✅ 不变 |
| 33 | `MarkdownWebPreviewPane.kt:183` | `runCatching { context.startActivity(intent) }` | 4 | ✅ 正确 — Intent 启动降级 | 否 | ✅ 不变 |
| 34 | `OpenCodeApp.kt:43` | `runCatching { WebView(...) }` | 4 | ✅ 正确 — 预热降级 | 否 | ✅ 不变 |
| 35 | `SettingsScreen.kt:201` | `runCatching { deleteHostProfile }.onFailure { }` | 4 | UserAction | 是(Compose onClick) | M1: 改为通过 ViewModel 层的标准错误通道 |
| **P5 — ViewModel 核心错误 | ** | | | | | |
| 36 | `MainViewModelConnectionActions.kt:76` testConnection | `onFailure { _state.error = errorMessageOrFallback(...) }` | 5 | FatalNetwork | 是(launch) | ✅ 已有正确范式 |
| 37 | `MainViewModelSessionActions.kt:131` loadSessions | 同上 | 5 | FatalNetwork | 是(launch) | ✅ 已有正确范式 |
| 38 | `MainViewModelSessionActions.kt:198` loadMoreSessions | 同上 | 5 | Recoverable | 是(launch) | ✅ 已有正确范式 |
| 39 | `MainViewModelSessionActions.kt:219` loadSessionStatus | `onFailure { reportNonFatalIssue(...) }` | 5 | CancelSilently | 是(launch) | ✅ 已有正确范式 |
| 40 | `MainViewModelSessionActions.kt:402` loadMessages | `onFailure { _state.error = ... }` | 5 | FatalNetwork | 是(launch) | ✅ 已有正确范式 |
| 41 | `MainViewModelSessionActions.kt:562` catchUp | `onFailure { reportNonFatalIssue(...) }` | 5 | Recoverable | 是(launch) | ✅ 已有正确范式 |
| 42 | `MainViewModelSessionActions.kt:645` closeGap | 同上 | 5 | Recoverable | 是(launch) | ✅ 已有正确范式 |
| 43 | `MainViewModelSessionActions.kt:724` loadMoreMessages | 同上 | 5 | Recoverable | 是(launch) | ✅ 已有正确范式 |
| 44 | `MainViewModelSessionActions.kt:748` loadProviders | `onFailure { onNonFatalError(...) }` | 5 | CancelSilently | 是(launch) | ✅ 已有正确范式 |
| 45 | `MainViewModelSessionActions.kt:767` createSession | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 46 | `MainViewModelSessionActions.kt:787` forkSession | 同上 | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 47 | `MainViewModelSessionActions.kt:810` setSessionArchived | 同上 | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 48 | `MainViewModelSessionActions.kt:867` deleteSession | 同上 | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 49 | `MainViewModelSessionActions.kt:913` sendMessage | 同上 | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 50 | `MainViewModelSyncActions.kt:34` SSE flow catch | `catch { Log.e(...); _state.error = ... }` | 5 | FatalNetwork | 是(launch) | ✅ 已有正确范式 |
| 51 | `MainViewModelSyncActions.kt:40` SSE event failure | `onFailure { Log.e(...); _state.error = ... }` | 5 | FatalNetwork | 是(launch) | ✅ 已有正确范式 |
| 52 | `MainViewModel.kt:1168-1173` executeCommand | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 53 | `MainViewModel.kt:1096-1106` loadCommands | `onFailure { reportNonFatalIssue(...); fallback }` | 5 | CancelSilently | 是(launch) | ✅ 已有正确范式 |
| 54 | `MainViewModel.kt:1550-1567` loadAgents | `onFailure { reportNonFatalIssue(...) }` | 5 | CancelSilently | 是(launch) | ✅ 已有正确范式 |
| 55 | `MainViewModel.kt:1823` abortSession | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 56 | `MainViewModel.kt:1868` editFromMessage | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 57 | `MainViewModel.kt:1909` respondPermission | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 58 | `MainViewModel.kt:2089-2101` activateTunnel | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 (有显式 CE rethrow) |
| 59 | `MainViewModel.kt:1718` draft session create | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 60 | `MainViewModel.kt:1793` unarchive before send | `onFailure { _state.error = ... }` | 5 | UserAction | 是(launch) | ✅ 已有正确范式 |
| 61 | `FilesViewModel.kt:107` loadPreview | `onFailure { fallback to directory }` | 5 | ProgressiveEnhancement | 是(launch) | ✅ 已有正确范式 |
| 62 | `FilesViewModel.kt:130` loadDirectoryPreview | `onFailure { _state.error = throwable.message }` | 5 | UserAction | 是(launch) | M1: 用 `errorMessageOrFallback` 统一 fallback |
| 63 | `FilesViewModel.kt:152` loadFiles | `onFailure { Log.e("OC_ERROR", ...); _state.error = ... }` | 5 | UserAction | 是(launch) | M1: 用 `DebugLog.e` 替代 `Log.e("OC_ERROR")` |
| 64 | `FilesViewModel.kt:191` loadFileContent | `onFailure { _state.error = throwable.message }` | 5 | UserAction | 是(launch) | 同 #62 |
| **P6 — 硬编码中文错误消息** | | | | | | | | |
| 65 | `MainViewModel.kt:2060` activateTunnel prep | `"未设置隧道密码"` | N/A | 国际化 | N/A | M3: 改为 sealed class MessageKey |
| 66 | `MainViewModel.kt:2070` activateTunnel blank pass | `"隧道密码为空"` | N/A | 国际化 | N/A | M3: 同上 |
| 67 | `MainViewModel.kt:1395` openSubAgent null child | `"子任务会话不可用"` | N/A | 国际化 | N/A | M3: 同上 |
| 68 | `MainViewModel.kt:33` TUNNEL_SUCCESS_TOAST | `"隧道激活成功"` | N/A | 国际化 | N/A | M3: 同上 |
| 69 | `SSEClient.kt:30` SSEConnectionExhausted | `"SSE 长时间无法连接…"` | N/A | 国际化 | N/A | M3: 异常改为中性 key; 消息由 UI 层解析 |
| 70 | 各处 `"Failed to ..."` / `"Connection failed"` 等英文硬编码 | N/A | 国际化 | N/A | M3: 统一改为资源引用 |

### 1.1 编排者更正（2026-06-30 终审核实）

> 终审阶段（gpter 7/9 + glmer 6.7/9）对 R-14 P0「三处吞 CancellationException」
> 的定位做了源码复核，结论如下（同步反映到 §6 M1.3 清单）：

- **#3 `MainViewModel.kt:1357-1359`**：编排者核实该处**已正确 rethrow
  `CancellationException`**（`catch (e: Exception) { if (e is
  CancellationException) throw e; ... }`）。上表原"当前代码/分类"列描述失准，
  **并非 P0 缺陷**，降级为 ✅ 正确范式，不纳入 R-14 修复范围。
- **#2 `MainViewModelSessionActions.kt:423`**：确为真问题，**已在 ui-lane 修复**
  （`getSessionTodos` 的 `catch(_:Exception){}` 改为 rethrow CE）。
- **Repository 层 `runCatching`**：已在 data-lane 由 `runSuspendCatching` 统一替换
  （R-14 repo 侧），消除 Repository 内所有协程上下文的 CE 吞。
- **#4 `MainViewModelSyncActions.kt:364`**：确认为**非 suspend 的 JSON decode**
  （`todo.updated` payload 解析），无 `Job` 可取消，**豁免**（与 §2.1 Parse 分类一致）。

**结论**：R-14 真实修复面 = #2（SessionActions，已修）+ Repository 全量
`runSuspendCatching`（已修）。#3 误报撤销，#4 豁免。§6 M1.3 的「3 处」清单据此
修正为 **1 处必修（已修）+ 1 处豁免注释 + 1 处误报撤销**。

---

## 2. 错误分类策略

### 2.1 分类定义

| 分类 | 说明 | Surface `_state.error`? | DebugLog.w? | Toast? | 监控计数? | CancellationException? |
|------|------|------------------------|-------------|--------|-----------|------------------------|
| **FatalNetwork** | 连接失败、SSE 流中断、HTTP 5xx 无法重试 | ✅ 是 | ✅ DebugLog.e | ✅ Error toast | ✅ 是 (connect failure counter) | ✅ **必须 rethrow** (协程上下文内) |
| **Recoverable** | 网络超时可重试、临时 429/503、loadMore 分页失败 | ❌ 否 (重试逻辑自行处理) | ✅ DebugLog.w | ❌ 否 | ✅ 是 (retry counter) | ✅ **必须 rethrow** (协程上下文内) |
| **UserAction** | 用户触发的操作失败 (发送消息、创建会话、回复权限等) | ✅ 是 | ✅ DebugLog.w | ✅ Error toast | ❌ 否 | ✅ **必须 rethrow** (协程上下文内) |
| **ProgressiveEnhancement** | 非关键功能的降级：加载子会话、加载 todos、解析非关键 SSE payload、loadCommands 失败 | ❌ 否 (静默降级) | ✅ DebugLog.w (仅首次或抽样) | ❌ 否 | ❌ 否 | ✅ **必须 rethrow** (协程上下文内) |
| **CancelSilently** | 后台轮询失败、session status 刷新失败 — 不需要用户感知 | ❌ 否 | ✅ DebugLog.w | ❌ 否 | ❌ 否 | ✅ **必须 rethrow** (协程上下文内) |
| **Parse** | JSON 解析失败 (SSE payload、持久化数据恢复、Data URI 解码) | ❌ 否 | ❌ DebugLog.d (低优先级) | ❌ 否 | ❌ 否 | **可豁免** — 非协程上下文 |

### 2.2 分类决策树

```
错误发生在协程上下文中?
├─ 是 → 抛出 CancellationException? → 是 → throw (不处理)
│        └─ 否 → 影响核心业务流程? → 是 → FatalNetwork / UserAction
│                  └─ 否 → 可自动重试? → 是 → Recoverable
│                            └─ 否 → 用户主动触发? → 是 → UserAction
│                                      └─ 否 → 渐进增强特性? → 是 → ProgressiveEnhancement
│                                                └─ 否 → 后台静默任务? → CancelSilently
└─ 否 (非协程上下文) → 最多 Parse 分类，静默降级即可
```

---

## 3. 统一抽象: `AppErrorHandler`

### 3.1 API 设计

```kotlin
/**
 * 应用级错误处理中枢。
 *
 * 统一了 DebugLog 写入、_state.error 设值、toast 触发、监控计数。
 * ViewModel / Coordinator 通过依赖注入获取单例。
 *
 * 与 [runSuspendCatching] 的协同：
 *   - [runSuspendCatching] 负责 CancellationException 安全包装（R-14 前置）
 *   - [AppErrorHandler] 负责错误分发到各通道（日志/状态/toast/计数）
 *   - 典型用法：
 *     repository.apiCall()
 *         .onFailure { errorHandler.handle(error, ErrorClass.UserAction) }
 *     // 或结合 runSuspendCatching 简写：
 *     runSuspendCatching { repository.apiCall() }
 *         .onFailure { errorHandler.handle(it, ErrorClass.UserAction) }
 */
interface AppErrorHandler {

    /** 严重错误：网络/SSE 中断，用户必须感知 */
    fun handleFatal(error: Throwable, context: String)

    /** 非严重但影响当前操作：用户触发的动作失败 */
    fun handleUserAction(error: Throwable, context: String, restoreDraft: (() -> Unit)? = null)

    /** 不向用户暴露的降级：仅写入日志 */
    fun handleSilent(error: Throwable, context: String, logLevel: DebugLog.Level = DebugLog.Level.WARN)

    /** 渐进增强降级：静默降级但可抽样告警 */
    fun handleProgressiveEnhancement(error: Throwable, context: String)

    /** 最佳尝试：即使失败也完全不影响功能 */
    fun bestEffort(block: () -> Unit)
}
```

### 3.2 实现要点

- 持有 `MutableStateFlow<AppState>` 的写入引用（由 ViewModel/Coordinator 注入或作为参数传入）
- 持有 `DebugLog` 和 toast 触发器的引用
- 监控计数器可选：初期用 `AtomicLong` 累计各分类错误数，通过 Settings 页暴露
- `bestEffort` 等价于 `runCatching { block() }` 的非协程安全版

### 3.3 与 `runSuspendCatching` 的关系

```
                    调用链
    
    协程 suspend block
        │
        ▼
    runSuspendCatching { ... }     ← R-14 提供：正确 rethrow CE
        │                             返回 Result<T>（失败侧 CE 已隔离）
        ▼
    .onFailure { error ->
        errorHandler.handle(error,   ← R-20 提供：统一分发
            ErrorClass.UserAction)
    }
```

**前置依赖**: R-14 (`runSuspendCatching`) 必须在 R-20 落地前就绪，否则 `AppErrorHandler` 处理的错误可能包含被吞的 `CancellationException`。

### 3.4 错误分类 sealed class (区别于裸 Throwable)

```kotlin
/** 业务错误标记，供 AppErrorHandler 及 UI 消息解析使用 */
sealed class AppError(
    override val message: String,
    val cause: Throwable? = null
) : Exception(message, cause) {

    /** 网络/SSE 致命错误 */
    data class FatalNetwork(val original: Throwable, val context: String)
        : AppError(message = original.message ?: "Network error", cause = original)

    /** 用户操作失败 */
    data class UserAction(val original: Throwable, val context: String)
        : AppError(message = original.message ?: "Action failed", cause = original)

    /** 渐进增强降级 */
    data class ProgressiveEnhancement(val original: Throwable, val context: String)
        : AppError(message = original.message ?: "Feature unavailable", cause = original)

    /** 业务校验失败（如未设置隧道密码）— 非 Exception，用 MessageKey 表达 */
    data class BusinessRule(val key: MessageKey)
        : AppError(message = key.name)
}
```

使用 `sealed class` 而非裸 `Throwable` 的好处：
- `when` 完备性检查确保所有分类都有处理分支
- 可携带 `MessageKey` 供 UI 国际化解析
- `cause` 保留原始异常栈用于 DebugLog

---

## 4. CancellationException 全局策略

### 4.1 判定规则

| 上下文 | 规则 | 依据 |
|--------|------|------|
| 协程作用域内 `catch(Throwable)` / `catch(Exception)` | **必须** rethrow `CancellationException` | Kotlin 结构化并发协议 |
| 非协程的回调线程 (OkHttp `EventSourceListener`、`Thread.setDefaultUncaughtExceptionHandler`) | 可豁免 — `CancellationException` 不会到达此处 | 这些回调不在 `Job` 作用域内 |
| 纯数据转换 (JSON decode、Data URI parse、字符串操作) | 可豁免 — 不在协程上下文中 | 无 `Job` 可取消 |
| `withContext(Dispatchers.IO)` 内 | **必须** rethrow | 仍是协程作用域的子上下文 |

### 4.2 实现: `runSuspendCatching` (R-14 前置)

```kotlin
/**
 * 替代标准库 runCatching 的协程安全版。
 *
 * 标准库 runCatching 等价于 try/catch(Throwable)，会吞 CancellationException。
 * 此函数 catch(Throwable) 后检查 is CancellationException → throw (不做包装)。
 *
 * R-20 代码审查规则：
 *   - 所有协程作用域内的 runCatching { } 必须替换为 runSuspendCatching { }
 *   - 所有协程作用域内的 catch(Throwable) / catch(Exception) 必须添加 CE rethrow
 */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}
```

### 4.3 代码审查检查清单

- [ ] 所有 `catch(Throwable)` / `catch(Exception)` 是否在协程作用域内？
- [ ] 是 → 是否有 `if (e is CancellationException) throw e` 守卫？
- [ ] 所有 `runCatching { }` 是否在 `suspend` 函数或 `launch {}` 内？
- [ ] 是 → 是否已替换为 `runSuspendCatching { }`？

---

## 5. ViewModel 用户消息国际化

### 5.1 问题

当前 ViewModel 业务层硬编码了 4 处中文错误字符串和大量英文 `"Failed to ..."` 前缀。ViewModel 不应硬编码展示文案。

### 5.2 MessageKey 设计

```kotlin
/**
 * 业务错误/提示消息的语义键。ViewModel 只断言语义，
 * UI 层根据当前 locale 通过 stringResource 解析为对应的展示文案。
 */
enum class MessageKey {
    // 网络/连接
    ConnectionFailed,
    SseExhausted,
    SseCollectionFailed,

    // 会话操作
    SessionCreateFailed,
    SessionDeleteFailed,
    SessionForkFailed,
    SessionArchiveFailed,
    SessionRestoreFailed,
    SessionUnarchiveBeforeSendFailed,
    SessionSendFailed,
    SessionEditFailed,
    SessionAbortFailed,
    SubAgentUnavailable,

    // 隧道
    TunnelNoPassword,
    TunnelPasswordBlank,
    TunnelActivationFailed,
    TunnelActivationSuccess,

    // 权限/问题/命令
    PermissionRespondFailed,
    LoadPermissionsFailed,
    LoadQuestionsFailed,
    QuestionReplyFailed,
    QuestionRejectFailed,
    CommandFailed,
    NoSessionForCommand,
    ConnectionTargetMissing,
}
```

### 5.3 strings.xml 示例

```xml
<!-- values/strings.xml (中文) -->
<string name="error_connection_failed">连接服务器失败</string>
<string name="error_sse_exhausted">SSE 长时间无法连接，消息可能不是最新</string>
<string name="error_session_create_failed">创建会话失败</string>
<string name="error_tunnel_no_password">未设置隧道密码</string>
<string name="error_tunnel_password_blank">隧道密码为空</string>
<string name="error_tunnel_activation_success">隧道激活成功</string>
<string name="error_tunnel_activation_failed">隧道激活失败</string>
<string name="error_sub_agent_unavailable">子任务会话不可用</string>

<!-- values-en/strings.xml (English) -->
<string name="error_connection_failed">Failed to connect to server</string>
<string name="error_sse_exhausted">SSE disconnected for too long, messages may be stale</string>
...
```

### 5.4 使用方式

```kotlin
// ViewModel 层
_state.update {
    it.copy(error = MessageKey.TunnelNoPassword)
}

// UI 层 (Composable)
@Composable
fun resolveErrorMessage(key: Any?): String? = when (key) {
    is MessageKey -> when (key) {
        MessageKey.TunnelNoPassword -> stringResource(R.string.error_tunnel_no_password)
        else -> stringResource(R.string.error_unknown)
    }
    is AppError -> resolveErrorMessage(key.messageKeyOrNull) ?: key.message
    is String -> key  // 过渡期兼容
    else -> null
}
```

### 5.5 过渡策略

- M2 阶段：`AppState.error` 类型从 `String?` 改为 `Any?`（兼容 `String` / `MessageKey` / `AppError`）
- M3 阶段：全量迁移为 `MessageKey` + `AppError`，移除所有硬编码中文/英文字符串
- 过渡期 UI 层用 `when (error) { is String -> error; is MessageKey -> stringResource(...) }` 双解

---

## 6. 落地路径

### M1: 基础设施 (R-14 同步)
**目标**: 抽 `AppErrorHandler` + 接入 `_state.error` + 修复 3 处 CE 吞

- [ ] M1.1 实现 `runSuspendCatching` (R-14)
- [ ] M1.2 在 `OpenCodeRepository` 全量替换 `runCatching` → `runSuspendCatching` (约 30 处)
- [ ] M1.3 修复 3 处 CE 吞:
  - `MainViewModelSessionActions.kt:423` (getSessionTodos)
  - `MainViewModel.kt:1359` (loadChildSessions)
  - `MainViewModelSyncActions.kt:364` (todo.updated) — 非协程上下文，仅加注释
- [ ] M1.4 将 `Log.w + return` 的 4 处 (loadPendingPermissions / loadPendingQuestions / replyQuestion / rejectQuestion) 改为 `_state.error` 写入
- [ ] M1.5 实现 `AppErrorHandler` 基础版 (handleFatal / handleSilent / bestEffort)，接入 `MainViewModel` 和 `FilesViewModel`
- [ ] M1.6 将 `AppState.error` 类型从 `String?` 改为 `Any?`
- [ ] M1.7 统一 `Log.e("OC_ERROR", ...)` → `DebugLog.e("OC_ERROR", ...)` 并注释
- [ ] M1.8 `SettingsScreen.kt:201` deleteHostProfile 改为通过 ViewModel 标准错误通道

**验收**:
- `grep 'catch\s*\(_\s*:\s*Exception\)' app/src` 在协程上下文内为零（除注释标注的豁免项）
- `grep 'runCatching'` 在 Repository 层只有 `runSuspendCatching`
- 所有 `Log.w` 错误路径改为 `_state.error` 或 `DebugLog.w` + 分类注释

### M2: 盘点表逐条归类改造
**目标**: 盘点表中每一条都标注分类注释 (categorized tag)，非正确范式的逐条改造

- [ ] M2.1 对盘点表中 `✅ 不变` 的条目加分类注释: `// ErrorClass: Parse — non-coroutine context`
- [ ] M2.2 ViewModel 接入 `AppErrorHandler.handleUserAction(...)` 替代所有手动 `_state.error = ...` + `Log.e`
- [ ] M2.3 `FilesViewModel` 接入: loadPreview / loadDirectoryPreview / loadFiles / loadFileContent → errorHandler
- [ ] M2.4 `AppLifecycleMonitor` 后台轮询接入: `runSuspendCatching` + errorHandler.handleSilent
- [ ] M2.5 `settingsManager` 内 `catch (e: Exception)` 统一用 `DebugLog.w` 替代 `android.util.Log.w`

**验收**:
- 每条 catch/runCatching 调用旁有 `// ErrorClass: <分类>` 注释
- `MainViewModel` 内无直接 `Log.e` / `Log.w` 调用 (全部通过 errorHandler / reportNonFatalIssue)
- `DebugLog.entries` 中 WARN/ERROR 级别日志数量可审计

### M3: 错误消息国际化 + 策略文档
**目标**: 硬编码中文/英文 → MessageKey + stringResource

- [ ] M3.1 定义 `MessageKey` enum (~30 个键)
- [ ] M3.2 新增 `values/strings.xml` 中文文案 + `values-en/strings.xml` 英文文案
- [ ] M3.3 ViewModel 所有 `error = "..."` 改为 `error = MessageKey.XXX`
- [ ] M3.4 UI 层 (ChatScreen / SettingsScreen / SessionsScreen) 实现 `resolveErrorMessage` Composable
- [ ] M3.5 `SSEConnectionExhausted.message` 改为中性英文，中文文案从 MessageKey 解析
- [ ] M3.6 编写 `docs/error-handling-policy.md` (基于本 RFC 精简为操作手册)
- [ ] M3.7 编写 `docs/error-handling-code-review-checklist.md` (基于 CE 检查清单)

---

## 7. 与 R-14 / R-16 / R-21 的关系

### 依赖图

```
R-14 (runSuspendCatching)     ← R-20 M1 前置 (CE 安全包装)
       │
       ▼
R-20 (错误处理策略)           ← 本 RFC
       │
       ├─► R-16 (拆 VM)       ← 错误处理点分散到各 Coordinator
       │     M1 的 AppErrorHandler 应设计为注入型单例
       │     Coordinator 拆分后各自注入 errorHandler 即可
       │
       └─► R-21 (SSEClient 测试) ← 错误分类策略稳定后写测试更精准
             测试可针对不同 ErrorClass 做断言
```

### 顺序建议

1. **R-14 先行** (1-2 天): 实现 `runSuspendCatching` → Repository 全量替换 → P0 CE 吞修复
2. **R-20 M1 紧随** (2-3 天): `AppErrorHandler` + `_state.error` 接入 (R-14 的 onFailure 直接喂给 errorHandler)
3. **R-16 编排拆分时复用** (后续 sprint): 拆分出的 `SessionSyncCoordinator` / `ForegroundCatchUpController` 等通过 DI 注入 `AppErrorHandler`，不引入新的错误处理风格
4. **R-21 测试精确化** (拆分后): 利用稳定的 `AppErrorHandler` 和 `ErrorClass` 做分类断言

### R-16 兼容性

`AppErrorHandler` 设计为**无状态服务** (不持有 stateFlow，仅接收参数)，因此拆分出的 Coordinator 通过构造函数注入即可独立使用：

```kotlin
class SessionSyncCoordinator(
    private val repository: OpenCodeRepository,
    private val errorHandler: AppErrorHandler,
    // ...
) {
    fun onSseEventError(error: Throwable) {
        errorHandler.handleFatal(error, "SSE event parse")
    }
}
```

`AppState.error` 的写入权仍由 `MainViewModel` (或未来的 `ErrorStateHolder`) 持有，`AppErrorHandler.handleFatal` 通过回调或参数写入。

---

## 附录 A: 错误处理范式速查表

| 场景 | 范式 | 示例 |
|------|------|------|
| Repository API 调用 | `runSuspendCatching { api.xxx() }` | 所有 Repository 方法 |
| ViewModel 用户操作 | `.onFailure { errorHandler.handleUserAction(it, "context") }` | sendMessage, createSession |
| ViewModel 后台刷新 | `.onFailure { errorHandler.handleSilent(it, "context") }` | loadSessionStatus |
| SSE Payload 解析 (非协程) | `runCatching { json.decode(...) }.getOrNull()` | parseSessionCreatedEvent |
| 持久化数据恢复 (非协程) | `try { decode } catch (e: Exception) { Log.w; default }` | SettingsManager getter |
| 日志本身 | `runCatching { android.util.Log.* }` | DebugLog.log |
| 崩溃记录器 | `try { writeCrash } catch (_: Throwable) {}` | CrashLogger.install |
| 独立子协程 (隔离崩溃) | `catch (_: Throwable) { /* 隔离 */ }` | SSEClient watchdog |

---

## 附录 B: transform 先后的 enum 命名对照

GitHub Copilot 有时会在 `catch` 语句处建议进行 enum 命名风格的 transform。以下为对照表以免混淆：

| 原枚举值 | 说明 |
|----------|------|
| `FatalNetwork` | 不变 |
| `Recoverable` | 不变 |
| `UserAction` | 不变 |
| `ProgressiveEnhancement` | 不变 |
| `CancelSilently` | 不变 |
| `Parse` | 不变 |
