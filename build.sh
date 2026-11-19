#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
TOOLS="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
mode="${1:---debug}"
if [[ $# -gt 1 || ( "$mode" != --debug && "$mode" != --release ) ]]; then
    printf 'Usage: bash build.sh [--debug|--release]\n' >&2
    exit 2
fi

if [[ "$mode" == --release ]]; then
    : "${SNEZHOK_KEYSTORE:?Set SNEZHOK_KEYSTORE to an existing release signing key store}"
    : "${SNEZHOK_KEY_ALIAS:?Set SNEZHOK_KEY_ALIAS}"
    : "${SNEZHOK_KEYSTORE_PASSWORD:?Set SNEZHOK_KEYSTORE_PASSWORD}"
    : "${SNEZHOK_KEY_PASSWORD:?Set SNEZHOK_KEY_PASSWORD}"
    if [[ ! -f "$SNEZHOK_KEYSTORE" || ! -r "$SNEZHOK_KEYSTORE" ]]; then
        printf 'Release signing needs a readable, existing key store.\n' >&2
        exit 2
    fi
    signing=(--ks "$SNEZHOK_KEYSTORE" --ks-key-alias "$SNEZHOK_KEY_ALIAS"
        --ks-pass env:SNEZHOK_KEYSTORE_PASSWORD --key-pass env:SNEZHOK_KEY_PASSWORD)
fi

VERSION="$(python3 - "$ROOT/AndroidManifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
print(ET.parse(sys.argv[1]).getroot().get('{http://schemas.android.com/apk/res/android}versionName'))
PY
)"
# A fresh directory prevents deleted/renamed Java classes leaking into a later APK.
mkdir -p "$ROOT/build"
BUILD="$(mktemp -d "$ROOT/build/v${VERSION}-XXXXXXXX")"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
"$TOOLS/aapt2" compile --dir "$ROOT/res" -o "$BUILD/resources.zip"
"$TOOLS/aapt2" link -o "$BUILD/resources.apk" -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" --java "$BUILD/gen" \
    --min-sdk-version 26 --target-sdk-version 36 "$BUILD/resources.zip"
mapfile -t SOURCES < <(rg --files "$ROOT/src" "$BUILD/gen" -g '*.java')
javac -encoding UTF-8 -source 8 -target 8 -Xlint:deprecation \
    -classpath "$PLATFORM" -d "$BUILD/classes" "${SOURCES[@]}"
mapfile -t CLASSES < <(rg --files "$BUILD/classes" -g '*.class')
"$TOOLS/d8" --lib "$PLATFORM" --min-api 26 --output "$BUILD/dex" "${CLASSES[@]}"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
python3 - "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" "$ROOT/exports/garland-modes-v4" <<'PY'
import hashlib
import json
from pathlib import Path
import sys
import zipfile
bundle = Path(sys.argv[3])
index_bytes = (bundle / "index.json").read_bytes()
if hashlib.sha256(index_bytes).hexdigest() != "4966b359d12de1f9801003fd8645c6d0439e180105300eed13b7951bbd778e39":
    raise SystemExit("Unexpected HONOR theme bundle")
index = json.loads(index_bytes)
choices = {f"{palette}-{effect}" for palette in ("warm", "color")
           for effect in ("twinkle", "wave", "sparks", "breath", "steady")}
common_name = "common.zip-part"
if set(index["themes"]) != choices or set(index["assets"]) != {
    common_name, *(f"{choice}.zip-tail" for choice in choices),
}:
    raise SystemExit("Unexpected HONOR theme choices")
parts = {}
for name, expected in index["assets"].items():
    data = (bundle / "assets" / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit(f"Unexpected contents for {name}")
    parts[name] = data
for choice in choices:
    digest = hashlib.sha256(parts[common_name] + parts[f"{choice}.zip-tail"]).hexdigest()
    if digest != index["themes"][choice]:
        raise SystemExit(f"HONOR theme cannot be reproduced: {choice}")
with zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_DEFLATED) as package:
    package.write(sys.argv[2], "classes.dex")
    for name, data in sorted(parts.items()):
        package.writestr(f"assets/honor-garland/{name}", data, compress_type=zipfile.ZIP_STORED)
PY
"$TOOLS/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
if [[ "$mode" == --debug ]]; then
    KEY="${SNEZHOK_DEBUG_KEYSTORE:-$ROOT/.signing/debug.keystore}"
    if [[ -n "${SNEZHOK_DEBUG_KEYSTORE:-}" && ! -f "$KEY" ]]; then
        printf 'The requested debug key store does not exist.\n' >&2
        exit 2
    fi
    if [[ ! -f "$KEY" ]]; then
        mkdir -p "$ROOT/.signing"
        keytool -genkeypair -keystore "$KEY" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -keysize 3072 -validity 10000 \
            -dname 'CN=Snowfall Local Development' -noprompt
        chmod 600 "$KEY"
    fi
    signing=(--ks "$KEY" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey)
fi
APK="$BUILD/Snezhok-${VERSION}.apk"
"$TOOLS/apksigner" sign "${signing[@]}" --out "$APK" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" |
    awk '/^Verifies$|^Verified using|^Signer #[0-9]+ certificate SHA-256 digest:/'
"$TOOLS/zipalign" -c -p 4 "$APK"
"$TOOLS/aapt2" dump badging "$APK"
sha256sum "$APK"
