#!/data/data/com.termux/files/usr/bin/bash
# CabinEQ + Spotify Soloist, test setup for Termux.
#
#   bash cabineq-soloist.sh setup    install Debian, PulseAudio and Soloist (safe to re-run)
#   bash cabineq-soloist.sh pair     pair Soloist with your Spotify account (on Wi-Fi)
#   bash cabineq-soloist.sh play     run Soloist and serve its audio on 127.0.0.1:4712
#   bash cabineq-soloist.sh status   what is running, and is audio flowing
#   bash cabineq-soloist.sh stop     stop Soloist and the audio daemons
#
# Audio path (no Android capture, nothing to mute, no double playback):
#
#   Soloist -> PulseAudio "CabinEQ" sink (Debian) -> module-simple-protocol-tcp
#            -> 127.0.0.1:4712 as raw s16le 44100 stereo -> CabinEQ
#
# PulseAudio is used rather than PipeWire because the stream has to leave Debian as
# PCM over a socket, and the simple protocol does exactly that. PipeWire is stopped so
# Soloist falls back to libpulse.
set -uo pipefail

MODE=${1:-status}
# proot-distro moved this between versions: newer keeps it in installed-rootfs/<name>,
# older in containers/<name>/rootfs. Take whichever is a real rootfs (has etc/).
ROOTFS=""
for c in "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" \
         "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
  if [ -d "$c/etc" ]; then ROOTFS="$c"; break; fi
done
[ -n "$ROOTFS" ] || ROOTFS="$PREFIX/var/lib/proot-distro/containers/debian/rootfs"
KEYFILE="$HOME/.soloist-key"
PORT=${CABINEQ_PORT:-4712}
LOG=/sdcard/Download/soloist-$MODE.log
say() { echo "[cabineq] $*" | tee -a "$LOG"; }

case "$MODE" in setup|pair|play|status|stop|key) ;; *)
  echo "usage: bash $0 setup|pair|play|status|stop|key"; exit 2 ;;
esac
exec > >(tee -a "$LOG") 2>&1
say "== $MODE  $(date)"

# ---- Termux side -----------------------------------------------------------------------

if [ ! -d "$HOME/storage" ]; then
  say "Allow storage access in the prompt (used for logs)."
  termux-setup-storage || true
  sleep 3
fi

command -v proot-distro >/dev/null || { say "installing proot-distro"; pkg install -y proot-distro; }
if ! proot-distro list 2>&1 | grep -q 'debian'; then
  say "installing Debian (a few minutes, once)"; proot-distro install debian
fi
[ -d "$ROOTFS/etc" ] || { say "Debian rootfs not found; run setup once Debian is installed"; exit 1; }

if [ "$MODE" = key ] || { [ "$MODE" != stop ] && [ ! -s "$KEYFILE" ]; }; then
  if [ "$MODE" = key ] || [ ! -s "$KEYFILE" ]; then
    read -rsp "Paste your Soloist API key (leave empty to try without one): " k; echo
    if [ -n "$k" ]; then umask 077; printf '%s' "$k" > "$KEYFILE"; say "key saved"; else say "no key: Soloist will run with its built-in one"; fi
  fi
  [ "$MODE" = key ] && exit 0
fi

