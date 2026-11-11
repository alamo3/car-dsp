#!/data/data/com.termux/files/usr/bin/bash
# CabinEQ + Spotify Soloist, run from Termux.
#
#   bash cabineq-soloist.sh setup    install what is needed, in Termux and in Debian (safe to re-run)
#   bash cabineq-soloist.sh key      store (or replace) the Soloist API key
#   bash cabineq-soloist.sh pair     pair Soloist with your Spotify account (on Wi-Fi)
#   bash cabineq-soloist.sh start    run it in the background; each piece is restarted if it dies
#   bash cabineq-soloist.sh stop     stop everything
#   bash cabineq-soloist.sh status   what is running
#
#   [proot: Debian]                  [Termux: native Android processes]
#   Soloist  --libpulse, unix socket-->  PulseAudio  "CabinEQ" pipe sink --> FIFO
#                                        cabineq-pcm-server.py  FIFO --> 127.0.0.1:4712 --> CabinEQ
#
# Only Soloist runs under proot, because it has to (it is a glibc program). PulseAudio and the PCM
# server run natively: under proot every system call is intercepted, and PulseAudio — which lives
# on poll, futexes and timers — missed its own deadlines there and wedged ("process present,
# connection refused"). Termux's PulseAudio is an Android build; what hung it earlier was its
# AAudio output module, which is not loaded here: the only sink is the pipe, so no Android audio
# device is involved at all.
#
# One clock: CabinEQ's. The pipe sink renders only as fast as the FIFO is drained; the PCM server
# drains it at real time when nobody is connected and with blocking sends when the app is, so
# Soloist is paced by the phone's AudioTrack (see cabineq-pcm-server.py and SoloistInput.kt).
#
# Logs: /sdcard/Download/cabineq-soloist.log (supervisor); PulseAudio, the PCM server and
# Soloist each keep their own log in $PREFIX/var/run/cabineq/ (tail them with `status`).
set -uo pipefail

MODE=${1:-status}
PORT=${CABINEQ_PORT:-4712}
DEVICE=CabinEQ
KEYFILE="$HOME/.soloist-key"
LOG=/sdcard/Download/cabineq-soloist.log
PIDFILE="$HOME/.cabineq-soloist.pid"
RUN="$PREFIX/var/run/cabineq"
SOCK="$RUN/pulse.sock"
FIFO="$RUN/cabineq.fifo"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOTFS=""
for c in "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" \
         "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
  [ -d "$c/etc" ] && { ROOTFS="$c"; break; }
done
say() { printf '%s [cabineq] %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }

case "$MODE" in setup|key|pair|start|stop|status|__supervise) ;; *)
  echo "usage: bash $0 setup|key|pair|start|stop|status"; exit 2 ;;
esac

# Every client of this PulseAudio (pactl here, Soloist in proot) goes to our socket, never
# autospawns a server, and never uses shared memory (not across the proot boundary).
export PULSE_SERVER="unix:$SOCK"
mkdir -p "$RUN" "$HOME/.config/pulse"
cat > "$HOME/.config/pulse/client.conf" <<CONF
autospawn = no
enable-shm = no
enable-memfd = no
default-server = unix:$SOCK
CONF

