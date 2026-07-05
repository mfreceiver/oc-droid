# R-17 重构延后项（Followup）

> 本次 R-17 综合重构（5 批次）主动延后或遗留的项。
> 每条含来源、当前状态、下次触发条件。
> **最后更新**：R-17 全部 5 批次完成 + 全局收尾。

## 批次完成状态

| 批次 | 内容 | 门控 | 状态 |
|---|---|---|---|
| 1 | 止血 + i18n | glm 9.5 + maxer 9.9 | ✅ |
| 2 | AppState mirror 退役 | glm 9.5 + maxer 9.9 | ✅ |
| 3 | VM 拆分 + callback→effect | glm 9.5 + maxer 9.5 | ✅ |
| 4 | currentDirectory 消除 | kimo 9.6 + maxer 9.5 | ✅ |
| 5 | SSE 半形式化 | （同 4，合并评审） | ✅ |

---

## 延后清单

### 1. SSE 全纯 reducer + effect 通道
- **来源**：gpter、dser
- **本次做**：delta coalescing 进 ChatState slice；22 个纯函数抽取 + 49 table-driven 测试
- **延后部分**：副作用触发（onRefreshMessages 等）迁 effect 通道；全纯 `(Event,State)->(State,Effects)` reducer
- **下次触发**：完成事件序列枚举测试覆盖后，独立编排
- **状态**：纯函数 + slice 化完成。effect 通道迁移待做。

### 2. connectionPhase → sealed class
- **来源**：dser、kimo
- **本次状态**：mirror 退役后 connectionPhase 仅在 ConnectionState slice。Actions 文件有 KDoc 标注。sealed class 改造可在 ConnectionViewModel 内独立做。
- **下次触发**：下次碰 ConnectionCoordinator/ConnectionState 时顺手

### 3. 单 profile delete store 层防御
- **来源**：kimo
- **状态**：已闭环。UI 禁用 + runCatch 保护 + deleteHostProfile(wasCurrent) 已补全 onHostProfileSwitched。store 层 `require` 仅纵深防御。

### 4. kover 分模块阈值提升
- **来源**：glm
- **本次做**：kover 接入 check + lint abortOnError
- **延后部分**：分模块设阈值；提升基线（当前 ~25% line）
- **下次触发**：覆盖率稳定后逐步提升

### 5. SettingsManager 接口抽象 + draft 性能优化
- **来源**：momo
- **状态**：未动。无接口不可 mock；draft 每次 JSON 全量序列化
- **下次触发**：SettingsManager 成为测试瓶颈或 draft 性能问题时

### 6. TrafficLogger 隐私脱敏
- **来源**：gpter
- **状态**：未动。release 保留完整 URL 日志
- **下次触发**：项目扩大用户群前

### 7. PartStateSerializer 独立 + fuzz 测试
- **来源**：glm
- **状态**：未动。150+ 行反序列化在 Message.kt
- **下次触发**：下次碰 Message.kt 序列化时

### 8. UI 大文件深度拆分
- **来源**：momo、kimo
- **本次做**：VM 拆分缓解了 ChatScreen 状态耦合
- **延后部分**：ChatScreen/ChatMessageContent 按职责分离
- **下次触发**：评估后决定

### 9. OrchestratorVM 进一步瘦身
- **来源**：gpter
- **本次做**：6 领域 VM + OrchestratorVM（198 行真实方法体）
- **延后部分**：观察 OrchestratorVM 是否可进一步萎缩
- **下次触发**：观察期后评估

### 10. AppCore 进一步分散
- **来源**：glm、maxer（批次 3 评审）
- **本次做**：AppCore 从 1017→284 行 + AppCoreOrchestration(329行) 跨域编排扩展
- **延后部分**：AppCoreOrchestration 中的 6 个跨域方法仍作为 AppCore 扩展存在（Hilt VM 不能互注入的约束）。未来可提取为 interface 或通过 SharedEffectBus 完全去中心化。
- **下次触发**：如果 AppCore/AppCoreOrchestration 膨胀超过 600 行

### 11. Actions 自由函数内联到 VM
- **来源**：maxer
- **本次做**：6 个 Actions 文件加 KDoc 标注为"domain orchestration helpers"
- **延后部分**：~150 行可内联到各 VM private 方法
- **下次触发**：batch3e+ 清理

### 12. VM/controller 层 error 字符串 i18n
- **来源**：glm
- **问题**：UiEvent.Error(message: String) 仍携带原始字符串
- **下次触发**：改为 UiEvent.Error(@StringRes Int)

### 13. HostConfig._currentDirectory 彻底移除
- **来源**：gpter、kimo（批次 4 评审）
- **本次做**：文件 API 改显式 directory；_currentDirectory @Deprecated（SSE/question/command 仍用 fallback）
- **延后部分**：SSE/question/command 路由也改显式 directory 后删除 _currentDirectory
- **下次触发**：下次碰 SSE/question/command API 路由时

### 14. SharedStateStore 写权限隔离
- **来源**：glm（批次 3 评审）
- **问题**：所有 slice MutableStateFlow 是 public val，任何 VM 可写任何 slice
- **下次触发**：如果领域边界需要编译期强制

### 15. FilesViewModel 空文件预览 UX
- **来源**：kimo（批次 4+5 评审）
- **问题**：空文件（0 字节）被误判为目录，UI 显示"Directory (empty)"
- **严重度**：🟡 UX 边缘，非 bug
- **下次触发**：如果用户报告或后端支持 file/directory 元数据区分

### 16. TestAppStateShim.kt 逐步删除
- **来源**：maxer（批次 3 评审）
- **问题**：测试侧重新引入 AppState data class（370 行）作为测试兼容层
- **下次触发**：逐步把 `core.state.value.X` 改为 `core.store.xxxFlow.value.X`，最终删除 shim

### 17. expandedParts .value = 风格统一
- **来源**：maxer
- **问题**：4 处直接 .value = 而非 .update {}
- **严重度**：🟡 cosmetic
- **下次触发**：顺手

### 18. applyPartDeltaLeadingEdge 双重载合并
- **来源**：kimo、maxer（批次 4+5 评审）
- **问题**：两个重载签名仅靠参数名区分
- **严重度**：🟡 防御性 refactor
- **下次触发**：下次碰 SessionSyncCoordinator 时
