#!/data/data/com.termux/files/usr/bin/bash
# CabinEQ + Soloist, single supervisor.
#
# One process (this one) owns the whole chain, and it lives in Termux rather than inside proot:
# proot sessions die with their login shell, so a supervisor there could never restart anything.
#
#   Termux PulseAudio (native; clocked by the AAudio sink, which is muted — CabinEQ makes sound)
#     ^  tunnel sink (clock source)
#   Debian PulseAudio (in proot)  <-  Soloist (one proot session, restarted on its own)
#     ^ monitor
#   parec (proot, one per app connection, spawned by socat)  ->  127.0.0.1:PORT  ->  CabinEQ
#
# Soloist cannot talk to Termux's PulseAudio directly: that server accepts playback but rejects
# recording clients ("Failed to set media name" / "Protocol error"), so the local server stays.
#
#   bash soloist-supervisor.sh start     start supervising (detached)
#   bash soloist-supervisor.sh status    what is running
#   bash soloist-supervisor.sh stop      stop everything
#
# Log: /sdcard/Download/soloist-supervisor.log
set -uo pipefail

PORT=${CABINEQ_PORT:-4712}
DEVICE=CabinEQ
PREFIX_BIN="$PREFIX/bin"
LOG=/sdcard/Download/soloist-supervisor.log
PIDFILE="$HOME/.soloist-supervisor.pid"
KEYFILE="$HOME/.soloist-key"
export PULSE_RUNTIME_DIR="${PULSE_RUNTIME_PATH:-$PREFIX/var/run/pulse}"
export PULSE_RUNTIME_PATH="$PULSE_RUNTIME_DIR"
SOCK="$PULSE_RUNTIME_PATH/proot"
AA=AAudio_sink

ROOTFS=""
for c in "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" \
         "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
  [ -d "$c/etc" ] && ROOTFS="$c" && break
done
SOLOIST=/root/.soloist/bin/soloist
SOLOIST_DATA=/root/.soloist/data

say() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >> "$LOG"; }

pa_up()      { timeout 2 pactl info >/dev/null 2>&1; }
soloist_pid() { cat "$HOME/.soloist.pid" 2>/dev/null; }
soloist_up() {
  local p; p=$(soloist_pid) || return 1
  [ -n "$p" ] && kill -0 "$p" 2>/dev/null
}
bridge_up()  { pgrep -f "socat TCP-LISTEN:$PORT" >/dev/null 2>&1; }

start_pa() {
  if ! pa_up; then
    say "starting Termux PulseAudio"
    # A stale socket from a killed daemon makes --start think a server is already there.
    rm -f "$PULSE_RUNTIME_PATH/native" "$PULSE_RUNTIME_PATH/proot" 2>/dev/null
    # Not `--start`: it waits for a daemon that cannot bind and never returns (measured). Started
    # directly, it either runs or exits, and the loop below tells us which.
    setsid nohup pulseaudio --exit-idle-time=-1 --log-target=stderr \
      >> /sdcard/Download/soloist-pulse.log 2>&1 &
    for _ in $(seq 1 30); do pa_up && break; sleep 0.5; done
  fi
  pa_up || { say "pulseaudio will not start"; return 1; }
  # AAudio is the clock; it is muted because the sound comes from CabinEQ. A null sink would
  # swallow audio as fast as it arrives and Soloist would never be paced.
  timeout 3 pactl list short modules | grep -q module-aaudio-sink || {
    say "loading AAudio sink"; timeout 8 pactl load-module module-aaudio-sink >/dev/null 2>&1; }
  timeout 3 pactl set-default-sink "$AA" >/dev/null 2>&1
  timeout 3 pactl set-sink-volume "$AA" 0 >/dev/null 2>&1
  timeout 3 pactl set-sink-mute "$AA" 0 >/dev/null 2>&1
  # Anonymous socket for the proot side (its own cookie cannot read Termux's).
  if ! timeout 3 pactl list short modules | grep -q "socket=$SOCK"; then
    say "opening proot socket"
    timeout 8 pactl load-module module-native-protocol-unix socket="$SOCK" auth-anonymous=1 >/dev/null 2>&1
  fi
  [ -S "$SOCK" ] || { say "proot socket missing"; return 1; }
  return 0
}

