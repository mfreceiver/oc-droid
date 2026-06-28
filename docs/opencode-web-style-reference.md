# opencode 原生 Web 聊天页样式参考

> 来源：本地参考仓库 `oc-ref/`（`github.com/anomalyco/opencode`，已 gitignore）。
> 用途：为本 Android 项目（opencode_android_client）的 Compose UI 提供 1:1 视觉还原依据。
> 两份后台探查（exp-1 结构映射 + exp-2 具体数值）综合产出。

---

## 0. 技术栈速览（仅供理解，不需移植）

- **前端包**：`oc-ref/packages/app/`（`@opencode-ai/app`），**SolidJS + Vite + Tailwind v4**。
- **UI 库**：内置 `@opencode-ai/ui`（基础组件）+ `@opencode-ai/session-ui`（聊天专用：消息块、Markdown、工具卡、Diff 查看器）。
- **CSS 策略**：Tailwind v4，**无 JS 配置文件**——所有 token 通过 CSS `@theme` 和 `@layer` 定义；组件用 `data-component` / `data-slot` 选择器 + co-located `.css`。
- **字体**：**Inter**（sans）+ **JetBrainsMono Nerd Font Mono**（mono/终端）。

> 移植原则：只搬"视觉规则 + token 值"，不搬 Solid/JS 实现。下面所有数值都是确定的可直接落到 Compose 的。

---

## 1. 关键源文件索引（在 oc-ref 内查证用）

| 关注点 | 文件 |
|---|---|
| **v2 设计 token（重点移植）** | `packages/ui/src/v2/styles/theme.css`、`colors.css`、`tailwind.css` |
| v1 token（备用/对照） | `packages/ui/src/styles/theme.css`、`utilities.css` |
| 字体声明 | `packages/app/src/index.css`、`packages/app/public/assets/Inter.ttf`、`JetBrainsMonoNerdFontMono-Regular.woff2` |
| 消息气泡 | `packages/session-ui/src/components/message-part.tsx` + `message-part.css`（~31KB，**最核心**） |
| 回合布局 | `packages/session-ui/src/components/session-turn.tsx` + `.css` |
| Markdown 内容 | `packages/session-ui/src/components/markdown.tsx` + `markdown.css` |
| 工具调用卡 | `packages/session-ui/src/components/basic-tool.css`、`tool-count-*.css`、`tool-error-card.css` |
| 流式 Markdown 投影器 | `packages/session-ui/src/components/markdown-stream.ts`、`markdown-shiki.worker.ts` |
| 输入框 | `packages/app/src/components/prompt-input.tsx`（contenteditable，非 textarea） |
| 会话页骨架 | `packages/app/src/pages/session.tsx` |
| 消息列表（虚拟化） | `packages/app/src/pages/session/timeline/message-timeline.tsx` |
| 输入停靠区 | `packages/app/src/pages/session/composer/session-composer-region.tsx` |
| 主题运行时 | `packages/ui/src/theme/context.tsx`、`themes/*.json`（37 个主题） |

---

## 2. 色彩 token（移植 V2 —— 它是新设计的活动路径）

### 2.1 灰阶（`colors.css`，明暗主题相同，语义角色互换）

```
grey-50   #ffffff       grey-700  #5c5c5c
grey-100  #fafafa       grey-800  #3a3a3a
grey-200  #f2f2f2       grey-900  #2e2e2e
grey-300  #eeeeee       grey-1000 #242424
grey-400  #dbdbdb       grey-1100 #161616
grey-500  #aeaeae       grey-1200 #080808
grey-600  #808080
```

### 2.2 强调色蓝（accent = blue-600）

```
blue-400  #a2bcff    blue-700  #3250df
blue-500  #7698fd    blue-900  #263fa9
blue-600  #3b5cf6
```

其他色阶（红/橙/黄/绿/青/紫/粉，各 100–1200 共 12 档）见 `colors.css:60-170`，按需取用。

### 2.3 Alpha 通道色（半透明覆盖层）

`--v2-alpha-dark-N`（黑+α）/ `--v2-alpha-light-N`（白+α），N ∈ {0,2,4,6,8,10,12,14,16,20,24,30,40,50,60,70,80,90,100}。
例：`alpha-dark-8 = #00000014`（≈8% 黑），`alpha-light-6 = #ffffff0f`。

### 2.4 语义 token → Compose ColorScheme 映射表

