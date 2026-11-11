#!/data/data/com.termux/files/usr/bin/bash
# CabinEQ + Spotify Soloist, run from Termux.
#
#   bash cabineq-soloist.sh setup    install Debian, PulseAudio, socat and Soloist (safe to re-run)
#   bash cabineq-soloist.sh key      store (or replace) the Soloist API key
#   bash cabineq-soloist.sh pair     pair Soloist with your Spotify account (on Wi-Fi)
#   bash cabineq-soloist.sh start    run it in the background, restarted if it dies
#   bash cabineq-soloist.sh stop     stop everything
#   bash cabineq-soloist.sh status   what is running
#
# One clock: CabinEQ's. Everything runs in ONE proot session:
#
#   Soloist -> PulseAudio (Debian) "CabinEQ" pipe sink -> FIFO -> socat -> 127.0.0.1:4712 -> CabinEQ
#
# A pipe sink has no clock of its own: it renders only as fast as the FIFO is drained, socat
# drains it only as fast as the app reads the socket, and the app reads only as fast as its
# AudioTrack plays. So Soloist is paced by the phone's audio output itself: no drift, no trims,
# no rate correction, and no Termux PulseAudio / AAudio sink / tunnel in between.
#
# When the session dies (PulseAudio, socat or Soloist), the whole session is replaced: the pieces
# share one lifetime, so there is never a stray Soloist advertising a second CabinEQ device.
#
# Logs: /sdcard/Download/cabineq-soloist.log (Termux side and the session's output).
set -uo pipefail

MODE=${1:-status}
PORT=${CABINEQ_PORT:-4712}
DEVICE=CabinEQ
KEYFILE="$HOME/.soloist-key"
LOG=/sdcard/Download/cabineq-soloist.log
PIDFILE="$HOME/.cabineq-soloist.pid"
ROOTFS=""
for c in "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" \
         "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
  [ -d "$c/etc" ] && { ROOTFS="$c"; break; }
done
say() { printf '%s [cabineq] %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }

case "$MODE" in setup|key|pair|start|stop|status|__supervise) ;; *)
  echo "usage: bash $0 setup|key|pair|start|stop|status"; exit 2 ;;
esac

# Every piece by name, from the Termux side (proot's processes are ordinary processes here).
# Includes the previous design's Termux PulseAudio, whose AAudio sink is what hung.
kill_all() {
  pkill -f "cabineq-soloist.sh __supervise" 2>/dev/null
  pkill -x soloist 2>/dev/null
  pkill -x socat 2>/dev/null
  pkill -x parec 2>/dev/null
  pkill -f "cabineq-session.sh" 2>/dev/null
  pkill -x pulseaudio 2>/dev/null
  pkill -f "soloist-supervisor.sh" 2>/dev/null
  rm -f "$PIDFILE"
}

supervised() { [ -s "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

status() {
  echo "supervisor: $(supervised && echo "running (pid $(cat "$PIDFILE"))" || echo stopped)"
  echo "soloist:    $(pgrep -x soloist >/dev/null && echo running || echo stopped)"
  echo "pulseaudio: $(pgrep -x pulseaudio >/dev/null && echo running || echo stopped)"
  echo "pcm server: $(pgrep -f cabineq-pcm-server >/dev/null && echo "serving on 127.0.0.1:$PORT" || echo stopped)"
  echo "key:        $([ -s "$KEYFILE" ] && echo stored || echo missing)"
  echo "log:        $LOG (last lines below)"
  tail -n 8 "$LOG" 2>/dev/null
}

case "$MODE" in
  stop)   kill_all; command -v termux-wake-unlock >/dev/null && termux-wake-unlock; say "stopped"; exit 0 ;;
  status) status; exit 0 ;;
esac

# ---- Termux side: prerequisites -------------------------------------------------------------

if [ ! -d "$HOME/storage" ]; then
  echo "Allow storage access in the prompt (the log goes to Downloads)."
  termux-setup-storage || true
  sleep 3