# Termux's own PulseAudio is the clock: it runs natively, outside proot, so its null sink
# keeps real time. The Debian sink is slaved to it with a tunnel sink (see the inner script).
export PULSE_RUNTIME_PATH="$PREFIX/var/run/pulse"
mkdir -p "$PULSE_RUNTIME_PATH"
command -v pulseaudio >/dev/null || yes | pkg install -y pulseaudio >/dev/null 2>&1
# One chain only: a second PulseAudio cannot have the same socket, and two daemons fighting over
# it is how "play" ends up blocked forever on --start.
# Soloist is the Spotify Connect endpoint: a stray copy (it is started detached, so killing the
# proot session does NOT kill it) keeps advertising the same device, and Spotify then loses it
# from the speaker list. Kill every piece by name before starting one of each.
pkill -x soloist 2>/dev/null; pkill -x socat 2>/dev/null; pkill -x parec 2>/dev/null
pkill -f proot 2>/dev/null; pkill -x pulseaudio 2>/dev/null; sleep 2
pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1
# Wait for the server, and bound every client call from here on: a bare pactl with no server
# autospawns one and blocks forever — that is how `play` hung with nothing on screen.
up=""
for i in $(seq 1 40); do timeout 2 pactl info >/dev/null 2>&1 && { up=1; break; }; sleep 0.5; done
say "termux pulseaudio: ${up:+up}"; [ -n "$up" ] || say "termux pulseaudio: not up"
# The sink everything is paced by must be a *device*, not a null sink: a null sink swallows
# whatever it is given as fast as it arrives, so Soloist runs free and delivers bursts (measured
# 60x realtime). Android's audio hardware keeps real time, so AAudio is the clock — muted, since
# the sound comes from CabinEQ, not from Termux.
if [ -n "$up" ]; then
  timeout 2 pactl list short modules 2>/dev/null | grep -q module-aaudio-sink || timeout 8 pactl load-module module-aaudio-sink >/dev/null 2>&1
  # A second socket, anonymous, for the proot side: it has its own cookie and no way to read
  # Termux's. Clients there speak the same protocol, so they talk to the same server.
  PROOT_SOCK="$PULSE_RUNTIME_PATH/proot"
  timeout 2 pactl list short modules 2>/dev/null | grep -q "socket=$PROOT_SOCK" || \
    timeout 8 pactl load-module module-native-protocol-unix socket="$PROOT_SOCK" auth-anonymous=1 >/dev/null 2>&1
  say "proot socket: $(test -S "$PROOT_SOCK" && echo ready || echo missing)"
  AA=$(timeout 2 pactl list short sinks 2>/dev/null | awk '$2 ~ /AAudio/ {print $2; exit}')
  if [ -n "$AA" ]; then
    timeout 2 pactl set-default-sink "$AA" >/dev/null 2>&1
    timeout 2 pactl set-sink-volume "$AA" 0 >/dev/null 2>&1
    timeout 2 pactl set-sink-mute "$AA" 0 >/dev/null 2>&1
    say "termux clock sink: $AA (muted; it exists to pace)"
  else
    say "no AAudio sink: Android audio will not pace playback"
  fi
else
  say "termux pulseaudio did not start: the source will not work"
fi

mkdir -p "$ROOTFS/root/.soloist"
[ -s "$KEYFILE" ] && install -m 600 "$KEYFILE" "$ROOTFS/root/.soloist/key"

# Reuse a pairing done earlier (the old layout, or one imported from another machine).
OLD="$ROOTFS/root/.local/share/soloist/soloist-data"
if [ ! -d "$ROOTFS/root/.soloist/data" ] && [ -d "$OLD" ]; then
  cp -r "$OLD" "$ROOTFS/root/.soloist/data"; say "reusing the earlier pairing"
fi
SEED="$HOME/storage/downloads/soloist-data"
if [ ! -d "$ROOTFS/root/.soloist/data/settings" ] && [ -d "$SEED" ]; then
  cp -r "$SEED" "$ROOTFS/root/.soloist/data"; say "imported the pairing from Downloads/soloist-data"
fi

# ---- Debian side (written fresh each run) -----------------------------------------------

mkdir -p "$ROOTFS/root" || { say "cannot write into $ROOTFS"; exit 1; }
cat > "$ROOTFS/root/cabineq-soloist-inner.sh" <<'INNER'
#!/bin/bash
set -uo pipefail
MODE=$1
PORT=$2
BASE=/root/.soloist
BIN=$BASE/bin/soloist
DATA=$BASE/data
KEY=$(cat $BASE/key 2>/dev/null || true)
CMD=/root/.soloist/cli.sh
TERMUX_SOCK=/data/data/com.termux/files/usr/var/run/pulse/native
say() { echo "[debian] $*"; }
# proot has no /run/user/0: PulseAudio needs somewhere to put its socket.
export XDG_RUNTIME_DIR=/tmp/cabineq-runtime
mkdir -p "$XDG_RUNTIME_DIR/pulse" && chmod 700 "$XDG_RUNTIME_DIR"
export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
# Pin the socket: pulseaudio runs as root here, and clients must not have to guess its path.
export PULSE_SERVER="unix:$XDG_RUNTIME_DIR/pulse/native"