#### 亮色（`[data-color-scheme="light"]`）

| 用途 | token | 值 |
|---|---|---|
| 页面深底（chat 卡片之外） | `--v2-background-bg-deep` | **#fafafa** |
| 聊天卡背景 | `--v2-background-bg-base` | **#ffffff** |
| 层级 1（侧栏/输入框背景） | `--v2-background-bg-layer-01` | **#fafafa** |
| 层级 2（用户消息气泡背景） | `--v2-background-bg-layer-02` | **#f2f2f2** |
| 层级 3 | `--v2-background-bg-layer-03` | **#eeeeee** |
| 对比反色（发送按钮底） | `--v2-background-bg-contrast` | **#242424** |
| accent 背景 | `--v2-background-bg-accent` | **#3b5cf6** |
| 主文本 | `--v2-text-text-base` | **#161616** |
| 次文本（思考/时间戳） | `--v2-text-text-muted` | **#5c5c5c** |
| 弱文本（placeholder） | `--v2-text-text-faint` | **#808080** |
| 反相文本（按钮上文字） | `--v2-text-text-contrast` | **#ffffff** |
| accent 文本/链接 | `--v2-text-text-accent` | **#3b5cf6** |
| 代码路径文本 | `--v2-text-text-code-accent` | **#263fa9** |
| 基础图标 | `--v2-icon-icon-base` | **#3a3a3a** |
| 弱图标 | `--v2-icon-icon-muted` | **#808080** |
| 细边框 | `--v2-border-border-muted` | **#00000014** |
| 标准边框 | `--v2-border-border-base` | **#0000001a** |
| 强边框 | `--v2-border-border-strong` | **#00000033** |
| 聚焦边框 | `--v2-border-border-focus` | **#7698fd** |
| hover 覆盖 | `--v2-overlay-simple-overlay-hover` | **#0000000a** |
| pressed 覆盖 | `--v2-overlay-simple-overlay-pressed` | **#00000014** |
| scrim（模态遮罩） | `--v2-overlay-simple-overlay-scrim` | **#00000066** |
| 成功背景/文本 | `--v2-state-bg-success` / `-fg-success` | **#e7f9ea** / **#198b43** |
| 危险背景/文本 | `--v2-state-bg-danger` / `-fg-danger` | **#fceceb** / **#b82d35** |
| 信息背景/文本 | `--v2-state-bg-info` / `-fg-info` | **#ecf1fe** / **#2c47c8** |

#### 暗色（`[data-color-scheme="dark"]`）

| 用途 | token | 值 |
|---|---|---|
| 页面深底（html 背景） | `--v2-background-bg-deep` | **#080808** |
| 聊天卡背景 | `--v2-background-bg-base` | **#161616** |
| 层级 1 | `--v2-background-bg-layer-01` | **#242424** |
| 层级 2（用户气泡） | `--v2-background-bg-layer-02` | **#2e2e2e** |
| 层级 3 | `--v2-background-bg-layer-03` | **#3a3a3a** |
| 对比反色（发送按钮） | `--v2-background-bg-contrast` | **#5c5c5c** |
| 主文本 | `--v2-text-text-base` | **#fafafa** |
| 次文本 | `--v2-text-text-muted` | **#aeaeae** |
| 弱文本 | `--v2-text-text-faint` | **#808080** |
| accent 文本 | `--v2-text-text-accent` | **#a2bcff** |
| 代码路径文本 | `--v2-text-text-code-accent` | **#a2bcff** |
| 基础图标 | `--v2-icon-icon-base` | **#dbdbdb** |
| 弱图标 | `--v2-icon-icon-muted` | **#808080** |
| 细边框 | `--v2-border-border-muted` | **#ffffff14** |
| 标准边框 | `--v2-border-border-base` | **#ffffff1a** |
| 强边框 | `--v2-border-border-strong` | **#ffffff33** |
| 聚焦边框 | `--v2-border-border-focus` | **#7698fd** |
| hover 覆盖 | `--v2-overlay-simple-overlay-hover` | **#ffffff0f** |
| scrim | `--v2-overlay-simple-overlay-scrim` | **#00000099** |
| 成功背景/文本 | `--v2-state-bg-success` / `-fg-success` | **#14361d** / **#6bd586** |
| 危险背景/文本 | `--v2-state-bg-danger` / `-fg-danger` | **#461516** / **#f17471** |
| 信息背景 | `--v2-state-bg-info` | **#1b2852** |

