# 文件功能重构 · 需求暂存（非方案）

> **状态：暂存，非执行方案。** 本期文件功能**不做改动**。用户正在思考更多功能，需求收齐后再产出完整重构方案。
> 诊断基于 exp-5（本项目现状审计）+ exp-6（opencode web workdir 选取）两份研究。

---

## 1. 现状诊断（为什么体验差）

**根因**：本项目把 workdir 当成会话的副作用，而 opencode web 把它当成一等公民"Project"。

| 维度 | 本项目现状 | opencode web v2 | 差距 |
|---|---|---|---|
| workdir 概念 | 无。仅 `Session.directory` → `repository.currentDirectory` 内存变量 | **Project = git worktree**，服务端 `sdk.project.list()` 发现 + 客户端按 server 持久化 | 🔴 大 |
| 持久化 | 无。切 tab/进程死即丢；"断开 workdir"是 Composable 的 `remember` | 按 server scope 存 `localStorage` | 🔴 大 |
| 选取 UI | 单一 `DirectoryPicker`，硬编码 `/home/` 起点一级级点 | 三处：Home 落地页项目列表 / Composer 内项目下拉 / 目录树对话框(带模糊搜索) | 🟡 中 |
| 独立浏览 | ❌ 必须 session.directory 选中才能浏览 | ✅ 选 project 即可浏览，与 session 解耦 | 🔴 大 |
| 文件树 | 平铺 `LazyColumn`，一级级进退 | 递归 `FileTree`（懒加载子目录），diff 着色，拖拽 | 🔴 大 |
| 文件搜索 | ❌ `find/file` 数据层实现了没接 UI | ✅ `sdk.find.files` 模糊搜索 | 🟡 中 |
| 代码预览 | 纯等宽文本，无高亮无行号 | Pierre 虚拟化查看器，行选择/评论/文件内搜索 | 🔴 大 |
| Markdown 预览 | WebView（白闪，代码里一堆缓解=病征） | 原生 markdown 渲染 | 🟡 中 |
| Diff 查看 | 聊天里只有 `+N -M` stat；Files 页无 diff | 虚拟化 DiffViewer，行级评论 | 🔴 大 |
| 目录预览 | hack（文件树 join 成纯文本） | 不预览目录，走文件树 | — |
| 编辑 | 只读 | 只读 | ✅ 一致 |
| header 机制 | `X-Opencode-Directory` interceptor | 同（per-directory SDK client） | ✅ 一致 |

**主要痛点（按影响排序）：**
1. 代码文件无高亮无行号（最扎眼）
2. 平铺列表浏览，无树
3. 必须"开会话"才能看文件
4. workdir 不持久化、换 tab 就丢
5. 无搜索
6. WebView markdown 白闪

## 2. 已锁定偏好（待重构时落实）

- ✅ **Markdown 预览换原生 mikepenz**（与聊天页同一渲染栈，消除白闪，主题天然统一）。
  - 与 `v2-redesign-plan.md` §3.1 的 `CodeHighlighter` 共享：Files 页代码预览也用 `dev.snipme:highlights` + 行号。
- 复用点：聊天页代码高亮栈 = Files 页代码预览栈，投入一份两处受益。

## 3. 候选改造方向（待选档，确认范围后再细化）

- **档 1（最小）**：代码高亮+行号（复用 `CodeHighlighter`）、Markdown 原生化、接 `find/file` 搜索。
- **档 2（中等）**：+ workdir 一等公民化（`HostProfile` 加 workdir 字段 + `SettingsManager` 持久化）、Files 页独立选 workdir 浏览、平铺→递归文件树、Composer 项目选择器。
- **档 3（完整）**：+ 虚拟化 DiffViewer、文件内搜索、Home 落地页项目列表。

## 4. 待用户思考的功能（暂存区，想到即追加）

> 占位。用户表示要"多思考几个功能，确认后全面重构"。新增需求在此处累积，收齐后据此产出完整方案。

- _(空，待补充)_

---

## 关键源码索引（重构时查证用）

**本项目：**
- `ui/files/FilesScreen.kt` — 入口，pager 第 2 页
- `ui/files/FileBrowserPane.kt` — 平铺列表
- `ui/files/FilePreviewPane.kt` — 预览分发（image/markdown-web/markdown-native/source/binary/text）
- `ui/files/MarkdownWebPreviewPane.kt` — WebView markdown（白闪缓解逻辑）
- `ui/files/FilesViewModel.kt` — `FilesUiState`、加载逻辑
- `ui/sessions/DirectoryPicker.kt` — `/home/` 起点的服务端 FS 浏览
- `data/repository/OpenCodeRepository.kt` — `X-Opencode-Directory` interceptor、`currentDirectory` 单变量
- `data/api/OpenCodeApi.kt` — `file`/`file/content`/`file/status`/`find/file`（后者未接 UI）
- `data/model/HostProfile.kt` — host 配置（**无 workdir 字段**）
- `util/SettingsManager.kt` — 持久化（**无 workdir key**）

**opencode web 参考（oc-ref 内）：**
- `packages/app/src/context/server.tsx` — ServerConnection（server ≠ workdir）
- `packages/app/src/pages/home.tsx` — NewHome 落地页、项目列表
- `packages/app/src/components/prompt-project-selector.tsx` — Composer 项目下拉
- `packages/app/src/components/dialog-select-directory-v2.tsx` — 目录树对话框(带 search)
- `packages/app/src/components/file-tree.tsx` — 递归 FileTree
- `packages/app/src/context/file.tsx` — FileProvider（load/tree/search）
- `packages/session-ui/src/components/file.tsx` — Pierre 文本/diff 查看器
- `packages/app/src/context/global-sync/bootstrap.ts:90` — `sdk.project.list()` 项目发现
- `packages/sdk/js/src/v2/client.ts:50` — per-directory SDK client（设 `x-opencode-directory` header）
