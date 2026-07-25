# ocdroid × Gitea Actions CI/CD 实施指南

> 本仓库(ocdroid)针对「OpenCode × Gitea × Android CI/CD」总文档的**落地版**。总文档是通用模板,本文是按本仓库实测(Gitea 1.25.4 / AGP 9.1.0 / Gradle 9.3.1 / JDK 21 / compileSdk 35 / go-around 版本号 / 本机 `pass` 签名)修正后的可执行指引。
>
> 决策记录:① **CI 签名**(keystore 上传 Gitea Secret,release.yml 出签名包);② **runner 用 docker.sock 模式 + 自建 CI 镜像**(Unraid 本机 build,本地 tag,免私有 registry);③ **阶段 1+2+脚手架**一次落齐,Bridge/Issue 自动触发留后续。

---

## 0. 已就位 vs 待你执行

**已就位(本次会话写入)**:`.gitea/workflows/{branch,integration,release}.yml`、`scripts/ci/{local,remote,release}-check.sh`、`scripts/gitea/{claim,comment,query,publish}-*.sh`、`docker/ci-android/Dockerfile`、`.opencode/agent/{android-primary,ci-failure-analyzer}.md`、`.opencode/skills/gitea-ci/SKILL.md`、`.gitea/ISSUE_TEMPLATE/feature.md`;Gitea 8 个状态标签已建。

**待你执行**:① Unraid 部署 act_runner + 构建 CI 镜像;② Gitea 建签名 Secrets(+ 可选 bot 账号);③ push 脚手架到 `agent/**` 分支验证端到端。

---

## 1. 本机 / 远端校验边界

| 位置 | 脚本 / 命令 | 用途 |
|---|---|---|
| 本机 | `./scripts/ci/local-check.sh`(=`compileDebugKotlin + testDebugUnitTest`) | 开发期几十秒挡低级错误 |
| 远端分支 | `branch-check.yml` → `remote-check.sh`(`lintDebug + testDebugUnitTest + assembleDebug`) | 干净容器验证任务分支可构建 |
| 远端整合 | `integration-check.yml`(`clean + lint + test + assembleDebug + koverVerify`) | 验证与主干组合 + 覆盖率门控 |
| 远端 Release | `release.yml` → `release-check.sh`(`clean + lintRelease + testRelease + bundleRelease + assembleRelease`) | CI 签名出 APK/AAB/mapping/SHA256SUMS |

---

## 2. Unraid:act_runner + CI 镜像部署

### 2.1 构建 Android CI 镜像(在 Unraid 宿主 SSH/终端)
```bash
# 把 docker/ci-android/Dockerfile 放到 Unraid 可访问处(从仓库拉,或 git clone 后)
cd /path/to/ocdroid
docker build -t ocdroid-ci-android:35-jdk21 docker/ci-android/
docker images | grep ocdroid-ci-android   # 确认
```
镜像含 JDK 21 + Android SDK(platform-35 / build-tools 35.0.0 / cmdline-tools),约 3–4GB。act_runner 与本镜像**同一 Docker daemon**即可,无需 registry。

### 2.2 取 runner 注册 token(repo admin 可取;`mfreceiver` 即是)
```bash
# 在开发机(有 GITEA_TOKEN 的地方)跑,把输出 token 复制到 Unraid
source ~/.config/opencode/gitea.env   # 或 tea config
curl -sf -H "Authorization: token $GITEA_TOKEN" -X POST \
  "https://git.vectory.cn:18443/api/v1/repos/mfreceiver/oc-droid/actions/runners/registration-token" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'
```

