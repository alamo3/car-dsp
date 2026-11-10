#!/usr/bin/env bash
# Android Auto UI loop with the Desktop Head Unit, for a phone connected over USB (or an
# emulator, if one ever runs here). The car screen is a window on this machine, so it can be
# screenshotted and clicked without touching the phone.
#
#   tools/dhu.sh [serial]          1920x1080 car window, headphones-style audio off
#   tools/dhu.sh [serial] 1280x720
#
# Needs: the DHU (sdkmanager "extras;google;auto") and the phone in developer mode with
# "Unknown sources" allowed in Android Auto's developer settings.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=/home/omar/Android/Sdk}"
DHU="$ANDROID_HOME/extras/google/auto/desktop-head-unit"
[ -x "$DHU" ] || { echo "DHU not installed: sdkmanager \"extras;google;auto\""; exit 1; }

# The DHU ships its own libraries (libc++.so.1 and friends) next to the binary.
export LD_LIBRARY_PATH="$ANDROID_HOME/extras/google/auto/libs:$ANDROID_HOME/extras/google/auto${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

serial="${1:-}"
geometry="${2:-1920x1080}"
cfg="$ANDROID_HOME/extras/google/auto/config/default_1080p.ini"
case "$geometry" in
  1280x720|720p) cfg="$ANDROID_HOME/extras/google/auto/config/default_720p.ini" ;;
  1920x1080|1080p) cfg="$ANDROID_HOME/extras/google/auto/config/default_1080p.ini" ;;
  *) echo "unknown geometry $geometry (1080p|720p)"; exit 2 ;;
esac

# DHU talks to the phone over adb's local transport; point it at the right device.
args=(--config="$cfg" --usb)
if [ -n "$serial" ]; then args+=("--adb=$ANDROID_HOME/platform-tools/adb" "-s" "$serial"); fi

echo "starting DHU ($geometry) against ${serial:-the only device}"
echo "screenshot from another shell:  adb -s ${serial:-<serial>} exec-out screencap -p > /tmp/dhu.png"
exec "$DHU" "${args[@]}"
