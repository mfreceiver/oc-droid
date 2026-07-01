package cn.vectory.ocdroid.util

import kotlinx.serialization.Serializable

/**
 * Per-level font sizes for Markdown rendering.
 *
 * v2 对齐（`docs/v2-redesign-plan.md` §3）：h1=17 / h2=15 / h3=13 / h4-h6=13 /
 * body=14 / code=13 / inlineCode=14 / quote=14 / reasoning=13。
 *
 * R-10 (分层下沉): 此 data class 原定义在 `ui/theme/Type.kt`，被 util 层的
 * [SettingsManager] 反向 import，违反分层（util 不得依赖 ui）。现下沉到 util 包，
 * `ui.theme` 改为正向 import util（UI → util 合法方向）。`@Serializable` 注解随
 * 定义一起搬迁，因为 [SettingsManager] 依赖 kotlinx 序列化持久化该类型。
 */
@Serializable
data class MarkdownFontSizes(
    val h1: Float = 17f,
    val h2: Float = 15f,
    val h3: Float = 13f,
    val h4: Float = 13f,
    val h5: Float = 13f,
    val h6: Float = 13f,
    val body: Float = 14f,        // 助手 markdown 正文（v2 14px）
    val code: Float = 13f,        // 代码块（v2 13px）
    val inlineCode: Float = 14f,  // 行内代码
    val quote: Float = 14f,
    /**
     * Reasoning blocks use a deliberately smaller, subdued size to visually
     * de-emphasize chain-of-thought output versus the main assistant reply.
     * v2: 13px / 行高 130%。
     */
    val reasoning: Float = 13f,
)
