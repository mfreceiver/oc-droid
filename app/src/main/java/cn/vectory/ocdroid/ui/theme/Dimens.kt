package cn.vectory.ocdroid.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// R-25 Dimens 系统：dp 字面量在 UI 代码里散落无规范。这里定义常用间距 /
// 组件尺寸 token，作为后续 UI 代码迁移的目标。本 lane **只**提供定义、不
// 批量替换现有 `4.dp`/`8.dp`/`16.dp` 字面量（风险大、与本 lane 范围不符）。
//
// 现有 Compose UI 没有统一的 dimen 入口（没有 `MaterialTheme.dimens`），
// 这些 token 当前以顶层 object 形式提供，使用方式：
//   ```
//   import cn.vectory.ocdroid.ui.theme.Dimens
//   Modifier.padding(horizontal = Dimens.spacing4)
//   ```
// 未来如需 Composable/CompositionLocal 化（如响应 window size），再演进。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * App-wide 间距 + 组件尺寸 token 集中定义。
 *
 * 命名约定：
 *  - `spacingN`：基础 4dp 网格的间距阶梯（N=1→4dp, N=2→8dp, …）。
 *  - 具名尺寸：常用组件的最小可点击高度、图标尺寸等。
 */
object Dimens {
    // ── 4dp 网格间距阶梯 ───────────────────────────────────────────────
    /** 4dp — 极小间距（图标与文字、紧凑 chip 内边距等）。 */
    val spacing1: Dp = 4.dp
    /** 8dp — 默认小间距（同一组元素间）。 */
    val spacing2: Dp = 8.dp
    /** 12dp — 中间距（半步，常见于 card 内 padding）。 */
    val spacing3: Dp = 12.dp
    /** 16dp — 默认内容间距（Material 默认内容 padding）。 */
    val spacing4: Dp = 16.dp
    /** 20dp — 大间距（半步）。 */
    val spacing5: Dp = 20.dp
    /** 24dp — 区块间距 / 大内边距（Material touch-target padding）。 */
    val spacing6: Dp = 24.dp
    /** 32dp — 区块之间分隔。 */
    val spacing7: Dp = 32.dp
    /** 48dp — 大区块外边距。 */
    val spacing8: Dp = 48.dp

    // ── 组件尺寸 ───────────────────────────────────────────────────────
    /** Material 推荐的最小可点击目标尺寸（无障碍标准）。 */
    val touchTargetMin: Dp = 48.dp
    /** 标准图标尺寸（Icons.Default 视觉等高）。 */
    val iconStd: Dp = 24.dp
    /** 小图标尺寸（toolbar 内、行内图标）。 */
    val iconSmall: Dp = 20.dp
    /** 极小图标尺寸（行内 inline 状态指示）。 */
    val iconXs: Dp = 14.dp
    /** 1dp 边框 / 分隔线粗细。 */
    val hairline: Dp = 1.dp
}