### 2.5 语法高亮（代码块内）

V1 token（Shiki 用，亮/暗各一套）：

```
              LIGHT              DARK
comment       #8f8f8f            rgba(255,255,255,.42)
string        #006656            #00ceb9
keyword       #8f8f8f            rgba(255,255,255,.42)
primitive     #fb4804            #ffba92
property      #ed6dc8            #ff9ae2
type          #596600            #ecf58c
constant      #007b80            #93e9f6
```

---

## 3. 排版

### 字体

- **Sans**：`Inter`（权重范围 100–900，可变字体；`oc-ref/packages/app/public/assets/Inter.ttf`）。
- **Mono**：`ui-monospace, SFMono-Regular, Menlo, …`；终端/字形图标用 `JetBrainsMono Nerd Font Mono`。

### 类型刻度（`theme.css`）

```
--font-size-small:   13px
--font-size-base:    14px     ← 消息正文默认
--font-size-large:   16px
--font-size-x-large: 20px

--font-weight-regular: 400
--font-weight-medium:  500
（V2 还用可变字体中间权重：正文 font-[440]，按钮 530）

--line-height-normal:    130%
--line-height-large:     150%   ← html 默认
--line-height-x-large:   180%
--line-height-2x-large:  200%

--letter-spacing-tight:    -0.16
--letter-spacing-tightest: -0.32
```

### 复合文字工具类（聊天中高频）

| 类 | 等价 |
|---|---|
| `.text-12-regular` | 13px / 400 / 行高 150% |
| `.text-12-medium` | 13px / 500 / 行高 150% |
| `.text-14-regular` | 14px / 400 / 行高 180% |
| `.text-14-medium` | 14px / 500 / 行高 150% |
| `.text-16-medium` | 16px / 500 / 行高 180% / 字距 -0.16 |
| `.text-20-medium` | 20px / 500 / 行高 180% / 字距 -0.32 |

### 聊天页具体用法

- **用户消息正文**：14px / 行高 150% / 权重 400。
- **助手 Markdown 正文**：14px / 行高 **160%** / Inter。
- **输入框编辑器**：13px / 可变权重 440 / 行高 20px。
- **会话标题/标签**：`.text-14-medium`。
- **bash/代码输出**：13px / 行高 150% / mono。

---

## 4. 消息气泡 / 消息行

### 用户消息（右对齐）

- 对齐：`flex-direction: column; align-items: flex-end`。
- 宽度：`width: fit-content; max-width: min(82%, 64ch); margin-left: auto`。
- 气泡背景：**layer-02**（亮 #f2f2f2 / 暗 #2e2e2e）。
- 内边距：`8px 12px`。
- 圆角：**10px**。
- 边框：无（v2 模式）。
- 文本：14px / 150% / `white-space: pre-wrap; word-break: break-word`。
- 附件芯片：圆角 6px，背景 layer-02，0.5px 标准边框；图片缩略图 48×48，文件芯片 `min(220px,100%) × 48px`。
- 附件间距 8px，附件→正文间距 8px。
- 悬停元信息行：高 24px，顶部 margin 4px，gap 10px，hover/focus 时 opacity 0→1。

### 助手消息（左对齐，全宽）

- 对齐：`flex-direction: column; align-items: flex-start; gap: 12px`。
- **无气泡背景**——文本直接落在页面背景上，由 Markdown 提供排版。
- 正文块 wrapper：`width: 100%; margin-top: 24px`（顶部额外留白）。
- "思考"子块：色 muted，13px / 行高 130%；内部 Markdown `margin-top: 16px`。

### 消息间距

- **回合之间**（顶层列表）：`gap: 24px`。
- 回合内助手内容之间：`gap: 12px`。
- 思考行：`margin-top: 12px; gap: 8px; min-height: 20px`。

---

## 5. 聊天页布局骨架

### 外层页面（`layout-new.tsx`）

```
<div bg=bg-deep, padding=env(safe-area-inset-*)>   // #fafafa / #080808
  <Titlebar/>                                       // 顶部窗口栏
  <main flex-1 contain=strict>                      // 性能隔离
    {children}
  </main>
  <HelpButton/> <ToastRegion/>
</div>
```

### 会话页（`session.tsx`）