fi
command -v proot-distro >/dev/null || { say "installing proot-distro"; pkg install -y proot-distro; }
if [ -z "$ROOTFS" ]; then
  say "installing Debian (a few minutes, once)"
  proot-distro install debian
  ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/debian"
  [ -d "$ROOTFS/etc" ] || ROOTFS="$PREFIX/var/lib/proot-distro/containers/debian/rootfs"
fi
[ -d "$ROOTFS/etc" ] || { say "Debian rootfs not found"; exit 1; }

if [ "$MODE" = key ] || { [ "$MODE" != __supervise ] && [ ! -s "$KEYFILE" ]; }; then
  read -rsp "Paste your Soloist API key (it won't be shown): " k; echo
  [ -n "$k" ] || { say "no key given"; exit 1; }
  ( umask 077; printf '%s' "$k" > "$KEYFILE" )
  say "key saved"
  [ "$MODE" = key ] && exit 0
fi

mkdir -p "$ROOTFS/root/.soloist"
install -m 600 "$KEYFILE" "$ROOTFS/root/.soloist/key"
# Keep a pairing made with the earlier scripts.
OLD="$ROOTFS/root/.local/share/soloist/soloist-data"
if [ ! -d "$ROOTFS/root/.soloist/data" ] && [ -d "$OLD" ]; then
  cp -r "$OLD" "$ROOTFS/root/.soloist/data"; say "reusing the earlier pairing"
fi

# ---- Debian side: written fresh each run ------------------------------------------------------

PCM_SERVER="$(cd "$(dirname "$0")" && pwd)/cabineq-pcm-server.py"
[ -s "$PCM_SERVER" ] || { say "missing $PCM_SERVER (push it next to this script)"; exit 1; }
install -m 755 "$PCM_SERVER" "$ROOTFS/root/cabineq-pcm-server.py"

cat > "$ROOTFS/root/cabineq-session.sh" <<'SESSION'
#!/bin/bash
# One session: PulseAudio + socat + Soloist, sharing one lifetime.
set -uo pipefail
MODE=$1; PORT=$2; DEVICE=$3
BASE=/root/.soloist
BIN=$BASE/bin/soloist
DATA=$BASE/data
RUN=/tmp/cabineq-runtime
FIFO=$RUN/cabineq.fifo
export XDG_RUNTIME_DIR=$RUN
export PULSE_SERVER=unix:$RUN/pulse/native
say() { echo "$(date '+%H:%M:%S') [debian] $*"; }

install_deps() {
  local need=""
  for p in curl ca-certificates libatomic1 pulseaudio pulseaudio-utils python3-minimal; do
    dpkg -s "$p" >/dev/null 2>&1 || need="$need $p"
  done
  if [ -n "$need" ]; then
    say "installing:$need"
    apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $need >/dev/null || return 1
  fi
}

fetch_soloist() {
  # An expired build exits with 10 (also on --version, presumably): download a fresh one.
  if [ -x "$BIN" ] && "$BIN" --version >/dev/null 2>&1; then return 0; fi
  say "downloading Soloist from Spotify"
  local tmp; tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/s.tgz" https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz \
    || { say "download failed"; rm -rf "$tmp"; return 1; }
  tar xzf "$tmp/s.tgz" -C "$tmp"
  local f; f=$(find "$tmp" -type f -name soloist | head -1)
  [ -n "$f" ] || { say "no soloist binary in the archive"; rm -rf "$tmp"; return 1; }
  mkdir -p "$BASE/bin" && install -m 755 "$f" "$BIN" && rm -rf "$tmp"
  say "Soloist $("$BIN" --version 2>&1 | head -1)"
}

# Without process substitution: proot has no /dev/fd/63, so `mapfile -t K < <(key_args)` left the
# array empty and Soloist exited 1 ("api key required").
key_args() {
  K=()
  [ -s "$BASE/key" ] && K=(-k "$(cat "$BASE/key")")
}

# Soloist leaves its session marker behind when it is killed, and then refuses to start
# ("another session is running for data directory"): only stale ones get here.
clear_stale() { rm -f "$DATA/soloist.pid" "$DATA/.lock" "$DATA/ws.addr" "$DATA/ws.port" 2>/dev/null; }