install_deps() {
  local need=""
  for p in curl ca-certificates libatomic1 pulseaudio pulseaudio-utils dbus sox socat netcat-openbsd; do
    dpkg -s "$p" >/dev/null 2>&1 || need="$need $p"
  done
  if [ -n "$need" ]; then
    say "installing:$need"
    apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $need >/dev/null
  fi
}

fetch_soloist() {
  [ -x "$BIN" ] && "$BIN" --version >/dev/null 2>&1 && return 0
  say "downloading Soloist from Spotify"
  local tmp; tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/s.tgz" https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz || { say "download failed"; return 1; }
  tar xzf "$tmp/s.tgz" -C "$tmp"
  local f; f=$(find "$tmp" -type f -name soloist | head -1)
  [ -n "$f" ] || { say "no soloist binary in the archive"; return 1; }
  mkdir -p "$BASE/bin" && install -m 755 "$f" "$BIN" && rm -rf "$tmp"
  "$BIN" --version | head -1
}

write_pulse_conf() {
  mkdir -p /root/.config/pulse
  # No .fail and no set-default-sink here: a tunnel that cannot connect must not take the
  # daemon (and with it the whole source) down. The default is set once the sink is known.
  cat > /root/.config/pulse/default.pa <<'EOF'
load-module module-native-protocol-unix socket=/tmp/cabineq-runtime/pulse/native auth-anonymous=1
load-module module-tunnel-sink server=unix:/data/data/com.termux/files/usr/var/run/pulse/native sink_name=CabinEQ
EOF
  # A tunnel to Termux's own PulseAudio, for testing on the phone speaker:
  #   pactl load-module module-tunnel-sink server=unix:/data/data/com.termux/files/usr/var/run/pulse/native sink_name=Speaker
  #   pactl load-module module-loopback source=CabinEQ.monitor sink=Speaker
}

start_audio() {
  pkill -x soloist 2>/dev/null
  pkill -x pipewire 2>/dev/null; pkill -x wireplumber 2>/dev/null
  pulseaudio --kill 2>/dev/null; sleep 1
  rm -f "$XDG_RUNTIME_DIR/pulse/native"
  # Not --start: a daemonised PulseAudio is orphaned when this proot session ends, so it runs
  # here in the background and lives exactly as long as the session does.
  pulseaudio --exit-idle-time=-1 --log-target=stderr >/tmp/cabineq-pulse.log 2>&1 &
  for _ in $(seq 1 40); do pactl info >/dev/null 2>&1 && break; sleep 0.5; done
  pactl set-default-sink CabinEQ
  pactl set-sink-volume CabinEQ 100%
  pactl info | grep -E "Default Sink|Server String"
  pactl list short sinks
  # The tunnel may take a moment, or fail if Termux's PulseAudio is not up yet. Say which, and
  # only claim a default when the sink is really there.
  for i in $(seq 1 20); do
    pactl list short sinks | grep -q CabinEQ && break
    sleep 0.5
  done
  if pactl list short sinks | grep -q CabinEQ; then
    pactl set-default-sink CabinEQ
    say "CabinEQ sink ready"
  else
    say "CabinEQ sink missing: is Termux PulseAudio running? (tail -5 /tmp/cabineq-pulse.log)"
  fi
}

serve_pcm() {
  # socat, not a standing parec | nc: a reader that is always attached but has no client lets
  # the monitor accumulate, and then the app starts on a backlog (measured: 68 s behind). socat
  # starts a fresh parec for each connection, so a client always joins the live edge.
  pkill -f "nc -l -p $PORT" 2>/dev/null
  pkill -x socat 2>/dev/null
  pkill -x parec 2>/dev/null
  nohup socat TCP-LISTEN:$PORT,reuseaddr,fork \
    EXEC:"parec -d CabinEQ.monitor --format=s16le --rate=44100 --channels=2 --raw",nofork \
    >/tmp/cabineq-socat.log 2>&1 &
  sleep 1
  say "serving PCM on 127.0.0.1:$PORT (socat + parec per connection)"
}

