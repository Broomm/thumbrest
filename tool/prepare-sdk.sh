#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?Run this script with devbox run sdk}"
cd "$(dirname "$0")/.."
cache="$PWD/.tool-cache/android/downloads"
mkdir -p "$cache" "$ANDROID_HOME"

install_package() {
    local destination="$ANDROID_HOME/$1" archive="$cache/$2" checksum="$3" layout="$4"
    if [[ -f "$destination/.thumbrest-package" ]] &&
       [[ "$(cat "$destination/.thumbrest-package")" == "$checksum" ]]; then
        return
    fi
    if [[ -e "$destination" ]]; then
        echo "SDK destination already exists: $destination" >&2
        exit 1
    fi
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --silent --show-error --retry 2 --max-time 900 \
            "https://dl.google.com/android/repository/$2" -o "$archive.pending"
        [[ "$(shasum -a 1 "$archive.pending" | cut -d ' ' -f 1)" == "$checksum" ]]
        mv "$archive.pending" "$archive"
    fi
    if [[ "$(shasum -a 1 "$archive" | cut -d ' ' -f 1)" != "$checksum" ]]; then
        echo "Android SDK checksum mismatch: $archive" >&2
        exit 1
    fi
    local stage
    stage="$(mktemp -d "$cache/extract.XXXXXXXX")"
    unzip -q "$archive" -d "$stage"
    local contents="$stage"
    if [[ "$layout" == nested ]]; then
        local entries=("$stage"/*)
        [[ ${#entries[@]} == 1 && -d "${entries[0]}" ]]
        contents="${entries[0]}"
    fi
    printf '%s\n' "$checksum" > "$contents/.thumbrest-package"
    mkdir -p "$(dirname "$destination")"
    mv "$contents" "$destination"
    if [[ "$contents" != "$stage" ]]; then rmdir "$stage"; fi
}

install_package platforms/android-36 platform-36_r02.zip 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576 nested

case "$(uname -s)" in
    Darwin)
        install_package build-tools/36.0.0 build-tools_r36_macosx.zip 199ae0047ee61e842f8ee0c6d3918e44fb9a1f83 nested
        install_package ndk/29.0.14206865 android-ndk-r29-darwin.zip 03d29fbb57e3c05a7d53597dd011d856c1456a4f nested
        install_package cmake/3.22.1 cmake-3.22.1-darwin.zip 8604eeef9adadb626dbb70a7ff58a87e6a7b967a flat
        install_package platform-tools platform-tools_r37.0.3-darwin.zip eca4effbe35f4968139b051bbb16753302a3c33c nested
        ;;
    Linux)
        install_package build-tools/36.0.0 build-tools_r36_linux.zip b0b6376977657e8ad9b969bacf4093601da2c6fb nested
        install_package ndk/29.0.14206865 android-ndk-r29-linux.zip 87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b nested
        install_package cmake/3.22.1 cmake-3.22.1-linux.zip fd0a48b4a758310df8c7aa51f59840ed48fe7ed8 flat
        install_package platform-tools platform-tools_r37.0.3-linux.zip e1579b288a7ae4e9bc28dedec6a154d78b3c04ed nested
        ;;
    *)
        echo "Build from macOS or x86-64 Linux." >&2
        exit 1
        ;;
esac
