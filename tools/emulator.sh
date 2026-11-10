#!/usr/bin/env bash
# Boot the AAOS emulator, install the x86_64 build and screenshot it. This is the loop that runs
# here without a phone: the app's media UI, playback, the DSP and the car's chrome.
#
#   tools/emulator.sh            boot (if needed), build, install, launch, screenshot
#   tools/emulator.sh --window   boot with a visible window and audio (to watch and listen)
#   tools/emulator.sh --shot     just take a screenshot of the running emulator
#   tools/emulator.sh --stop     shut it down
#
# Why the flags: with -gpu swiftshader_indirect (and -gpu guest) QEMU segfaults in SwiftShader's
# libGLESv2.so on a RenderThread, ~2 s into boot, on this host. -gpu host survives and booted in
# ~12 s. Memory was never the problem (37 GB of swap free during the crash).
set -uo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=/home/omar/Android/Sdk}"
: "${JAVA_HOME:=/home/omar/android-studio/jbr}"
export JAVA_HOME
AVD="${AVD:-CabinCar}"
SERIAL="emulator-5554"
SHOT="${SHOT:-/tmp/aaos.png}"

shot() {
  adb -s "$SERIAL" shell screencap -p /sdcard/_shot.png >/dev/null 2>&1
  adb -s "$SERIAL" pull /sdcard/_shot.png "$SHOT" >/dev/null 2>&1
  echo "screenshot: $SHOT"
}

WINDOW_ARGS=(-no-window -no-audio)
case "${1:-}" in
  --shot) shot; exit 0 ;;
  --stop) adb -s "$SERIAL" emu kill 2>/dev/null; echo "stopped"; exit 0 ;;
  # The window and the audio stream are QEMU's: the guest's 48 kHz output is resampled by the
  # host audio backend, so it is good for "can I hear it at all", not for judging the DSP.
  --window) WINDOW_ARGS=(-audio pulseaudio) ;;
esac

if ! adb devices | grep -q "^$SERIAL[[:space:]]*device"; then
  echo "== booting $AVD"
  nohup env DISPLAY="${DISPLAY:-:0}" "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
      "${WINDOW_ARGS[@]}" -no-snapshot -gpu host -memory 2048 -cores 2 -no-boot-anim \
      -dns-server 1.1.1.1,8.8.8.8 > /tmp/aaos-emulator.log 2>&1 &
  for _ in $(seq 1 60); do
    [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
  adb devices | grep -q "^$SERIAL[[:space:]]*device" || { echo "emulator did not boot (see /tmp/aaos-emulator.log)"; exit 1; }
fi

echo "== building x86_64"
./gradlew :app:assembleDebug -PcabinAbis=x86_64 -PcabinVersionCode=1000 \
    -PcabinVersionName=0.1.1000-dev || exit 1

echo "== installing"
# The emulator may hold an older build signed differently; a signature mismatch is fatal.
adb -s "$SERIAL" uninstall dev.alamo3.cabineq >/dev/null 2>&1
adb -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk | tail -1

# Optional: seed prefs (e.g. the addon link) so the car home has content.
# On AAOS the app runs as the driver profile (user 10), not user 0 — `run-as` would seed the
# wrong one and the app would still look unconfigured. The AOSP image allows adb root, so write
# the file for every user the app is installed under, with the app's own uid.
if [ -n "${SEED_PREFS:-}" ] && [ -f "$SEED_PREFS" ]; then
  adb -s "$SERIAL" shell am force-stop dev.alamo3.cabineq
  adb -s "$SERIAL" push "$SEED_PREFS" /data/local/tmp/cabin_seed.xml >/dev/null
  adb -s "$SERIAL" root >/dev/null 2>&1
  sleep 2
  for user in $(adb -s "$SERIAL" shell ls /data/user 2>/dev/null | tr -d '\r'); do
    dir="/data/user/$user/dev.alamo3.cabineq"
    adb -s "$SERIAL" shell "[ -d $dir ] || exit 0
      owner=\$(stat -c %u:%g $dir)
      mkdir -p $dir/shared_prefs
      cp /data/local/tmp/cabin_seed.xml $dir/shared_prefs/cabin_eq.xml
      chown -R \$owner $dir/shared_prefs
      chmod 660 $dir/shared_prefs/cabin_eq.xml" 2>/dev/null
    echo "seeded user $user"
  done
  adb -s "$SERIAL" unroot >/dev/null 2>&1
  sleep 2
fi

# Grant notifications up front: the first play otherwise stops behind a system dialog.
adb -s "$SERIAL" shell pm grant dev.alamo3.cabineq android.permission.POST_NOTIFICATIONS 2>/dev/null
adb -s "$SERIAL" shell am start -n dev.alamo3.cabineq/.MusicActivity >/dev/null 2>&1
sleep 3
shot
