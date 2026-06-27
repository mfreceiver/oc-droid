# 安全审计工作流（同步上游后触发）

> **目标**：每次 `master` 跟进 GitHub 上游更新后，**只对"差异部分"做增量安全审计**（排查后门 + 严重性能问题），产出新的带日期报告。全量基线见 `docs/security-audit-2026-06-27.md`。
>
> **触发时机**：执行 `bash scripts/sync-upstream.sh`（master 同步上游）成功之后。审计是**手动后续步骤**，不在同步脚本内自动执行（多模型评审较重）。

---

## 一、为什么要"差异审计"

- 上游已通过全量基线审计（2026-06-27），其代码可信。
- 真正的风险来自**每次上游更新带来的新增/改动代码**——上游可能引入新依赖、新网络端点、新的凭证处理逻辑等。
- 因此聚焦 `git diff` 出来的差异，既快又精准，不必每次重审整个仓库。

---

## 二、执行步骤

### 步骤 1：确定差异范围

读取本文件末尾「审计台账」的**最近一次"已审计上游基点"**，计算到新同步点 `upstream/master` 的差异：

```bash
# 上次审计基点（从台账取，例如 ec517f1）
LAST_AUDITED_BASE=ec517f1
# 同步后的上游最新
NEW_BASE=$(git rev-parse upstream/master)

echo "=== 新增/改动的上游提交 ==="
git log --oneline "$LAST_AUDITED_BASE"..upstream/master

echo "=== 改动的文件（含 app 源码、Manifest、网络安全配置、构建、依赖、WebView 资源、ProGuard）==="
git diff --stat "$LAST_AUDITED_BASE"..upstream/master \
  -- app/src/main app/build.gradle.kts gradle/libs.versions.toml settings.gradle.kts app/proguard-rules.pro
```

> 若 `LAST_AUDITED_BASE..upstream/master` 无 app 源码/配置改动（仅文档/CI），可判定本次**无需审计**，只在台账记一笔"无差异代码"即可。

### 步骤 2：三方并行审计差异部分

派 **gpter / glmer / kimo**（或 `/council review-3`）**只审差异文件**，关注：

- **后门**：新增硬编码凭证、新网络端点（数据外发）、新增依赖是否可信、新代码是否引入可绕过的鉴权/调试后门。
- **严重性能**：新增代码的主线程阻塞、内存泄漏、资源未释放、低效逻辑。
- **回归**：上游本次改动是否**重新引入**已在基线报告里标注待修的问题（H1~H4、M1~M6）。

给评审方的 prompt 要点：指明 `diff` 范围（文件 + 行）、项目背景、输出格式（严重度/类型/位置/描述/修复），明确只审差异、不重复全量。

### 步骤 3：产出新的带日期报告

文件命名：`docs/security-audit-YYYY-MM-DD.md`（同一天多次则加序号 `-2`、`-3`）。

报告头部记录**元数据**（版本、日期、差异范围、审计方、后门结论），正文为发现清单。模板：

```markdown
# 安全审计报告（增量）

> **审计项目**：opencode_android_client（mfreceiver fork）
> **项目版本**：<versionName，如 0.1.202607xx>
> **审计日期**：<YYYY-MM-DD>
> **审计类型**：增量（同步上游后）
> **差异范围**：上游 <old-base..new-base>（<N> 个上游提交，<M> 个 app 文件变动）
> **审计基线**：上游基点 <new-base>；dev HEAD @ <commit>
> **审计范围**：仅差异文件 —— <列出 app 源码/配置/依赖等>
> **审计方式**：gpter / glmer / kimo 三方并行
> **后门结论**：<yes/no>

---（发现清单，格式同基线报告）---
```

### 步骤 4：更新本文件末尾的「审计台账」

把本次审计的日期、上游版本、新基点、dev HEAD、报告文件追加到台账。台账的"已审计上游基点"即下一次差异审计的起点。

---

## 三、审计台账

| 审计日期 | 类型 | 上游版本 | 已审计上游基点 | dev HEAD | 报告 |
|----------|------|----------|----------------|----------|------|
| 2026-06-27 | 全量基线 | v0.1.20260622 | `ec517f1` | `27b4dc0` | `docs/security-audit-2026-06-27.md` |

> 下次差异审计起点 = 台账最后一行的「已审计上游基点」。

---

## 四、相关

- 基线全量报告：`docs/security-audit-2026-06-27.md`
- 上游同步流程：`FORK_SYNC.md` + `scripts/sync-upstream.sh`
- Agent 指引：`AGENTS.md`「安全审计」章节
