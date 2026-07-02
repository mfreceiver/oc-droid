建议你把 opencode 项目管理从“一个巨大的 `AGENTS.md`”改成 **分层治理 + 按需加载 + 脚本强约束**。核心原则是：

> `AGENTS.md` 只放“入口规则、索引、不可违反的总原则”；
> 具体流程放到独立规范文件；
> 能机器执行的全部写成脚本；
> agent 只负责理解、决策、调用流程，不负责记忆所有细节。

---

## 1. 推荐的整体结构

可以在项目根目录建立类似下面的目录：

```text
.
├─ AGENTS.md
├─ .opencode/
│  ├─ README.md
│  ├─ workflows/
│  │  ├─ release.md
│  │  ├─ bugfix.md
│  │  ├─ requirement-analysis.md
│  │  ├─ architecture-design.md
│  │  ├─ code-review.md
│  │  └─ version-control.md
│  ├─ policies/
│  │  ├─ quality-gate.md
│  │  ├─ security.md
│  │  ├─ build-signing.md
│  │  ├─ versioning.md
│  │  └─ repository-policy.md
│  ├─ checklists/
│  │  ├─ pre-release.checklist.md
│  │  ├─ pre-merge.checklist.md
│  │  ├─ bugfix.checklist.md
│  │  └─ upload.checklist.md
│  ├─ templates/
│  │  ├─ release-report.md
│  │  ├─ review-report.md
│  │  ├─ bug-report.md
│  │  ├─ design-proposal.md
│  │  └─ changelog-entry.md
│  ├─ manifests/
│  │  ├─ release.yaml
│  │  ├─ agents.yaml
│  │  ├─ build.yaml
│  │  └─ repos.yaml
│  └─ scripts/
│     ├─ release.sh
│     ├─ preflight.sh
│     ├─ build-release.sh
│     ├─ sign.sh
│     ├─ upload-gitea.sh
│     └─ bump-version.sh
```

这个结构的关键是：
**文本规范给 agent 看，YAML/脚本给机器执行，`AGENTS.md` 只做总入口。**

---

## 2. `AGENTS.md` 应该变成“启动器”，不要变成百科全书

你现在的问题，本质是把“长期规则、流程细节、项目知识、临时任务上下文”混在一起了。`AGENTS.md` 应该只放最稳定、最高优先级的内容。

建议控制在 100–200 行以内。

示例：

```md
# AGENTS.md

## Core Rule

This project uses a layered workflow system. Do not infer release, build, signing, upload, or versioning rules from memory. Always read the relevant workflow and policy files before acting.

## Rule Index

For release tasks:
- Read `.opencode/workflows/release.md`
- Read `.opencode/policies/build-signing.md`
- Read `.opencode/policies/versioning.md`
- Read `.opencode/checklists/pre-release.checklist.md`
- Use `.opencode/scripts/release.sh` unless explicitly instructed otherwise.

For bugfix tasks:
- Read `.opencode/workflows/bugfix.md`
- Read `.opencode/checklists/bugfix.checklist.md`

For requirement analysis:
- Read `.opencode/workflows/requirement-analysis.md`
- Output using `.opencode/templates/design-proposal.md`

For code review:
- Read `.opencode/workflows/code-review.md`
- Output using `.opencode/templates/review-report.md`

## Hard Gates

- Release builds must use release mode.
- Signing must use the configured signing key location in `.opencode/manifests/build.yaml`.
- Uploads must use the configured Gitea target in `.opencode/manifests/repos.yaml`.
- Release cannot proceed unless the required review agents have completed review and the score gate is satisfied.
- If any required rule file is missing or ambiguous, stop and report the blocker instead of guessing.

## Agent Behavior

- Before executing a workflow, state which workflow files were read.
- For objective operations, prefer scripts over manual commands.
- Do not bypass scripts unless the user explicitly asks.
- Do not create new release/version rules ad hoc.
```

这个文件的作用不是“包含所有规则”，而是告诉 agent：
**遇到什么任务，该去读哪个文件，该调用哪个脚本，哪些事情不能凭记忆。**

