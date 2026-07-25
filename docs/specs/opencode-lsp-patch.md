# opencode LSP 膨胀补丁 & 本机二进制维护(SOP)

> 标准操作流程:给**本机实际运行的 opencode 二进制**打补丁抑制 LSP 诊断膨胀,并随上游升级重建。
> 版本无关——所有版本号用变量,按本流程代入即可。

---

## 1. 背景与补丁原理

### 问题
opencode 的 `edit` / `write` 工具在每次编辑后,把**整个工作区的 LSP diagnostics**(所有文件、所有诊断)塞进消息的 `state.metadata.diagnostics`,且**逐条消息重复持久化**。

后果:
- 会话库 `~/.local/share/opencode/opencode.db` 随使用膨胀(实测可达数 GB)。
- `getMessages` / SSE 把全量诊断原样吐给客户端 → 单次响应可达上百 MB → ocdroid 等客户端 OOM。
- 关闭 LSP(`lsp: false`)能消除膨胀,但失去全部语义诊断。

### 上游状态
- **存储/传输层未修**:全量 diagnostics 仍写入 `metadata.diagnostics` 并随消息走 wire(截至本文档维护时所有版本)。
- 上游只截断了**发给 LLM 的输出**(每文件 ≤10 条、≤5 文件),**不影响持久化**。
- 收窄到"仅被改文件"的修复思路未被上游采纳。
- 结论:`lsp: false` 是治标;本补丁是治本(从源头收窄持久化),可**保留 LSP 且不膨胀**。

### 补丁原理
把 `metadata.diagnostics` 从"全工作区 map"改为"仅当前被改文件的单键 map"。LLM 侧输出(已有上游封顶)不动。两行改动,类型保持不变。

---

## 2. 目录约定(ocdroid 仓库内)

源码树放在 ocdroid 仓库内、**已 gitignore**(`.gitignore` 含 `opencode-src/`),按版本分目录,`current` symlink 指向当前在用版本:

```
ocdroid/opencode-src/
├── lsp-bloat.patch        # 补丁文件(见 §4.3)
├── v<VER>/                # 每个 shallow clone 一个版本目录
└── current -> v<VER>      # "最新版本指向";deploy/build 一律走 current
```

> 升级新版本 = 新建 `v<VER>/` → patch + build → 把 `current` 切过去。旧版本目录保留以便回滚。

---

## 3. 本机 opencode 安装实况(关键,别踩坑)

| 路径 | 作用 | 是否被 server 使用 |
|---|---|---|
| `~/.opencode/bin/opencode` | systemd `opencode-web.service` 的 `ExecStart` 指向的二进制 | ✅ **这是真正要替换的** |
| `~/.nvm/.../node_modules/opencode-ai/bin/opencode.exe` | npm 全局包带的二进制 | ❌ server 不用(替换它无效) |
| `~/.config/opencode/opencode.json` | 主配置(`lsp` 等) | 读 |
| `~/.local/share/opencode/opencode.db` | 会话库 | 读写 |

**systemd 服务定义**(确认替换目标):
```
ExecStart=/home/mar/.opencode/bin/opencode serve --hostname 0.0.0.0 --port 4096
WorkingDirectory=/home/mar/opencode_wd
```

---

## 4. 操作流程

### 4.0 设版本变量(每次操作前代入当前目标版本)
```bash
VER_TAG=v1.18.4          # git tag(带 v 前缀)
VER=1.18.4               # opencode --version 期望值(去 v 前缀)
```

### 4.1 (步骤②)下载最新源码(shallow)+ 更新 current 指向
```bash
cd /home/mar/personal_projects/ocdroid/opencode-src

# shallow clone(省流量/省时;代价见 §5,必须配合 build 时注入 version)
git clone --depth 1 --branch "$VER_TAG" https://github.com/anomalyco/opencode.git "$VER_TAG"

# 更新"最新版本指向"
ln -sfn "$VER_TAG" current
```
> `anomalyco/opencode` 是 canonical 仓库(`sst/opencode` 重定向到此)。

### 4.2 (步骤③-a)应用补丁

补丁文件 `opencode-src/lsp-bloat.patch`(内容见 §4.3)。应用:
```bash
cd /home/mar/personal_projects/ocdroid/opencode-src/current
git apply ../lsp-bloat.patch
```
若 `git apply` 因行号偏移失败,手工改这两处(定位方法见 §4.3 末尾)。

### 4.3 补丁内容

`lsp-bloat.patch`:
```diff
--- a/packages/opencode/src/tool/edit.ts
+++ b/packages/opencode/src/tool/edit.ts
@@ return {
           return {
             metadata: {
-              diagnostics,
+              diagnostics: { [normalizedFilePath]: diagnostics[normalizedFilePath] ?? [] },
               diff,
               filediff,
             },
--- a/packages/opencode/src/tool/write.ts
+++ b/packages/opencode/src/tool/write.ts
@@ return {
           return {
             title: path.relative(instance.worktree, filepath),
             metadata: {
-              diagnostics,
+              diagnostics: { [normalizedFilepath]: diagnostics[normalizedFilepath] ?? [] },
               filepath,
               exists: exists,
             },
```
> - **大小写**:`edit.ts` 用 `normalizedFilePath`(大写 P),`write.ts` 用 `normalizedFilepath`(小写 p)。两个 accessor 在各自文件上方已计算好(`FSUtil.normalizePath(...)`),无需新增声明。
> - **行号随版本可能变**:以实际为准。定位方法:在两个文件里找工具返回值的 `metadata: {` 块,把其中的 `diagnostics,` 改成上述单键 map。注意 `edit.ts` 另有一处 `diagnostics: {}`(中间 `ctx.metadata`,已为空)**不要动**。

