#!/usr/bin/env bash
# scripts/emulator.sh — Android 模拟器生命周期管理（多用户共享）
# 详见 docs/specs/emulator-debug.md。
#
# 用法:
#   ./scripts/emulator.sh status     # 查看运行状态（退出码 0=运行中, 1=未运行, 2=异常）
#   ./scripts/emulator.sh start      # 启动（若已在运行则拒绝，防止抢占他人会话）
#   ./scripts/emulator.sh stop       # 关闭并清理（adb emu kill + 清 lockfile）
#   ./scripts/emulator.sh restart    # stop && start
#   ./scripts/emulator.sh scrcpy start|stop|status
#                                    # LAN scrcpy 镜像：暴露 15555 给局域网内的远端
#                                    # scrcpy 客户端（如 Windows）。opt-in，非默认。
#
# 标准流程（每个用户/会话）:
#   1. status  → 确认「未运行」（=没人在用）
#   2. start   → 启动
#   3. 跑测试 / adb 调试
#   4. (可选) scrcpy start → 开 LAN 镜像给远端查看/协助
#   5. stop    → 用完必关，清理环境（scrcpy 监听随 stop 一并清）
#
# 多用户协调：通过 lockfile（/tmp/ocdroid-emulator.lock，含 PID/启动时间/所有者）
# 标识当前占用者；start 前检查，stop 时清除。模拟器是本机共享资源。

set -euo pipefail
source "$(dirname "$0")/env.sh"

# emulator 二进制不在 env.sh 的 PATH 里，这里补上
export PATH="$PATH:$ANDROID_HOME/emulator"

AVD_NAME="${OCDROID_AVD:-ocdroid}"
LOCKFILE="/tmp/ocdroid-emulator.lock"
EMU_LOG="${EMU_LOG:-/tmp/emulator.log}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"   # 等待开机秒数

# ---- LAN scrcpy 镜像（端口 15555）----
# 把模拟器经 LAN 暴露给远端 scrcpy 客户端（如局域网 Windows）。
# 链路：客户端 adb connect <本机IP>:15555
#       -> socat 0.0.0.0:15555 -> 127.0.0.1:55556
#       -> adb forward -> guest adbd :5555（network adb，与本地 emulator-5554 并存）。
# 客户端用自己的 adb server，故 scrcpy 的 adb reverse 正确指向客户端本机 → 原生可用，
# 且不抢占本地 emulator-5554 传输（adbd 双传输并存）。
SCRCPY_LAN_PORT="${SCRCPY_LAN_PORT:-15555}"   # LAN 监听端口（客户端连这个）
SCRCPY_FWD_PORT="${SCRCPY_FWD_PORT:-55556}"   # 主机内部 forward 端口 -> guest:5555
SCRCPY_ADBD_PORT="${SCRCPY_ADBD_PORT:-5555}"  # guest adbd 网络端口
SCRCPY_LOCK="/tmp/ocdroid-scrcpy-lan.lock"    # socat 主进程 PID

# 模拟器启动参数：headless、无快照（保证干净状态）、软 GPU（无显示器也能跑）
EMU_ARGS=(
  "-no-window" "-no-audio" "-no-boot-anim" "-no-snapshot"
  "-gpu" "swiftshader_indirect"
)

# ---- 辅助函数 ----

# 判断某个 PID 对应的 emulator 进程是否存活
proc_alive() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

# 读取 lockfile 里的 PID（若无/格式错返回空）
lock_pid() {
  [[ -f "$LOCKFILE" ]] || return 0
  sed -n '1s/^\([0-9]\+\).*/\1/p' "$LOCKFILE" 2>/dev/null || true
}

# adb 是否能看到在线设备
adb_has_device() {
  "$ANDROID_HOME/platform-tools/adb" devices 2>/dev/null \
    | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'
}

# ---- 子命令 ----

cmd_status() {
  local pid
  pid="$(lock_pid)"
  if [[ -n "$pid" ]] && proc_alive "$pid"; then
    echo "▶ 模拟器运行中 (pid=$pid)"
    [[ -f "$LOCKFILE" ]] && echo "  $(sed '1d' "$LOCKFILE" 2>/dev/null | tr '\n' ' ')"
    adb_has_device && echo "  设备: $("$ANDROID_HOME/platform-tools/adb" devices | awk 'NR>1 && $2=="device"{print $1}')"
    return 0
  fi
  if [[ -n "$pid" ]] && ! proc_alive "$pid"; then
    echo "⚠ lockfile 存在但进程已退出（残留）— 请先 stop 清理" >&2
    return 2
  fi
  # 无 lockfile，再以防万一直接查 adb（防止脚本外手动启动的情况）
  if adb_has_device; then
    echo "⚠ adb 发现在线设备但无 lockfile（被脚本外方式启动）— 请先 stop 清理" >&2
    return 2
  fi
  echo "○ 模拟器未运行"
  return 1
}

