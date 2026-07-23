# ocdroid 项目治理（agent 操作系统）

> `AGENTS.md` 是入口索引，本文件展开任务路由。原则：**客观操作脚本化、规则分层、按需加载**。

## 任务路由表

| 任务 | 入口 | 相关 policy / 文档 |
|---|---|---|
| 改动后校验（替代 LSP） | `./scripts/check.sh` | `policies/build-signing.md` |
| 发版（出包 + tag） | `./scripts/release.sh <patch\|minor\|major>` | `policies/build-signing.md`、`policies/versioning.md`、`docs/specs/build-apk.md` §6 |
| bump 版本号 | `./scripts/release.sh` 内部调用（禁止手改） | `policies/versioning.md` |
| 构建环境 export | `source ./scripts/env.sh` | `policies/build-signing.md` |
| 发版前评审 | 写入 `.opencode/runs/reviews/<date>/<reviewer>_<scope>.json` | `policies/review-gate.md`、`templates/review-report.json` |

## 目录约定

```
.opencode/
├── README.md            # 本文件：任务路由总表
├── policies/            # 长期制度（不随任务变化）
│   ├── versioning.md
│   ├── build-signing.md
│   └── review-gate.md
├── templates/           # 输出格式模板
│   └── review-report.json
└── runs/                # 执行证据（入库，可追溯）
    └── reviews/<date>/  # 评审 JSON 归档
```

## 硬规则

- 发版/签名/上传/版本号修改，**不得**由 agent 自由发挥命令，必须走 `scripts/release.sh`。
- 禁止手改 `app/build.gradle.kts` 的 `version*` 字段。
- 评审产物必须按 `review-gate.md` 命名规范归档，禁止覆盖旧文件。

## 不在本系统内的事

- bugfix / 需求分析 / 架构设计等通用流程暂不固化成 workflow（避免过度工程），按 agent 通用判断执行。
- review gate 不强制阻断 `release.sh`；是否强制评审由用户在发版前自行把控。
