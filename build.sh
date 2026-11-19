#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
TOOLS="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
VERSION="$(python3 - "$ROOT/AndroidManifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
print(ET.parse(sys.argv[1]).getroot().get('{http://schemas.android.com/apk/res/android}versionName'))
PY
)"
# A fresh directory prevents deleted/renamed Java classes leaking into a later APK.
mkdir -p "$ROOT/build"
BUILD="$(mktemp -d "$ROOT/build/v${VERSION}-XXXXXXXX")"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/.signing"
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
KEY="$ROOT/.signing/debug.keystore"
if [[ ! -f "$KEY" ]]; then
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=Snowfall Local Development' -noprompt
fi
chmod 600 "$KEY"
"$TOOLS/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$BUILD/Snezhok-${VERSION}.apk" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$BUILD/Snezhok-${VERSION}.apk"
"$TOOLS/aapt2" dump badging "$BUILD/Snezhok-${VERSION}.apk"
sha256sum "$BUILD/Snezhok-${VERSION}.apk"
