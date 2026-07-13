# MiMo-Code 借鉴方案设计

> ⚠️ **v2 勘误（2026-07-12，四 agent 调研后，以此为准）**
>
> 本文档 A/B/C/D 节成文较早，基于"用户跑 sst 上游 opencode"的错误前提。经四 agent 独立复测（源码 `opencode-src/v1.17.18` + 本机 binary `~/.opencode/bin/opencode` = **1.17.18**），修正三条关键结论：
>
> 1. **血统**：`opencode-src/v1.17.18` 与本机 binary 都是 **anomalyco/opencode**（package.json repository.url 证实），**不是 sst 上游**。但 anomalyco v1.17.18 是 32 包结构、跟踪 sst 上游，**能力 ≠ MiMo**（MiMo 是 17 包大单体 + actor/记忆系统，是 anomalyco 的另一旧分支血统）。
> 2. **`task` 工具是原生 sub-session 机制**（`packages/opencode/src/tool/task.ts`）：创建独立子 session（`parentID` 指向主 session）+ **fresh context 不污染主 session** + 文本返回值可直接喂下一个子 agent。原文 `task.txt:16`："Each agent invocation starts with a fresh context"。**用户"不污染主 session、输出转输入"的需求有原生答案**，无需自造。见 [E2](#e2-核心答案task-工具就是你要的-sub-session-机制)。
> 3. **codemode 修正**：anomalyco v1.17.18 的 codemode **只有基础 JS 沙箱，无 `agent()`/`parallel()` 原语**（`codemode/src/stdlib/` 仅 collections/console/date/json/math/... 基础类型）。D4 节"上游已有 Dynamic Workflow 可用"判断**错误**——MiMo 博客的 Dynamic Workflow 是 MiMo 独有扩展。anomalyco 的 codemode 需 `flags.experimentalCodeMode` 才作为 `execute` 工具启用，且不能派子 agent。
>
> A/B/C/D 节保留作分析背景，**结论性内容以 E 节为准**。

> ⚠️ **v3 勘误（2026-07-13，ocmar 全量实施 + Stage 6 实战后，以此为准）**
>
> v2/F 节的 8 Stage 计划已**全部执行完成**（Stage 1–6 实施 + Stage 7 schedule resume dry 验证 + Stage 8 本文档收口）。关键结论修正与固化：
>
> 1. **ocmar 已落地**：10 个 `ocmar-*` skill 安装到 `~/.config/opencode/skills/`，deepwork 归档到 `archive/deepwork-retired/` + OMO 双重禁用（`disabled_skills:["deepwork"]` + orchestrator `skills:["*","!deepwork"]`）防 auto-update 复活。回滚快照在 `archive/pre-ocmar/`。
> 2. **F.2 六大风险点全部解决并验证**：①Git 不自动 commit（ocmar 默认 record diff）②task 无 model 参数 + 角色路由（fixer/oracle/explorer/librarian）③GLM 计费 → brief/report/diff 文件交接 + small-task 单 oracle 双 verdict ④fresh verifier 跑项目校验命令 ⑤using-superpowers 触发收紧 ⑥脚本 `.ocmar` + 可移植。
> 3. **Stage 6 实战证明 review 非走过场**：sync-player 真实任务（VideoUtils+FormatUtils 测试）跑完整流程，**final whole-branch review 抓到 per-task review 漏的真实 bug**（一个额外测试 codifies 了 `formatFileSize` >TB 产线缺陷）→ fix → re-review pass。证明两阶段评审（per-task + whole-branch）的独立价值。详见 [F.6](#f6-stage-6-实战证据sync-player2026-07-13)。
> 4. **持久 ledger 状态机验证**：三态 `implementing→reviewed→verified` + 内容级 content-fingerprint（plan-hash/base/fingerprint 三校验）+ owner-session + Stage 7 resume dry 全通过。schedule_task `mode=existing` 注入 → conductor resume 链路设计可行。
> 5. **改进点（留持续优化）**：final verifier 命中 gradle `UP-TO-DATE` 从 JUnit XML 读结果（有效但非 live rerun），verifier prompt 可加 `--rerun-tasks` 消除缓存疑虑。
>
> 结论性内容：**F 节（含 F.6 实战证据 + F.7 回滚 runbook）为最权威**。

> 对比源：`opencode-src/mimo-code/`（v0.1.5，fork 自 anomalyco/opencode 旧大单体分支）
> 基准：`opencode-src/v1.17.18/`（上游 sst/opencode）
> 落点：用户运行时 = **上游 opencode 1.17.9 + oh-my-opencode-slim (OMO)**（见 `~/.config/opencode/package.json`）
> 范围：用户感兴趣的 3 个"可直接照搬"skill（compose 闭环 / design-blueprint / skill-creator）+ plugin 的「超时+熔断+回滚三件套」+「preStop/postStop 循环控制」

---

## 0. 关键前提（决定可行性，必须先读）

### 0.1 两个运行时不是一回事

| | 你的环境 | MiMo-Code |
|---|---|---|
| 血统 | 上游 sst/opencode **1.17.9** | anomalyco/opencode fork（再往上也是早期 opencode）|
| plugin SDK | `@opencode-ai/plugin@1.17.9` | `@mimo-ai/plugin` |
| agent 循环抽象 | 无 `actor` / 无 `task` 工具 | **有** `actor`（spawn/turn/registry/waiter）+ `task` 工具 |
| 终止控制 hook | **无** `actor.preStop`/`actor.postStop` | **有**（ReAct 循环终止前后拦截）|

**直接影响**：
- A 类（skill，纯 markdown）→ **可直接移植**，与运行时无关。
- B 类（preStop/postStop hook）→ **上游不支持**，不能直接用，只能"思路借鉴"或换运行时。

### 0.2 上游 opencode 1.17.x 实际可用的 plugin Hooks

来源：`opencode-src/v1.17.18/packages/plugin/src/index.ts:222-335`

```
dispose | event | config | tool | auth | provider
chat.message | chat.params | chat.headers
permission.ask
command.execute.before
tool.execute.before | tool.execute.after | tool.definition
shell.env
experimental.chat.messages.transform | experimental.chat.system.transform
experimental.provider.small_model
experimental.session.compacting | experimental.compaction.autocontinue
experimental.text.complete
```

**没有** `actor.preStop` / `actor.postStop`。最接近"循环控制"的是 `experimental.compaction.autocontinue`（压缩后是否自动续跑）和 `event`（订阅全部事件）。

### 0.3 一个被掩盖的事实：你的 writing-plans 已经断链

`~/.config/opencode/skills/writing-plans/SKILL.md` 与 MiMo `compose:plan` **几乎逐字同源**（都来自 obra/superpowers），但你的版本引用：

- `superpowers:using-git-worktrees`
- `superpowers:subagent-driven-development`
- `superpowers:executing-plans`

这些 skill **在你的环境里不存在**（你装的是 OMO，不是 obra/superpowers）。也就是说 plan 写出来后，它指示的执行路径是断的。compose 系列的价值之一就是把这条链补成自洽闭环（前缀统一为 `compose:`，且每个下游 skill 真实存在）。

---

# A 类：三个可直接照搬的 skill

## A1. compose 方法论 skill 闭环

### 架构

一组互相编排的 SKILL.md，构成"需求 → 计划 → 隔离环境 → 执行 → 校验 → 评审 → 合并 → 报告"完整工作流，外加交叉技能 ask（结构化提问）/ tdd / brainstorm。

```
compose:plan ──┐
               ├─→ compose:worktree（隔离环境）
compose:brainstorm ─┘           │
                                 ▼
                    compose:subagent（同会话，每任务一个 fresh subagent）
                    compose:execute（另开会话，批量执行）
                                 │
                  每任务：compose:tdd → compose:verify → compose:review
                                 │   (spec-anchored 两阶段评审门禁)
                                 ▼
                    compose:merge → compose:report
任一节点卡住 → compose:ask（结构化选项提问，而非散文询问）
```

- 全部 `hidden: true`（不进 available_skills 列表，靠其它 skill 显式 invoke），避免污染上下文。
- 用 memory 文件（`compose-preferences`）持久化用户偏好（如执行方式 subagent vs inline）。
- subagent 分派时，**控制器把所需 SKILL.md 内容直接塞进 prompt**（compose skill 默认不进 subagent 的 available_skills）。
- 依赖 anomalyco 的 `task` 工具（`task create` / `task done`）与 `actor run` 命令做任务绑定与 postStop 校验。

### 目标

把"写一个复杂功能"从一次性大 prompt 拆成：spec 锚定 → 隔离执行 → 证据驱动评审 → 自动门禁。核心反模式是"subagent 自说自话写完就结束"，compose 用 **spec-anchored 两阶段评审**强制每个 in-scope 声明都有可验证证据（测试名/命令输出/file:line）。

### 实现方式（关键机制）

1. **Announce at start**：每个 skill 强制开头声明"I'm using the compose:X skill"——可观测、可审计。
2. **No Placeholders**：`TBD/TODO/implement later/add appropriate error handling` 一律视为 plan failure。
3. **Self-Review**（compose:plan）：spec coverage / placeholder scan / type consistency 三查。
4. **Spec-anchored review gate**（compose:subagent）：
   - Phase 1：只给评审者「spec 段落 + git diff」，**不给实现者报告**（防止报告锚定偏见）。
   - Phase 2：仅当 Phase 1 有 flag 时再给报告，**只降级不升级**。
   - Gate：所有 in-scope claim 必须 `pass` 且有证据，否则 implementer 修→重审，循环到通过。
5. **Continuous execution**：不在任务间停下来问"要继续吗"，只在 BLOCKED/真实歧义/全部完成时停。
6. **Model tier 分级**：机械实现用便宜模型，评审用 ≥ 实现者能力的模型（防 rubber-stamp）。
7. 配套 `references/` 与 `*-prompt.md` 模板（implementer-prompt / spec-reviewer-prompt / code-quality-reviewer-prompt）。

### 优点

- 闭环自洽，链路不断（对比你当前 writing-plans 断链）。
- 评审是**证据驱动 + 两阶段 + adversarial**，不是"看起来对了"。
- 控制器上下文不被污染（fresh subagent per task）。
- 强制 announce / 反 placeholder / self-review，产出可审计。

### 缺点 / 代价

- **重**：每个任务 = implementer + 2 reviewer，token/成本高 2-3 倍。MiMo 自己也承认"More subagent invocations ... But catches issues early"。
- **依赖 `actor`/`task` 工具**：compose:subagent 的任务绑定、postStop 校验强依赖 anomalyco 的 actor/task。上游 opencode 无 `actor run`，`task` 工具是否存在需验证（见引进方式）。
- 对"探索性/一次性脚本"过度工程化——它自己说 ≤3 紧耦合任务用 inline，>3 独立任务才用 subagent。
- hidden skill 互引，新人调试时不易发现链路。

### 如何引进

1. **复制 bundle**：
   `opencode-src/mimo-code/packages/opencode/src/skill/compose/.bundle/*` → `~/.config/opencode/skills/compose-*/`（每个子目录一个 skill，保留内部 `*-prompt.md`/`references/`）。
2. **改前缀**：若你想保留旧 skill，把 SKILL.md 里 `compose:` 与你现有命名空间对齐（或保留 `compose:` 作为独立家族，与 writing-plans 共存）。
3. **降级适配 actor/task 依赖**：
   - compose:subagent 里 `actor run general "..." "..." --task T1` → 改用上游 `task` 工具（若可用）或 opencode 的 background session / subagent 机制。
   - 若上游无等价物，**先用 compose:plan + compose:execute + compose:tdd + compose:verify + compose:review 这条不依赖 actor 的链**，放弃 compose:subagent 的自动任务绑定（手动维护任务清单）。
4. **先验证 task 工具**：在你的 opencode 里跑一次 `task create` 看是否可用，决定 compose:subagent 能否原样用。
5. **处理与 writing-plans/test-driven-development 的重复**：见下。

### 与你现有功能的冲突 / 重合

| 你现有 | compose 对应 | 关系 | 建议 |
|---|---|---|---|
| `writing-plans` | `compose:plan` | **同源旧副本 + 断链** | 用 compose:plan **替换** writing-plans（修复 superpowers:* 断链）|
| `test-driven-development` | `compose:tdd` | 同源，compose 版做了 subagent 适配 | 保留你的 tdd（371 行更完整），compose:subagent 里引用它即可 |
| `brainstorming` | `compose:brainstorm` | 重叠 | 二选一，或 compose 引用你的 brainstorming |
| `grill-me` / `grilling` | `compose:review` 部分 | 目标相近（压力测试）但 grill 针对人，review 针对 diff | 不冲突，并存 |
| `worktrees`（skill）| `compose:worktree` | 同源 | 保留你的，compose:worktree 可删或并存 |
| `deepwork` | 整个 compose 闭环 | **目标高度重叠，机制不同** | 见下单独分析 |

**deepwork vs compose（重要决策点）**：
- deepwork = orchestrator-centric，依赖 OMO 的 `@oracle`/`@designer`/`@fixer` agent + `.slim/deepwork/` 进度文件 + phased 实施；评审靠 `@oracle`。
- compose = controller-centric，依赖 `actor`/`task` + spec-anchored 两阶段评审 + fresh subagent。
- 两者解决同一问题（重活编排）。**建议不要同时启用**，否则 agent 会混乱。若你深度依赖 OMO 的 agent 生态（oracle 评审面板等），保留 deepwork；若你想要更通用、不绑 OMO 的方案，换 compose。

---

## A2. design-blueprint（设计方法论 skill）

### 架构

单 skill + 一套 `references/`，基于「六层模型」（Instructions / Taste / Constraints / Feedback / Memory / Orchestration），单次蓝图只显式操作 Taste/Constraints/Feedback 三层，其余继承。

```
SKILL.md（主流程：Move 0-5）
├─ references/six-layer-model.md      （框架全貌，按需读）
├─ references/embody-modes.md         （7 种设计师身份 + 混合指南）
├─ references/anti-slop.md            （反 AI 套路化清单）
├─ references/decision-trace.md       （每个非显然选择记录原因）
├─ references/design-directions.md
└─ assets/design-md-template.md       （DESIGN.md 模板）
```

产物是 `DESIGN.md`（品牌侧持久 spec）+ 结构布局 + Decision Trace，**先于任何视觉产物**。

### 目标

治"AI 生成的设计一眼假"——同款渐变 hero、同款卡片栅格、同款 emoji 列表。根因是模型在"有观点"之前就渲染像素。强制先 spec，把"通用 AI 设计师"坍缩成一个具体身份（Slide Deck Designer / Editorial Web Designer / …），让选择空间收窄到那个行当的专家真正会做的选择。

### 实现方式（关键机制）

1. **Move 0 — Reuse before regenerate**：先找已有 `DESIGN.md`，有则复用、只发 delta，**绝不从零重写**（否则品牌漂移）。
2. **Move 1 — Embody**：选一个具体设计师身份并声明，每个身份有「先问什么问题」（如 Slide Deck Designer 先问"阅读型还是演讲型"）。
3. **Move 2 — Ground the brief**：按 brief 里 taste-signal 多少分叉（Junior Designer mode vs 5-Direction Picker）。
4. **每个 Move 带 Why**：理解 Why 就能因地制宜，而不是机械执行。
5. **Decision Trace**：每个非显然选择留原因，可审计、可回溯。
6. **references 按需加载**：主 SKILL.md 精简，重资料放 references，只在用户问或审计时读（progressive disclosure）。

### 优点

- 把"设计"从一次性生成变成可累积的品牌资产（DESIGN.md 跨产物复用）。
- 反 slop 清单 + embody 身份 = 显著降低 AI 味。
- Decision Trace 让设计决策可解释、可评审。
- progressive disclosure 让上下文占用可控。

### 缺点 / 代价

- 偏"视觉/产品/营销"设计，对你（Android 客户端 + 后端 coding）**日常相关性中等**——除非做 UI/落地页/海报。
- 多了一层"先写 DESIGN.md 再实现"的流程，对快速原型是负担。
- references 需要一并移植，单复制 SKILL.md 会丢上下文。

### 如何引进

1. 整目录复制：
   `opencode-src/mimo-code/packages/opencode/src/skill/builtin/.bundle/design-blueprint/` → `~/.config/opencode/skills/design-blueprint/`（含 SKILL.md + references/ + assets/）。
2. 触发词已写在 frontmatter（"make a slide / landing page / dashboard / poster / 帮我做个页面"），无需额外配置。
3. 若不做视觉设计，可只借鉴它的 **skill 写作范式**（见 A3 与附录"高质量 SKILL.md 规范"）。

### 与你现有功能的冲突 / 重合

- 你有 `designer` **agent**（OMO preset 里的 designer 角色），但**没有设计方法论 skill**。design-blueprint 是 designer agent 缺的那层"怎么想"，互补不冲突。
- 你的 `simplify` skill 是代码简化，无关。
- 无替换关系，纯新增。

---

## A3. skill-creator（元 skill：用 skill 生产 skill）

### 架构

教 agent 如何创建/评审/改进/校验 SKILL.md 的元 skill。核心理念：**progressive disclosure（渐进披露）**。

```
skill-creator/SKILL.md（4 步创建 + 评审/修复/校验流程）
└─ references/frontmatter.md（可选字段、good/bad description 范例）
```

skill 文件夹规范：
```
your-skill/
├── SKILL.md       # 必须 — frontmatter + 指令
├── scripts/       # 可选 — 可执行代码（确定性优于散文）
├── references/    # 可选 — 按需加载的文档
└── assets/        # 可选 — 模板/字体/图标
```

### 目标

把"写 skill"从手艺变成可复现工程。frontmatter 是**唯一**决定 skill 是否被加载的东西（它永远在上下文里），所以它是 skill 成败的关键——多数 skill "从不触发"或"触发过度"都是 frontmatter 写差了。

### 实现方式（关键机制）

1. **Step 1 先定 2-3 个具体用例**：Trigger → Steps → Result，收集用户真实会说出的字面短语。
2. **Step 2 文件夹结构**：kebab-case 命名、SKILL.md < 5000 词、关键校验用 script 不用散文。
3. **Step 3 frontmatter**：description 必须 WHAT + WHEN + 字面触发词，< 1024 字符，**禁 XML 尖括号**（会注入 system prompt）。
4. **Step 4 指令体**：推荐结构 + 渐进披露。
5. 还覆盖：评审既有 skill、修复"不触发/乱触发"、分享前校验。

### 优点

- 让你后续造 skill 的质量稳定化、可复现。
- 直接诊断你现有 skill 的触发问题（如某些 skill 从不生效）。
- 元能力，杠杆最高——改进一次，惠及所有未来 skill。

### 缺点 / 代价

- 需要一次性投入去用它回审你现有的 13 个 skill。
- 它本身不会自动改你的 skill，还是要人/agent 执行。

### 如何引进

1. 复制：
   `opencode-src/mimo-code/packages/opencode/src/skill/builtin/.bundle/skill-creator/` → `~/.config/opencode/skills/skill-creator/`。
2. 用法：对 agent 说"create a skill for X" / "review my writing-plans skill" / "fix skill that never triggers"。
3. 建议第一件事：让它回审 `writing-plans`（诊断断链 + frontmatter）、`brainstorming`、`deepwork` 的触发质量。

### 与你现有功能的冲突 / 重合

- 无。你没有任何等价的"skill 工程化"工具。纯新增，零冲突。

---

# B 类：plugin hook 工程模式

## B4. 超时 + 熔断器 + 输出快照回滚三件套

### 架构

位于 MiMo plugin loader 的 **file-hook 执行路径**（`src/plugin/index.ts:556-615`）。file hook = 从 config 目录 `{hook,hooks}/*.{js,ts}` 加载的用户 hook（区别于内置/外部 npm 插件）。每个 hook 事件触发时，按注册顺序串行执行所有 file hook。

```
plugin.trigger(eventName, input, output)
  └─ for each file hook:
       ① 熔断器检查（hookFailures[hookID] >= 3 → 跳过 + warn）
       ② snapshot = structuredClone(output)          ← 回滚点
       ③ Promise.race([fn(input,output), timeout(5s)]) ← 超时
       ④ 失败 → Object.assign(output, snapshot)      ← 回滚
                hookFailures[hookID]++               ← 计入熔断
       ⑤ 成功 → hookFailures.delete(hookID)          ← 清零
```

> 注：内置插件（INTERNAL_PLUGINS）和外部 npm 插件**没有**这三件套保护，只有 file hook 有。MiMo 把 file hook 视为"不可信用户代码"，而内置插件视为可信。

### 目标

让一个**坏的/慢的/抛异常的用户 hook 无法拖垮主循环**。具体防御三种故障：
1. hook 死循环/慢 IO → 5s 超时强制返回。
2. hook 抛异常并**已污染 output** → 快照回滚，保证下游 hook 拿到的是干净 output。
3. hook **持续**坏（如依赖的服务挂了）→ 熔断后不再调用，避免每个事件都浪费 5s。

### 实现方式（关键代码语义）

- **超时**：`Promise.race([fn(...), new Promise((_,rej)=>setTimeout(()=>reject(超时), 5000))])`。
- **回滚**：执行前 `structuredClone(output)` 深拷贝；异常时 `Object.assign(output, snapshot)` 把原值写回。注意是**浅 attach**（顶层属性恢复），嵌套引用可能不完美，但对 hook output（通常是扁平结构）够用。
- **熔断**：`Map<hookID, count>`，阈值 3。一旦 Open，后续事件直接 skip 并 warn，**不再自愈**（除非进程内再无成功记录——实际上失败计数只在成功时清零，熔断后不再调用就永远不会清零，等于"进程内永久熔断"）。
- **作用域**：仅 file hook；hook 标识 = `file:<basename>#<event>`。

### 优点

- 三个故障域都覆盖，file hook 不会让 opencode 卡死。
- 回滚保证 hook 链的输出语义可预测（前一个 hook 失败不影响后一个的输入）。
- 实现简单，无外部依赖。

### 缺点 / 代价

- **进程内永久熔断**：熔断后不会半开试探，只能重启恢复（对长跑进程不友好）。
- **structuredClone 限制**：含函数/symbol/循环引用的 output 会抛；且只恢复顶层属性。
- **只保护 file hook**：内置插件和 npm 插件裸跑——若 `MimoAuthPlugin` 这类内部插件 hang，无保护。
- 5s 是硬编码，不可按 hook 配置。
- 上游 opencode **loader 层没有这套**，你想用得自己加（见引进方式）。

### 如何引进

**关键事实**：上游 opencode 1.17.9 的 plugin loader **没有** file hook 的三件套保护（这套是 anomalyco 加的）。所以你有两条路：

1. **路 A（推荐，零改源码）—— 在你自己的 plugin/hook 里自实现**：你写任何 plugin（如 nighty-night/schedule 的升级版）时，把每个 hook handler 手动包一层 `withGuard(fn)`：
   ```js
   const fail = new Map()
   function withGuard(hookID, fn, opts={timeout:5000, threshold:3}) {
     return async (input, output) => {
       if ((fail.get(hookID)??0) >= opts.threshold) return
       const snap = structuredClone(output)
       try {
         await Promise.race([
           fn(input, output),
           new Promise((_,rej)=>setTimeout(()=>rej(new Error('timeout')), opts.timeout))
         ])
         fail.delete(hookID)
       } catch (e) {
         Object.assign(output, snap)   // 回滚
         fail.set(hookID, (fail.get(hookID)??0)+1)
         // log
       }
     }
   }
   ```
   优点：不动 opencode 源码，随插件走。缺点：只保护你自己的 hook。

2. **路 B（重）—— 改 opencode 源码 loader**：不现实（你用上游发行版，不改源码）。除非你切 anomalyco/MiMo 运行时，它自带。

**建议**：走路 A。把 `withGuard` 抽成你 plugins 目录下的一个小 util，所有自写 plugin 复用。

### 与你现有功能的冲突 / 重合

- 你的 plugins（nighty-night/schedule/quota-fallback/see-image/dingding-notify/wecom-notify）目前**用 `tool` API 注册工具**，不是 file hook，所以现在**没有** hook 故障域问题。
- 这套三件套是**为未来准备的**：一旦你开始写 `event` / `tool.execute.after` / `chat.params` 这类 hook（例如做"工具执行后自动通知""改 LLM 参数"），就需要它。当前无冲突，是新增能力。

---

## B5. preStop / postStop 循环控制

### 架构

MiMo 的 `actor` 是 agent 循环的抽象（spawn 一个 actor 跑一轮 ReAct）。每个 actor 停止前/后，plugin loader 调用 `triggerActorPreStop` / `triggerActorPostStop`，**所有注册了该 hook 的插件投票**：

```
actor 即将停止
  └─ triggerActorPreStop(input)
       └─ for each hook (含 matcher 过滤):
            hook.run(input, output)
            if output.continue && output.reason:
              anyContinue=true; 收集 reason/pluginName/hookID
       └─ 聚合决策: { continue: anyContinue, reason: reasons.join("\n\n"),
                     contributingPluginNames, contributingHookIDs }
  └─ if continue: 把 reason 注入，actor 重新进入 ReAct（ReActReentered 事件）
     else: actor 正常停止 → triggerActorPostStop（同样可投票 continue）
  └─ 防失控: ReActMaxReached（达上限强制停）
```

挂载点：`src/plugin/index.ts:447-554`（aggregateDecision）+ `src/actor/turn.ts`（状态机 running→idle）。

### 目标

让插件能在 agent "想停"的时候**有条件地拒绝停止**，强制它再做一轮。典型场景：
- subagent 声称做完了，但**没写规定的进度日志** → postStop 返回 `continue=true` + "请补写 progress.md"，强制它回去写完再停。
- 代码没通过规定检查 → preStop 拒绝停止，让它继续修。
- 自动续跑（无人值守）。

### 实现方式（教科书范例：SubagentProgressCheckerPlugin）

`src/plugin/subagent-progress-checker.ts`（147 行，完整可读）：

```ts
export async function SubagentProgressCheckerPlugin(): Promise<Hooks> {
  return {
    "actor.postStop": {
      matcher: { agentType: { excludeOnly: ["checkpoint-writer","title","summary",
                                            "dream","distill","compaction","main"] } },
      run: async (input, output) => {
        const taskId = input.task_id
        if (!taskId) return                      // 没绑定任务 → no-op
        if (input.canWrite === false) return     // 只读 agent → 跳过（fail-open）

        const body = await read(progressPath(sessionID, taskId))
        if (body === undefined) {
          output.continue = true                  // ← 强制重入
          output.reason = buildFeedback({kind:"missing", ...})  // 告诉它缺什么
          return
        }
        const missing = REQUIRED_SECTIONS.filter(s => !body.includes(s))
        if (missing.length) {
          output.continue = true
          output.reason = buildFeedback({kind:"incomplete", missing, ...})
          return
        }
        // 通过 → 注入 frontmatter，干净退出（不设 continue）
      },
    },
  }
}
```

要点：
- **matcher**：用 `excludeOnly` 精确排除不该校验的 agent 类型（title/summary/compaction 等永无 task 语义）。注释里明确写了为何不用默认 `!isBuiltIn`（会漏掉 built-in 的 task-bound subagent）。
- **fail-open**：`canWrite === false` 用严格等号，absent 不抑制——避免对只读 agent 提不可能的要求。
- **continue 必须带 reason**：loader 里 `continue=true` 但无 reason 会被 warn 并忽略（防止无限静默续跑）。
- **幂等**：frontmatter 注入用正则替换已有 frontmatter，跨 ReAct retry 幂等。

### 优点

- 实现"agent 必须满足结构化契约才能终止"，这是 subagent 质量保障的关键一环。
- 多插件投票 + reason 聚合，可组合（多个检查器各管一摊）。
- matcher 精确控制作用域，不误伤无关 agent。
- 配合 compose:subagent 的 spec gate，形成"声明→校验→强制修复"闭环。

### 缺点 / 代价

- **上游 opencode 不支持**（最大代价）——`actor.preStop/postStop` 是 anomalyco 独有，你换不来。
- 有"无限续跑"风险，必须配 `ReActMaxReached` 上限（MiMo 有）。
- continue 注入的 reason 占用 context，多轮重试会膨胀。
- 调试难（hook 隐式触发，agent 视角看不到"为什么又被踢回去"）。
- matcher 逻辑易写错（excludeOnly vs includeOnly），错配会静默跳过。

### 如何引进

**上游 1.17.9 没有 actor/preStop，三条路：**

1. **路 1（最省事，推荐先做）—— 用上游现有 hook 近似模拟"终止前校验"**：
   - 用 `tool.execute.after`（每个工具执行后）或 `event`（订阅 session 完成/消息事件）做"事后校验"。
   - 校验失败时，通过 **`/api/session/<id>/prompt`** 或自定义 tool 注入一条"请补写 X"的新 prompt，让 session 再跑一轮。这复刻了 continue 的**效果**（强制再来一轮），但不是 actor 级的精细拦截。
   - 这恰好是你 **nighty-night 插件已在做的事**（无人值守自动应答 + 推进），思路一致，可扩展。

2. **路 2（要原汁原味）—— 切换到 anomalyco/MiMo 运行时**：
   - anomalyco/opencode 或 MiMo-Code 自带 actor + preStop/postStop + task 工具 + 三件套 + compose 全链路。
   - 代价：放弃上游 1.17.x 的 V2 架构演进（core/cli/tui/server 拆分、SessionV2 等），OMO 可能需要重新适配。
   - 适合：你想深度用 compose 全家桶 + subagent 自动校验。

3. **路 3（等上游）—— 给 sst/opencode 提 feature request**：
   - 上游 plugin v2 是 Effect-based，要加 preStop 等于在 SessionV2 的 turn 边界加可中断的 hook 点。短期无望。

**建议**：先走路 1，把"终止前契约校验"做成一个**自定义 tool + event hook** 的 plugin（校验 progress 文件→缺失则注入 prompt）。这样不绑运行时，且与你现有 nighty-night 生态一致。

### 与你现有功能的冲突 / 重合

- **nighty-night 插件**：目标高度相近（让任务自动持续推进、不打断）。区别：nighty-night 是**时间驱动**的无人值守应答，preStop 是**事件驱动**的终止拦截。两者**互补**——nighty-night 负责"会话停滞时推进"，preStop 负责"agent 想停时拒绝"。可共存。
- **OMO backgroundJobs**（`maxSessionsPerAgent: 2`）：是后台 session 调度，与 preStop 不同层，不冲突。
- **quota-fallback**：模型配额回退，无关。
- 无直接替换关系。

---

# C. 引进路线图（按 ROI 排序）

| 优先级 | 项目 | 成本 | 收益 | 风险 | 备注 |
|---|---|---|---|---|---|
| ★★★ | **A3 skill-creator** | 极低（复制目录）| 高（元能力，杠杆最大）| 无 | 先装，立刻用它回审现有 skill |
| ★★★ | **A1 compose:plan（替换 writing-plans）** | 低 | 高（修复断链 + self-review）| 低 | 先只换 plan，不动执行链 |
| ★★☆ | **A1 compose:tdd/verify/review** | 低 | 中（不依赖 actor 的评审闭环）| 低 | 与你 tdd 融合 |
| ★★☆ | **B4 三件套（路 A：自写 withGuard）** | 中（写个 util）| 中（为未来 hook 铺路）| 低 | 现在用不上，但写 hook 时必备 |
| ★★☆ | **A2 design-blueprint** | 低（复制目录）| 中（仅做视觉设计时）| 无 | 不做视觉可只借鉴写作范式 |
| ★☆☆ | **A1 compose:subagent（完整）** | 高（需 actor/task）| 高 | 高 | 需先验证上游 task 工具；否则降级用 |
| ★☆☆ | **B5 preStop/postStop（路 1 近似）** | 中高 | 中 | 中 | 用 event+tool 模拟，效果不等价 |
| ☆☆☆ | **B5 preStop/postStop（路 2 原生）** | 极高（换运行时）| 高 | 极高 | 切 anomalyco/MiMo，放弃上游 V2 |

### 推荐第一步

1. 装 **skill-creator**，让它诊断你 `writing-plans` 的断链 + 触发词质量。
2. 用 **compose:plan 替换 writing-plans**（修复 superpowers:* 断链，统一到 compose 命名空间）。
3. 写一个 **`withGuard` util** 放进 plugins 目录，为后续写 hook 做准备。

这三步成本低、无风险、收益立竿见影。compose:subagent 全链路和 preStop/postStop 留到你决定是否换运行时再说。

> 本节 ROI 表在结合官方 long-horizon 博客后有一处**上调**与一处**下调**，见 [D6](#d6-博客视角下的优先级修订)。

---

# D. 结合官方博客「计算-记忆-进化」的补充与修正

> 来源：[MiMo Code：将编程 Agent 扩展到长程任务](https://mimo.xiaomi.com/zh/blog/mimo-code-long-horizon)
> 博客把 MiMo-Code 定位为**长程自动化编程 Agent**，核心是三个时间尺度对应的三主题：
> **计算**（单轮决策质量）·**记忆**（多轮状态连续性）·**进化**（跨 session 经验提炼）。
> 本节用源码行号佐证博客每个机制，修正前文两处盲点，并补上前文未覆盖的两大核心系统。

## D1. 三主题 → 源码 → 前文映射

| 主题 | 机制 | 源码佐证（MiMo） | 前文 | 博客揭示的真实意图 |
|---|---|---|---|---|
| 计算·并行 | **Max Mode**（N=5 采样 + judge）| `config/config.ts:400 maxMode`、`session/prompt.ts:3273-3339`、`agent/agent.ts:141` | 未覆盖 | 单步投 N 倍算力选最优；+10-20% / 4-5x token |
| 计算·串行 | **Goal**（独立完成度验证）| `goal` 是 slash command（`tui/i18n/slash-command.ts:1`）| B5 部分 | **preStop/postStop 的设计初衷**，防"提前宣称完成" |
| 计算·编排 | **Dynamic Workflow** | MiMo 兼容 Anthropic 语义 + `workflow()`/落盘恢复 | 未覆盖 | **compose skill 的终态**：编排从 prompt 变代码 |
| 记忆·单元 | **Cycle**（checkpoint+rebuild）| `session/checkpoint.ts`、`session/prune.ts` | 未覆盖 | 无界会话基本单元 |
| 记忆·提取 | **checkpoint-writer** | `agent/agent.ts:333`、`session/checkpoint.ts`、`tool/memory-path-guard.ts` | 未覆盖 | 主 Agent 不维护自己的记忆；single-writer |
| 记忆·分层 | **四层记忆** | `memory/{fts-query,fts.sql,reconcile}.ts`、`history/` | 未覆盖 | session/project/global/history，可审查 |
| 进化·整理 | **Dream（7d）/ Distill（30d）** | `command/index.ts:65-66`、`session/auto-dream.ts`、`agent/agent.ts:364/391` | 未覆盖 | 自动合并去重 / 自动生产 skill |
| 进化·生产 | **skill-creator** | `skill/builtin/.bundle/skill-creator/` | A3 | Distill 的下游：把模式固化成 SKILL.md |

## D2. 重大修正：B5 的 preStop/postStop 真意是 Goal 完成度验证

前文 B5 把 `actor.preStop/postStop` 理解为"终止前契约校验"（SubagentProgressCheckerPlugin 校验 progress 日志）。博客揭示其**设计初衷是 Goal**：

- 长任务最常见的失败模式：Agent 看到已有进展就**提前宣称"完成"或开始提问**，无人值守时无人纠正。
- Goal = 用户设自然语言停止条件（如"所有测试通过且代码已提交"）→ Agent 每次想终止 → **独立模型调用**审完整历史 → 未满足则反馈具体差距让它继续，确认无法完成则判定不可能。
- 验证者**不参与工作**（无认同偏差），但与 Agent **共享相同上下文**（含工具实际输出）。误拦 > 漏放，死循环 <0.5%，到上限自动退出。

**因此**：`preStop/postStop` 是 Goal 的**通用 hook 基础设施**。SubagentProgressCheckerPlugin 只是它的一个实例（把"日志写全"作为子任务完成条件）；真正的 Goal 验证器（测试是否全过 / 是否已提交 / 是否满足用户停止条件）挂在**同一个 hook** 上，只是 `reason` 逻辑不同。`goal`/`dream`/`distill` 在 MiMo 里都是一等 slash command，印证它们是系统级能力而非边角插件。

**对 B5 引进的修正**：路 1（用上游 `event` + 自定义 tool 模拟）仍成立，但目标更清晰——你要模拟的是 **Goal 完成度验证**（长任务最痛的点），不只是日志校验。具体落地：监听 session 即将 idle 的信号 → 注入一次独立的"完成度审查"模型调用 → 不满足则往同一 session 追加一条"差距反馈"prompt 让它继续。这与你的 **nighty-night** 生态天然契合（都是"推进停滞会话"），可合并设计。

## D3. 前文未覆盖的核心：记忆系统（运行时级，不可移植）

博客"记忆"主题是 MiMo/anomalyco 相对上游 opencode **最大的运行时创新**，前文 A/B 完全没涉及。

### 架构

- **Cycle** = `checkpoint`（窗口预算 20%/45%/70% 触发）+ `rebuild`（接近上限）。逻辑会话 = cycle 链，**无上限**；每个 cycle 受限于物理窗口。
- **checkpoint-writer**：独立 subagent（`agent/config.ts:5` 的 SYSTEM_SPAWNED_AGENT_TYPES），主 Agent **不维护自己的记忆**。并发写 11 字段结构化 checkpoint（`session/llm.ts:109` 列出：意图/下一步/约束/任务树/当前工作/文件/发现/错误/运行时状态/设计决策/笔记）。
- **single-writer 不变量**：`tool/memory-path-guard.ts` 在代码层强制——只有 checkpoint-writer 能写 checkpoint/memory 文件，**越界写入直接拒绝**（`memory-path-guard.ts:122-154`）。
- **四层记忆**：session（`checkpoint.md`）/ project（`MEMORY.md`）/ global / history（SQLite 完整轨迹，无索引，回溯兜底）。上层精炼持久，下层完整慢。`memory/fts-query.ts` + `fts.sql.ts` 提供全文索引（印证博客"全文索引在文件之上"）。
- **主 Agent 唯一写通道**：`notes.md`（append-only scratchpad），writer 每次 checkpoint 读它、路由到结构化字段、清空。
- **Rebuild 注入**：分层 prompt ≤65K token（任务清单→checkpoint→用户消息逐字切片→project→global→notes→memory 索引→tail reminder）。

### 博客的关键洞察（源码佐证）

- **为何提早提取**（20/45/70% 而非快满）：① lost-in-the-middle，高上下文利用率时结构化提取能力衰减；② 提取本身需要空间（95% 无处思考，30% 游刃有余）。`session/prune.ts:248` 注释印证 checkpoint-writer 不应自身触发 checkpoint。
- **为何独立 writer**：调棘手 bug 的模型同时维护结构化日志会两败俱伤 → 把提取完全移出主循环。

### 对你的可借鉴度

- **不可移植**（运行时级重构，上游用 `experimental.session.compacting` 单次摘要，无 checkpoint/writer/分层）。这是"换运行时"决策的最大权重因素。
- **思路可借鉴**（你已手版了一半）：
  - 你的 `.slim/deepwork/<task>.md` 进度文件 ≈ session 记忆；`AGENTS.md` ≈ project 记忆。**差距**：你是 orchestrator 自己写，MiMo 是独立 writer 写——可考虑用 `@librarian` 或后台 session 充当 writer，让主 agent 只读。
  - **single-writer + 路径白名单**思想可直接用于你的项目：给写记忆的角色一个 allowlist，越界拒绝，避免多 agent 互相覆盖。
  - notes.md 的 append-only scratchpad 是低成本可抄的——给主 agent 一个"零散发现先扔这里，由别人整理"的通道。

## D4. 编排终态：Dynamic Workflow = 上游 codemode（已有！）

博客原话："目前以 prompt 形式定义的许多 skill，未来会逐步演变为这种代码形式的 workflow 脚本——当一个流程的每一步都必须执行、分支必须精确、重试必须可靠时，它就该用代码而非自然语言来保证。"

**重大发现**：博客说的 Dynamic Workflow，**上游 opencode v1.17.18 已经有了**——就是 `packages/codemode/`：

- README 原文："**CodeMode lets a model write a small JavaScript program that can call only the tools supplied by the host. The program can sequence calls, transform data, branch, loop, and run independent calls in parallel without receiving ambient filesystem/process/network authority.**"
- `interpreter/runtime.ts`：fork semaphore + parallel-call concurrency cap（即博客的 `parallel()`/`pipeline()` 上游等价）。
- `interpreter/model.ts:125`：支持 `Promise.all/allSettled/race` 并行、`for...of`、`try/catch`、array methods、Date/RegExp/Map/Set/URL——**if 不会忘记分支，for 不会提前退出**正是靠这个。
- Effect-native、schema-described tools、沙箱无 ambient authority。

**对 A1（compose）引进决策的影响**：
- compose skill = **prompt 形式编排**（自然语言描述流程，会被压缩吞、被跳过、不可验证）。
- codemode = **代码形式编排**（确定、可恢复、可验证）——博客明确说是 compose 的**终态**。
- 你用的 1.17.9 接近 1.17.18，**codemode 很可能已可用或即将可用**。
- **修订建议**：引进 compose:plan 仍值得（计划生成本身适合 prompt），但 compose:subagent/execute 的**编排部分**别过度定制——优先调研你的 opencode 是否已暴露 codemode 工具，若有，编排用代码而非 compose prompt。这是博客给的最重要方向信号。

## D5. Max Mode 与自进化闭环

### Max Mode（计算·并行）

- `experimental.maxMode.candidates`（默认 N=5），temp=1 采样、低温度 judge 选优。博客：SWE-Bench Pro +10-20%，代价 4-5x token。
- 上游 opencode **无**（grep 无 maxMode）。你的 OMO `variant: high/medium/low` 是模型/参数选择，不是并行采样。
- **借鉴度**：上游不直接支持。但 `chat.params` hook 可在单 plugin 内实现"并行 N 次 + 自判选优"（成本高，仅关键步骤启用）。优先级低——ROI 不如记忆与 Goal。

### 进化闭环：Dream/Distill + skill-creator（进化）

- **Dream**（`auto-dream.ts`，默认 7 天）：独立 agent 读历史 session + 现有记忆，合并/去重/验证路径/压缩 → 紧凑当前状态 + 更新 global 记忆。
- **Distill**（`auto-dream.ts`，默认 30 天）：独立 agent 读历史，识别**反复工作模式** → 固化为 skill/CLI/agent/SOP。
- 二者 + skill-creator（A3）形成**自进化闭环**：Distill 发现模式 → skill-creator 生产 SKILL.md → 新 skill 进池 → 下次 Distill 再提炼。

**对你的启示**：你已有 `reflect` skill（回顾近期工作、建议可复用 skill）——这是**手版的 Distill**。可强化：
1. 给 reflect 加一个"扫描近期 session 重复模式"的步骤。
2. 命中模式后调 skill-creator 固化。
3. 用 nighty-night/schedule 的定时能力，把 reflect 跑成周期性 dream/distill。
这是你能在上游 opencode **真正落地的进化机制**（不依赖 anomalyco 运行时），ROI 高。

## D6. 博客视角下的优先级修订

博客三主题让 C 节 ROI 表有两处调整：

| 项目 | 原优先级 | 修订 | 理由（博客视角） |
|---|---|---|---|
| **B5 preStop/postStop → Goal 模拟** | ★☆☆ | **★ ★☆ 上调** | Goal 解决长任务最痛的"提前宣称完成"，是无人值守的关键；与 nighty-night 合并设计 |
| **A1 compose:subagent/execute 编排** | ★☆☆ | 维持/甚至**降级** | 博客明示编排终态是 codemode（D4），prompt 编排别过度投入 |
| **强化 reflect 成 distill** | 未列 | **★★☆ 新增** | 进化闭环是你能落地的高 ROI 机制（D5）|
| **codemode 调研** | 未列 | **★★★ 新增** | 上游已有 Dynamic Workflow 引擎，决定 compose 的去留，必须先探明 |

其余（skill-creator ★★★、compose:plan 替换 writing-plans ★★★、三件套 withGuard ★★☆、design-blueprint ★★☆）维持不变。

### 博客视角的最终一句话

MiMo-Code 的长程优势**不在 skill 数量，而在运行时**：Cycle 记忆 + Goal 验证 + Dream/Distill 进化是它的护城河，且都依赖 anomalyco 运行时。你（上游 1.17.9）**能拿到的**是：① 可移植的 skill 内容（A1/A2/A3）、② 上游已有的 codemode 代码编排（D4）、③ 可用 event+tool 近似的 Goal（D2）、④ 可强化的 reflect→distill 进化（D5）。**拿不到的**是 Cycle 记忆系统（D3）——除非换运行时。这正好印证前文 C 节"先低成本移植，再决定是否换运行时"的结论。

---

# E. 四 agent 调研汇总与可执行方案（v2 权威）

> 经 explorer×2 + librarian + oracle 四 agent 独立调研交叉验证。**本节取代 A–D 节的结论性内容**。

## E1. anomalyco v1.17.18 机制可用性真相（复测结果）

| 机制 | 有/无 | 证据 file:line | 对你的意义 |
|---|:---:|---|---|
| plugin Hooks v1（chat/tool/permission/experimental.*）| ✅ | `packages/plugin/src/index.ts:222-335` | 写 plugin 的 hook 面**完整可用** |
| `actor.preStop`/`postStop` | ❌ | 全 grep 无 | Goal 完成度验证无原生 hook，需模拟（见 B5/E6）|
| **`task` 工具**（派发子 agent）| ✅ | `packages/opencode/src/tool/task.ts:24-345`，注册 `registry.ts:68` | **你"不污染主 session"需求的原生答案**（见 E2）|
| **sub-session（`parentID`）**| ✅ | `schema/session.ts:21`、`session/sql.ts:31,64`、`task.ts:144-158` | task 工具自动建独立子 session |
| agent `mode:"subagent"` | ✅ | `config/agent.ts:19`、`agent.ts:68`（自动从主选择排除）| 定义"只被 task 调、不主动选"的子 agent |
| codemode（Dynamic Workflow）| ⚠️ 仅基础沙箱 | `packages/codemode/src/stdlib/`（只基础类型）；作 `execute` 工具需 `flags.experimentalCodeMode` | **无 `agent()`/`parallel()` 原语**，不能派子 agent（D4 修正）|
| 记忆系统（checkpoint-writer/dream/distill/MEMORY.md）| ❌ | grep 无 | MiMo 独有，anomalyco 主线没有 |
| Max Mode（并行采样）| ❌ | grep 无 | MiMo 独有 |
| Session HTTP API | ✅ | `protocol/src/groups/session.ts`：`POST /api/session`、`POST /api/session/:id/prompt` 等 | schedule/nighty-night 已用（legacy `/session` 路径）|

**一句话**：anomalyco v1.17.18 在 agent 协同上用 **`task` 工具 + parentID 子 session + agent mode** 替代了 MiMo 的 actor 模型；**缺** MiMo 的记忆系统、Max Mode、preStop hook、codemode 的 agent 原语。

## E2. 核心答案：`task` 工具就是你要的 sub-session 机制

你第 2 点问"如何不污染主 session、把一个子 agent 的输出作为另一个的输入"——**anomalyco v1.17.18 已原生支持**，就是 `task` 工具。

**`task` 工具行为**（`tool/task.ts`）：
- 调 `sessions.create({ parentID: ctx.sessionID, ... })` 建**独立子 session**（DB 新行，独立对话历史）。
- 子 agent **fresh context**——不继承主 session 任何历史（`task.txt:16` 原文）。
- 子 session 权限从父派生（`subagent-permissions.ts:14-27`），自动 deny `task`+`todowrite`（防递归）。
- 结果以**文本**返回主 agent（`task.ts:199` `result.parts.findLast(...)?.text`），主 agent 拿到后可拼进下一个 task 的 prompt。
- 支持 `background:true` 并发派多个；`task_id` 可续接同一子 session。

**三种数据传递方案对比**：

| 方案 | 隔离度 | 复杂度 | 污染主 session | 可用性 |
|---|---|---|:---:|---|
| **B：`task` 工具返回值**（推荐）| 高（独立子 session）| 极低（原生）| 否 | ✅ v1.17.18 内置 |
| A：REST API 建独立 session（schedule/nighty-night 模式）| 高（完全独立）| 中（自己管 session 生命周期）| 否 | ✅ 你的 schedule 已在做 |
| C：codemode workflow 变量传递 | — | — | — | ❌ codemode 无 agent() 原语 |

**针对你的目标模式（主 agent 派子 agent plan→fresh 子 agent 执行/验证/评审）**：
- **首选方案 B**（`task` 工具）：主 agent（轻上下文，只协调）按需 `task(subagent_type="...", prompt=精准任务包)`，每个执行/验证/评审角色开 fresh 子 session。产出写文件，主 agent 只收文本摘要。
- **方案 A**（REST API）适合**跨进程/定时**场景（你的 schedule、nighty-night 已是此模式）。两者可共存：会话内编排用 task，定时/无人值守用 schedule。
- 你说"没试过创建 sub-session"——其实 task 工具每次调用就在建 sub-session（带 parentID）。**可以直接试**：在任意会话里让主 agent 用 task 派一个 explorer，观察它独立上下文、返回文本。

> ⚠️ 注意：`task` 工具当前在 `packages/opencode`（V1），core 层 `builtins.ts:27` 注释 "TODO: Port ... task" 表示尚未移植到 V2 core。**当前可用**，但属 legacy leaf，未来 V2 迁移时需关注。

## E3. GLM-5.2 计费经济学（你的模式选择依据）

你的计费：**纯 token 总数，输入/输出/缓存无加权，缓存命中反亏**。

**核心洞察**："缓存命中反亏"≠ 缓存被罚款，而是**"为命中缓存而维持的长上下文"持续累积计费，但缓存本身不省钱** → 应优化的是**每次推理的 active context 总和**，而非 cache hit rate。模式 A（长 session 靠缓存复用 prefix 省钱）的逻辑**完全失效**。

**成本模型**（设 M=调用次数，S=系统提示固定开销，d=每轮新增，C=fresh 任务包，L=压缩后平均上下文，ρ=压缩丢失返工率）：

| 模式 | 成本特性 | 临界点 |
|---|---|---|
| **A 单长 session** | 未压缩：`T ≈ M·S + d·M(M-1)/2`（**二次增长**）；压缩后：稳定大成本 L·M + 压缩/返工 | 历史重复计费随 M 二次膨胀 |
| **B 轻编排器+fresh workers** | `T ≈ T_orch + Σ(S+C_i+O_i) + 交接 H` | 每个 worker 覆盖一个原子可验证单元即结束 |

**决策表**：

| 场景 | 推荐 | 原因 |
|---|---|---|
| ≤3-5 次调用且强相关 | A | 拆分/交接成本不值 |
| 已有明确 plan，执行/验证/评审可分离 | **B** | 每角色只需 plan + 局部证据 |
| ≥3 角色或 ≥8-10 次调用 | **B** | 长会话历史重复 > fresh 启动成本 |
| 上下文膨胀到最小任务包 2-3 倍 | **B** | 大量 token 与当前工作无关 |
| 独立评审/安全/发布验证 | **B** | 即使 token 略高，fresh verifier 更可信 |
| 探索阶段（需求未收敛）| A 后转 B | 先连续探索，plan 稳定后切 fresh workers |

**针对你的目标模式，加两条硬限制**（oracle 建议）：
1. 主 agent 只持**任务状态、产物路径、决策、简短结果**，**不回收完整子 agent transcript**。
2. fresh worker 的任务包**基于稳定产物**（`plan.md` / scoped file list / acceptance criteria / 测试命令 / 已知风险），**而非聊天历史**。

这同时拿到更低 token 总量 + 更强独立性。**你的计费模式天然偏爱方案 B**（task 工具 + fresh 子 agent）——这正是你感兴趣的模式，经济上成立。

## E4. superpowers 工作链：下载 + 引用纠正 + hidden + 缺口

### E4.1 完整闭环（obra/superpowers，13 skill）

```
using-superpowers（入口引导，可选）
  └→ brainstorming → writing-plans
        ├→ subagent-driven-development（主干：per-task fresh executor + 两阶段评审）
        │     ├→ using-git-worktrees（隔离工作区）
        │     ├→ [per task] test-driven-development → requesting-code-review
        │     └→ finishing-a-development-branch（终局：merge/PR/keep/discard）
        └→ executing-plans（备选：同会话批量执行）
  └→ systematic-debugging → test-driven-development → verification-before-completion
writing-skills（元 skill，独立）
```

### E4.2 断链诊断（你的 writing-plans，逐行）

| 行 | 引用 | 本机状态 |
|---|---|---|
| 16 | `superpowers:using-git-worktrees` | ⚠️ 本机有 `worktrees`（别名/不同模式）|
| 61,169 | `superpowers:subagent-driven-development` | ❌ 缺失（核心断链）|
| 61,173 | `superpowers:executing-plans` | ❌ 缺失 |

`writing-plans` 末尾已有 adaptation note 承认这两个执行 skill 未装、建议用 deepwork 或手动派 fixer/explorer。**核心执行链断裂**。

### E4.3 下载清单（wget，按优先级）

**P0 修复断链**（subagent-driven-development + 伴随文件，它是 ~500 行精密 skill）：
```bash
D=~/.config/opencode/skills/subagent-driven-development
mkdir -p $D/scripts
wget -O $D/SKILL.md https://raw.githubusercontent.com/obra/superpowers/main/skills/subagent-driven-development/SKILL.md
wget -O $D/implementer-prompt.md https://raw.githubusercontent.com/obra/superpowers/main/skills/subagent-driven-development/implementer-prompt.md
wget -O $D/task-reviewer-prompt.md https://raw.githubusercontent.com/obra/superpowers/main/skills/subagent-driven-development/task-reviewer-prompt.md
wget -O $D/scripts/review-package https://raw.githubusercontent.com/obra/superpowers/main/skills/subagent-driven-development/scripts/review-package
wget -O $D/scripts/task-brief https://raw.githubusercontent.com/obra/superpowers/main/skills/subagent-driven-development/scripts/task-brief
```

**P1 上下游**：
```bash
for s in requesting-code-review finishing-a-development-branch; do
  D=~/.config/opencode/skills/$s; mkdir -p $D
  wget -O $D/SKILL.md https://raw.githubusercontent.com/obra/superpowers/main/skills/$s/SKILL.md
done
# requesting-code-review 的 code-reviewer.md 模板
wget -O ~/.config/opencode/skills/requesting-code-review/code-reviewer.md \
  https://raw.githubusercontent.com/obra/superpowers/main/skills/requesting-code-review/code-reviewer.md
```

**P2 质量/调试**：`verification-before-completion`、`systematic-debugging`、`receiving-code-review`（同样格式 wget SKILL.md）。

**不下载** `using-git-worktrees`——改 writing-plans 第 16 行引用为本机 `worktrees`（已等价）。

### E4.4 引用名纠正：推荐 kebab-case 本机名（方案 b）

oracle 给出三方案对比，**推荐 (b) kebab-case 本机名**：

| 断链引用 | 纠正为 |
|---|---|
| `superpowers:using-git-worktrees` | `worktrees`（本机已有）|
| `superpowers:subagent-driven-development` | `subagent-driven-development` |
| `superpowers:executing-plans` | `executing-plans`（若装）|
| `superpowers:test-driven-development` | `test-driven-development`（本机已有）|

理由：符合本机命名、`skill({name})` 引用最直接、不绑 MiMo/superpowers 命名空间、便于替换单环节。**不用** `compose:*`（MiMo 惯例，引入无实际隔离的命名层）、**不保留** `superpowers:*`（制造不存在的运行时命名空间）。

**纠正原则**：不是纯文本替换就完事——每个引用需核验①被调 skill 存在 ②frontmatter `name` 一致 ③输入/输出契约一致 ④不依赖 MiMo 独有能力。`worktrees` 与原 `using-git-worktrees` 行为不同时，写小型适配层而非仅改名。

### E4.5 hidden 判定（哪些只被其他 skill 触发、不污染 available_skills）

**判定准则**：用户几乎不直接喊 / 只在上层 workflow 特定阶段有意义 / 依赖上游产物 / 易与可见入口抢触发 → `hidden:true`。

| Skill | hidden | 理由 |
|---|:---:|---|
| brainstorming | ❌ | 用户直接入口 |
| writing-plans | ❌ | 常用入口 |
| **subagent-driven-development** | ❌ | **推荐的 fresh-worker 执行主入口** |
| executing-plans | ✅ | 与上一项竞争，仅内部/兼容 |
| test-driven-development | ❌ | 独立方法 + 被执行器调 |
| worktrees | ❌ | 用户可能独立要 |
| requesting-code-review | ✅ | 编排阶段，不与"审查代码"竞争 |
| receiving-code-review | ✅ | 处理反馈的内部规则 |
| verification-before-completion | ✅ | 完成前门禁，由执行器显式调 |
| finishing-a-development-branch | ❌ | 用户可能直接要收尾 |
| systematic-debugging | ❌ | 明确用户入口 |

> `hidden` ≠ 不重要，而是"不让模型自行选择"，上层 skill 仍可显式 invoke。

### E4.6 缺口与 deepwork 关系

**最小闭环缺口**（本机要补的）：`subagent-driven-development`、`verification-before-completion`、`requesting-code-review`、`receiving-code-review`、`finishing-a-development-branch`（+可选 `systematic-debugging`/`executing-plans`）。其中**执行/验证/评审必须分别开 fresh worker**（task 工具）。

**与 deepwork 的关系**：两者都是顶层编排器，**不能嵌套**（否则双重拆任务、两套进度状态、重复派 agent、`.slim/deepwork/` 与 plan 文件冲突）。oracle 建议保留一个 canonical conductor：短期 deepwork 继续服务旧任务，新任务用 subagent-driven-development，稳定后把 deepwork 的持久进度/agent 路由/中断恢复能力迁入新编排器，再降级 deepwork。统一状态模型：`plan → executing → verifying → reviewing → ready-to-finish`，主状态存稳定文件，session 只是临时执行环境。

## E5. skill-creator 分析方法论（结合 opencode DB 语料）

**理念**：progressive disclosure 下，frontmatter 决定 skill 是否加载，是成败关键。分析应**聚焦 frontmatter，不是正文**。

**5 步落地**（按成本递增）：

1. **静态审计现有 skill**（最低成本最高收益，先做）：建清单 name/description/hidden/引用的 skills/依赖的 agent+插件+状态文件。输出：断链、重名、描述弱/重叠、hidden 不可达、MiMo/superpowers 遗留引用。
2. **只读确认 SQLite schema**：`~/.local/share/opencode` 下的 DB，先 schema inspection 确认 session/message/part/tool 字段（不硬编码表名，版本会变）。工具调用可能是 `part.type='tool'` + JSON。
3. **构建 skill invocation 事件表**：每次 `skill` tool call 归一化——`session_id/parent_id/message_id/timestamp/skill_name(=input.name)/tool_status/agent+model/user_request/前后阶段/后续产物`。统计调用频率/首次延迟/失败/连续调用。
   - ⚠️ 区分三个概念：① skill 在 system prompt 的 available list（只说明可选）② 模型调 `skill` 工具（**明确触发**）③ 行为相似但没调（DB 无法判为显式触发）。优先解析 tool call，不要靠"message 文本搜 skill 名"。
4. **标注 should-trigger 集**：抽代表性 session，先隐藏实际 tool-call 结果独立标注 should/should-not trigger + expected phase + expected skill，再算每个 skill 的 **precision/recall/never-trigger/late-trigger/wrong-phase/overlap 矩阵**。
5. **小步改 frontmatter 复测**：一次只改一类变量（触发词/排除条件/hidden/name），用固定 benchmark prompts 比较前后 recall/precision。**先别改正文**——skill 若没被正确加载，正文再好也白搭。

**frontmatter 审查重点**：name 唯一稳定可精确引用；description 写"何时用"（含用户真实表达）而非"是什么"；排除相邻场景；不承诺 runtime 不支持的能力；hidden skill 至少有一个可达调用方；触发条件必须在 frontmatter（不能藏正文）。

## E6. 最终行动计划（按顺序执行）

| # | 行动 | 成本 | 验证 |
|---|---|:---:|---|
| 1 | **下载 P0 skill**（subagent-driven-development + 伴随文件，E4.3 wget）| 5 分钟 | `ls` 确认 SKILL.md + scripts/ |
| 2 | **纠正 writing-plans 引用**：第 16→`worktrees`，第 61/169/173→`subagent-driven-development`/`executing-plans`（E4.4）| 5 分钟 | grep 无残留 `superpowers:*` |
| 3 | **试跑 task 工具**：在任意会话让主 agent `task(subagent_type="explorer", prompt="...")`，观察 fresh 子 session + 文本返回（E2）| 1 次会话 | 子 agent 不带主会话历史 |
| 4 | **静态审计 skill frontmatter**：用 skill-creator 扫现有 13 个 skill，找断链/触发词弱/描述重叠（E5 步骤1）| 30 分钟 | 产出 skill 质量清单 |
| 5 | **下载 P1**（requesting-code-review + finishing-a-development-branch）补闭环（E4.3）| 5 分钟 | 闭环可用 |
| 6 | **定 hidden**：按 E4.5 表给 requesting-code-review/receiving-code-review/verification-before-completion/executing-plans 设 `hidden:true` | 5 分钟 | available_skills 列表干净 |
| 7 | **决定 deepwork 去留**：新任务用 subagent-driven-development，观察一周后决定是否迁移/废弃 deepwork（E4.6）| 观察 | 无双重编排冲突 |
| 8 | （可选）**开 `experimentalCodeMode`** 试 codemode 基础沙箱，确认它不能派子 agent（仅做受限 JS 编排）| 配置 | 知晓 codemode 边界 |

**第 1-3 步零风险、立竿见影**，是你四个需求的直接落地：①下载+工作链（E4）②task 工具验证 sub-session（E2）③skill-creator 静态审计（E5 步骤1）④血统复测已完成（E1）。

**关键认知更新**：你之前以为要"探讨如何不污染主 session"——其实 `task` 工具天生就做这件事（fresh context + parentID 子 session + 文本返回）。你的 GLM 计费模式又天然偏爱这种 fresh-worker 模式。所以你要的"主 agent 派子 agent plan→fresh 子 agent 执行/验证/评审"在 anomalyco v1.17.18 上**开箱即用**，配合下载的 superpowers skill 形成闭环即可。

---

# F. Oracle 评审与执行计划固化（v3）

> 经 oracle 全面评审 E6 + 两个新约束（直接替换 deepwork / ocmar 空间名）。**本节取代 E6 的结论性内容**。

## F.1 采纳的关键决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | ocmar 空间用**平铺 `ocmar-<skill>`** + frontmatter `name: ocmar-<skill>`，**不用冒号** | skill-creator 硬规则：目录名=name 且 kebab-case；冒号虽能加载但破坏一致性 |
| 2 | **`hidden:true` 在 v1.17.18 无效**；内部 skill 改用**省略 `description`** 实现不进 available list，但仍可 `skill({name})` 精确调用 | `skill/index.ts:321-345`、`core/skill/guidance.ts:52-55` 只列有 description 的 |
| 3 | task dispatch 合同：实现→`fixer`、评审→`oracle`、探索→`explorer`、资料→`librarian`；**不传 model 参数** | `task.ts:43-62` 无 model 参数；`general` 已禁用 |
| 4 | deepwork **不删除**，归档 `~/.config/opencode/archive/`；OMO 防复活用 `oh-my-opencode-slim.json` orchestrator 加 `skills:["*","!deepwork"]` | managed 状态会被 auto-update 重装；手改 manifest 会被覆盖 |
| 5 | `.slim/deepwork/` 历史 11 文件**保留只读**；新状态用 `.ocmar/workflows/<slug>/` | 固定单文件不支持多任务并发 |
| 6 | E6 重写为 8 Stage；**先全闭环 staging 验证再切换** | "下 P0 就改 writing-plans"会造成新断链 |

## F.2 适配本机的 6 大风险点（Stage 2 改造时必须处理）

1. **Git commit**：upstream 假定每任务 commit；ocdroid 只用户要求才 commit → 改"记录 baseline SHA + working-tree diff"，用户要求时才 commit。
2. **task 工具**：upstream 用 `general-purpose` + model 参数；本机无 model 参数、`general` 禁用 → 用决策 3 的 agent 路由。
3. **评审成本**：GLM 按总 token 计费，不能把完整历史放 prompt → brief/report/diff 文件交接；小任务合并 spec+quality 为单 fresh oracle reviewer。
4. **验证**：必须 fresh session 读 plan/diff/命令输出；最终门禁跑"项目约定的校验命令"（通用化表述，不硬编码 `./scripts/check.sh`）。
5. **using-superpowers 触发过度**：原版"每次会话 1% 可能就调用" → 收紧到"用户明确要 ocmar / 复杂实现重构 / 已有计划待执行 / 多阶段 fresh-worker"。
6. **脚本可移植性**：`task-brief`/`review-package` 改 `.superpowers`→`.ocmar`，支持未提交 working tree，检查 shebang/执行位/GNU-BSD。

## F.3 Stage 1 结果（已完成 ✅）

- **来源冻结**：`obra/superpowers` main HEAD，`PINNED_SHA=d884ae04edebef577e82ff7c4e143debd0bbec99`，vendor 到 `/tmp/opencode/ocmar-vendor/`。
- **14 个 skill**（librarian 曾漏报 `dispatching-parallel-agents`）：brainstorming, dispatching-parallel-agents, executing-plans, finishing-a-development-branch, receiving-code-review, requesting-code-review, subagent-driven-development, systematic-debugging, test-driven-development, using-git-worktrees, using-superpowers, verification-before-completion, writing-plans, writing-skills。
- **48 文件**，伴随文件齐全。
- **task smoke test ✅**：explorer 返回 nonce `OCMAR-SMOKE-7f3a9`，**确认 fresh context**（看不到主会话任何历史）→ E2 证实：task 工具天生不污染主 session。

## F.4 8 Stage 执行计划

| Stage | 做什么 | 验证 | 状态 |
|---|---|---|:---:|
| **1 冻结来源** | pin SHA + 整目录 vendor + task smoke test | SHA/清单/smoke test | ✅ |
| **2 离线构建 ocmar bundle** | staging 改 name/去 `superpowers:*`/适配 fixer-oracle-explorer/去自动 commit/`.superpowers`→`.ocmar`/description 策略 | grep 无残留旧 namespace；agent 路由正确 | ✅ |
| **3 闭环 dry-run** | 隔离 test：plan→fixer→oracle→verifier→finish 非破坏流程 | oracle/glmer 门控 9.5 通过 | ✅ |
| **4 原子安装 ocmar** | 整目录装到 `~/.config/opencode/skills/`；加 `.ocmar/` ignore 规则 | 新进程 debug skill；名称唯一 | ✅ |
| **5 切换引用 + 替换 deepwork** | 改 writing-plans/brainstorming/oh-my-opencode-slim；加 `!deepwork`；归档 deepwork | 全局 grep 无功能性 deepwork 引用 | ✅ |
| **6 真实小任务验收** | sync-player VideoUtils+FormatUtils 测试跑完整 ocmar 流程 | 97/97 全绿；final review 抓到 per-task 漏（9PB codify-bug）；见 F.6 | ✅ |
| **7 无人值守协同** | schedule_task + ledger resume dry 验证（不测 nighty） | plan-hash/base/fingerprint 三校验 + skip-verified 通过 | ✅ |
| **8 文档收口** | v3 勘误 + F.6 实战证据 + F.7 回滚 runbook | 本文档 | ✅ |

## F.5 14 skill 的 ocmar 落地映射

| obra 原名 | 最终名称 | 来源 | 可见 |
|---|---|---|:---:|
| using-superpowers | `ocmar-using-superpowers` | 下载+收紧触发 | ✅ |
| brainstorming | `brainstorming` | 本机已有 | ✅ |
| writing-plans | `writing-plans` | 本机已有+改引用 | ✅ |
| subagent-driven-development | `ocmar-subagent-driven-development` | 下载+适配（主 conductor，替代 deepwork）| ✅ |
| dispatching-parallel-agents | `ocmar-dispatching-parallel-agents` | 下载+适配 task | ❌ |
| executing-plans | `ocmar-executing-plans` | 下载+适配（备选）| ❌ |
| using-git-worktrees | `worktrees` | 本机已有，不下载 | ✅ |
| test-driven-development | `test-driven-development` | 本机已有（371 行）| ✅ |
| requesting-code-review | `ocmar-requesting-code-review` | 下载+适配 oracle | ❌ |
| receiving-code-review | `ocmar-receiving-code-review` | 下载 | ❌ |
| verification-before-completion | `ocmar-verification-before-completion` | 下载 | ❌ |
| finishing-a-development-branch | `ocmar-finishing-a-development-branch` | 下载+适配 Git 规则 | ✅ |
| systematic-debugging | `ocmar-systematic-debugging` | 下载 | ✅ |
| writing-skills | `ocmar-writing-skills` | 下载 | ✅ |

可见性：`✅`=保留 description 进 available list；`❌`=省略 description 不进 list 但可 `skill({name})` 精确调（替代无效的 hidden）。

---

## F.6 Stage 6 实战证据（sync-player，2026-07-13）

首个真实 ocmar 任务：为 sync-player 的 `VideoUtils` + `FormatUtils` 补单元测试（AGENTS 要求高覆盖，原 4/5 函数 + 整文件零测）。

**流程**（10 个角色 dispatch，全 fresh session）：
plan → Task1(fixer implement) → Task1(oracle review) → Task2(fixer) → Task2(oracle review) → final(oracle whole-branch) → fix(fixer，复用 T2 session) → re-review(oracle，复用 session) → verifier(fresh fixer) → finishing(single-main keep)

**产出**：VideoUtilsTest +29 测试（保留原 4）、FormatUtilsTest 24 测试，97/97 全绿，working tree preserved（未 commit，符合 ocmar 默认）。ledger 在 sync-player `.ocmar/workflows/sync-player-test-coverage/progress.md`。

**验证的关键机制**：

| 机制 | 实战表现 |
|---|---|
| fixer/oracle 角色路由 | 10 dispatch 全正确（无 model 参数） |
| 文件交接（brief/report/review-package） | 主会话只持路径 + 摘要，未污染 |
| ledger 三态 | implementing→reviewed→verified 正确流转 |
| content-fingerprint | 每次 task 状态变更刷新；Stage 7 resume dry 三校验全 match |
| small-task optimization | 单 oracle 返 spec+quality 双 verdict（≤2 文件任务） |
| fresh verifier | 独立 fixer 跑 `testDebugUnitTest`，exit 0 + JUnit XML 证据 |
| single-main finishing | Step 2b 检测 master + normal repo → 2-option keep |

**证明 review 非走过场的真实发现**：

1. **Task1 reviewer 的 plan-mandated finding**（individual vs wildcard import）→ controller 用真实代码裁决为 **plan 笔误**（现有代码本就用 individual import，fixer 跟随惯例正确），不 dispatch fix。体现 controller 持 cross-task/真实代码知识裁决 reviewer 的 plan-mandated finding。
2. **Task2 的额外 cap test 被 per-task reviewer 接受，但 final whole-branch reviewer 从整体视角抓出它 codifies 了 `formatFileSize` >TB 产线 bug**（9PB→"8.0 TB"）→ fix 移除该测试 + 加 1_000_000 bps 边界测试 → re-review pass。**印证 per-task + whole-branch 两阶段评审的独立价值**。

**Follow-up**（out-of-scope，记录）：`FormatUtils.formatFileSize` 对 >TB 输入有真实产线 bug（用未 capping exponent 除、只 cap unit label），test-only scope 未修，留单独产线修复。

**gpter should-fix 实测**（Stage 3 门控时的待验项，Stage 6 验证）：

- **#8 多任务累计 diff**：Task2 reviewer 看到 Task1 改动，dispatch 明确 scope 聚焦 → reviewer 未困惑 ✓
- **#9 verifier failure count**：用 JUnit XML 提取（97/0/0/0）✓
- **#10 重复 check.sh 成本**：verifier 命中 gradle `UP-TO-DATE`（缓存有效，XML 反映 fix 后代码），但**改进点**：verifier prompt 加 `--rerun-tasks` 消除缓存疑虑（留持续优化）

## F.7 回滚 runbook

ocmar 全量替换了 deepwork。如需回滚到 ocmar 前状态：

**回滚快照位置**：`~/.config/opencode/archive/pre-ocmar/`
- `writing-plans.SKILL.md`（原版，引用 `superpowers:*`）
- `brainstorming.SKILL.md`（原版）
- `oh-my-opencode-slim.json`（原版，无 `disabled_skills` / `!deepwork`）
- `deepwork/`（完整目录，归档在 `archive/deepwork-retired/`）

**回滚步骤**：

```bash
CD=~/.config/opencode
# 1. 恢复 writing-plans / brainstorming 原文
cp $CD/archive/pre-ocmar/writing-plans.SKILL.md   $CD/skills/writing-plans/SKILL.md
cp $CD/archive/pre-ocmar/brainstorming.SKILL.md   $CD/skills/brainstorming/SKILL.md
# 2. 恢复 OMO 配置（去 disabled_skills + !deepwork）
cp $CD/archive/pre-ocmar/oh-my-opencode-slim.json $CD/oh-my-opencode-slim.json
# 3. 恢复 deepwork
mv $CD/archive/deepwork-retired/deepwork          $CD/skills/deepwork
# 4. 移除 ocmar skill
rm -rf $CD/skills/ocmar-*
# 5. 重启 opencode 服务（~/.opencode/bin/opencode serve --port 4096）
```

**验证回滚**：新 session 的 available skills 含 `deepwork`、无 `ocmar-*`；`writing-plans` 引用恢复 `superpowers:*`（断链状态，即 ocmar 前原样）。

**注意**：回滚后 writing-plans 的 `superpowers:*` 断链会恢复（ocmar 前的已知问题）。如只想回滚单个 skill，从 `/tmp/opencode/ocmar-vendor/`（obra 原文，SHA `d884ae04`）或 staging `/tmp/opencode/ocmar-build/` 取。

---

## F.8 可借鉴条目实现状态（ocmar 全量实施后）

ocmar 项目（Stage 1–8）实现了 superpowers 工作链（A1 的等价）+ B5 的 verifier 门禁替代。MiMo 的其他可借鉴条目状态如下：

| 条目 | 原优先级 | ocmar 后状态 | 说明 |
|---|---|---|---|
| **A1 compose:plan（替换 writing-plans）** | ★★★ | ✅ **ocmar 等价实现** | writing-plans 引用改 `ocmar-*`；Step 5 Commit→Record diff；与 ocmar-subagent-driven-development 闭环 |
| **A1 compose:tdd/verify/review** | ★★☆ | ✅ **ocmar 等价实现** | `ocmar-verification-before-completion`（fresh verifier）+ `ocmar-requesting-code-review`（per-task + final oracle 两阶段评审）|
| **A1 compose:subagent（完整）** | ★☆☆ | ✅ **ocmar 等价实现** | `ocmar-subagent-driven-development`（主 conductor，task 工具 + fixer/oracle 路由），Stage 6 实战验证（97/97 全绿） |
| **A3 skill-creator** | ★★★ | ⚠️ **部分替代** | ocmar 用 `ocmar-writing-skills`（superpowers 源）；未做 A3 的 frontmatter 静态审计（E5 方法论，留独立任务）|
| **A2 design-blueprint** | ★★☆ | ⏸ 未涉及 | ocmar 范围外（视觉设计方法论）；需要时复制即可 |
| **B4 三件套 withGuard** | ★★☆ | ⏸ 未涉及 | ocmar 是 skill 层，不涉及 plugin hook；写 hook 时仍需自实现 withGuard |
| **B5 preStop/postStop → Goal** | ★★☆（D6 上调）| 🔄 **ocmar 等价替代** | ocmar 用 fresh verifier（Final Verification Gate）+ ledger resume 替代 Goal 完成度验证；非 preStop hook 但达"未过校验不放行"效果。原生 preStop 仍需换运行时 |
| **强化 reflect 成 distill** | ★★☆（D6 新增）| ⏸ 未涉及 | reflect skill 已存在；强化为周期 distill 留独立任务 |
| **codemode 调研** | ★★★（D6 新增）| ⏸ 未涉及 | D4 确认 anomalyco codemode 仅基础沙箱无 `agent()` 原语；prompt 编排（ocmar）仍是当前主线 |

**小结**：ocmar 实现了 **A1（fresh-worker 闭环）+ B5 的等价替代（verifier 门禁）**。其余条目（A2 视觉 / A3 frontmatter 审计 / B4 hook 防护 / D3 记忆 / D4 codemode / D5 Dream-Distill）在 ocmar 范围外，状态不变，按需推进。

> 完整项目文档（思路/机制/实现/案例/反思/改进）见 `~/opencode_wd/ocmar工作流完整项目文档.md`。

---

## 附录：调研位置索引

| 内容 | 路径 |
|---|---|
| MiMo 源码 | `opencode-src/mimo-code/`（v0.1.5）|
| compose skills | `packages/opencode/src/skill/compose/.bundle/{plan,subagent,execute,tdd,verify,review,ask,worktree,brainstorm,parallel,debug,feedback,merge,report}/SKILL.md` |
| builtin skills | `packages/opencode/src/skill/builtin/.bundle/{design-blueprint,skill-creator,arxiv,deep-research,super-research,evolve,frontend-design,html-to-video-pipeline,research-paper-writing,modern-python-toolchain,loop,mimocode,*-official}/` |
| skill 加载逻辑 | `packages/opencode/src/skill/index.ts` |
| bundle 内嵌机制 | `packages/opencode/src/skill/{builtin,compose}/bundle.macro.ts` + `extract.ts` |
| plugin loader（三件套+preStop）| `packages/opencode/src/plugin/index.ts` |
| preStop 真实范例 | `packages/opencode/src/plugin/subagent-progress-checker.ts`、`checkpoint-splitover.ts` |
| actor 抽象 | `packages/opencode/src/actor/{index,turn,spawn,registry,waiter}.ts` |
| 记忆系统（Cycle/writer/四层）| `packages/opencode/src/session/checkpoint.ts`、`memory/{fts-query,reconcile}.ts`、`history/`、`tool/memory-path-guard.ts`、`agent/agent.ts:333`(checkpoint-writer) |
| Max Mode | `packages/opencode/src/config/config.ts:400`、`session/prompt.ts:3273-3339` |
| Dream/Distill 进化 | `packages/opencode/src/command/index.ts:65`、`session/auto-dream.ts`、`agent/agent.ts:364/391` |
| Goal 完成度验证 | `packages/opencode/src/cli/cmd/tui/i18n/slash-command.ts:1`（goal/dream/distill 为一等命令）|
| **上游 Dynamic Workflow** | `opencode-src/v1.17.18/packages/codemode/`（README + `src/interpreter/{runtime,model}.ts`）|
| 上游 Hooks 定义 | `opencode-src/v1.17.18/packages/plugin/src/index.ts:222-335` |
| 你的 skills | `~/.config/opencode/skills/{writing-plans,test-driven-development,brainstorming,deepwork,grill-me,grilling,worktrees,simplify,reflect,codemap,clonedeps,...}` |
| 你的 plugins | `~/.config/opencode/plugins/{nighty-night,schedule,quota-fallback,see-image,dingding-notify,wecom-notify}/` |
| 你的 OMO 配置 | `~/.config/opencode/oh-my-opencode-slim.json` |
