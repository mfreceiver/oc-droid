# Follow-up: 流式 Markdown 零闪烁重写（延期至 0.6.2）

> 0.6.1 曾尝试纳入"完整流式 markdown 重写"，经 gpter 专项评审 **8.4/10（< 9.5）**，含 1 个真实现缺陷 + 门控基础设施需求。经用户决策：0.6.1 只发 3 个回归修复（VCS reactive + 模型目录容错 + 历史加载并发），**md 延期至 0.6.2 专项重做**。本文档捕获 0.6.1 的 md 工作与评审结论，供 0.6.2 复用，避免重蹈覆辙。

## 目标
让 LLM 流式输出的**正文 prose 在流式期间就看到 markdown 格式**（bold/列表/链接/表格/代码），且**可见高度零收缩闪烁**，完成态无缝过渡。当前 0.6.x 实现只把 code 块格式化、prose 当纯文本（完成才 snap 成 markdown）。

## 已验证方案：ora-2（(i) block 分解 + (ii) tail 走 Markdown + HeightAnchor）
- **(i) block 级分解**：只有增长的 tail 被 re-parse；已完成块独立渲染、稳定 key（cache 复用）。消除 mikepenz"全量 re-parse 增长前缀"病态（实测 151 次 height-shrink/turn）。
- **(ii) tail 也走 Markdown（非纯 Text）**：消灭"纯 Text→Markdown"边界收缩（朴素 boundary-aware 实测 57 次的根因）。
- **(iii) HeightAnchor**：`可见高度 = max(H_natural(t), H_anchor(t-1))` 非递减 → 0-shrink 兜底。
- 流式→完成态共享 `stableKey`（`messageId|partId`）→ maxHeight 继承 → 无缝过渡。
- 解析器：intellij-markdown（mikepenz 内置 `GFMFlavourDescriptor` + `MarkdownParser`，无需新依赖）。
- 必须保留定制：`WrappedCodeBlock`/`WrappedTable`/`DataUriImageTransformer`/`ResolvedMarkdownText`/`rememberPacedStreamingText`/`CodeBlockSurface`/`markdownTypography`。

## 0.6.1 实现概要（已回滚，供参考）
- `StreamingMarkdownRender.kt`：`StreamingRenderUnit` + `buildStreamingRenderUnits`（block 分解）+ `HeightAnchor`/`DebugHeightAnchor`（SubcomposeLayout 测 natural 高度）+ `HeightAnchorRegistry`（跨调用点共享 maxHeight）+ `StreamingMarkdownContent`/`StreamingMarkdownRender`。
- `StreamingMarkdownHelpers.kt`：纯函数（`buildStreamingRenderUnits`/`splitTailProse`/`HeightShrinkCounter`），抽离以计入 kover。
- `ChatTextParts.TextPart`：流式分支调 `StreamingMarkdownRender`；完成态包 `HeightAnchor(stableKey)`。
- `ChatMessageRow`：`stableKey = "${messageId}|${part.id}"`。

## gpter 8.4 阻塞项（0.6.2 必须解决）
1. **🟠 HeightAnchor width-reset 是真 bug**：`onSizeChanged` 拿到的是 `heightIn(min)` **clamp 后**的高度，`HeightAnchorRegistry.update()` 据此更新 → 旋转/分屏时旧宽度的 maxHeight 污染新宽度，width-reset 实际失效。**修法**：生产版用 `SubcomposeLayout` 先测 natural 高度再 `layout(w, max(natural, anchor))`（与 DebugHeightAnchor 统一）；或在 width 变化时清空本地 maxHeight + registry entry（接受一帧 natural，旋转场景可接受）。
2. **🟠 stable key 不稳定**：块从 `tail` 提升到 `seg:$i` 时 key 改变（tail-local offset / segment index），削弱"完成块 cache 复用"。**修法**：key 基于全局源文本 offset + 类型（`block:$globalStartOffset:$kind`），code/prose 段都保留 fence 起始 offset。
3. **🟠 0-shrink Compose 测试不在 check.sh 门控**（在 androidTest，CI 不跑）——gpter 判定不可接受。**修法**：0.6.2 须加专项 release-gate 脚本跑 `connectedDebugAndroidTest`（emulator，按 AGENTS.md 模拟器纪律 status→start→run→stop）的 0-shrink 测试 + 归档结果；**前置**：先修预先存在的 androidTest 编译坏损（`SettingsSectionsInstrumentedTest.kt:33` 缺 `groupProfileCount`/`cachedSessionCount`/`onStatsClick`）。
4. **🟠 0-shrink 证据链不足**：缺 width 变化、流式→完成态 + `ResolvedMarkdownText` 异步图片解析、远程图片加载、长文本多段 promotion 的测试。**修法**：补这些场景的 0-shrink 测试。

## 非阻塞（🟡）
- `HeightAnchorRegistry` 永不清理（per-stableKey 常驻）→ 长 session 泄漏，加 LRU/session reset。
- `trackedWidth` 未实际参与逻辑（并发写残留）→ 删除或接入真实 width reset。
- `ChatTextParts.kt` 旧注释仍描述"PROSE renders as plain Text"（与 ora-2 冲突）→ 更新。
- 重复测试文件（两个 JVM + 两个 androidTest）→ 合并为一个 JVM（segmentation+counter）+ 一个 androidTest（0-shrink+过渡）。

## 0.6.2 建议
- 按本 ora-2 方案重做，**优先修 gpter 的 4 个阻塞项**（尤其 HeightAnchor width-reset 正确性 + key 方案 + emulator 门控）。
- 工作量：~2 个新文件 + TextPart/ChatMessageRow 小改 + androidTest 0-shrink 门控；估 1-2 专注日（修阻塞项）。
- 9.5 门控前：HeightAnchor 用 SubcomposeLayout 统一、key 全局 offset、emulator 实证 0-shrink、补齐测试场景。
