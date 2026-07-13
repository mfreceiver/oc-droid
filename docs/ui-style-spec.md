# ocdroid UI 样式规范 (MANDATORY)

> **本规范是强制的（MANDATORY）**。新增 UI 代码必须遵循本文的三层 overlay 规则
> 与共享原语；偏离需在 PR 描述中给出书面理由。本文件由 WT0（ui-foundation lane）
> 落地，对应原语位于 `app/src/main/java/cn/vectory/ocdroid/ui/theme/`。

---

## 1. 三层 Overlay 规则

任何 overlay（picker / dialog / menu）都归到以下三层之一。**先选层，再选原语**。

| Tier | 原语 | 适用场景 | 触发坐标 | 显式 dismiss |
|------|------|----------|----------|--------------|
| **A** — anchored menu | M3 `DropdownMenu` | ≤6 项的单选 / 上下文动作；**必须**贴在 trigger 的坐标上（trigger-anchored） | 是（坐在 trigger 处） | scrim / 外部点击 |
| **B** — bottom sheet | [`AppBottomSheet`](../app/src/main/java/cn/vectory/ocdroid/ui/theme/SheetRecipe.kt) | 列表 / 预览 / 只读选择 / preview，**从 trigger 打开**但**不**叠加在 trigger 上方 | 否（底部弹出） | swipe-down / scrim |
| **C** — modal dialog | `AlertDialog` / [`AppConfirmDialog`](../app/src/main/java/cn/vectory/ocdroid/ui/theme/AppConfirmDialog.kt) / [`AppFormDialog`](../app/src/main/java/cn/vectory/ocdroid/ui/theme/AppFormDialog.kt) | 表单、阻塞确认、破坏性动作 | 否（屏幕居中） | scrim / 返回键 |

### 1.1 判别流程（按顺序问自己）

```
Q1: 必须 sit at the trigger's coordinates?  ── yes ──▶ Tier A (DropdownMenu)
        │ no
Q2: 是表单 / 阻塞决策 / 破坏性确认?        ── yes ──▶ Tier C (AlertDialog family)
        │ no
        ▼
   Tier B (AppBottomSheet)
```

- **A vs B**：判据是「必须 sit at the trigger's coordinates 吗」。`DropdownMenu`
  永远锚定 trigger（用 `Popup`），如果你只是「从 trigger 触发」但内容是底部弹出的
  全宽 sheet，那是 Tier B。
- **B vs C**：判据是「是不是表单 / 阻塞决策」。Tier B 是非阻塞的列表 / 预览（用户可
  scrim-dismiss 不丢失数据）；Tier C 是表单（提交前要确认）或破坏性动作（一旦确认
  不可撤销）。**纯文本 + 两按钮的破坏性确认**用 `AppConfirmDialog`；**含 `Switch`
  / `TextField` 等可交互控件**的用 `AppFormDialog`（`AlertDialog.text` 会吞 Switch
  触摸事件，详见 `AppFormDialog.kt` 文件头）。

### 1.2 现有 surface → tier 映射

| Surface | Tier | 备注 |
|---|---|---|
| Composer agent picker sheet | B | `AppBottomSheet`（已迁移） |
| Composer model picker sheet | B | `AppBottomSheet`（已迁移） |
| Composer session picker sheet | B | `AppBottomSheet`（WT1 待迁移） |
| ChatContextUsageDialog | B | `AppBottomSheet`（已迁移，footer 恒可见旗舰例） |
| TodoListPanel | B | `AppBottomSheet`（已迁移） |
| **WorkdirControl switcher** | B | `AppBottomSheet`（WT0 本次迁移） |
| **DirectoryPickerSheet** | B | `AppBottomSheet`（WT0 本次迁移） |
| Composer Add-menu (Photos/Reference/Commands) | B | `AppBottomSheet` |
| Composer stop-confirm | **C** destructive | `AppConfirmDialog` 候选（WT1） |
| Message revert confirm | C destructive | `AppConfirmDialog` 候选（WT1） |
| Sessions archive confirm | C destructive | `AppConfirmDialog` 候选（WT5） |
| Files disconnect-workdir confirm | C destructive | `AppConfirmDialog` 候选（WT5） |
| Settings clear-data confirm | C destructive | `AppConfirmDialog` 候选（WT5） |
| Host delete confirm | C destructive | `AppConfirmDialog` 候选（WT5） |
| Settings model-management dialog | **C form** | `AppFormDialog` 候选（WT5，现网已用 BasicAlertDialog+Surface 验证过） |
| 任何 top-bar overflow `DropdownMenu` | A | ≤6 项的单选 / 动作 |

