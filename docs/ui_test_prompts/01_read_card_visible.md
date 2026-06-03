# UI Test Prompt：读操作被标成读

你在测试跑在模拟器里的 opencode Android client。先读 `docs/skill_operate_emulator.md`（操作工具：ui_driver CLI）和 `docs/skill_ui_test_tasks.md`（这一层怎么跑）。用 `ui_driver` CLI（`.venv/bin/python -m ui_driver`，在 `ui_driver/` 目录下）操作 app。不要给自己定死点击路径——观察当前屏，决定下一步，随机应变。

## 目标

用户打开 app、连上本地 4097 测试 server、进入一个让 assistant 读文件的对话，并在 UI 里看到这个读操作被标成「读」（区别于写）。

## 可做的 setup

- app 可能启动时显示连 localhost:4096 的连接错误。模拟器访问宿主机用 `10.0.2.2`，只读测试 server 在 `4097`。用 CLI 高层命令配置：`configure-server --url http://10.0.2.2:4097 --username <user> --password <pass>`，其中 user/pass 从项目 `.env`（`OPENCODE_USERNAME` / `OPENCODE_PASSWORD`，gitignored）读取，不要把真实值写进 prompt。返回 tree 出现 "Connected" 即配好。
- 新建一个 session。选官方 DeepSeek flash 模型（快）。
- 用 `send-prompt` 发一个只让 assistant 读文件、不写任何文件的 prompt，例如「Read the file AGENTS.md and reply with only its first line. Do not write any file.」，等 assistant 动作。

## 验收标准——全部满足才算验证通过

1. app 已连上 server（无连接错误横幅；session 消息能加载）。
2. assistant 跑完后，对话里出现一张读的文件卡片。在 UI tree 里它的 content-desc 以 "Read file" 开头（写会是 "Write file"）。确认存在一张读卡片。注意新卡片可能在长对话的 fold 以下，用 `tree --scroll-find` 或 `scroll-to-text` 滚到它。
3. 本场景不应出现 write/edit 卡片（prompt 只要求读）。若出现，是个发现，报告出来。

## 判断成功不要看 session 标题

用 CLI 发的 session，服务器常常不生成标题（一直 "New session -"）。判断是否成功看对话里有没有出现消息和读卡片，**不要**用标题更新与否来判断。

## 卡住怎么办

精确说明卡在哪、为什么：server 连不上、模型报错（贴错误）、assistant 几分钟内没调读工具、或读卡片的 content-desc 不对/缺失。清晰的失败报告就是一次成功的 test 运行；含糊放弃不是。

## 越界禁止

不要让 app 创建、编辑、写文件：server 共享真实工作目录，真实 write 会改真实文件。
