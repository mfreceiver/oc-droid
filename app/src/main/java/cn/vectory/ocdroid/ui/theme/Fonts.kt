@file:OptIn(ExperimentalTextApi::class)

package cn.vectory.ocdroid.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import cn.vectory.ocdroid.R

// ─────────────────────────────────────────────────────────────────────────────
// Bundled Variable Fonts (Phase 1 — Expressive 字体路线 B).
//
// 策略：bundle 两套 Variable Font 作为 app 默认字体。
//  - 常规文本：Noto Sans VF（拉丁/希腊/西里尔，wght 轴 400-900），CJK 回退系统字体
//    （Android 系统 CJK 兜底本就是 Noto 系，视觉同源过渡自然）。
//  - 等宽文本：JetBrains Mono VF（专为代码可读性设计，wght 轴 400-800），CJK 回退系统等宽。
//
// 每个 FontFamily 提供 4 档 weight（Normal/Medium/SemiBold/Bold = 400/500/600/700）——
// 这是 M3 全部 15 个 Typography slot 实际使用的 weight 集合（见 Type.kt appTypography）。
// Compose 按 TextStyle.fontWeight 匹配最近档位，再应用对应 variationSettings。
// 两款 VF 的 wght 轴范围均覆盖这 4 档（Noto 400-900 / JetBrains 400-800，已核验 fvar 表）。
// 注：非"全 weight 连续覆盖"——若未来引入 300/800/900 等 TextStyle，会匹配到最近档位
// 渲染固定值；当前 M3 刻度不需要。
//
// CJK / 非拉丁字符的字重渲染：每个 FontFamily 末尾声明 4 档系统回退条目，供 Compose
// weight 匹配；**但实际 CJK 字重合成最终依赖 Android 平台字体 fallback**（系统 Noto CJK
// 的 weight 感知）。本应用未对此做截图回归验证——若实测发现 CJK 字重与拉丁错配，可在此
// 调整系统回退条目的声明顺序或移除（平台兜底通常已足够）。
//
// 用户字体偏好（SettingsManager.fontLatin/fontCJK）作为 override 仍保留——见
// [OpenCodeTheme.resolveFontFamilyOrNull] / [systemFontFamily]：偏好非空时走 4 档系统
// 字体名加载（镜像本结构，避免 weight 退化），否则回落到 [BundledSansFamily]。
//
// ── APK 体积决策（记录，避免后人重复评估）──────────────────────────────────
// Noto Sans VF = 2MB（含拉丁/希腊/西里尔/越南文 script + wdth 轴声明，wdth 固定 100 非真轴）。
// JetBrains Mono VF = 187KB。合计 ~2.2MB 原始字体入 APK（isShrinkResources 对 TTF 二进制
// 无效）。已评估 pyftsubset 子集化（剥离未用 script 可省 30-50%，压到 ~250KB-1MB）——
// 当前 2.2MB 对工具型 App 可接受，**暂不做子集化**，列为后续优化项（若体积敏感再执行）。
//
// 变量字体 API（FontVariation）属 ExperimentalTextApi，file-level @OptIn 统一开启。
// compose-bom 已 pin 到 2025.12.00，API 稳定性可控；BOM 升级时需回归 FontVariation.Settings。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bundled 拉丁常规字体族：Noto Sans VF 4 档 weight + 系统 sans-serif 4 档 weight 回退。
 *
 * 作为 [OpenCodeTheme] 的默认 app 字体（当用户未设置字体偏好时）。所有 Typography
 * slot 经 `fontFamily = family` 注入，Compose 按 fontWeight 匹配下列档位之一。
 */
val BundledSansFamily: FontFamily = FontFamily(
    Font(
        R.font.noto_sans_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.noto_sans_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.noto_sans_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.noto_sans_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    // 系统 CJK / 覆盖域回退（weight 匹配供 Compose 解析；实际 CJK 字重依赖平台 fallback，
    // 见文件头注释）。
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.SemiBold),
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.Bold),
)

/**
 * Bundled 等宽字体族：JetBrains Mono VF 4 档 weight + 系统 monospace 4 档 weight 回退。
 *
 * 用于全部代码块 / 行内代码（替换原 `FontFamily.Monospace`）。JetBrains Mono 的
 * `0`/`O`/`l`/`1` 强区分与操作符对齐对 AI 编程客户端价值显著，故选它而非通用
 * Noto Sans Mono。
 */
val BundledMonoFamily: FontFamily = FontFamily(
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    // 系统等宽回退——CJK 代码注释等走系统 monospace（实际字重依赖平台 fallback）。
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.SemiBold),
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.Bold),
)
