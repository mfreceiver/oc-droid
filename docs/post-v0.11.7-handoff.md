# ocdroid 0.11 线状态交接（更新 2026-07-21）

> **本文件为上下文压缩后的恢复锚点。**  
> **0.11 近/中/远期完整计划（供 slimapi 共同评审）见：`docs/0.11-joint-roadmap.md`。**

## 0. 当前状态（已完成，勿重做）

### 发版

| 版本 | commit | 内容 | Gitea |
|---|---|---|---|
| **v0.11.7** | `668e3e2` | slim resync 移出 Main；roots=true+limit=500；服务器配置高级折叠 UI | [release](https://git.vectory.cn:18443/mfreceiver/oc-droid/releases/tag/v0.11.7) |
| **v0.11.8** | `109eb1a` | T2 窄化 focus gate + T3 死锁/stripe 回归 | [release](https://git.vectory.cn:18443/mfreceiver/oc-droid/releases/tag/v0.11.8) |
| **v0.11.9** | `34db07f` | UI 高级 Column 堆叠修复；upload-release 非破坏 poll-wait | [release](https://git.vectory.cn:18443/mfreceiver/oc-droid/releases/tag/v0.11.9) |
| **v0.11.10** | `69724a2` | **slimapi rev F 客户端消费**：placeholder message-level 替换、`server.reconfigured`、sessions 三头、连接建立 coalesce | [release](https://git.vectory.cn:18443/mfreceiver/oc-droid/releases/tag/v0.11.10) |

### Phase 2–5（v0.11.7 后续独立任务）— 已完成

- Phase 2：rev-grok plan review **8.5/10** → **T1 NO-GO**；T2/T3 **GO**
- Phase 3：T2+T3 落地；joint `check.sh` 绿
- Phase 4：rev-grok + rev-glm **9.6/9.6 GO**
- Phase 5：commit/push → **v0.11.8** 发版

### slimapi 契约线 — 已闭环（rev F）

| 议题 | slimapi | ocdroid v0.11.10 |
|---|---|---|
| §1 sessions 三头 | 已实施 | 已消费；Ready=false 不权威清 |
| §2 partId + placeholder | 已 ratify；placeholder 保留 | message-level 替换已做 |
| §3 `server.reconfigured` | 已实施 | 同 resync 冷启动 + coalesce |
| §4 health schema 三键 | 已实施 | **未消费**（可选诊断） |
| G6 `/full?ids=` | 生产已开 | 主路径 batch + 旧实例 fallback |

slimapi 对 ocdroid 落地确认：**全部对齐、无 blocker**（2026-07-21 回复）。  
权威：slimapi `docs/ocmar/reports/2026-07-21-v0.11.7-feedback-handoff.md` + `docs/v1-contract.md` rev F。

### 明确不做 / 已否决

- **T1** prepare→commit 两段式：rev-grok **NO-GO**（冷启动 RMW 风险）
- sessions **强制二次分页**（Complete=false）：协议不要求；limit=500 兜底
- Wire bump：保持 **1**

## 1. 下一步默认动作

1. **真机回归 v0.11.10**（Slim 指向 rev F 行为实例）：
   - placeholder / thin 消息「展开」成功
   - discovery / ready 翻转 → 自动 cold-start
   - 重连建立期无明显双倍列表闪烁
2. 回归通过后按 **`docs/0.11-joint-roadmap.md`** 推进近期项（文档已部分更新；health 三键可选；slimapi 打正式 patch tag）。

## 2. 关键文件

- 计划：`docs/0.11-joint-roadmap.md`（**评审入口**）
- 路由文档：`docs/slim-mode-api-routing.md`（2026-07-21 rev F 对齐）
- 实现任务书：`docs/slimapi-client-impl-v1.md`
- Phase2 细节（历史）：`docs/post-v0.11.7-phase2-plan.md`
- 代码：`PartExpandState.kt` / `SlimapiMessageMerge.kt` / `ServiceSseConnectionOwner.kt` / `OpenCodeRepository.kt` / `SessionSyncCoordinator.kt`

## 3. 校验/发版入口（硬规则）

```bash
./scripts/check.sh
./scripts/release.sh patch   # 唯一发版入口；版本 git 派生
git push origin main && git push origin <tag>
./scripts/upload-release.sh <version>
```

## 4. resume 后第一步

1. 读本文件 + **`docs/0.11-joint-roadmap.md`**
2. 确认 slimapi 实例行为（三头 / schema / reconfigured），**勿**用 `sidecar.version==0.2.2` 否定 rev F
3. 真机回归或按 roadmap 拆任务
