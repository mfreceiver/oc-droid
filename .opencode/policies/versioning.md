# Versioning Policy

ocdroid 版本号语义与来源的**唯一权威来源**。`app/build.gradle.kts`（git 派生）+
`scripts/release.sh`（打 tag）共同实现本规则。

## 版本来源（go-around pattern）

ocdroid **不在源码里写版本号**。`app/build.gradle.kts` 的 `versionCode` / `versionName`
**没有硬编码值**，二者在 Gradle 配置期由 git 派生：

| 字段 | 来源 | 形态 |
|---|---|---|
| `versionName` | `git describe --tags --always --dirty`（去前导 `v`） | 干净 tag 提交=`0.8.1`；tag 后 N 个提交=`0.8.1-3-gd5e1311`；脏树=`…-dirty`；非 git 仓库=`dev` |
| `versionCode` | `git rev-list --count HEAD` | 单调递增整数，每 commit +1，永不回退、永不复用（远大于历史手 bump 的最大值 64） |

唯一例外：`release.sh` 构建时传 `-PreleaseVersion=<tag>`，让 release APK 的 `versionName`
显示**即将创建的干净 tag 名**（而非 `<prev>-N-gHASH`）。tag 创建后，任何人从该 tag 提交重建
（不带 `-PreleaseVersion`），`git describe` 自然返回干净 tag 名，二者一致。**人不需要、也不应该
手写版本号到任何地方。**

## 两类发版

| 场景 | 做法 | 产物 versionName | versionCode |
|---|---|---|---|
| **里程碑发版**（新 semver） | `./scripts/release.sh <patch\|minor\|major>`：门禁 → 推算下一 tag → assembleRelease+归档（注入 tag）→ changelog → 打 annotated tag | 干净 `<tag>`（如 `0.8.1`） | 该提交的 commit count |
| **同族小修复**（不加新 tag） | 修 bug → commit → `./gradlew assembleRelease archiveReleaseApk`（**不带** `-PreleaseVersion`）→ 直接发 `APK/oc-droid-<tag>-N-g<hash>.apk` | `<tag>-N-g<hash>`（如 `0.8.1-1-gabc`） | 更高（新 commit count）→ 可装升级 |

> 这是 go-around 模式的核心收益：**tag 后的小修无需 bump 新 semver**，重建即得一个可追溯、可升级的 APK（versionName 自带 commit 锚点，versionCode 自动更高）。

## bump 类型（仅里程碑发版）

`release.sh` 从最新 tag 推算：

| 类型 | tag 变化 | 何时使用 |
|---|---|---|
| `patch` | `0.8.0 → 0.8.1` | Bug 修复、内部重构、构建/发版工具改动、无明显行为变化 |
| `minor` | `0.8.0 → 0.9.0` | 新用户可见功能、向后兼容的新配置/行为 |
| `major` | `0.8.0 → 1.0.0` | 破坏性变更、存储/API 不兼容 |

## 硬规则

- **禁止手改** `app/build.gradle.kts` 的 `versionCode` / `versionName` 字段——它们由 git 派生，手写值毫无意义且会被下次构建的派生值取代。
- **禁止手动 `git tag`** 做里程碑发版。必须经 `./scripts/release.sh <type>`（内部推算 + 构建 + 打 tag）。
- **禁止复用已 push 的 tag**（`release.sh` 不会覆盖；误打则本地删重建，已 push 的不要强推，用下一 patch 修正）。
- tag 必须打在 `main` 分支的提交上（`release.sh` 已强校验）。
- **versionCode 单调性仅对 main 上的 release 升级链保证**：`git rev-list --count HEAD` 是分支相对值——worktree（feature 分支）的 count 跨分支不唯一、也不保证比 main 高。worktree 构建仅供开发期 debug 验证（且为 debug 签名，与 release 签名分属不同安装域）；**勿用 release 签名对外分发未合并的 worktree 状态**（其 count 可能高于未来某 main release → 已装用户被 Android 判降级拒装）。worktree 成果须先 ff-merge / rebase 回 main，再由 main 发版。

## 禁止

- 不要为图省事跳过一个版本号（如 `0.8.0 → 0.8.2`）。
- 不要在不发版的情况下创建 tag。
- 不要把版本号硬编码进任何源码/资源/文档（运行时一律读 `PackageManager.getPackageInfo` 的 `versionName` / `PackageInfoCompat.getLongVersionCode`）。
