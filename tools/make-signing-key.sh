#!/bin/sh
# Creates the key CI signs releases with, and prints the exact values for the two GitHub secrets.
#
#   tools/make-signing-key.sh        # prompts for a password, writes ~/.android/cabineq-release.jks
#
# Then, in the repo: Settings -> Secrets and variables -> Actions -> New repository secret
#   SIGNING_KEYSTORE_B64 = the base64 line this script prints
#   SIGNING_PASSWORD     = the password you typed
#
# Keep the .jks and the password. Both are needed again for updates: Android refuses to install a
# release signed with a different key over the existing app.
set -e

KEYSTORE="${CABINEQ_KEYSTORE:-$HOME/.android/cabineq-release.jks}"
ALIAS="cabineq"

if [ -e "$KEYSTORE" ]; then
    echo "$KEYSTORE already exists - refusing to overwrite a signing key." >&2
    echo "Move it away first if you really want a new one (updates will need an uninstall)." >&2
    exit 1
fi

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if ! command -v "$KEYTOOL" >/dev/null 2>&1; then
    for candidate in "$HOME/android-studio/jbr/bin/keytool" /usr/lib/jvm/*/bin/keytool; do
        [ -x "$candidate" ] && KEYTOOL="$candidate" && break
    done
fi
command -v "$KEYTOOL" >/dev/null 2>&1 || { echo "keytool not found - install a JDK or set JAVA_HOME." >&2; exit 1; }

printf 'Password for the new key (this becomes SIGNING_PASSWORD): '
old_stty=$(stty -g 2>/dev/null || true)
stty -echo 2>/dev/null || true
IFS= read -r PASSWORD
stty "$old_stty" 2>/dev/null || true
echo
[ -n "$PASSWORD" ] || { echo "Empty password - aborting." >&2; exit 1; }
[ ${#PASSWORD} -ge 8 ] || { echo "Use at least 8 characters." >&2; exit 1; }

mkdir -p "$(dirname "$KEYSTORE")"
"$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=CabinEQ, O=alamo3, C=CA"
chmod 600 "$KEYSTORE"

echo
echo "Keystore : $KEYSTORE"
echo "Alias    : $ALIAS"
echo "SHA-256  : $("$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" \
    | awk '/SHA256:/{print $2; exit}')"
echo
echo "Add these repository secrets (Settings -> Secrets and variables -> Actions):"
echo "  SIGNING_KEYSTORE_B64  = the base64 below"
echo "  SIGNING_PASSWORD      = the password you just typed"
echo
if base64 --help 2>/dev/null | grep -q -- '-w'; then
    base64 -w0 "$KEYSTORE"
else
    base64 < "$KEYSTORE" | tr -d '\n'
fi
echo
echo
echo "For local builds that replace the CI app, also do:"
echo "  echo 'cabineq.storePassword=<the password>' >> local.properties"
echo "  ./gradlew installDebug -PcabinVersionCode=9999"