children=()
cleanup() {
  for p in "${children[@]}"; do kill "$p" 2>/dev/null; done
  pkill -x socat 2>/dev/null
  pkill -f cabineq-pcm-server 2>/dev/null
  # A wedged PulseAudio may not act on SIGTERM: give it a second, then force it.
  pkill -x pulseaudio 2>/dev/null; sleep 1; pkill -9 -x pulseaudio 2>/dev/null
}

# Is the server answering, not just present: a PulseAudio whose main loop is wedged still shows up
# in the process list, but refuses every client.
pulse_ok() { timeout 3 pactl info >/dev/null 2>&1; }

pulse_log_tail() { echo "--- pulseaudio log (last 15 lines)"; tail -n 15 /tmp/cabineq-pulse.log 2>/dev/null; echo "---"; }

# PulseAudio as proot can run it. Under proot, shared memory (posix shm and memfd) between server
# and clients breaks in ways that wedge the server, realtime scheduling is refused (and a missed
# RT deadline is answered with SIGXCPU), and a client that finds no server autospawns a second
# daemon with the wrong config. All of that off; every client goes to the one socket.
write_pulse_conf() {
  mkdir -p /root/.config/pulse
  rm -f /root/.config/pulse/default.pa     # earlier scripts' tunnel config: never auto-loaded again
  cat > /root/.config/pulse/daemon.conf <<CONF
exit-idle-time = -1
enable-shm = no
enable-memfd = no
realtime-scheduling = no
high-priority = no
rlimit-rttime = -1
flat-volumes = no
default-sample-format = s16le
default-sample-rate = 44100
avoid-resampling = yes
CONF
  cat > /root/.config/pulse/client.conf <<CONF
autospawn = no
enable-shm = no
enable-memfd = no
default-server = unix:$RUN/pulse/native
CONF
  # The only sink: a pipe, paced by whoever drains it. No suspend-on-idle (the module is not
  # loaded), so a paused Spotify still yields a steady stream of silence at the reader's pace.
  cat > /root/.config/pulse/cabineq.pa <<CONF
load-module module-native-protocol-unix socket=$RUN/pulse/native auth-anonymous=1
load-module module-pipe-sink file=$FIFO sink_name=CabinEQ format=s16le rate=44100 channels=2 use_system_clock_for_timing=no
set-default-sink CabinEQ
CONF
}