cmd_start() {
  # 1. 占用检查：已在运行则拒绝
  if cmd_status >/dev/null 2>&1; then
    echo "❌ 模拟器已在运行，可能正被他人使用。先用 status 查看，或 stop 后重启。" >&2
    cmd_status || true
    exit 1
  fi
  # 残留 lockfile / 异常状态也拒绝，提示先 stop
  if cmd_status 2>/dev/null; then :; else rc=$?; if [[ $rc -eq 2 ]]; then exit 1; fi; fi

  # 2. 前置检查
  local emu_bin="$ANDROID_HOME/emulator/emulator"
  [[ -x "$emu_bin" ]] || { echo "❌ 未安装 emulator：sdkmanager \"emulator\"" >&2; exit 1; }
  local avd_dir="$HOME/.android/avd/${AVD_NAME}.avd"
  [[ -d "$avd_dir" ]] || { echo "❌ AVD '$AVD_NAME' 不存在。创建: avdmanager create avd -n $AVD_NAME -k 'system-images;android-35;google_apis;x86_64'" >&2; exit 1; }

  # 3. KVM 访问：当前会话可能未含 kvm 组（usermod 后未重新登录），
  #    直接访问失败则用 `sg kvm` 包一层启动。
  local launch=("$emu_bin" "@$AVD_NAME" "${EMU_ARGS[@]}")
  if [[ ! -r /dev/kvm ]] || [[ ! -w /dev/kvm ]]; then
    echo "ℹ 当前会话无 /dev/kvm 读写权限，改用 'sg kvm' 启动"
    launch=(sg kvm -c "$(printf '%q ' "${launch[@]}")")
  fi

  # 4. 后台启动
  : > "$EMU_LOG"
  nohup "${launch[@]}" >> "$EMU_LOG" 2>&1 &
  local pid=$!

  # 写 lockfile（首行 PID，其余为元信息）
  {
    echo "$pid"
    echo "avd=$AVD_NAME started=$(date '+%Y-%m-%dT%H:%M:%S') user=${USER:-$(id -un)} log=$EMU_LOG"
  } > "$LOCKFILE"

  echo "▶ 启动中 (pid=$pid)，等待开机完成 (timeout=${BOOT_TIMEOUT}s) ..."

  # 5. 等待 adb 上线 + sys.boot_completed=1
  local adb="$ANDROID_HOME/platform-tools/adb"
  "$adb" wait-for-device 2>/dev/null || true
  local i
  for ((i=0; i<BOOT_TIMEOUT; i+=5)); do
    if [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      echo "✅ 开机完成 (${i}s)，设备: $("$adb" devices | awk 'NR>1 && $2=="device"{print $1}')"
      return 0
    fi
    proc_alive "$pid" || { echo "❌ 模拟器进程已退出，见 $EMU_LOG" >&2; tail -20 "$EMU_LOG" >&2; rm -f "$LOCKFILE"; exit 1; }
    sleep 5
  done
  echo "❌ 开机超时（${BOOT_TIMEOUT}s），见 $EMU_LOG" >&2
  rm -f "$LOCKFILE"
  exit 1
}

cmd_stop() {
  local pid adb
  adb="$ANDROID_HOME/platform-tools/adb"
  pid="$(lock_pid)"

  if [[ -n "$pid" ]] && proc_alive "$pid"; then
    echo "▶ 停止模拟器 (pid=$pid)"
    # 优先优雅关闭
    "$adb" emu kill 2>/dev/null || true
    for i in $(seq 1 20); do
      proc_alive "$pid" || break
      sleep 1
    done
    # 仍未退出则强杀
    if proc_alive "$pid"; then
      echo "⚠ emu kill 未生效，强杀"
      kill "$pid" 2>/dev/null || true
      sleep 2
      proc_alive "$pid" && kill -9 "$pid" 2>/dev/null || true
    fi
  elif adb_has_device; then
    echo "▶ 无 lockfile 但 adb 有设备，尝试 adb emu kill"
    "$adb" emu kill 2>/dev/null || true
    sleep 3
  else
    echo "○ 模拟器未运行"
  fi

  # 清理：kill 残留 qemu/emulator 子进程 + adb server（避免端口占用残留）
  pkill -f "qemu-system-x86_64.*$AVD_NAME" 2>/dev/null || true
  # scrcpy-lan 一并清理（避免模拟器关了却仍暴露 15555）
  local spid; spid="$(scrcpy_lan_pid)"
  [[ -n "$spid" ]] && kill "$spid" 2>/dev/null || true
  "$adb" forward --remove "tcp:${SCRCPY_FWD_PORT}" 2>/dev/null || true
  rm -f "$SCRCPY_LOCK"
  rm -f "$LOCKFILE"
  "$adb" kill-server 2>/dev/null || true
  echo "✅ 已清理"
}

cmd_restart() {
  cmd_stop
  sleep 2
  cmd_start
}

# ---- LAN scrcpy 镜像辅助 ----

# 本机非 loopback 的 IPv4（供客户端连接）
lan_ips() {
  ip -4 addr show 2>/dev/null \
    | grep -oP 'inet \K[0-9.]+' | grep -v '^127\.' || true
}

# 15555 是否在监听
scrcpy_lan_listening() {
  ss -ltn 2>/dev/null | grep -q ":${SCRCPY_LAN_PORT}\b"
}

# 读取 socat 主进程 PID（lockfile 优先并校验存活，回退按端口查）
scrcpy_lan_pid() {
  local p=""
  if [[ -f "$SCRCPY_LOCK" ]]; then
    p="$(head -1 "$SCRCPY_LOCK" 2>/dev/null || true)"
    if [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null; then echo "$p"; return 0; fi
  fi
  ss -ltnp 2>/dev/null | grep ":${SCRCPY_LAN_PORT}" | grep -oP 'pid=\K[0-9]+' | head -1 || true
}

cmd_scrcpy() {
  local act="${1:-status}"
  local adb="$ANDROID_HOME/platform-tools/adb"
  case "$act" in
    start)
      adb_has_device || { echo "❌ 模拟器未运行，先 ./scripts/emulator.sh start" >&2; exit 1; }
      command -v socat >/dev/null 2>&1 || { echo "❌ 未安装 socat：sudo apt install socat" >&2; exit 1; }
      if scrcpy_lan_listening; then
        echo "○ ${SCRCPY_LAN_PORT} 已在监听（socat pid=$(scrcpy_lan_pid)）"
      else
        echo "▶ 开启 guest network adbd (:${SCRCPY_ADBD_PORT}) ..."
        "$adb" tcpip "$SCRCPY_ADBD_PORT" >/dev/null 2>&1 || true
        sleep 2
        echo "▶ 建立主机 forward ${SCRCPY_FWD_PORT} -> guest:${SCRCPY_ADBD_PORT} ..."
        "$adb" forward "tcp:${SCRCPY_FWD_PORT}" "tcp:${SCRCPY_ADBD_PORT}" >/dev/null
        echo "▶ 启动 LAN 监听 0.0.0.0:${SCRCPY_LAN_PORT} -> 127.0.0.1:${SCRCPY_FWD_PORT} ..."
        nohup socat "TCP-LISTEN:${SCRCPY_LAN_PORT},bind=0.0.0.0,reuseaddr,fork" \
          "TCP:127.0.0.1:${SCRCPY_FWD_PORT}" >/dev/null 2>&1 &
        local spid=$!
        disown 2>/dev/null || true
        echo "$spid" > "$SCRCPY_LOCK"
        sleep 1
        scrcpy_lan_listening || { echo "❌ socat 启动失败（端口 ${SCRCPY_LAN_PORT} 被占？）" >&2; exit 1; }
        echo "✅ 已监听 0.0.0.0:${SCRCPY_LAN_PORT} (socat pid=$spid)"
      fi
      echo ""
      echo "客户端（局域网内装了 scrcpy 的机器）执行："
      local ip
      for ip in $(lan_ips); do
        echo "  adb connect ${ip}:${SCRCPY_LAN_PORT} && scrcpy -s ${ip}:${SCRCPY_LAN_PORT}"
      done
      echo ""
      echo "⚠ ${SCRCPY_LAN_PORT} 对整个局域网开放控制权；用完务必 ./scripts/emulator.sh scrcpy stop"
      ;;
    stop)
      local pid; pid="$(scrcpy_lan_pid)"
      if [[ -n "$pid" ]]; then
        kill "$pid" 2>/dev/null || true
        echo "▶ 已停 socat pid=$pid"
      else
        echo "○ ${SCRCPY_LAN_PORT} 未在监听"
      fi
      "$adb" forward --remove "tcp:${SCRCPY_FWD_PORT}" 2>/dev/null || true
      rm -f "$SCRCPY_LOCK"
      echo "✅ scrcpy-lan 已关闭（forward 已清；guest tcpip 随模拟器关闭自动复位）"
      ;;
    status)
      if scrcpy_lan_listening; then
        echo "▶ scrcpy-lan 运行中：0.0.0.0:${SCRCPY_LAN_PORT} (socat pid=$(scrcpy_lan_pid))"
        echo "  客户端：adb connect <本机IP>:${SCRCPY_LAN_PORT} && scrcpy -s <本机IP>:${SCRCPY_LAN_PORT}"
        lan_ips | sed 's/^/  本机IP: /'
      else
        echo "○ scrcpy-lan 未开启（./scripts/emulator.sh scrcpy start）"
      fi
      ;;
    *)
      echo "用法: emulator.sh scrcpy {start|stop|status}" >&2
      exit 64
      ;;
  esac
}

# ---- 入口 ----

case "${1:-}" in
  status)  cmd_status ;;
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  scrcpy)  cmd_scrcpy "${2:-status}" ;;
  *)
    echo "用法: emulator.sh {status|start|stop|restart|scrcpy start|stop|status}" >&2
    exit 64 ;;
esac
