# Review Gate Policy

发版前的多 agent 评审规范与**评审产物的统一命名/留存规则**。

## 何时需要评审

- 每批发版前（patch / minor / major）建议至少一个评审 agent 介入。
- 行为/UI 变更建议观察类 agent（observer）介入；纯逻辑/重构建议代码质量类 agent。
- 是否强制阻断发版由用户在发版前自行把控；`scripts/release.sh` 不强制拦截。

## 特定改动的测试门控（必做）

`./scripts/check.sh --full` 不含插桩测试（connectedDebugAndroidTest 需模拟器，是本机共享资源，见 `docs/specs/emulator-debug.md` 纪律）。但以下改动**必须**在发版前用模拟器跑一次对应 connectedDebugAndroidTest 类作为门控（`./scripts/emulator.sh status` 确认空闲 → `start` → 跑 → `stop`）：

| 改动范围 | 必跑门控类 | 理由 |
|---|---|---|
| 流式 Markdown（`StreamingMarkdown*` / `ChatTextParts.TextPart` / HeightAnchor） | `cn.vectory.ocdroid.ui.chat.StreamingMarkdownZeroShrinkTest` | 0-shrink 是 SubcomposeLayout 行为，check.sh 的 JVM 单测覆盖不到 Compose 布局回归 |
| 设置/连接 UI（`Settings*` / `ConnectionProfileSection`） | `cn.vectory.ocdroid.SettingsSectionsInstrumentedTest` | 编译依赖（参数签名）+ UI 断言 |
| 其它集成行为 | 全量 `connectedDebugAndroidTest`（需 `.env`） | 端到端 |

跑法：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<全限定类名>`（仅跑指定类，避免无 `.env` 时集成测试失败）。

## 评审报告产物

每次评审必须产出一个结构化 JSON 文件，集中存放到 `.opencode/runs/reviews/`。

### 命名规范（关键，解决"评审文件缺少统一命名/管理"问题）

```
.opencode/runs/reviews/<YYYY-MM-DD>/<reviewer>_<scope>.json
```

| 占位 | 含义 | 取值约定 |
|---|---|---|
| `YYYY-MM-DD` | 评审日期 | 用评审当天（脚本或人工按日期归档） |
| `reviewer` | 评审 agent 名 | 小写，去掉可能的后缀；如 `glmer`、`gpter`、`observer` |
| `scope` | 评审范围 | `release`（发版前整体）/ `feature-<slug>`（某特性）/ `fix-<slug>`（某修复）/ `pr-<n>` |

**示例：**

```
.opencode/runs/reviews/2026-07-02/glmer_release.json
.opencode/runs/reviews/2026-07-02/gpter_release.json
.opencode/runs/reviews/2026-07-05/observer_feature-session-persist.json
.opencode/runs/reviews/2026-07-08/glmer_fix-streaming-flicker.json
```

### JSON Schema

```json
{
  "agent": "glmer",
  "scope": "release",
  "date": "2026-07-02",
  "reviewed_commit": "abc1234",
  "version_target": "0.2.5",
  "verdict": "pass",
  "score": 8.5,
  "blocking_issues": [],
  "non_blocking_issues": [
    "Consider adding regression test for version parsing."
  ],
  "notes": "Optional free-form remarks."
}
```

字段规则：

- `verdict`: `pass` | `pass-with-fixes` | `block`。
- `score`: 0–10，一位小数；建议 `>= 8` 视为通过线。
- `reviewed_commit`: 必须 = 当时 HEAD；后续若重新提交，需重新评审。
- `blocking_issues`: 必须为空才能视为通过；非空 = `block`。
- `version_target`: 若是发版前评审，填目标 versionName（如 `0.2.5`），便于事后对账。

### 模板

新增评审时直接复制 `.opencode/templates/review-report.json` 改写。

## 留存与索引

- `.opencode/runs/reviews/` **已纳入版本库**（v0.13.5 起由 `.gitignore` 否定链**强制**再包含，而非仅靠 policy 约束）：`.opencode/*` / `!.opencode/runs/` / `.opencode/runs/*` / `!.opencode/runs/reviews/`——只放行 `reviews/` 这一条路径，`.opencode/policies/`、`templates/`、其它 `runs/` 仍被忽略。评审产物**应提交**（git 可跟踪），作为可追溯的项目资产。
- 同一天同 scope 多轮评审：在文件名后加 `-r2`、`-r3`，如 `glmer_release-r2.json`。
- 不删旧评审文件；若结论被推翻，新增一轮文件而非覆盖。

## 索引表（建议维护）

在 `.opencode/README.md` 维护一张任务路由总表，其中包含 review 行（见该文件）。