pulse_ok()  { timeout 3 pactl info >/dev/null 2>&1; }
pcm_up()    { pgrep -f "cabineq-pcm-server.py" >/dev/null 2>&1; }
soloist_up(){ pgrep -x soloist >/dev/null 2>&1; }
supervised(){ [ -s "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

# Everything, by name — including the earlier designs' pieces (Debian's PulseAudio, socat).
kill_all() {
  pkill -f "cabineq-soloist.sh __supervise" 2>/dev/null
  pkill -x soloist 2>/dev/null
  pkill -f "cabineq-pcm-server.py" 2>/dev/null
  pkill -x socat 2>/dev/null
  pkill -f "cabineq-session.sh" 2>/dev/null
  pkill -f "soloist-supervisor.sh" 2>/dev/null
  pkill -x pulseaudio 2>/dev/null; sleep 1; pkill -9 -x pulseaudio 2>/dev/null
  rm -f "$PIDFILE"
}

status() {
  echo "supervisor: $(supervised && echo "running (pid $(cat "$PIDFILE"))" || echo stopped)"
  echo "pulseaudio: $(pulse_ok && echo answering || { pgrep -x pulseaudio >/dev/null && echo "present but NOT answering" || echo stopped; })"
  echo "pcm server: $(pcm_up && echo "serving on 127.0.0.1:$PORT" || echo stopped)"
  echo "soloist:    $(soloist_up && echo running || echo stopped)"
  echo "key:        $([ -s "$KEYFILE" ] && echo stored || echo missing)"
  pulse_ok && { echo "--- streams into the pipe sink"; timeout 3 pactl list short sink-inputs; }
  for f in pulse pcm soloist; do echo "--- $f.log"; tail -n 4 "$RUN/$f.log" 2>/dev/null; done
  echo "--- supervisor ($LOG)"; tail -n 8 "$LOG" 2>/dev/null
}

case "$MODE" in
  stop)   kill_all; command -v termux-wake-unlock >/dev/null && termux-wake-unlock; say "stopped"; exit 0 ;;
  status) status; exit 0 ;;
esac

# ---- prerequisites ----------------------------------------------------------------------------

if [ ! -d "$HOME/storage" ]; then
  echo "Allow storage access in the prompt (the log goes to Downloads)."
  termux-setup-storage || true
  sleep 3
fi
for p in proot-distro:proot-distro pulseaudio:pulseaudio python:python; do
  command -v "${p%%:*}" >/dev/null || { say "installing ${p##*:}"; pkg install -y "${p##*:}" >/dev/null 2>&1; }
done
ls "$PREFIX"/lib/pulse*/modules/module-pipe-sink.so >/dev/null 2>&1 \
  || { say "Termux's PulseAudio has no module-pipe-sink: this setup cannot work as built"; exit 1; }
if [ -z "$ROOTFS" ]; then
  say "installing Debian (a few minutes, once)"
  proot-distro install debian
  for c in "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
    [ -d "$c/etc" ] && { ROOTFS="$c"; break; }
  done
fi
[ -d "$ROOTFS/etc" ] || { say "Debian rootfs not found"; exit 1; }

[ -s "$HERE/cabineq-pcm-server.py" ] || { say "missing $HERE/cabineq-pcm-server.py (keep it next to this script)"; exit 1; }
install -m 755 "$HERE/cabineq-pcm-server.py" "$RUN/cabineq-pcm-server.py"

if [ "$MODE" = key ] || { [ "$MODE" != __supervise ] && [ ! -s "$KEYFILE" ]; }; then
  read -rsp "Paste your Soloist API key (it won't be shown): " k; echo
  [ -n "$k" ] || { say "no key given"; exit 1; }
  ( umask 077; printf '%s' "$k" > "$KEYFILE" )
  say "key saved"
  [ "$MODE" = key ] && exit 0
fi
mkdir -p "$ROOTFS/root/.soloist"
install -m 600 "$KEYFILE" "$ROOTFS/root/.soloist/key"
OLD="$ROOTFS/root/.local/share/soloist/soloist-data"
if [ ! -d "$ROOTFS/root/.soloist/data" ] && [ -d "$OLD" ]; then
  cp -r "$OLD" "$ROOTFS/root/.soloist/data"; say "reusing the earlier pairing"
fi

# ---- Debian side: Soloist only ----------------------------------------------------------------

