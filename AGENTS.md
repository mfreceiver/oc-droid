# AGENTS.md - ocdroid (mfreceiver fork)

> 本仓库是上游 [grapeot/opencode_android_client](https://github.com/grapeot/opencode_android_client) 的自建 Git fork，用于持续开发并定期同步上游。
> 本文件是给在此仓库工作的 agent 的操作指引。完整同步策略见 `FORK_SYNC.md`，构建细节见 `docs/build-apk.md`。

---

## 构建环境（本机 Linux）

终端默认找不到 Java，**每次构建前必须导出**（或写入 `~/.zshrc` 持久化）：

```bash
export JAVA_HOME=/home/mar/android-studio/jbr
export ANDROID_HOME=/home/mar/android-sdk
export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools"
```

- **JDK 21**（JBR），构建用项目自带 `./gradlew` wrapper（Gradle 9.3.1），**不要**用系统 gradle。
- 工具链（在 `gradle/libs.versions.toml` 锁定，**勿擅自升级，跟随上游**）：AGP 9.1.0 / Kotlin 2.2.10 / KSP 2.3.6 / compileSdk 35 / minSdk 26 / targetSdk 34。
- SDK 已就绪：android-35 + build-tools 34.0.0/35.0.0/35.0.1。
- 首次构建需 `local.properties`（已 gitignore）：`printf 'sdk.dir=/home/mar/android-sdk\n' > local.properties`。
- **工具链与其它本机项目（x-liker / syncplayer）完全隔离**：各项目用自己的 wrapper + 版本目录，互不影响。

## 构建命令

```bash
./gradlew assembleDebug      # 测试 APK → app/build/outputs/apk/debug/app-debug.apk（调试密钥签名，可直接装）
./gradlew assembleRelease    # 发布 APK → app/build/outputs/apk/release/app-release.apk（release 密钥签名）
```

- 首次 debug 构建约 10+ 分钟（下载依赖），release 约 1–2 分钟（依赖缓存后）。
- 加速：在 `gradle.properties` 加 `org.gradle.configuration-cache=true`、`org.gradle.caching=true`、`org.gradle.parallel=true`。

## Release 签名

- keystore：`/home/mar/.android/opencode_release.keystore`（**仓库外**，永不入库），alias `release`。
- 凭证写入 `local.properties`（gitignored）：`release.storeFile / storePassword / keyAlias / keyPassword`。
- 签名配置在 `app/build.gradle.kts` 的 `signingConfigs.release`，从 `local.properties` 读取；`buildTypes.release` 绑定该签名。
- **keystore 与密码务必备份**——丢失则无法以同一身份升级 App。密码不在本文件，存于本机 `local.properties`。
- 新增 App 签名应使用**独立 key**（一 App 一 key，规范），不要复用 x-liker / syncplayer 的 key。

## 发布产物

- 发布的 APK 统一放**项目根目录 `APK/` 文件夹**（已 gitignore，不入库），按 `oc-droid-<版本号>.apk` 命名（如 `oc-droid-0.5.0.apk`）。应用名称为 **OC Droid**。
- 发版用 `tea` CLI 打 Gitea Release，tag 用 `v<版本号>`（如 `v0.5.0`），tag 指向 `dev` 分支提交。详见 `docs/build-apk.md` 第 6 节。

## 测试

```bash
./gradlew testDebugUnitTest                 # 单元测试
./gradlew koverHtmlReport                   # 覆盖率 → app/build/reports/kover/html/index.html
./gradlew connectedDebugAndroidTest         # 集成测试（需 .env 凭证 + 运行中的 OpenCode Server）
```

集成测试前：复制 `.env.example` 为 `.env`，填入 `OPENCODE_*` 凭证。

## 改动校验（替代 LSP 自检，必做）

> **本工作区的 opencode 服务端已关闭 LSP（`lsp: false`）**，编辑后不再有编译器/诊断的自动反馈。因此 **agent 每次改动 Kotlin/资源后，必须主动运行下列命令**确认无编译与测试错误，相当于手动 LSP 自检。这些命令的输出走会被服务端截断（≤50KB）的 `output` 通道，不会产生 `metadata.diagnostics` 膨胀。

```bash
export JAVA_HOME=/home/mar/android-studio/jbr
export ANDROID_HOME=/home/mar/android-sdk
export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools"
./gradlew compileDebugKotlin        # 编译校验（最快，每次改动必跑）
./gradlew testDebugUnitTest         # 单元测试（改动逻辑后必跑）
./gradlew lintDebug                 # 静态检查（可选）
```

只有上述命令全部通过，才视为一次改动完成；失败则先修复再继续。

## 设备安全（硬性规定）

- **不得**在物理 Android 手机上跑 `connectedDebugAndroidTest`、安装或启动 debug 构建，**除非用户明确要求**。真机含用户正式 App 与凭证，测试包可能覆盖。
- UI / 插桩测试与安装**仅用模拟器**。若同时连了真机和模拟器，用 `ANDROID_SERIAL=<模拟器id>` 明确指定。

## Git 分支模型（两线制）

- **`master`**：纯跟上游，只接收上游更新，**零自定义提交**。
- **`dev`**：开发分支，承载本 fork 所有自定义（文档、签名配置、功能开发）。**在此分支工作与出包**。

```bash
# 日常开发
git checkout dev              # 改代码 → commit → git push

# 同步上游（master 永远只跟上游）
git checkout master && bash scripts/sync-upstream.sh

# 把上游新功能带到 dev
git checkout dev && git rebase master        # 必要时 git push --force-with-lease origin dev
```

发布包：`git checkout dev && ./gradlew assembleRelease`。

## Remote 与上游同步

- `origin` = 自建 Git：`https://git.vectory.cn:18443/mfreceiver/oc-droid.git`（日常推送目标）。
- `upstream` = GitHub 源：`grapeot/opencode_android_client`（只读拉取）。
- 一键同步：`bash scripts/sync-upstream.sh [--rebase]`（fetch 上游+tag → 同步 master → 推 origin）。
- 上游的 `feat/*`、`fix/*` 分支已镜像备查；**不要**主动并入 dev，等上游合并后自动随 master 进入。需要提前试用某未合并功能时，临时 `git checkout -b trial upstream/<分支>`，不并入 dev。

## 安全审计（同步上游后）

- **每次 `sync-upstream.sh` 跟进上游后，对差异部分做增量安全审计**（排查后门 + 严重性能问题），产出新的带日期报告。
- 工作流与审计台账见 `docs/security-audit-workflow.md`；全量基线报告见 `docs/security-audit-2026-06-27.md`。
- 审计用 gpter / glmer / kimo 三方并行，只审 `diff` 出来的差异文件；重点查新增凭证/网络端点/依赖，并对照基线报告的 H1~H4、M1~M6 是否被上游重新引入。
- 报告命名 `docs/security-audit-YYYY-MM-DD.md`，完成后把日期/上游版本/基点追加到工作流文档的「审计台账」。

## 改动与冲突控制

- 自定义内容尽量放**新文件**（如 `FORK_SYNC.md`、`docs/build-apk.md`），少改上游文件，降低合并冲突。
- 必须改上游文件时（如 `app/build.gradle.kts` 签名块、本 `AGENTS.md`），改动要**小而集中**，方便上游合并后重新应用。
- 合并冲突时，公共文件优先采纳上游版本，再迁移自定义部分。

## 常见问题

- **Java 找不到 / gradlew 失败**：先 `export JAVA_HOME=/home/mar/android-studio/jbr`（见上）。
- **Run 报 Module not found**：Android Studio → File → Sync Project with Gradle Files；仍失败 → Invalidate Caches / Restart。Run 配置用 module `ocdroid.app`（settings.gradle.kts 的 rootProject.name + `:app`）。
- **构建慢**：开 Gradle 配置缓存（见上）。
- **缺 `local.properties`**：见「构建环境」末尾的 `printf` 命令。

## 相关文档

- `FORK_SYNC.md` — fork 同步与管理策略（完整版）
- `docs/build-apk.md` — 本地构建 / 签名 / 发版指南（实测版）
- `docs/security-audit-workflow.md` — 同步上游后的差异审计工作流与台账
- `docs/security-audit-2026-06-27.md` — 安全审计全量基线报告
- `README.md` — 项目功能与使用说明