```
<div size-full overflow-hidden flex-col>
  <SessionHeader/>
  <div flex-1 gap-2 p-2 flex [md:flex-row]>        // 外缘 padding 8px
    <div 宽度=sessionPanelWidth() transition[width 240ms cubic-bezier(.22,1,.36,1)]>
      <div 聊天卡: bg-base, rounded-10px, elevation-raised, overflow-hidden>
        {消息列表 + 输入停靠区}
      </div>
    </div>
    [右侧 Diff/审查面板，仅 md+，同样 rounded-10px]
  </div>
</div>
```

- **聊天卡圆角**：**10px**。
- **聊天卡阴影**：`--v2-elevation-raised`（见 §6）。
- 卡片距视口：8px（`p-2`）。

### 消息列居中模式（`message-timeline.tsx`）

- md+：`max-width: 800px; margin: 0 auto`（`md:max-w-200` = 200×4px）。
- 2xl+：放宽到 **1000px**。
- 消息行水平内边距：**移动 16px**（`px-4`）/ **≥768px 20px**（`px-5`）。
- 粘性会话标题头：`sticky top-0 z-30`，高 **48px**（`h-12`），底部渐变淡出到 bg-base。

### 输入停靠区（`session-composer-region.tsx`）

```
<div 数据=session-prompt-dock, w-full, 居中, pb-3, bg=bg-base, pointer-events-none>
  <div w-full px-3 md:max-w-200 2xl:max-w-1000px, pointer-events-auto>
    {停靠卡片体（权限/问答/待办/followup/revert 堆叠 + 输入框）}
  </div>
</div>
```

### 侧栏（桌面端，可作平板参考）

- 折叠态（图标条）：宽 **64px**（`w-16`），bg-base，`gap-3 px-3 py-3`。
- 展开态：至少 **244px**。悬停展开。

---

## 6. 阴影（elevation）

V2 elevation token（layered box-shadow）：

```
--v2-elevation-raised          // 聊天卡、输入框卡
  0 2px 4px 0 alpha-dark-4,
  0 1px 2px -1px alpha-dark-8,
  0 0 0 0.5px alpha-dark-12

--v2-elevation-floating        // 菜单/弹层
  0 8px 16px 0 alpha-dark-4,
  0 4px 8px 0 alpha-dark-8,
  0 0 0 0.5px alpha-dark-12

--v2-elevation-button-neutral
  0 1px 1.5px 0 alpha-dark-10,
  0 0 0 0.5px alpha-dark-14

--v2-elevation-button-contrast // 主按钮（发送）
  0 1px 1.5px 0 alpha-dark-20,
  0 0 0 0.5px grey-800,
  inset 0 1px 2px 0 alpha-light-14,
  inset 0 -1px 2px 0 alpha-dark-6
```

暗色主题下 `alpha-dark-*` 改用更重的 `alpha-dark-30`，并加 0.5px `alpha-light-16` 发丝边 + 顶部 `-0.5px alpha-light-6` 高光。

V1 阴影（备用）：`--shadow-xs/md/lg`，以及 `-border`、`-focus`、`-hover`、`-select` 变体（`theme.css:51-88`）。

---

## 7. 输入框 / Composer

### 新设计外壳（`prompt-input.tsx`）

```
<DockShell data-component=session-composer
  class="min-h-[96px] w-full rounded-xl bg-base shadow=[elevation-raised]">
```

- 最小高 **96px**，圆角 **10px**（`rounded-xl` = `--radius-xl`），背景 bg-base，阴影 elevation-raised，**默认无边框**；拖拽态 → 虚线 accent 边框。

### 编辑器（contenteditable）

```
min-h-[52px] w-full px-4 pt-4 pb-2
focus:outline-none whitespace-pre-wrap leading-5
text-[13px] font-[440] text-base
```

- 内边距：水平 16px，上 16px，下 8px。
- 最小高 52px，最大滚动高 180px（新设计）/ 240px（旧）。
- 字号 13px，可变权重 440，行高 20px。

### Placeholder

```
absolute top-0 inset-x-0 px-4 pt-4, 13px / weight-440, color=text-faint
```

文本：`"Ask anything, / for commands, @ for context..."`。

### 操作栏

`flex h-11 items-center px-2` → 高 **44px**，水平内边距 8px。

### 发送按钮