---

## 3. 把流程分成 4 类，不要混写

我建议你把 opencode 相关知识分成四层。

### 第一层：永久规则，放 `policies/`

比如：

```text
.opencode/policies/
├─ quality-gate.md
├─ build-signing.md
├─ versioning.md
├─ repository-policy.md
└─ security.md
```

这些文件描述“不随任务变化的制度”。

例如 `versioning.md`：

```md
# Versioning Policy

## Version Format

Use semantic versioning:

MAJOR.MINOR.PATCH+BUILD

## Patch Version

Increment PATCH for:
- Bug fixes
- Small compatibility changes
- Internal refactoring without user-visible behavior changes

## Minor Version

Increment MINOR for:
- New user-visible features
- New configuration options
- Backward-compatible API additions

## Major Version

Increment MAJOR for:
- Breaking changes
- Storage format incompatibility
- API behavior changes that require migration

## Forbidden

- Do not manually edit version numbers in multiple files.
- Use `.opencode/scripts/bump-version.sh`.
```

---

### 第二层：任务流程，放 `workflows/`

这些文件描述“遇到某类任务时应按什么步骤处理”。

比如 `release.md`：

```md
# Release Workflow

## Required Inputs

- Target version
- Release type: patch / minor / major
- Target branch
- Release notes source

## Required Review

Before build:
1. Run oracle review
2. Run fixer review
3. Run observer review if UI or behavior changed

Release may continue only if:
- oracle score >= 8
- fixer score >= 8
- no blocking issue remains

## Procedure

1. Confirm clean git status.
2. Confirm branch is allowed for release.
3. Run tests.
4. Run required agent reviews.
5. Apply fixes if needed.
6. Re-run affected tests.
7. Bump version using `.opencode/scripts/bump-version.sh`.
8. Build using release mode.
9. Sign using configured signing key.
10. Generate release notes.
11. Upload artifact to configured Gitea target.
12. Create release report using `.opencode/templates/release-report.md`.

## Forbidden

- Do not build debug artifacts for release.
- Do not upload manually unless script fails and user approves.
- Do not skip review gate.
```

---

### 第三层：可执行事实，放 `manifests/` 和 `scripts/`

比如签名 key、Gitea 地址、构建模式、上传工具这些不要让 agent “记住”，也不要只写自然语言。

放成结构化配置：

```yaml
# .opencode/manifests/build.yaml

build:
  release_mode: true
  command: "./gradlew assembleRelease"

signing:
  key_path: "/secure/keys/project-release.jks"
  alias: "project-release"
  env_password_var: "PROJECT_KEYSTORE_PASSWORD"

artifacts:
  output_dir: "app/build/outputs/apk/release"
  pattern: "*.apk"
```

```yaml
# .opencode/manifests/repos.yaml

gitea:
  host: "https://git.example.com"
  owner: "your-org"
  repo: "your-project"
  upload_tool: ".opencode/scripts/upload-gitea.sh"
```

然后脚本读取这些配置。这样 objective facts 不靠 prompt，也不靠 agent 记忆。

---

### 第四层：输出格式，放 `templates/`

这样可以避免每次都告诉 agent “评审报告怎么写、发布报告怎么写、需求分析怎么写”。

例如：

```md
# .opencode/templates/review-report.md

## Review Summary

- Reviewer:
- Scope:
- Verdict: PASS / PASS WITH FIXES / BLOCK
- Score:

## Blocking Issues

1.

## Non-blocking Issues

1.

## Risk Assessment

- Functional risk:
- Regression risk:
- Security risk:
- Release risk:

## Required Fixes

1.

## Final Recommendation

```

---

## 4. 最重要：能脚本化的不要交给 agent 判断

你提到的这些内容里，有一大半应该变成脚本强约束：

