#!/bin/sh
# Writes the CI release key (the same value as the SIGNING_KEYSTORE_B64 secret) to
# ~/.android/cabineq-release.jks, so local builds are signed with the key CI uses and a
# `./gradlew installDebug` replaces the installed app instead of failing with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE.
#
#   export SIGNING_KEYSTORE_B64='<value of the GitHub secret>'
#   tools/import-signing-key.sh
#   # then put the password in local.properties (never committed):
#   #   cabineq.storePassword=<SIGNING_PASSWORD>
set -e
[ -n "$SIGNING_KEYSTORE_B64" ] || { echo "Set SIGNING_KEYSTORE_B64 first (GitHub secret)." >&2; exit 1; }
mkdir -p "$HOME/.android"
printf '%s' "$SIGNING_KEYSTORE_B64" | base64 -d > "$HOME/.android/cabineq-release.jks"
chmod 600 "$HOME/.android/cabineq-release.jks"
echo "Wrote $HOME/.android/cabineq-release.jks"
echo "Now add to local.properties:  cabineq.storePassword=<the SIGNING_PASSWORD secret>"
