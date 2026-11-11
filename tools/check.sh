#!/usr/bin/env bash
# The closed loop that runs without a phone: the native DSP harnesses, the JVM tests and lint.
# Exits non-zero on the first failure, so it can gate a commit or run before installing.
#
#   tools/check.sh            everything
#   tools/check.sh native     just the C++ harnesses (fast, no Gradle)
#   tools/check.sh gradle     just tests + lint
set -uo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:=/home/omar/android-studio/jbr}"
export JAVA_HOME
mode="${1:-all}"
status=0

native() {
  local cxx=${CXX:-g++}
  for t in dsp-test resampler-test; do
    echo "== native: $t"
    if ! "$cxx" -O2 -std=c++17 -I app/src/main/cpp "tools/$t.cpp" -o "/tmp/cabin-$t"; then
      echo "   build failed"; status=1; continue
    fi
    "/tmp/cabin-$t" || { echo "   FAILED"; status=1; }
  done
}

gradle() {
  echo "== unit tests"
  ./gradlew --no-daemon :app:testDebugUnitTest || status=1
  echo "== lint"
  ./gradlew --no-daemon lintDebug || status=1
}

case "$mode" in
  native) native ;;
  gradle) gradle ;;
  all)    native; gradle ;;
  *) echo "usage: $0 [all|native|gradle]"; exit 2 ;;
esac

if [ "$status" -eq 0 ]; then echo "== all checks passed"; else echo "== CHECKS FAILED"; fi
exit "$status"
