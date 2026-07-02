# Versioning Policy

ocdroid 的版本号语义与修改规则的**唯一权威来源**。`scripts/bump-version.sh` 实现本规则。

## 版本字段

`app/build.gradle.kts` 维护两个字段：

| 字段 | 含义 | 规则 |
|---|---|---|
| `versionName` | 用户可见的语义化版本 | `MAJOR.MINOR.PATCH` |
| `versionCode` | 单调递增整数 | 每次 bump **+1**，**永不回退** |

## bump 类型

| 类型 | versionName 变化 | versionCode | 何时使用 |
|---|---|---|---|
| `patch` | `0.2.4 → 0.2.5` | +1 | Bug 修复、内部重构、无明显行为变化 |
| `minor` | `0.2.4 → 0.3.0` | +1 | 新用户可见功能、向后兼容的新配置/行为 |
| `major` | `0.2.4 → 1.0.0` | +1 | 破坏性变更、存储/API 不兼容 |

## 硬规则

- **禁止手改** `app/build.gradle.kts` 的 `versionCode` / `versionName` 字段。必须通过 `scripts/bump-version.sh <type>`，或 `scripts/release.sh <type>`（内部调用 bump）。
- `versionCode` 必须严格单调递增，已发布过的值不可复用。
- 发版流程参见 `scripts/release.sh` 与 `docs/build-apk.md` §6。

## 禁止

- 不要为图省事跳过一个版本号（如 `0.2.4 → 0.2.6`）。
- 不要在不发版的情况下 bump 版本号。
- 不要同时手改多个版本字段（脚本会保证一致性）。
