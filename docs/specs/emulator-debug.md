# 模拟器调试指南

> 本文说明在本机用 **Android 模拟器**（headless / 无窗口）进行调试与集成测试的标准流程。
> 生命周期统一通过 `scripts/emulator.sh` 管理；构建/签名规则见 `docs/specs/build-apk.md`。
> 设备安全规定见 `AGENTS.md`：**仅用模拟器，不在物理真机上跑测试或安装 debug 构建**（除非用户明确要求）。

---

## 0. 一句话流程

```bash
./scripts/emulator.sh status    # 1. 确认未运行（=没人在用）
./scripts/emulator.sh start     # 2. 启动
# ... 跑测试 / adb 调试 ...
./scripts/emulator.sh stop      # 3. 用完必关，清理环境
```

**核心约定**：模拟器是本机共享资源。每个用户/会话在使用前**必须先 `status` 确认未运行**（防止抢占他人会话），用完**必须 `stop`**。不确认就 start、用完不关，都是违规。

---

## 1. 多用户协调机制

模拟器同一时刻只能由一个会话占用（一个 AVD 实例 + 一组 adb 端口）。为避免抢占，`emulator.sh` 用 lockfile 标识当前占用者：

- lockfile 路径：`/tmp/ocdroid-emulator.lock`
- 内容：首行 = 模拟器 PID；后续行 = AVD 名、启动时间、用户、日志路径
- `start` 前检查：已在运行（lockfile 存在且进程存活）→ **拒绝启动**并提示。
- `stop` 时清除 lockfile；进程已退出但 lockfile 残留时，`status` 报「残留」异常并提示先 `stop`。

> 即使是用脚本外方式（如直接敲 `emulator @ocdroid ...`）启动的，`status` 也会通过 adb 检测到在线设备并报异常——仍需 `stop` 归位。

---

## 2. 环境与 AVD（本机已配置）

| 项 | 值 |
|---|---|
| AVD 名 | `ocdroid`（可用 `OCDROID_AVD` 环境变量覆盖） |
| 系统镜像 | `system-images;android-35;google_apis;x86_64`（与 compileSdk=35 一致） |
| CPU/ABI | x86_64 |
| KVM 加速 | `/dev/kvm`，`mar` 已加入 `kvm` 组 |
| 启动模式 | headless：`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect` |
| 日志 | `/tmp/emulator.log`（可用 `EMU_LOG` 覆盖） |
| 开机超时 | 180s（可用 `BOOT_TIMEOUT` 覆盖） |

### KVM 权限说明（首次/重新登录后）

`usermod -aG kvm` 后，**当前已登录的会话不会立刻获得 kvm 组**，导致 `/dev/kvm` 无读写权限。两种处理：

- **推荐**：重新登录一次（或重启），之后 `emulator.sh` 直接启动。
- **临时**：脚本已内置兜底——检测到无 `/dev/kvm` 读写权限时，自动用 `sg kvm -c '...'` 包一层启动，无需重新登录。

### 重建 AVD（新机器）

```bash
source ./scripts/env.sh
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator"
sdkmanager "platform-tools" "emulator" "system-images;android-35;google_apis;x86_64"
yes | sdkmanager --licenses
echo "no" | avdmanager create avd -n ocdroid \
  -k "system-images;android-35;google_apis;x86_64" -d pixel_6
```

---

## 3. `emulator.sh` 命令参考

| 命令 | 作用 | 退出码 |
|---|---|---|
| `status` | 查看运行状态 | 0=运行中，1=未运行，2=异常（残留/脚本外启动） |
| `start` | 启动并等待开机完成 | 0=成功；1=拒绝/失败 |
| `stop` | 关闭 + 清理（adb emu kill → 强杀兜底 → 清 lockfile → adb kill-server） | 0 |
| `restart` | stop 后重新 start | 同 start |

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `OCDROID_AVD` | `ocdroid` | AVD 名称 |
| `EMU_LOG` | `/tmp/emulator.log` | 启动日志 |
| `BOOT_TIMEOUT` | `180` | 等待开机秒数 |

---

## 4. 调试用法

启动模拟器后（`status` 显示运行中），即可用 adb / Gradle 调试。

### 4.1 安装并查看日志

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

adb logcat -v time | grep -iE 'ocdroid|FATAL|AndroidRuntime'   # 实时日志
adb logcat -d > /tmp/logcat.txt                                 # 导出当前日志
adb shell am start -n <pkg>/<main-activity>                     # 启动 App
```

### 4.2 集成测试（connectedDebugAndroidTest）

需运行中的 OpenCode Server：把 `.env.example` 复制为 `.env` 填入凭证，然后：

```bash
./gradlew connectedDebugAndroidTest
# 报告：app/build/reports/androidTests/connected/
```

> 集成测试与安装**仅在模拟器**执行，禁止针对物理真机（`AGENTS.md` 设备安全）。
> 若同时连了真机和模拟器，用 `ANDROID_SERIAL=emulator-5554` 明确指定。

### 4.3 常用排查命令

```bash
adb devices                      # 列设备
adb shell getprop sys.boot_completed   # 应为 1
adb shell pm list packages | grep <pkg>
adb shell input keyevent 3       # HOME
adb exec-out screencap -p > /tmp/screen.png   # 截图
adb shell dumpsys activity <pkg> # Activity 栈
adb bugreport /tmp/bug.zip       # 完整诊断包
```

---

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| `status` 报「残留」或「脚本外启动」 | 跑 `./scripts/emulator.sh stop` 归位 |
| `start` 报「已在运行」 | 先 `status` 看占用者；确属自己遗留则 `stop` 后重启，**不要强占他人会话** |
| 开机超时 | 提高超时 `BOOT_TIMEOUT=300 ./scripts/emulator.sh start`；查 `/tmp/emulator.log` |
| `/dev/kvm` 权限拒绝 | 脚本已自动 `sg kvm` 兜底；根治需重新登录或重启 |
| 端口 5554/5555 被占 | `stop` 会清理 adb server；仍异常：`pkill -f qemu-system-x86_64` |
| 模拟器卡死 | `stop`（含强杀兜底）；必要时 `pkill -9 -f qemu-system-x86_64` |

---

## 附：本机环境实测记录

| 项 | 结果 |
|---|---|
| `/dev/kvm` | 存在（kvm_amd 已加载，8 核 vmx/svm 标志）✓ |
| emulator | 36.6.11 ✓ |
| system-image | android-35 google_apis x86_64 ✓ |
| AVD `ocdroid` | pixel_6 / 2560MB / sdcard 512MB ✓ |
| `start` → 开机完成 | ~15–30s（KVM 加速）✓ |
| `stop` → adb 无设备 | 优雅 kill 生效，lockfile 清除 ✓ |
