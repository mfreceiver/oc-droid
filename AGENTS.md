# AGENTS.md - ocdroid

> 本仓库基于 [grapeot/opencode_android_client](https://github.com/grapeot/opencode_android_client)（MIT）深度改造，已独立发展，不再跟踪上游。
> 本文件是给在此仓库工作的 agent 的**入口索引**。具体规则下沉到 `.opencode/policies/`，操作命令收敛到 `scripts/`，细节见 `docs/build-apk.md`。

---

## 流程入口（优先用脚本，不要手拼命令）

| 任务 | 入口 | 规则 / 细节 |
|---|---|---|
| 改动后校验（替代 LSP，必做） | `./scripts/check.sh` | `.opencode/policies/build-signing.md`「改动校验」 |
| 发版（出包 + commit + tag） | `./scripts/release.sh <patch\|minor\|major>` | `.opencode/policies/build-signing.md`、`.opencode/policies/versioning.md` |
| bump 版本号 | `./scripts/release.sh` 内部调用，**禁止手改** `app/build.gradle.kts` | `.opencode/policies/versioning.md` |
| 构建环境 export | `source ./scripts/env.sh` | `scripts/env.sh` |
| 发版前评审（产物归档） | `.opencode/runs/reviews/<YYYY-MM-DD>/<reviewer>_<scope>.json` | `.opencode/policies/review-gate.md` |

> 任何 release / 签名 / 上传 / 版本号修改，都不得由 agent 自由发挥命令，必须走上述脚本。

## 硬规则（不可违反）

- **改动校验必做**：本工作区的 opencode 服务端已关闭 LSP（`lsp: false`），编辑后无编译器/诊断的自动反馈。每次改 Kotlin/资源后，必须 `./scripts/check.sh` 通过才算改动完成。等价于手动 LSP 自检；其输出走会被服务端截断（≤50KB）的 `output` 通道，不会产生 `metadata.diagnostics` 膨胀。
- **设备安全**：不得在物理 Android 手机上跑 `connectedDebugAndroidTest`、安装或启动 debug 构建，**除非用户明确要求**。UI/插桩测试与安装**仅用模拟器**。若同时连了真机和模拟器，用 `ANDROID_SERIAL=<模拟器id>` 明确指定。
- **Git 分支**：单一主线分支 `main`，在此分支工作与出包。
- **版本号**：禁止手改 `app/build.gradle.kts` 的 `versionCode`/`versionName`，必须经脚本（见 `versioning.md`）。

## 构建/发版/测试细节

完整的构建命令、签名配置、产物命名、发版流程等**统一在以下文件**，本文件不再重复：

- `docs/build-apk.md` — 本地构建 / 签名 / 发版完整指南（含本机路径、命令、tea CLI 用法）
- `.opencode/policies/build-signing.md` — 构建/签名/校验规则（权威）
- `.opencode/policies/versioning.md` — 版本号语义与 bump 规则（权威）
- `.opencode/policies/review-gate.md` — 发版前评审与评审产物命名/留存规范
- `.opencode/README.md` — 任务路由总表

### 常用命令速查（细节见上）

```bash
source ./scripts/env.sh                # 导出 JAVA_HOME / ANDROID_HOME（构建前必做，或写 ~/.zshrc）
./scripts/check.sh                     # 编译 + 单测（每次改动必跑）
./scripts/check.sh --full              # + lint + 覆盖率
./scripts/release.sh patch             # 发版（唯一入口；内部已做 bump/构建/tag）
./gradlew assembleDebug                # 仅 debug 包（仅模拟器）
./gradlew connectedDebugAndroidTest    # 集成测试（需 .env + 模拟器）
```

## 常见问题

- **Java 找不到 / gradlew 失败**：`source ./scripts/env.sh`（见上）。
- **缺 `local.properties`**：`printf 'sdk.dir=/home/mar/android-sdk\n' > local.properties`。
- **Run 报 Module not found**：Android Studio → File → Sync Project with Gradle Files；仍失败 → Invalidate Caches / Restart。Run 配置用 module `ocdroid.app`。
- **构建慢**：在 `gradle.properties` 加 `org.gradle.configuration-cache=true` / `.caching=true` / `.parallel=true`。
- **工具链与其它本机项目（x-liker / syncplayer）完全隔离**：各项目用自己的 wrapper + 版本目录，互不影响。

## 相关文档

- `docs/build-apk.md` — 本地构建 / 签名 / 发版指南
- `README.md` — 项目功能与使用说明
- `.opencode/` — 项目治理（policy / template / runs）