| 事项              | 不建议方式         | 推荐方式                        |
| --------------- | ------------- | --------------------------- |
| release 模式编译    | 让 agent 记住命令  | `build-release.sh`          |
| 签名 key 位置       | 写在大段 prompt 里 | `build.yaml`                |
| 上传到 Gitea       | 让 agent 手打命令  | `upload-gitea.sh`           |
| 版本号规则           | 自然语言提醒        | `bump-version.sh`           |
| 编译前 review gate | 靠 agent 自觉    | `preflight.sh` 检查 review 报告 |
| 禁止 debug 包上传    | 文字提醒          | 脚本检查 artifact 名称和路径         |
| git 分支规则        | prompt 约束     | 脚本检查当前 branch               |
| changelog 格式    | 每次说明          | template 生成                 |

你的目标不是让 agent “变得更听话”，而是让 agent **没有机会用错流程**。

---

## 5. 发布流程可以设计成一个“唯一入口”

例如所有发布都只能走：

```bash
.opencode/scripts/release.sh --type patch
```

`release.sh` 内部再调用：

```text
preflight.sh
run-tests.sh
collect-reviews.sh
bump-version.sh
build-release.sh
sign.sh
upload-gitea.sh
generate-release-report.sh
```

agent 的任务就变成：

1. 读 release workflow；
2. 判断是否满足启动条件；
3. 调用 release 脚本；
4. 解释失败原因；
5. 必要时修复；
6. 重新执行。

而不是让 agent 自己拼接十几条命令。

---

## 6. 对“评审 agent 打分后才能编译”的建议

你可以把 review gate 设计成一个结构化产物，而不是聊天记录。

比如每个评审 agent 必须输出到：

```text
.opencode/runs/reviews/2026-07-02/oracle.json
.opencode/runs/reviews/2026-07-02/fixer.json
.opencode/runs/reviews/2026-07-02/observer.json
```

格式类似：

```json
{
  "agent": "oracle",
  "scope": "release",
  "score": 8.5,
  "verdict": "pass",
  "blocking_issues": [],
  "non_blocking_issues": [
    "Consider adding regression test for version parsing."
  ],
  "reviewed_commit": "abc1234"
}
```

然后 `preflight.sh` 检查：

```text
- 是否有指定 agent 的 review 文件
- reviewed_commit 是否等于当前 HEAD
- score 是否达标
- blocking_issues 是否为空
- 是否超时或过期
```

这样比“聊天里说已经评审过”可靠得多。

---

## 7. 你可以建立一个“流程索引表”

在 `.opencode/README.md` 里写一个简洁索引：

```md
# OpenCode Project Operating System

## Task Routing

| Task Type | Workflow | Required Policies | Script |
|---|---|---|---|
| Release | workflows/release.md | quality-gate.md, build-signing.md, versioning.md | scripts/release.sh |
| Bugfix | workflows/bugfix.md | version-control.md | scripts/preflight.sh |
| Requirement analysis | workflows/requirement-analysis.md | repository-policy.md | none |
| Architecture design | workflows/architecture-design.md | quality-gate.md | none |
| Code review | workflows/code-review.md | quality-gate.md | none |
| Upload artifact | workflows/release.md | repository-policy.md | scripts/upload-gitea.sh |
```

`AGENTS.md` 只需要告诉 agent：
**先查这个索引，再读对应文件。**

---

## 8. 对问题排查、需求理解、方案制定、代码管理分别建独立 workflow

例如：

```text
workflows/
├─ bugfix.md
├─ requirement-analysis.md
├─ architecture-design.md
├─ version-control.md
└─ release.md
```

### `bugfix.md` 可以规定：

```md
# Bugfix Workflow

1. Reproduce or localize the issue.
2. Identify the smallest affected module.
3. Search related logs, tests, recent commits.
4. State root-cause hypothesis before editing.
5. Make minimal code change.
6. Add or update regression test if feasible.
7. Run focused tests first.
8. Run broader tests if core behavior changed.
9. Summarize:
   - cause
   - changed files
   - verification
   - residual risk
```

### `requirement-analysis.md` 可以规定：