cat > "$ROOTFS/root/cabineq-soloist-run.sh" <<SOLO
#!/bin/bash
set -uo pipefail
MODE=\$1
BASE=/root/.soloist
BIN=\$BASE/bin/soloist
DATA=\$BASE/data
say() { echo "\$(date '+%H:%M:%S') [debian] \$*"; }
# Termux's PulseAudio, across the proot boundary. No shared memory, no autospawn.
export PULSE_SERVER=unix:$SOCK
mkdir -p /root/.config/pulse
printf 'autospawn = no\nenable-shm = no\nenable-memfd = no\ndefault-server = unix:$SOCK\n' > /root/.config/pulse/client.conf
rm -f /root/.config/pulse/default.pa /root/.config/pulse/cabineq.pa   # earlier designs' servers

install_deps() {
  local need=""
  for p in curl ca-certificates libatomic1 libpulse0 pulseaudio-utils; do
    dpkg -s "\$p" >/dev/null 2>&1 || need="\$need \$p"
  done
  if [ -n "\$need" ]; then
    say "installing:\$need"
    apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \$need >/dev/null || return 1
  fi
}

fetch_soloist() {
  if [ -x "\$BIN" ] && "\$BIN" --version >/dev/null 2>&1; then return 0; fi
  say "downloading Soloist from Spotify"
  local tmp; tmp=\$(mktemp -d)
  curl -fsSL -o "\$tmp/s.tgz" https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz \\
    || { say "download failed"; rm -rf "\$tmp"; return 1; }
  tar xzf "\$tmp/s.tgz" -C "\$tmp"
  local f; f=\$(find "\$tmp" -type f -name soloist | head -1)
  [ -n "\$f" ] || { say "no soloist binary in the archive"; rm -rf "\$tmp"; return 1; }
  mkdir -p "\$BASE/bin" && install -m 755 "\$f" "\$BIN" && rm -rf "\$tmp"
  say "Soloist \$("\$BIN" --version 2>&1 | head -1)"
}

# No process substitution here: proot has no /dev/fd.
K=()
[ -s "\$BASE/key" ] && K=(-k "\$(cat "\$BASE/key")")
# Soloist leaves its session marker behind when killed and then refuses to start.
rm -f "\$DATA/soloist.pid" "\$DATA/.lock" "\$DATA/ws.addr" "\$DATA/ws.port" 2>/dev/null

case "\$MODE" in
  setup) install_deps && fetch_soloist && say "Debian side ready" ;;
  pair)
    install_deps && fetch_soloist || exit 1
    say "on Wi-Fi: open Spotify, play anything, tap the devices icon and pick '$DEVICE'"
    "\$BIN" --pair -n "$DEVICE" "\${K[@]}" -D "\$DATA" 2>&1 | grep -E 'pairing|error|Error| E ' --line-buffered
    ;;
  run)
    fetch_soloist || exit 1
    # exec: this proot session is Soloist, and lives exactly as long as it does.
    exec "\$BIN" -n "$DEVICE" "\${K[@]}" -D "\$DATA" -i 100
    ;;
esac
SOLO
chmod 755 "$ROOTFS/root/cabineq-soloist-run.sh"

debian() { proot-distro login debian -- bash /root/cabineq-soloist-run.sh "$1"; }

# ---- the native pieces --------------------------------------------------------------------------

start_pulse() {
  pkill -x pulseaudio 2>/dev/null; sleep 1; pkill -9 -x pulseaudio 2>/dev/null
  rm -f "$SOCK" "$FIFO"
  # The only sink is the pipe. No AAudio/OpenSL output module (the AAudio one is what hung), no
  # suspend-on-idle (a paused Spotify must still produce a steady stream of silence).
  cat > "$RUN/cabineq.pa" <<PA
load-module module-native-protocol-unix socket=$SOCK auth-anonymous=1
load-module module-pipe-sink file=$FIFO sink_name=CabinEQ format=s16le rate=44100 channels=2 use_system_clock_for_timing=no
set-default-sink CabinEQ
PA
  setsid nohup pulseaudio --daemonize=no --use-pid-file=no --system=no -n -F "$RUN/cabineq.pa" \
    --exit-idle-time=-1 --disable-shm=yes --realtime=no --high-priority=no \
    --log-target=stderr --log-time=yes >"$RUN/pulse.log" 2>&1 &
  for _ in $(seq 1 30); do
    timeout 3 pactl list short sinks 2>/dev/null | grep -q CabinEQ && { say "pulseaudio up (native), pipe sink ready"; return 0; }
    sleep 0.5
  done
  say "pulseaudio did not come up:"; tail -n 8 "$RUN/pulse.log" | tee -a "$LOG" >&2
  return 1
}