# The proot session that holds the Debian PulseAudio and Soloist: two pieces with one lifetime.
write_session() {
  local key="$1"
  mkdir -p "$ROOTFS/root"
  cat > "$ROOTFS/root/cabineq-session.sh" <<INNER
#!/bin/bash
export XDG_RUNTIME_DIR=/tmp/cabineq-runtime
export PULSE_SERVER="unix:/tmp/cabineq-runtime/pulse/native"
mkdir -p "\$XDG_RUNTIME_DIR/pulse"; chmod 700 "\$XDG_RUNTIME_DIR"
rm -f "\$XDG_RUNTIME_DIR/pulse/native"
cat > /root/.config/pulse/default.pa <<'EOF2'
load-module module-native-protocol-unix socket=/tmp/cabineq-runtime/pulse/native auth-anonymous=1
load-module module-tunnel-sink server=unix:/data/data/com.termux/files/usr/var/run/pulse/native sink_name=CabinEQ
EOF2
pulseaudio --exit-idle-time=-1 --log-target=stderr >/tmp/cabineq-pulse.log 2>&1 &
for i in \$(seq 1 40); do pactl info >/dev/null 2>&1 && break; sleep 0.5; done
pactl list short sinks | grep -q CabinEQ || { echo "[session] CabinEQ sink missing"; exit 1; }
pactl set-default-sink CabinEQ
echo "[session] audio ready"
exec $SOLOIST -n "$DEVICE" ${key:+-k "$key"} -D "$SOLOIST_DATA" -i 100
INNER
  chmod 755 "$ROOTFS/root/cabineq-session.sh"
}

start_soloist() {
  [ -d "$ROOTFS" ] || { say "no Debian rootfs"; return 1; }
  local key=""; [ -s "$KEYFILE" ] && key=$(cat "$KEYFILE")
  # Soloist refuses to start while its data dir holds a session marker ("another session is
  # running for data directory ..."), and it does not clean that up when it is killed. That is
  # why the Spotify device vanishes after a restart and never comes back. We only get here when
  # no Soloist is alive, so the marker is stale by definition.
  pkill -x soloist 2>/dev/null; sleep 1
  rm -f "$ROOTFS/root/.soloist/data/soloist.pid" "$ROOTFS/root/.soloist/data/.lock" 2>/dev/null
  say "starting audio session + Soloist"
  write_session "$key"
  # One proot session owns the Debian PulseAudio and Soloist; Soloist in the foreground means the
  # session lives exactly as long as the Connect endpoint does.
  setsid nohup proot-distro login debian -- bash /root/cabineq-session.sh \
      >> /sdcard/Download/soloist.log 2>&1 &
  echo $! > "$HOME/.soloist.pid"
  sleep 4
  soloist_up || { say "session exited (see /sdcard/Download/soloist.log)"; return 1; }
  return 0
}

start_bridge() {
  say "starting PCM bridge on 127.0.0.1:$PORT"
  # A fresh parec per connection: a standing reader with no client accumulates a backlog, and
  # then the app starts minutes behind. It reads the Debian server's tunnel sink monitor.
  setsid nohup socat TCP-LISTEN:"$PORT",reuseaddr,fork \
    EXEC:"proot-distro login debian -- /usr/bin/parec -d CabinEQ.monitor --format=s16le --rate=44100 --channels=2 --raw",nofork \
    >/dev/null 2>&1 &
  sleep 2
  bridge_up || say "socat did not start"
}

supervise() {
  say "supervisor up (pid $$), port $PORT"
  trap 'say "supervisor stopping"; stop_all; exit 0' INT TERM
  while true; do
    pa_up || start_pa
    [ -S "$SOCK" ] || start_pa
    soloist_up || start_soloist
    bridge_up || start_bridge
    sleep 3
  done
}

stop_all() {
  say "stopping"
  pkill -x soloist 2>/dev/null
  pkill -f "proot-distro login" 2>/dev/null
  pkill -f "socat TCP-LISTEN:$PORT" 2>/dev/null
  pkill -x parec 2>/dev/null
  pulseaudio --kill 2>/dev/null
  rm -f "$HOME/.soloist.pid" "$PIDFILE"
}

status() {
  echo "pulseaudio: $(pa_up && echo up || echo down)"
  echo "aaudio sink: $(timeout 2 pactl list short sinks 2>/dev/null | awk '$2 ~ /AAudio/ {print $2}')"
  echo "proot socket: $([ -S "$SOCK" ] && echo ready || echo missing)"
  echo "soloist: $(soloist_up && echo "running (pid $(soloist_pid))" || echo down)"
  echo "bridge: $(bridge_up && echo "listening on $PORT" || echo down)"
  echo "supervisor: $([ -s "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null && echo "running (pid $(cat "$PIDFILE"))" || echo down)"
  echo "log: $LOG"
}

case "${1:-status}" in
  start)
    if [ -s "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "already supervising (pid $(cat "$PIDFILE"))"; exit 0
    fi
    setsid nohup bash "$0" __run >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    sleep 5
    status
    ;;
  __run) supervise ;;
  stop)  stop_all ;;
  status) status ;;
  *) echo "usage: bash $0 start|stop|status"; exit 2 ;;
esac
