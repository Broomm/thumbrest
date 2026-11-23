#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Run this script through Devbox}"
adb="${ADB:-$ANDROID_HOME/platform-tools/adb}"

case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) echo "Use a macOS or x86-64 Linux development host." >&2; exit 1 ;;
esac

output="$PWD/.tool-cache/checks/device-session-test"
mkdir -p "$(dirname "$output")"
compiler="$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android29-clang++"
"$compiler" -std=c++17 -O1 -Wall -Wextra -Werror -pthread -static-libstdc++ -fPIE -pie \
    -Iapp/src/main/cpp app/src/main/cpp/linux_pad.cpp \
    app/src/test/cpp/device_session_test.cpp -o "$output"

case "${1:-}" in
    --build-only) exit 0 ;;
    "") ;;
    *) echo "Usage: check-device.sh [--build-only]" >&2; exit 1 ;;
esac
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the test device serial from adb devices}"

remote_dir="$("$adb" -s "$ANDROID_SERIAL" shell mktemp -d /data/local/tmp/thumbrest-check.XXXXXXXX | tr -d '\r')"
if [[ ! "$remote_dir" =~ ^/data/local/tmp/thumbrest-check\.[A-Za-z0-9]{8}$ ]]; then
    echo "The device did not create a private test directory." >&2
    exit 1
fi
cleanup() {
    "$adb" -s "$ANDROID_SERIAL" shell rm -f "$remote_dir/check"
    "$adb" -s "$ANDROID_SERIAL" shell rmdir "$remote_dir"
}
trap cleanup EXIT
"$adb" -s "$ANDROID_SERIAL" push "$output" "$remote_dir/check"
"$adb" -s "$ANDROID_SERIAL" shell chmod 700 "$remote_dir/check"
"$adb" -s "$ANDROID_SERIAL" shell su -c "$remote_dir/check"