case "$MODE" in
  setup)
    install_deps && fetch_soloist && say "setup done"
    ;;

  pair)
    install_deps && fetch_soloist || exit 1
    clear_stale
    key_args
    say "on Wi-Fi: open Spotify, play anything, tap the devices icon and pick '$DEVICE'"
    "$BIN" --pair -n "$DEVICE" "${K[@]}" -D "$DATA" 2>&1 | grep -E 'pairing|error|Error| E ' --line-buffered
    ;;

  run)
    install_deps && fetch_soloist || exit 1
    trap cleanup EXIT
    pkill -x pipewire 2>/dev/null; pkill -x wireplumber 2>/dev/null
    pkill -x pulseaudio 2>/dev/null; pkill -x socat 2>/dev/null; pkill -f cabineq-pcm-server 2>/dev/null; sleep 1
    mkdir -p "$RUN/pulse" && chmod 700 "$RUN"
    rm -rf "$RUN/pulse"/* "$FIFO"
    write_pulse_conf

    # Not daemonised (it lives and dies with this session), no default script (-n), only ours.
    pulseaudio --daemonize=no --use-pid-file=no --system=no -n -F /root/.config/pulse/cabineq.pa \
      --exit-idle-time=-1 --disable-shm=yes --realtime=no --high-priority=no \
      --log-target=stderr --log-time=yes --log-level=info >/tmp/cabineq-pulse.log 2>&1 &
    children+=($!)
    up=""
    for _ in $(seq 1 40); do pactl list short sinks 2>/dev/null | grep -q CabinEQ && { up=1; break; }; sleep 0.5; done
    if [ -z "$up" ]; then
      say "PulseAudio did not come up"; pulse_log_tail; exit 1
    fi
    pactl set-sink-volume CabinEQ 100% 2>/dev/null
    say "pipe sink ready ($FIFO)"

    # One process owns the FIFO for the session's life. A reader per client (socat OPEN:FIFO) has
    # the pipe with no reader the moment the app is not connected — PA blocks, Soloist stalls and
    # Spotify drops the device (heard as "works until the second song"); with fork, two overlapping
    # clients split the stream, and each reconnect can start mid-frame (channels swapped). The
    # server drains at real time when nobody listens, blocks when a client is slow (that is the
    # pacing), and never reopens the FIFO.
    python3 /root/cabineq-pcm-server.py "$FIFO" "$PORT" >/tmp/cabineq-pcm.log 2>&1 &
    pcm_pid=$!
    children+=($pcm_pid)
    up=""
    for _ in $(seq 1 20); do kill -0 "$pcm_pid" 2>/dev/null && { up=1; break; }; sleep 0.3; done
    [ -n "$up" ] && say "serving PCM on 127.0.0.1:$PORT" || { say "PCM server failed"; tail -5 /tmp/cabineq-pcm.log; exit 1; }

    clear_stale
    key_args
    say "starting Soloist as '$DEVICE'"
    "$BIN" -n "$DEVICE" "${K[@]}" -D "$DATA" -i 100 >/tmp/cabineq-soloist.log 2>&1 &
    sp=$!
    children+=($sp)

    # The session lives exactly as long as all three pieces do — and PulseAudio counts as alive
    # only while it answers. Two missed answers in a row (10 s) and the session is replaced.
    misses=0
    while true; do
      sleep 5
      if pulse_ok; then misses=0; else
        misses=$((misses + 1))
        if [ $misses -ge 2 ]; then
          say "PulseAudio stopped answering (process $(pgrep -x pulseaudio >/dev/null && echo present || echo gone))"
          pulse_log_tail; exit 1
        fi
      fi
      kill -0 "$pcm_pid" 2>/dev/null || { say "the PCM server exited"; tail -3 /tmp/cabineq-pcm.log; exit 1; }
      if ! kill -0 "$sp" 2>/dev/null; then
        wait "$sp"; code=$?
        if [ "$code" = 10 ]; then
          say "Soloist build expired: downloading a new one"; rm -f "$BIN"; fetch_soloist
        elif grep -q 'pairing mode' /tmp/cabineq-soloist.log; then
          say "Soloist is not paired: run 'bash cabineq-soloist.sh pair'"; sleep 60
        else
          say "Soloist exited ($code):"; grep -E ' E | W ' /tmp/cabineq-soloist.log | tail -5
        fi
        exit 1
      fi
    done
    ;;
esac
SESSION
chmod 755 "$ROOTFS/root/cabineq-session.sh"

# ---- run --------------------------------------------------------------------------------------

session() { proot-distro login debian -- bash /root/cabineq-session.sh "$1" "$PORT" "$DEVICE"; }

case "$MODE" in
  setup) session setup ;;
  pair)  kill_all; session pair; say "paired. Next: bash $0 start" ;;
  start)
    kill_all
    command -v termux-wake-lock >/dev/null && termux-wake-lock
    setsid nohup bash "$0" __supervise >>"$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    say "started (supervisor pid $!). In Spotify, pick '$DEVICE' and press play; then switch on"
    say "'Spotify via Soloist' in CabinEQ. Watch: tail -f $LOG"
    ;;
  __supervise)
    say "supervisor up"
    fails=0
    while true; do
      t0=$(date +%s)
      session run
      # A session that dies within a minute, repeatedly, is broken, not unlucky: back off.
      if [ $(( $(date +%s) - t0 )) -lt 60 ]; then fails=$((fails + 1)); else fails=0; fi
      wait_s=$(( fails < 5 ? 3 : 30 ))
      say "session ended; restarting in ${wait_s}s"
      pkill -x soloist 2>/dev/null; pkill -x socat 2>/dev/null; pkill -9 -x pulseaudio 2>/dev/null
      sleep "$wait_s"
    done
    ;;
esac