```
size-7 (28×28) rounded-md (6px) p-[6px]
icon 色 = icon-muted (#808080)
背景 = 线性渐变叠加 contrast 色 (#242424 亮 / #5c5c5c 暗)
阴影 = elevation-button-contrast
disabled: opacity 0.5
图标: arrow-up(发送) → stop(流式中) → arrow-undo-down(shell 模式)
```

### 附件按钮

`size-7 rounded-md ghost` 变体：透明背景，hover → overlay-hover。

---

## 8. 代码块 / Markdown

### Markdown 基础

```
color = text-base
font = Inter
font-size = 14px
line-height = 160%
overflow-wrap: break-word
```

### 标题

- h1：17px / 600 / 150% / margin-bottom 24px
- h2：15px / 600 / 150% / margin-bottom 24px
- h3：13px / 500 / 150% / margin-bottom 24px
- h4-h6：13px / 500 / 色 muted

### 段落 / 列表 / 引用

- `p`：margin-bottom 12px。
- `ul`：disc，padding-left 32px，margin-top 8px / bottom 12px。
- `ol`：padding-left 2.25rem（36px）。
- `li`：margin-bottom 8px，marker 色 muted。
- `blockquote`：`border-left: 0.5px border-base; margin: 1.5rem 0; padding-left: 0.5rem; color: muted; font-style: normal`。
- `hr`：无样式，高 0，`margin: 40px 0`。
- 链接：色 `text-interactive-base`（#034cff 亮 / #9dbefe 暗），仅 hover 下划线，`text-underline-offset: 2px`。
- 表格：宽 100%，`margin: 24px 0`，字号 14px，`th/td` 底边 0.5px border，padding 12px。
- 图片：`max-width: 100%; border-radius: 4px; margin: 1.5rem 0`。

### 行内代码

```
:not(pre) > code {
  font-family: mono;
  color: text-base;
  font-weight: 500;
  padding: 2px 4px;
  border-radius: 4px;
  background: color-mix(in oklch, text-base 8%, transparent);
}
```

路径类型行内代码（`[data-inline-code-kind="path"]`）：色 code-accent（blue-900 亮 / blue-400 暗），背景同 accent 8%。

### 围栏代码块（`.shiki`）

```
background: #fcfcfc (亮) / #151515 (暗)    // v1 background-stronger
color: text-base
font-size: 13px
padding: 12px
border-radius: 6px
border: 0.5px border-base
pre: margin-top 12px / bottom 32px / overflow auto / 隐藏滚动条
```

Shell 类型代码块覆盖：背景 layer-02（#f2f2f2 / #2e2e2e），边框 border-muted，token 跨强制 currentColor。

### 复制按钮

```
position: absolute; top: 4px; right: 4px;
opacity: 0;                                  // 默认隐藏
transition: opacity 0.15s;
// 代码块 :hover 时 opacity: 1
```

工具提示 pill：圆角 4px，背景 `surface-float-base`（#161616），13px / 500，0.5px 边框，`shadow-md`。复制后图标 copy → check。

### bash 输出块

```
width: 100%; border: 0.5px border-base;
border-radius: 6px; background: transparent; overflow: hidden;
scroll 区 max-height: 240px; overflow-y: auto; 隐藏滚动条;
pre: margin 0; padding 12px;
code: mono / 13px / 行高 150%; white-space: pre-wrap; overflow-wrap: anywhere;
```

### 诊断块（错误）

```
padding: 8px 12px;
background: state-bg-danger;            // 红面
border-top: 0.5px state-border-danger;
font: mono 13px / 150%;
color: state-fg-danger;
label: 大写 / 字距 -0.5 / 500
message: -webkit-line-clamp: 3
```

---

## 9. 间距 & 圆角刻度

### 间距（Tailwind v4 `--spacing: 0.25rem` = 4px 基准）

`p-N` / `gap-N` / `w-N` = `calc(var(--spacing) * N)`：

| 类 | 像素 |
|---|---|
| gap-2 / p-2 / px-2 | 8 |
| gap-3 / px-3 / py-3 | 12 |
| gap-4 / px-4 | 16 |
| px-5 | 20 |
| gap-6 / gap-24px | 24 |
| w-7 / size-7 | 28 |
| w-8 / size-8 | 32 |
| h-11 | 44 |
| w-16 | 64 |
| max-w-200（md 居中列） | 800 |

### 断点

```
sm 640  md 768  lg 1024  xl 1280  2xl 1536  3xl 1792  4xl 2048  5xl 2304
```