### 2.3 Unraid Docker UI「Add Container」逐字段
| 字段 | 值 |
|---|---|
| Name | `gitea-act-runner-android` |
| Repository | `gitea/act_runner` |
| Tag | `nightly`(首次;验证稳定后改具体 tag 或 digest 固定) |
| Network Type | `bridge`(若 Gitea 在自定义网络,runner 加入同一网络) |
| **Volume** `/data` | `/mnt/cache/appdata/gitea-act-runner`(SSD cache,Gradle 小文件多,勿落阵列) |
| **Volume** `/var/run/docker.sock` | `/var/run/docker.sock`(Docker job 模式;≈宿主 root 权限,**仅可信私有仓库**) |
| **Volume** `/config.yaml` | `/mnt/cache/appdata/gitea-act-runner/config.yaml`(见 2.4,控制 pull 策略) |
| Env `GITEA_INSTANCE_URL` | `https://git.vectory.cn:18443` |
| Env `GITEA_RUNNER_REGISTRATION_TOKEN` | <2.2 取到的 token> |
| Env `GITEA_RUNNER_NAME` | `unraid-android-01` |
| Env `GITEA_RUNNER_LABELS` | `android:docker://ocdroid-ci-android:35-jdk21` |
| Env `CONFIG_FILE` | `/config.yaml`(让 act_runner 读你挂的配置) |

> 注:若 Unraid 模板不便挂 config,先不挂 `/config.yaml`、不设 `CONFIG_FILE`,让 act_runner 自动生成默认配置;**仅当 job 报「pull image 失败」(本地镜像被尝试 pull)时**,再按 2.4 挂上 `--pull=never` 配置。

### 2.4 act_runner config.yaml(挂到 `/config.yaml`)
```yaml
# /mnt/cache/appdata/gitea-act-runner/config.yaml
log:
  level: info
runner:
  capacity: 1            # Android 构建吃内存,Unraid 单 runner 建议 1 并发
  timeout: 60m           # 首次构建下依赖很慢
cache:
  enabled: true          # actions/cache 后端
container:
  options: "--pull=never"   # 用本地构建好的 ocdroid-ci-android:35-jdk21,不去 registry pull
  valid_volumes:
    - "**"
```

### 2.5 启动后确认
Unraid Docker 页面起容器 → 日志应出现 `Runner registered successfully`;Gitea 仓库 → Settings → Actions → Runners 可见 `unraid-android-01` 标签 `android`,状态 idle。

---

## 3. Gitea:签名 Secrets(+ 可选 bot / 分支保护)

### 3.1 生成签名 Secrets 的值(在开发机跑;**输出勿提交**)
```bash
# keystore → base64(填入 Secret RELEASE_KEYSTORE)
base64 -w0 /home/mar/.android/opencode_release.keystore

# 3 个密码(从 pass 读,填入对应 Secret)
pass ocdroid/release/store-password    # → RELEASE_STORE_PASSWORD
pass ocdroid/release/key-alias         # → RELEASE_KEY_ALIAS(应为 "release")
pass ocdroid/release/key-password      # → RELEASE_KEY_PASSWORD
```

### 3.2 在 Gitea 建 Secrets(仓库 Settings → Actions → Secrets → Add Secret)
| Secret 名 | 值来源 | 仅 release.yml 用 |
|---|---|---|
| `RELEASE_KEYSTORE` | 3.1 的 base64 串 | ✓ |
| `RELEASE_STORE_PASSWORD` | `pass ocdroid/release/store-password` | ✓ |
| `RELEASE_KEY_ALIAS` | `pass ocdroid/release/key-alias` | ✓ |
| `RELEASE_KEY_PASSWORD` | `pass ocdroid/release/key-password` | ✓ |

> 或用 API:`curl -X POST .../repos/mfreceiver/oc-droid/actions/secrets -d '{"name":"RELEASE_KEYSTORE","data":"<base64>"}'`(repo admin 可设)。
> `release.yml` 上传 Release 用 Gitea 自动注入的 `GITHUB_TOKEN`(contents:write);若 asset 上传 403,再建 `RELEASE_GITEA_TOKEN`(个人 token)并在 workflow 里改用它。

### 3.3 bot 账号(可选,Phase 4 推荐;需站点管理员)
当前 Primary 可复用 `mfreceiver` 的 token。若要独立 bot:`mfreceiver` 非站点管理员,**需你的 Gitea 站点管理员**建 `opencode-bot` 账号 + 颁发最小权限 token(该仓库 `issue read/write`、`repo:issue`、`write:repository` 推 `agent/**`),存入 `~/.config/opencode/gitea.env`。

### 3.4 分支保护(main)
仓库 Settings → Branches → main:
- `Block force pushes` = 开(防 bot/误操作强推)。
- 可选 `Require status checks`:等 branch-check 跑通后再勾 `branch-check / build` 为必需(首启时 CI 还没绿,勿先勾否则阻塞)。
- tag 保护:Gitea 社区版无原生 tag 保护规则;靠 release.yml 只在 `v*` 触发 + 人工 push tag 来约束。