```md
# Requirement Analysis Workflow

Output must include:

1. User goal
2. Current behavior
3. Target behavior
4. Non-goals
5. Ambiguities
6. Proposed implementation
7. Risk points
8. Test strategy
9. Files likely affected

Do not modify code during requirement analysis unless explicitly asked.
```

### `version-control.md` 可以规定：

```md
# Version Control Policy

## Branches

- main: stable integration branch
- feature/*: feature work
- fix/*: bugfix work
- release/*: release preparation

## Commit Rules

- One logical change per commit.
- Do not mix refactor and behavior change unless necessary.
- Commit message format:
  type(scope): summary

## Merge Rules

- Do not rebase shared release branches.
- Use merge commit for multi-agent worktree integration when preserving lineage is useful.
- Before merge, run pre-merge checklist.
```

---

## 9. 减少提示词浪费的关键：按需加载，而不是全局加载

你不希望所有内容放进 `AGENTS.md`，这个判断是对的。

更好的策略是：

```text
AGENTS.md：只放索引和硬规则
↓
任务开始时读取对应 workflow
↓
workflow 再引用 policy / checklist / template
↓
脚本读取 manifest
```

也就是：

```text
少量常驻上下文 + 大量按需上下文 + 客观操作脚本化
```

这比“一个超长 AGENTS.md”稳定得多。

---

## 10. 推荐一个更抽象的模型：把项目当成“小型操作系统”

你可以把 opencode 项目管理抽象成：

| 层级          | 内容        | 例子                                   |
| ----------- | --------- | ------------------------------------ |
| Kernel      | 不可违反的核心规则 | 不跳过 review gate、不 debug 发布           |
| Routing     | 任务路由      | release 读哪些文件                        |
| Workflow    | 操作流程      | 发布、修复、需求分析                           |
| Policy      | 长期制度      | 版本号、签名、安全                            |
| Manifest    | 机器可读配置    | key 路径、Gitea 地址                      |
| Script      | 可执行动作     | build、sign、upload                    |
| Template    | 输出格式      | review report、release report         |
| Run Records | 本次执行证据    | review json、build log、release report |

这样管理后，agent 不需要“记忆整个项目制度”，只需要知道如何进入制度。

---

## 11. 我建议你优先落地这 5 个文件

不需要一开始就建很复杂。可以先建最小可用版本：

```text
AGENTS.md
.opencode/README.md
.opencode/workflows/release.md
.opencode/policies/versioning.md
.opencode/policies/build-signing.md
.opencode/scripts/release.sh
```

然后逐步增加：

```text
.opencode/workflows/bugfix.md
.opencode/workflows/requirement-analysis.md
.opencode/templates/review-report.md
.opencode/templates/release-report.md
.opencode/manifests/build.yaml
.opencode/manifests/repos.yaml
```

---

## 12. 最推荐的实践方式

你的项目里可以形成一句强规则：

> 任何 release、上传、签名、版本号修改，都不得由 agent 自由发挥；必须通过 `.opencode/workflows/release.md` 和 `.opencode/scripts/release.sh` 执行。

这句话应该放进 `AGENTS.md`。

同时，所有细节不要塞进 `AGENTS.md`，而是下沉到：

```text
workflow → policy → manifest → script
```

---

## 结论

你的问题不是“`AGENTS.md` 写得不够详细”，而是需要把 opencode 使用方式从“提示词驱动”升级为“流程系统驱动”。

我建议采用：

```text
AGENTS.md 作为入口索引
.opencode/workflows/ 管任务流程
.opencode/policies/ 管长期规则
.opencode/manifests/ 管机器可读配置
.opencode/scripts/ 管强制执行
.opencode/templates/ 管输出格式
.opencode/runs/ 管执行证据
```

这样既能减少提示词浪费，又能让稳定流程可复用、可审计、可迭代。对你这种大量使用 opencode 做项目开发、并且已经有多 agent preset / review gate / release 规范需求的场景，这会比单纯维护一个巨大 `AGENTS.md` 稳定很多。