### 圆角 token

```
--radius-xs  2px    // 复选框、小控件
--radius-sm  4px    // 行内代码、tooltip、选项
--radius-md  6px    // 按钮、文件芯片、代码块、bash 块
--radius-lg  8px
--radius-xl  10px   // 输入框外壳、会话卡（核心圆角）
```

额外显式覆盖：用户气泡字面 `10px`；附件/上下文芯片 `6px`；旧 dock `12px`；头像/状态点 `rounded-full`。

---

## 10. 暗色模式机制（移植到 Compose 的建议）

### 原理

- 切换点在 `<html>`：`data-theme="oc-2"`（选主题）+ `data-color-scheme="light|dark"`（选明暗）。
- V2 token 由 **`[data-color-scheme]` 选择器**切换（**不是** `prefers-color-scheme` 媒体查询）。
- 用户选择持久化 `localStorage`：`opencode-theme-id`、`opencode-color-scheme`（`light`/`dark`/`system`）。
- `system` 时监听 `matchMedia("(prefers-color-scheme: dark)")` 实时更新。
- 跨标签同步：监听 `window.storage` 事件。
- 默认主题 `oc-2`；共 37+ 主题可选（`packages/ui/src/theme/themes/*.json`）。
- `<html>` 背景随明暗设 `#080808` / `#fafafa`。

### Compose 移植建议

1. **只移植 V2 + oc-2 默认主题**，忽略 v1 和其他 36 个主题（除非产品后续要主题市场）。
2. 在顶层 composable 定义两个 `ColorScheme`（light/dark），用 `isSystemInDarkTheme()` 作 `system` 模式默认；用 `DataStore<Preferences>` 持久化 `opencode-color-scheme`，键名照搬以保持概念一致。
3. 把 §2.4 两张表直接落成 `ColorScheme` 的 `background/surface/onSurface/primary/outline/...` 等 slot。
4. `data-new-layout` 不需移植（Android 端假设纯 V2）。
5. 字体：把 `Inter.ttf` 复制到 `app/src/main/res/font/`，用 `FontFamily` 加载；mono 可选加载 `JetBrainsMonoNerdFontMono-Regular.woff2`（先转 ttf/otf）。

---

## 11. 一页纸速查（最常调用的值）

| 项 | 值 |
|---|---|
| accent | `#3b5cf6`（暗色文字用 `#a2bcff`） |
| 聊天卡圆角 | **10px** |
| 用户气泡圆角 | **10px**，背景 layer-02（#f2f2f2 / #2e2e2e），padding `8×12` |
| 回合间 gap | **24px** |
| 消息行水平 padding | 移动 16 / ≥768 20 |
| 居中列宽 | md 800px / 2xl 1000px |
| 输入框最小高 | 96px（外壳）/ 52px（编辑器） |
| 输入框字号 | 13px / weight 440 |
| 发送按钮 | 28×28 / 圆角 6px / contrast 色（#242424 / #5c5c5c） |
| 消息正文字号 | 14px / 行高 150%（用户）/ 160%（助手 markdown） |
| 代码块 | 13px mono / padding 12px / 圆角 6px / 0.5px 边框 |
| 行内代码 | 500 / padding 2×4 / 圆角 4px / 8% 文本色底 |
| 间距基准 | 4px（Tailwind v4） |
| 字体 | Inter（sans）+ JetBrainsMono Nerd Font（mono） |

---

## 附录：移植优先级建议

1. **先建 token 层**：`ui/theme/OpencodeColors.kt`——把 §2.4 两表 + §9 间距/圆角落成 `data class` 或 `ColorScheme`，明暗各一份。这是后续一切的基础。
2. **字体**：复制 `Inter.ttf` 到 res/font，配 `Typography`。
3. **气泡**：先做用户/助手两个 Composable（§4），圆角 10px + layer-02 背景（用户）/无背景（助手）。
4. **Markdown**：用现有库（如 `dev.jeziellago:compose-markdown` 或自渲染），按 §8 调字号/代码块/行内代码样式对齐。
5. **输入框**：Compose 用 `BasicTextField`，外壳 Card（圆角 10px + elevation-raised），编辑器 13px / 行高 20px。
6. **布局**：`Scaffold` 顶部 SessionHeader，内容 `LazyColumn`（居中列宽 + 水平 padding），底部 Composer 停靠区。