---

## 4. 端到端验证(阶段 1 验收)

> ⚠️ **前置**:工作树当前在一个未完成的 Kotlin 重构中(god-file 拆分,32 文件改动未接线),`check.sh` 现在会**因重构失败**。验证 CI 前,脚手架分支必须基于**已知绿**的提交 `v0.14.1`,否则 branch-check 一上就红(红在 Kotlin,不在 CI/CD)。

```bash
cd /home/mar/personal_projects/ocdroid
git stash                        # 暂存未完成的重构(或单独分支保存)
git checkout -b ci/gitea-scaffold v0.14.1   # 基于已知绿 tag
# 把本次会话的 CI/CD 文件恢复到此分支:见下方说明
git stash pop                    # 若 stash 含脚手架文件;否则手动 checkout 这批文件
git push -u origin ci/gitea-scaffold
```
> 若脚手架文件与重构改动混在 stash 里:先 `git stash`,建分支后 `git checkout stash@{0} -- .gitea docker scripts/ci scripts/gitea .opencode/agent .opencode/skills docs/specs/gitea-cicd.md` 只取这批。

push 后到 Gitea → Actions 看 `branch-check` 是否触发、`runs-on: android` 是否被 `unraid-android-01` 接单、Debug APK artifact 是否产出。绿 = 阶段 1 通过。

**release 验证**(阶段 5,可选先跑通):临时把 `release.yml` 的 `runs-on` 改回 `android`,建 Secrets 后打测试 tag `git tag v0.0.0-ci-test && git push origin v0.0.0-ci-test`,看 release.yml 是否出签名 APK/AAB 并建 Gitea Release;验后删 tag/release。

---

## 5. Bridge 设计(阶段 3,本次不实现)

技术选型:单文件 Python(FastAPI)或 Go,部署为 Unraid 容器,持久化用 SQLite。
| 路由 | 用途 |
|---|---|
| `POST /webhook/issue` | 校验 Gitea Webhook Secret、事件类型、`agent:ready` 标签 → 查幂等表 → 调 `opencode run --agent android-primary` 启 session |
| `POST /webhook/ci` | 收 CI 完成 Webhook → 比对 head_sha == 分支 HEAD → 更新状态 / 调 `session_send` 回传原 session |
| `GET /mappings/{issue}` | 读 Issue→Session→worktree→branch→commit 映射 |

记录结构见总文档附录 B。审计日志落 `/mnt/cache/appdata/opencode-bridge/`。**安全**:验证 Secret、白名单 Issue 作者、单 Issue 锁、绝不把 Issue 正文当 shell 执行。

---

## 6. 验收清单(总文档附录 C × 本仓库)
- [ ] push `agent/**` / `ci/**` → 仅触发一次 `branch-check`,被 `unraid-android-01` 接单。
- [ ] Debug APK artifact 可下载;测试/lint 报告上传。
- [ ] 故意引入 Kotlin 编译错误 → CI 红,状态绑定该 commit。
- [ ] 修复后新 commit 触发新 run,旧失败结果不污染。
- [ ] `v*` tag → `release.yml` 出**签名** APK/AAB + mapping + SHA256SUMS,建 Gitea Release。
- [ ] 普通 `android` runner / Primary agent 无法读 `RELEASE_*` Secrets。
- [ ] `integration-check` 在 main 触发,含 `koverVerify` 门控。

## 7. 回滚 / 维护
- **撤 CI**:删 `.gitea/workflows/*.yml`(仓库即时停);runner 容器 `stop` 即停接单。
- **轮换签名**:重新 `pass` 生成新 key → 重建 keystore → 覆盖 Gitea Secret `RELEASE_KEYSTORE` + 3 密码。
- **磁盘**:定期清 `/mnt/cache/appdata/gitea-act-runner` 下旧 job cache;Gitea Actions artifacts 按仓库 Settings 设保留期。
- **升级 CI 镜像**:`docker build --no-cache -t ocdroid-ci-android:35-jdk21 docker/ci-android/`(SDK/AGP 升级时同步改 Dockerfile 版本)。
