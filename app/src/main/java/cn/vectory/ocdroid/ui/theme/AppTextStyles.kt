package cn.vectory.ocdroid.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// B2·P2 设计语言地基：命名扩展样式（Named extension styles）。
//
// 这里集中定义**不属于 M3 15-slot Typography**、但被特定场景复用的命名样式
// token（watermark / codeBody）。它们是「扩展」——不替换 [Typography] 的任何 slot，
// 也不改全局 bodyLarge/bodyMedium 的数值（那是 P5 单独阶段）。调用方通过
// `AppTextStyles.<name>.copy(...)` 覆盖场景化属性（如 color/alpha）。
//
// 与 [Type.kt] 的关系：[Type.kt] 只管 M3 [Typography] 构造（slot 化），
// 本文件管「跨批次复用的命名 TextStyle」（非 slot）。保持两份关注点分离，降低
// 误改 M3 数值的风险。
//
// 引用 [BundledMonoFamily] 的方式与 [Type.kt] / [AgentTone.kt] 所在包一致——
// 同包顶层 val，直接引用，无需 @Composable。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 跨批次复用的命名 [TextStyle] 扩展 token（非 M3 Typography slot）。
 *
 * 这些样式固化设计语言中「特定场景」的字号 / 字重 / 行高，**不写死 color**——
 * color 与 alpha 由调用方按上下文覆盖（例如水印的 color 来自 workdirTone、
 * alpha 0.07f）。
 *
 * 使用方式：
 * ```
 * Text(
 *     text = "...",
 *     style = AppTextStyles.watermark.copy(color = workdirTone(dir).copy(alpha = 0.07f)),
 * )
 * ```
 *
 * 不修改 [Typography] 的任何 slot，也不改全局 bodyLarge 数值（P5 阶段单独做）。
 */
object AppTextStyles {

    /**
     * 水印文字样式（B5 消费）。
     *
     * 固化设计要求：水平排版、**48sp**、**[FontWeight.ExtraBold]**、lineHeight 56sp、
     * letterSpacing 0sp。用于在聊天背景 / 空态渲染 session/workdir 水印。
     *
     * 调用方**必须**覆盖以下属性：
     *  - `color`：来自 [cn.vectory.ocdroid.ui.chat.workdirTone]（hash 派生的 session 色）。
     *  - `alpha`：建议 `0.07f`（极低对比，作为背景纹理而非内容）。
     *
     * 可选覆盖：
     *  - `fontFamily`：本样式不绑定 family（默认 null → 平台字体）。若希望水印跟随
     *    用户字体偏好，调用方 `.copy(fontFamily = LocalAppFontFamily.current)`。
     *
     * 示例：
     * ```
     * AppTextStyles.watermark.copy(
     *     color = workdirTone(sessionDir).copy(alpha = 0.07f),
     * )
     * ```
     */
    val watermark: TextStyle = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = 0.sp,
        // fontFamily 与 color 故意留空：前者让调用方按需注入，后者必须由调用方覆盖。
    )

    /**
     * 等宽正文样式（B3 diff 渲染器 / B3 文件路径消费）。
     *
     * size / lineHeight 与 [Typography.bodySmall] 一致（12sp / 16sp），letterSpacing
     * 归零适配等宽字体（bodySmall 是 0.4sp，等宽字体本身已列对齐，无需额外字距）：
     *  - `fontFamily` = [BundledMonoFamily]（JetBrains Mono，等宽对齐）；
     *  - `fontWeight` = [FontWeight.Normal]；
     *  - `letterSpacing` = 0sp（与 bodySmall 的 0.4sp 不同，见上）。
     *
     * 用于代码 diff、文件路径、等宽元数据行等需要列对齐的场景。color 由调用方
     * 覆盖（如 diff 的 +/- 行用 onSurface / error / primary）。
     *
     * 注：本值**显式固定** 12sp/16sp 而非 `Typography.bodySmall.copy(...)`——
     * 避免 B4/P5 调整 bodySmall 时连带改变代码块行高（代码渲染需稳定刻度）。
     * 若未来需联动，再改成 `.copy()` 派生。
     */
    val codeBody: TextStyle = TextStyle(
        fontFamily = BundledMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )
}
