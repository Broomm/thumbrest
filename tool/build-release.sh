#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:?Run this script with devbox run release}"
: "${ANDROID_KEYSTORE_BASE64:?Missing ANDROID_KEYSTORE_BASE64}"
: "${ANDROID_KEYSTORE_PASSWORD:?Missing ANDROID_KEYSTORE_PASSWORD}"
: "${ANDROID_KEY_ALIAS:?Missing ANDROID_KEY_ALIAS}"
: "${ANDROID_KEY_PASSWORD:?Missing ANDROID_KEY_PASSWORD}"

umask 077
signing_dir="$(mktemp -d "${TMPDIR:-/tmp}/thumbrest-signing.XXXXXXXX")"
cleanup() {
    rm -f "$signing_dir/keystore.p12" "$signing_dir/certificate.der"
    rmdir "$signing_dir"
}
trap cleanup EXIT
export ANDROID_KEYSTORE_FILE="$signing_dir/keystore.p12"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$ANDROID_KEYSTORE_FILE"
test -s "$ANDROID_KEYSTORE_FILE"

./gradlew --no-daemon :app:assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
sdk_tools="$ANDROID_HOME/build-tools/36.0.0"
"$sdk_tools/apksigner" verify --verbose "$apk"
"$sdk_tools/zipalign" -c -P 16 4 "$apk"
unzip -tq "$apk"

keytool -exportcert -keystore "$ANDROID_KEYSTORE_FILE" \
    -storepass:env ANDROID_KEYSTORE_PASSWORD -alias "$ANDROID_KEY_ALIAS" \
    -file "$signing_dir/certificate.der"
expected="$(shasum -a 256 "$signing_dir/certificate.der" | cut -d ' ' -f 1)"
actual="$("$sdk_tools/apksigner" verify --print-certs "$apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ "$actual" != "$expected" ]]; then
    echo "The APK signing certificate does not match the supplied keystore." >&2
    exit 1
fi
printf 'APK signer SHA-256: %s\n' "$actual"

mkdir -p dist
cp "$apk" dist/Thumbrest.apk
(
    cd dist
    shasum -a 256 Thumbrest.apk > Thumbrest.apk.sha256
    shasum -a 256 -c Thumbrest.apk.sha256
)
