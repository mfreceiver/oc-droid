# Skill：用 ui_driver CLI 操作模拟器里的 client

## 元数据

- 类型：API Guide / 操作层
- 适用场景：需要驱动跑在 Android 模拟器里的 opencode client（`com.yage.opencode_client`）做任何 UI 操作——配置 server、新建/切换 session、发 prompt、读屏幕状态。
- 上层 skill：测试任务编排见 `skill_ui_test_tasks.md`，它建立在本 skill 之上。
- 工具：`ui_driver/`（Python CLI，`.venv/bin/python -m ui_driver`）。完整命令参考、输出 schema、架构见 `ui_driver/README.md`。

## 这个 skill 解决什么

直接让 agent 用 adb/uiautomator 一步步点屏幕，慢且脆：每个动作都要截图、判断、再决定下一步，配一次 server 要几分钟。但配 server、发 prompt 这类动作序列是**固化的**——步骤不变，不需要 agent 现场推理。把它们绑成确定性 CLI 命令后，agent 一条命令几秒钟完成，且不会因为 LLM 现场判断失误而点错。

分工原则：固化的机械动作交给 CLI，需要判断的部分（看渲染对不对、等某个异步结果）才留给 agent。本 skill 教你怎么用 CLI 这一半。

## 核心契约

**每个会改变 UI 的命令，执行后返回操作后的 UI tree（JSON 打到 stdout）。** 所以 agent 的循环是：发一条命令 → 读返回的 tree → 判断 → 下一条命令。不需要在命令之间单独再 dump tree。

读 tree 时优先看 `compact`（只保留有 text/content-desc 的节点），skim 一屏内容足够。需要坐标或完整结构时再看 `nodes`。

## 可用命令（agent 视角）

低层原子命令：`tree`（只读当前屏，`--scroll-find LABEL` 会先往下滚到 LABEL）、`tap-xy`、`tap-text <label>`（按 text/content-desc 子串匹配点击）、`type-text`、`clear-field`、`key <back|home|enter>`、`launch`、`clear-data`（pm clear 重置）、`scroll-to-text <label>`。

高层确定性序列（本 CLI 的主要价值，优先用这些而非自己拼原子命令）：

- `configure-server --url U --username U --password P` — 走完整个 Settings 配置流程并返回结果 tree。返回里带 `connected` 字段表示连接是否成功；tree 里出现 "Connected" 文字即配置成功。
- `send-prompt <text>` — 在 chat 界面填入 prompt 并发送（自动处理 Send 按钮位置漂移）。
- `select-model <name>` — 打开模型选择器选包含 name 的项（best-effort）。

所有命令可加 `--screenshot <path>` 额外存截图。设备选择：默认选第一个 emulator-*；机器上同时连着真机时，用 `--serial emulator-5554` 或 `ANDROID_SERIAL` 明确指定。

## 安全边界（硬约束）

- **只跑 read 类操作，绝不让 client 触发真实 write。** 4096/4097 server 共享真实工作目录 `/Users/grapeot/co/knowledge_working`，client 触发的真实 write tool 会改真实文件。发 prompt 时只发读类（"读某文件"），不发写/改/建文件类。
- 测试 server 用 4097；模拟器里访问宿主机用 `10.0.2.2`，不是 `localhost`。

## 前置条件

1. 模拟器在跑（`adb devices` 能看到 emulator-*）。
2. **app 已安装**——这是最容易忘的前提，见下方陷阱。
3. server 在跑（4097），credential 见项目 `.env`（gitignored）。

## 已知陷阱（真机踩出来的，不是预测的）

- **`connectedDebugAndroidTest` 跑完会卸载 app。** gradle 的 instrumented test 跑完会把 app 和 test APK 都卸掉。之后 `clear-data`/`launch` 会失败（`pm clear` 返回 "Failed"）或停在 launcher 桌面。症状：`tree` 返回的 package 是 `nexuslauncher`、texts 里全是 Gmail/Photos/YouTube。解决：先 `./gradlew assembleDebug` 再 `adb -s emulator-5554 install -r -g <apk>` 重装，再驱动。
- **`launch` 后 activity 需要时间到前台。** CLI 的 `launch` 已内置等待（轮询 topResumedActivity），但若你直接用 `am start`，立刻 dump 可能抓到上一个界面。
- **不要无条件按 BACK 收键盘。** app 在根界面、没有键盘弹起时按 BACK 会直接退出 app 到 launcher。CLI 的高层命令已经做了 conditional dismiss（只在 `mInputShown=true` 时按 BACK）；agent 自己拼原子命令时也要注意这点。
- **用 CLI / prompt_async 发的 session，服务器往往不生成 title。** 这些 session 标题一直是 "New session - <时间戳>"，服务器侧就没生成过真标题（title agent 没被触发）。所以**不要用 session 标题是否更新来判断 CLI 发消息成功了**；判断成功要看消息/tool call 是否出现在对话里。这条对测「标题刷新」类行为尤其关键：得走 app 真实 send 路径、足够长的对话才可能触发 title agent。

- **Test Connection 会卡死（app 侧 bug）。** 在同一个 app session 里反复点 Test Connection（或反复跑 configure-server），它会进入无限转圈状态，既不出 "Connected" 也不报错，之后怎么配都连不上（`connected: False`），即使 host 上 server 健康、URL 填对、网络通。解法：`am force-stop` + `pm clear` + 重启 app，干净状态下**只配一次**。相关修复见社区 PR #34（cancel-and-restart 替代 cooldown）。配 server 失败时先怀疑这个，别反复重试 configure-server（越重试越卡）。

- **emulator → host 连接偶尔会断。** 现象同上（`connected: False` 但 host 4097 health 200）。`10.0.2.2` 是标准宿主机别名，断了可尝试 cold-boot emulator 或 `adb reverse tcp:4097 tcp:4097` + 改用 `localhost:4097`。注意这和上一条的 Test-Connection 卡死表现一样，需要分辨：先确认 emulator 能不能 ping 通 host，再判断是网络还是 app 卡死。

- **send-prompt 的可靠性来自「发后验证」。** 早期 send-prompt 会打了字但 Send 点击不生效（dump UI tree 时键盘弹起会扰动 IME，紧接着的 tap 落空）。现在的实现先收键盘再定位 Send、发送后 poll 输入框确认文字离开、不行就重试。这条逻辑有单测覆盖；若真机上仍遇到没发出去，先确认是不是上面两条（Test Connection 卡死 / 网络断）导致根本没进到可发送状态。

## 验收标准

一次操作算成功，看的是**返回 tree 里的客观状态**，不是命令退出码：

- 配 server 成功：返回 tree 出现 "Connected"（或 `connected: true`）。
- 发 prompt 成功：随后对话里出现该消息，以及（read 类）出现对应 tool card（content-desc 以 "Read file" 开头）。
- 命令失败时 CLI 透传 adb 原始错误（exit code + stderr），据此定位，不要猜。

## 命令本身的测试

CLI 是被测对象，自己有测试（`ui_driver/tests/`）：纯逻辑 unit test 用 XML fixture（无需模拟器），integration test 默认 skip（需模拟器 + `UI_DRIVER_RUN_INTEGRATION=1`）。改 CLI 后跑 `cd ui_driver && .venv/bin/python -m pytest`。
