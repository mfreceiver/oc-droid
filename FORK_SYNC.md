# Fork 同步与管理指南

> 本仓库是上游 [grapeot/opencode_android_client](https://github.com/grapeot/opencode_android_client) 的自建镜像 fork。
> 目标：在自建 Git 上持续开发完善，并定期把上游的新功能合并回来。

---

## 1. Remote 布局

| Remote | 地址 | 用途 |
|--------|------|------|
| `origin` | `https://git.vectory.cn:18443/mfreceiver/oc-droid.git` | **自建 Git**，日常开发与推送的目标 |
| `upstream` | `https://github.com/grapeot/opencode_android_client.git` | **上游 GitHub 源**，只用来拉取更新 |

查看当前 remote：

```bash
git remote -v
```

> 若在新机器上 clone 了本仓库但缺少 `upstream`，补一条即可：
> `git remote add upstream https://github.com/grapeot/opencode_android_client.git`

---

## 2. 上游情况

- **默认分支**：`master`
- **分支**（镜像时共 4 个）：

  | 分支 | 说明 |
  |------|------|
  | `master` | 主线 |
  | `feat/ollama-kimi-k27-code` | 功能：Ollama Kimi K2.7 Code 预设 |
  | `fix-markdown-caption-images` | 修复：Markdown 图片说明渲染 |
  | `fix/transcription-failure-diagnostics` | 修复：语音转写失败诊断 |

- **版本标签**：`v0.1.YYYYMMDD` 命名，约每月一版。
  镜像时共 13 个 tag，最新 `v0.1.20260622`。
- **镜像基点**：`master @ ec517f1`（2026-06-22），自建 Git 与上游 HEAD 完全一致。

---

## 3. 分支管理策略

核心原则：**让本地 `master` 尽量贴近上游 `master`，自己的开发放到独立分支**。
这样每次合并上游时冲突最少、成本最低。

- **`master`**：跟踪上游，只做“合并上游 + 必要的本地适配”。**不要直接在 master 上做功能开发。**
- **`dev` / `feature/*`**：你自己的功能分支，基于最新 `master` 创建。
- **上游的 `feat/*`、`fix/*`**：已镜像保存备查，无需长期维护本地副本；如要基于其开发再 checkout 即可。
- **自定义改动**：尽量集中、模块化、可复用，方便每次上游合并后快速 reapply。

### 版本号约定

- 沿用上游 `v0.1.YYYYMMDD` 风格。
- 自己的发布可在其后加后缀区分，例如 `v0.1.20260622-fork.1`，避免与上游 tag 冲突。

### 降低合并冲突的实践

- 纯新增文件几乎不冲突 → 自定义内容尽量放新文件，少改上游文件。
- 必须改上游文件时（如 `build.gradle.kts`、`README.md`），改动要小而集中，方便每次重新应用。
- 不要删除/重命名上游的文件或目录。

---

## 4. 日常开发（推到自建 Git）

```bash
git checkout master
git pull                            # 默认拉 origin（自建 Git）

git checkout -b feature/my-change   # 在新分支上开发
# ...改代码、提交...
git push -u origin feature/my-change
```

---

## 5. 定期同步上游新功能

### 5.1 合并上游 master（最常用）

```bash
git fetch upstream
git checkout master
git merge upstream/master           # 有冲突就解决，然后 git add + git merge --continue
git push origin master
```

### 5.2 用 rebase 代替 merge（保持线性历史）

```bash
git fetch upstream
git checkout master
git rebase upstream/master
git push origin master --force-with-lease   # rebase 改写历史，需强推
```

> 注意：rebase 会改写已推送的历史，仅当 master 上只有你自己的少量提交、且协作者知情时使用。
> 不确定就用 5.1 的 merge。

### 5.3 同步标签

```bash
git fetch upstream --tags
git push origin --tags
```

### 5.4 一键脚本

```bash
bash scripts/sync-upstream.sh             # 默认 merge
bash scripts/sync-upstream.sh --rebase    # 改用 rebase
```

脚本会自动：fetch 上游与 tag → 同步 master → 推送到 origin。

---

## 6. 冲突处理与回退

- 解决冲突时，**公共文件优先采纳上游版本**，再把你的定制迁移到独立文件/模块。
- 查看与上游的差异：

  ```bash
  git log upstream/master..master          # 本地领先上游的提交（你的改动）
  git log master..upstream/master          # 上游领先本地、尚未合并的提交
  ```

- 如果 master 历史被自己改乱，可重置后再 reapply 自定义改动（**谨慎，会丢弃本地提交**）：

  ```bash
  git fetch upstream
  git reset --hard upstream/master
  ```

---

## 7. 验证同步结果

```bash
git log --oneline -5                      # 确认最新提交
git diff master upstream/master --stat    # 本地与上游的差异概览
git ls-remote origin master               # 自建 Git 的远程 HEAD
git ls-remote upstream master             # 上游的远程 HEAD
```

---

## 附：本地构建测试 APK

构建命令与环境要求见根目录 `README.md` 的「构建」章节（`./gradlew assembleDebug` 产物位于
`app/build/outputs/apk/debug/app-debug.apk`，debug 包已用调试密钥签名、可直接安装测试）。
更完整的本地打包/签名指南见 `docs/build-apk.md`。