start_soloist() {
  # setsid: the proot session that starts this exits, and a plain child would be hung up.
  if [ -n "$KEY" ]; then
    setsid nohup "$BIN" -n CabinEQ -k "$KEY" -D "$DATA" -i 100 >/tmp/cabineq-soloist.log 2>&1 &
  else
    setsid nohup "$BIN" -n CabinEQ -D "$DATA" -i 100 >/tmp/cabineq-soloist.log 2>&1 &
  fi
  sleep 3
  if pgrep -x soloist >/dev/null; then
    say "Soloist running (pid $(pgrep -x soloist | head -1))"
    grep -E "AudioDriver|PipeWire|Pulse|error|Error| E " /tmp/cabineq-soloist.log | tail -5
    return 0
  else
    say "Soloist exited:"
    tail -20 /tmp/cabineq-soloist.log
    return 1
  fi
}

case "$MODE" in
  setup) install_deps && fetch_soloist && write_pulse_conf && say "setup done" ;;
  pair)
    install_deps && fetch_soloist || exit 1
    write_pulse_conf; start_audio
    say "on Wi-Fi: open Spotify, tap the devices icon and pick CabinEQ"
    if [ -n "$KEY" ]; then
      "$BIN" --pair -n CabinEQ -k "$KEY" -D "$DATA" 2>&1 | grep -E 'pairing|error|Error| E ' --line-buffered
    else
      "$BIN" --pair -n CabinEQ -D "$DATA" 2>&1 | grep -E 'pairing|error|Error| E ' --line-buffered
    fi
    ;;
  play)
    install_deps && fetch_soloist && write_pulse_conf || exit 1
    start_audio
    serve_pcm
    start_soloist || say "Soloist is not up yet: give it a key (bash cabineq-soloist.sh key)"
    say "playing. In Spotify, pick CabinEQ and press play."
    # Hold the session open while its PulseAudio is alive. proot's PulseAudio does die on its
    # own now and then; when it does, exit so the supervisor below replaces the whole session.
    while true; do
      sleep 5
      timeout 3 pactl info >/dev/null 2>&1 || { say "pulseaudio went away: restarting the session"; exit 1; }
    done
    ;;
  status)
    say "--- pulse"; pactl info 2>&1 | grep -E "Default Sink|Server String|Server Name"
    say "--- sinks"; pactl list short sinks
    say "--- playback streams"; pactl list short sink-inputs
    say "--- pcm server"; pgrep -af "socat TCP-LISTEN:$PORT" | head -2 || say "no PCM server running"
    say "--- soloist"; pgrep -x soloist >/dev/null && echo "running" || echo "not running"
    say "--- volume"; pactl get-sink-volume CabinEQ 2>&1 | head -1
    ;;
  stop)
    pkill -x soloist 2>/dev/null
    pkill -f "nc -l -p $PORT" 2>/dev/null
    pkill -x socat 2>/dev/null
    pkill -x parec 2>/dev/null
    pactl unload-module module-simple-protocol-tcp 2>/dev/null
    pulseaudio --kill 2>/dev/null
    say "stopped"
    ;;
esac
INNER
chmod 755 "$ROOTFS/root/cabineq-soloist-inner.sh"
[ -s "$ROOTFS/root/cabineq-soloist-inner.sh" ] || { say "the inner script was not written"; exit 1; }

# ---- run it -----------------------------------------------------------------------------

[ "$MODE" != stop ] && command -v termux-wake-lock >/dev/null && termux-wake-lock || true
status=0
if [ "$MODE" = play ]; then
  # Detached: the session must outlive this shell, because PulseAudio and Soloist are its
  # children. stop kills the session.
  pkill -f "while true; do proot-distro login" 2>/dev/null
  PORT_FOR_WATCH="$PORT" LOG_FOR_WATCH=/sdcard/Download/soloist-play.log \
  setsid nohup bash -c 'while true; do
      proot-distro login debian -- bash /root/cabineq-soloist-inner.sh play "$PORT_FOR_WATCH" >> "$LOG_FOR_WATCH" 2>&1
      echo "[cabineq] session ended, restarting in 3 s" >> "$LOG_FOR_WATCH"
      sleep 3
    done' >/dev/null 2>&1 &
  sleep 12
  say "started in the background; log: /sdcard/Download/soloist-play.log"
else
  if [ "$MODE" = stop ]; then
    pkill -f cabineq-soloist-inner 2>/dev/null
  fi
  proot-distro login debian -- bash /root/cabineq-soloist-inner.sh "$MODE" "$PORT" || status=$?
fi
[ "$MODE" = stop ] && command -v termux-wake-unlock >/dev/null && termux-wake-unlock || true
say "exit $status (log: $LOG)"
exit $status