start_pcm() {
  pkill -f "cabineq-pcm-server.py" 2>/dev/null; sleep 0.3
  setsid nohup python "$RUN/cabineq-pcm-server.py" "$FIFO" "$PORT" >>"$RUN/pcm.log" 2>&1 &
  sleep 1
  pcm_up && say "pcm server on 127.0.0.1:$PORT" || { say "pcm server failed:"; tail -n 5 "$RUN/pcm.log" | tee -a "$LOG" >&2; return 1; }
}

start_soloist() {
  pkill -x soloist 2>/dev/null; sleep 1
  setsid nohup proot-distro login debian -- bash /root/cabineq-soloist-run.sh run >>"$RUN/soloist.log" 2>&1 &
  for _ in $(seq 1 20); do soloist_up && { say "soloist up as '$DEVICE'"; return 0; }; sleep 0.5; done
  if grep -q 'pairing mode' "$RUN/soloist.log" 2>/dev/null; then
    say "soloist is not paired: run 'bash $0 pair'"
  else
    say "soloist did not start:"; grep -E ' E |expire|key|error' "$RUN/soloist.log" | tail -n 5 | tee -a "$LOG" >&2
  fi
  return 1
}

# ---- run ----------------------------------------------------------------------------------------

case "$MODE" in
  setup) debian setup && say "setup done. Next: bash $0 start (or pair first, if not paired)" ;;
  pair)  kill_all; debian pair; say "paired. Next: bash $0 start" ;;
  start)
    kill_all
    command -v termux-wake-lock >/dev/null && termux-wake-lock
    setsid nohup bash "$0" __supervise >>"$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    sleep 8
    status
    echo
    say "In Spotify, pick '$DEVICE' and press play; then switch on 'Spotify via Soloist' in CabinEQ."
    ;;
  __supervise)
    say "supervisor up"
    misses=0; soloist_fails=0
    while true; do
      # PulseAudio: alive means answering. When it is replaced, Soloist loses its connection
      # with it, so Soloist is restarted too; the PCM server keeps the FIFO and carries on.
      if pulse_ok; then misses=0; else
        misses=$((misses + 1))
        if [ $misses -ge 2 ] || ! pgrep -x pulseaudio >/dev/null; then
          say "pulseaudio not answering ($(pgrep -x pulseaudio >/dev/null && echo "present" || echo "gone")); restarting it and Soloist"
          tail -n 6 "$RUN/pulse.log" 2>/dev/null >> "$LOG"
          # A PulseAudio that shut down cleanly unlinks its FIFO, leaving the PCM server reading a
          # dead one: restart the server after it, on the new FIFO. Soloist lost its connection.
          start_pulse && { start_pcm; pkill -x soloist 2>/dev/null; }
          misses=0
        fi
      fi
      pcm_up || { say "pcm server down; restarting it"; tail -n 3 "$RUN/pcm.log" >> "$LOG" 2>/dev/null; start_pcm; }
      if ! soloist_up && pulse_ok; then
        # A Soloist that keeps dying within seconds is broken, not unlucky: back off.
        if start_soloist; then soloist_fails=0; else soloist_fails=$((soloist_fails + 1)); fi
        [ $soloist_fails -ge 5 ] && { say "soloist keeps failing; waiting a minute"; sleep 60; }
      fi
      sleep 5
    done
    ;;
esac
