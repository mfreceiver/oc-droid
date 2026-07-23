# docs/specs/ — 长期规格与指南

本目录存放 ocdroid 的**长期有效**规格、规范与操作指南。

区别于：
- `docs/ocmar/` —— 按日期归档的流程产物（plans / reports / specs），历史快照，不随本文档迁移而更新其内部链接。（docs/ 根目录的过程性文档已清理，持久内容并入本目录与 `architecture.md`。）

| 文件 | 作用 |
|---|---|
| `architecture.md` | **代码框架规范**（分层 / 双 API 变体共存 / 端口模式 / 能力读模型 / 不变量 / 包约定）—— 新增/重构代码须读 |
| `build-apk.md` | 本地构建 / 签名 / 发版完整指南（本机路径、命令、Gitea 上传） |
| `emulator-debug.md` | 模拟器调试指南（启停流程、adb、集成测试） |
| `mtls-setup-guide.md` | mTLS（stunnel）服务端配置与客户端证书导入指南 |
| `ui-style-spec.md` | UI 样式规范（三层 overlay 规则 + `ui/theme/` 共享原语，MANDATORY） |
| `slim-mode-api-routing.md` | 省流模式 API 路由权威契约（A/B/C/D 桶、INTERFACE_MAP、health 形状） |
| `sse-client-spec.md` | SSE 客户端规范（legacy `/global/event` + slim `/slimapi/events` 控制面） |

> **引用约定**
> - 本目录内文档互相引用用相对路径 `./<file>.md`。
> - 被仓库其它位置（`AGENTS.md` / `README.md` / `scripts/` / 源码 KDoc）引用时用 `docs/specs/<file>.md`。
> - 规则类**权威**仍下沉在 `.opencode/policies/`（构建签名、版本号、评审门控等）；本目录是其操作细节与规格补充，规则冲突以 `.opencode/policies/` 为准。
