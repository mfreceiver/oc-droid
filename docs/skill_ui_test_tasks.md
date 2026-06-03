# Skill：用操作-skill 完成 UI 测试任务（Tier 4，LLM-driven）

## 元数据

- 类型：Workflow
- 适用场景：需要做那种「确定性测试做不到、必须靠现场判断」的 UI 测试——异步状态、定时刷新、等待某个事件后看 UI 变没变、整屏渲染是否合理。这是四层测试体系（见 `docs/test.md`）里最上面一层。
- 依赖：操作层 skill `skill_operate_emulator.md`（怎么用 ui_driver CLI 驱动 client）。本 skill 建立在它之上。
- 成本：唯一有 LLM token 开销的一层。按需/定期跑，不进每次 commit。

## 这一层为什么存在

下面三层（unit / component / integration-UI）是过程确定性：预先把断言写死，只能查你想到要查的东西。有些 bug 没法这么测——比如「发消息后 session 标题该自动更新，但实际要手动触发刷新才更新」。这种异步、依赖时序、要等某个事件再判断的行为，没法可靠地写成确定性断言，但 agent 可以：真的去操作、等一段时间或等某个事件、再看结果对不对。

核心范式是结果确定性（见博客《从过程确定性到结果确定性》）：不规定 agent 每步怎么走，只给它用户场景目标和验收标准，让它自己用工具达成并验证。

## 结构：CLI 是 skeleton，agent 做判断，test 是 prompt

三者分工：

- **CLI（skill 1）= 固化的骨架。** 配 server、发 prompt 这些不变的动作序列，用 ui_driver 的高层命令几秒钟跑完，不耗 agent 的现场推理。
- **agent = 灵活的判断层。** 只做需要判断的部分：读返回的 tree、决定等多久、判断 UI 状态对不对。
- **每个 test = 一个 prompt。** 写到「健忘症实习生」粒度：一个没有上下文、只读这一个 prompt 的 agent，据此能判断自己验证完了没有，不行的话知道还差什么。
- **跑一个 test = 开一个 sub-agent**，把 prompt + skill 1 + skill 2 交给它，让它驱动。

prompt 该写「验证用户能进入一个 session 并看到读操作被标成读」这种目标 + 验收标准，**不要**写「点坐标 x,y、再点 z」。一旦规定了每步怎么点，就退回了过程确定性，那不如去写 integration-UI 的确定性测试。

## 怎么写一个 test prompt

一个合格的 test prompt 包含四块：

1. 指向 skill 1（操作工具）。
2. **目标**：一句话说清要验证的用户场景。
3. **可做的 setup**：达成场景前要做的固化操作（配 server、建 session、发 prompt），尽量用 CLI 高层命令，不规定细到点哪。
4. **验收标准**：客观、可判断的成功条件，写到 agent 能自行判断的程度；以及失败时要报告什么。

现有 prompt 在 `docs/ui_test_prompts/`。新增就按同样粒度写。

## 怎么跑

开一个 sub-agent，把目标 prompt、`skill_operate_emulator.md`、本文件交给它，让它用 `ui_driver` CLI 驱动真实 app 完成验证。它会跑 act → 观察返回 tree → 判断 → 下一步 的循环，最后给出 PASS / FAIL / BLOCKED 和证据。

先确认前置：模拟器在跑、**app 已安装**（`connectedDebugAndroidTest` 跑完会卸载 app，记得重装）、4097 server 在跑。

## 安全边界

同 skill 1：只跑 read 类安全场景，不让 agent 让 app 触发真实 write（server 共享真实工作目录）。prompt 里明确写「不要创建/编辑/写文件」。

## 验收标准（一次 test 运行算成功）

- agent 给出明确 VERDICT：PASS（所有验收标准满足）/ FAIL（不满足，说清哪条）/ BLOCKED（卡在哪、为什么）。
- 给出证据：证明 verdict 的 tree 摘要或截图观察（比如 read card 的 content-desc 以 "Read file" 开头）。
- 一个精确的 FAIL / BLOCKED 报告也是成功的 test 运行；含糊地放弃不是。

## 从 Tier 4 沉淀到 Tier 3

Tier 4 用来探明一个行为对不对（尤其是异步、时序相关的）。一旦探明且修复，把它能固化的部分沉淀成 Tier 3 的确定性测试（连真实 server、定死断言），让它进每次 commit 守住。Tier 4 探明 → Tier 3 固化，是这两层的协作方式。

## 已知陷阱

- **别用 session 标题判断发消息成功。** 用 CLI/prompt_async 发的 session，服务器常常不生成 title（一直 "New session -"）。判断发送成功看对话里有没有出现消息和 tool card，不看标题。
- **app 可能没装。** 见 skill 1 同名陷阱。Tier 4 跑之前先确认 app 在、且已连上 server。