### 4.4 (步骤③-b)编译
```bash
cd /home/mar/personal_projects/ocdroid/opencode-src/current
export PATH="$HOME/.bun/bin:$PATH"     # bun 需匹配 root package.json 的 packageManager 字段

bun install

# 编译当前平台(linux-x64)原生二进制
# !!! 必须带 OPENCODE_VERSION + OPENCODE_CHANNEL,否则 version 退化(见 §5)!!!
cd packages/opencode
OPENCODE_VERSION="$VER" OPENCODE_CHANNEL=latest bun run script/build.ts --single
```
产物:`packages/opencode/dist/opencode-linux-x64/bin/opencode`

**验证编译结果**(`--version` 必须等于 `$VER`,不能是 `0.0.0--...`):
```bash
./dist/opencode-linux-x64/bin/opencode --version
```

### 4.5 (步骤④-a)部署(更新到本机)
```bash
SRC=/home/mar/personal_projects/ocdroid/opencode-src/current/packages/opencode/dist/opencode-linux-x64/bin/opencode
DST=/home/mar/.opencode/bin/opencode     # ← server 实际用的(systemd ExecStart)

# 备份当前在用版本(回滚用;文件名含原版本号)
cp -a "$DST" "$DST.bak-$("$DST" --version 2>/dev/null | head -1)"

# 记录产物 sha(部署后比对,确认 cp 成功)
SRC_SHA=$(sha256sum "$SRC" | awk '{print $1}')

# 替换(--remove-destination 处理硬链接;不影响正在运行的进程)
cp --remove-destination "$SRC" "$DST"
chmod +x "$DST"

# 核对:版本 + sha 与产物一致
"$DST" --version
sha256sum "$DST" | awk '{print $1}'     # 应等于 $SRC_SHA

# 重启服务生效
systemctl --user restart opencode-web.service
```

### 4.6 (步骤④-b)恢复(回滚)
```bash
DST=/home/mar/.opencode/bin/opencode
ls -1 "$DST".bak-*                       # 列出可用备份

# 选一个还原(例:回到某旧版本)
cp --remove-destination "$DST.bak-<旧版本>" "$DST"
chmod +x "$DST"
"$DST" --version
systemctl --user restart opencode-web.service
```

---

## 5. ⚠️ 最大坑:shallow clone → version 退化为 `0.0.0`

### 现象
不带 `OPENCODE_VERSION` 编译出的二进制,`--version` 是 `0.0.0-<时间戳>`;启动后:
- TUI 左上角显示 **dev** 标记;
- **读不到历史会话**(数据没丢,是 preview/dev 状态导致会话加载异常)。

### 根因(`packages/script/src/index.ts`)
version/channel 在 build 时从 git 派生:
```
没设 OPENCODE_VERSION → CHANNEL 回退到 `git branch --show-current`
shallow clone + detached HEAD → 该命令返回空 → CHANNEL="" → IS_PREVIEW=true
→ VERSION = 0.0.0-${CHANNEL}-${timestamp}   ← 退化
```
build.ts 把这个退化版本通过 `define: { OPENCODE_VERSION: '${Script.version}' }` 注入二进制。

### 规避(必须)
build 时**显式注入**(见 §4.4):
```bash
OPENCODE_VERSION="$VER" OPENCODE_CHANNEL=latest bun run script/build.ts --single
```
- `OPENCODE_VERSION` → 直接采用,不走 git 派生。
- `OPENCODE_CHANNEL=latest` → `IS_PREVIEW=false`,非 dev。
- build 后务必 `--version` 核对,不是 `0.0.0--...` 才能部署。

> 这是 shallow clone 的固有代价。要么 shallow + 注入 version(本方案),要么完整 clone 让 `git describe` 正常工作(仓库大,不推荐)。

---

## 6. LSP 开关与补丁验证

补丁部署后,LSP 可常开(膨胀已被源头收窄)。开关在 `~/.config/opencode/opencode.json`:
```jsonc
"lsp": true,   // 补丁在位时安全开;补丁不在位时务必 false,否则全量膨胀复发
```
改后重启服务生效:`systemctl --user restart opencode-web.service`。

### 补丁生效的运行时验证(开着 LSP)
| 观察 | 正常(补丁生效) | 异常(补丁某路径漏了) |
|---|---|---|
| 编辑后单条消息体积 | 保持小(p50≈KB 级) | 回到 multi-MB |
| `opencode.db` 增长 | 缓慢 | 再暴涨 |
| 客户端拉消息 | 流畅 | 卡 / OOM |

若出现膨胀:大概率是另一条写入 `metadata.diagnostics` 的路径(别的 tool 或 plugin)。到 `opencode-src/current/packages/opencode/src` 搜 `metadata` + `diagnostics`,把所有写入点补齐。

---

## 7. 维护:随上游升级

- **发版节奏**:anomalyco/opencode 约 2–4 周一版。
- **补丁稳定性**:`edit.ts`/`write.ts` 的诊断持久化代码 historically 行号稳定,rebase 一般零冲突;若冲突按 §4.3 的定位方法手工调整两行。
- **升级流程**(改 §4.0 的版本变量后):
  1. §4.1 clone 新版本到 `opencode-src/v<NEW>/`;
  2. §4.2 应用补丁(冲突则按 §4.3 调整);
  3. §4.4 编译(带新版本号的 `OPENCODE_VERSION`);
  4. §4.4 `--version` 核对 → §4.5 部署 → 重启;
  5. `ln -sfn v<NEW> current` 切换指向;
  6. §6 验证 LSP 开着不膨胀。