---

## 2. 共享原语（强制使用）

下列原语位于 `cn.vectory.ocdroid.ui.theme` 包下，新增 UI 代码**必须**使用，不得
另起炉灶手写。

| 用途 | 原语 | 关键约定 |
|------|------|----------|
| **行（row）primitive** | M3 `ListItem` | 一律用 `ListItem`，不要手写 `Row` + 自定义 padding |
| **单选 selected-state** | [`PickerTrailingCheck`](../app/src/main/java/cn/vectory/ocdroid/ui/theme/PickerTrailingCheck.kt) | 选中 = `Icons.Filled.Check` + `Dimens.iconSm`(18dp) + `colorScheme.primary`；未选中 = 同尺寸 `Spacer`。**无 per-item 选中底色** |
| **section header** | [`AppSectionHeader`](../app/src/main/java/cn/vectory/ocdroid/ui/theme/AppSectionHeader.kt) | `titleSmall`(14sp) + `onSurfaceVariant` + `padding(h=16dp, v=8dp)`，可选 `trailing` 槽 |
| **间距 token** | [`Dimens`](../app/src/main/java/cn/vectory/ocdroid/ui/theme/Dimens.kt) | **禁止**散落 `4.dp` / `8.dp` / `16.dp` 字面量；用 `Dimens.spacing1` / `spacing2` / `spacing4` … |
| **图标尺寸 token** | `Dimens.iconXs` / `iconSm` / `iconStd` / `iconLg` / `iconXl` | 14 / 18 / 24 / 28 / 32dp。`DropdownMenu` 的 leading icon = `Dimens.iconSm`(18dp) |
| **sheet 容器** | `AppBottomSheet` | `skipPartiallyExpanded=true` + `surfaceContainerLow` + `titleLarge`(24/8dp padding) + 可选 footer(divider+16dp) + 底部 16dp Spacer |
| **确认 / 破坏性 dialog** | `AppConfirmDialog` | 基于 `AlertDialog`；`destructive=true` 时 confirm 按钮染 `colorScheme.error` |
| **表单 dialog**（含 Switch/字段） | `AppFormDialog` | 基于 `BasicAlertDialog` + `Surface`；内层 `Column(24dp padding + verticalScroll + heightIn(max=screenHeight*0.85f))`。**不要**用 `AlertDialog` 承载 `Switch`（`text` 槽吞触摸） |

### 2.1 间距 token 速查

```
Dimens.spacing1     = 4dp    极小间距（图标与文字）
Dimens.spacingCompact = 6dp  紧凑 chip 内边距
Dimens.spacing2     = 8dp    小间距（同组元素）
Dimens.spacing3     = 12dp   中间距
Dimens.spacing4     = 16dp   默认内容间距（ListItem 水平 padding）
Dimens.spacing5     = 20dp
Dimens.spacing6     = 24dp   区块间距（AppBottomSheet title / AppFormDialog 内 padding）
Dimens.spacing7     = 32dp
Dimens.spacing8     = 48dp

Dimens.iconSm  = 18dp   DropdownMenu leading / picker trailing / 密集行内
Dimens.iconStd = 24dp   IconButton 内容
Dimens.iconLg  = 28dp
Dimens.iconXl  = 32dp
```

---

## 3. 偏离规范

任何偏离（例如：Tier B sheet 必须承载表单、必须用裸 `AlertDialog` 渲染 Switch、
必须用字面量 dp 而非 `Dimens`）需要在 PR 描述中给出**书面理由**，并在原文件就近
处留注释说明原因（参考 `SheetRecipe.kt` §inset-note 的写法）。

---

## 4. 维护

- 新增 / 修改原语：在 `app/src/main/java/cn/vectory/ocdroid/ui/theme/` 下加文件，
  并在本文件 §2 表格里登记。
- 新增 overlay surface：先按 §1 判别流程定 tier，再在 §1.2 表格里登记。
- 本规范由 WT0 落地（commit `feat(ui-theme): foundation primitives + mandatory
  style spec (WT0)`），后续 lane（WT1 chat-sheets / WT5 settings …）按本规范迁移。
