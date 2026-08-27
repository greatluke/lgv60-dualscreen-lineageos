#!/bin/sh
# Build DS2NavBar.apk. Needs Android SDK build-tools (aapt2/d8/zipalign/apksigner) and a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
BT="${BT:-$(ls -d "${ANDROID_SDK:-$HOME/Android/Sdk}"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
AJ="${AJ:-$(ls -d "${ANDROID_SDK:-$HOME/Android/Sdk}"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)}"
[ -x "$BT/aapt2" ] || { echo "set BT=/path/to/build-tools" >&2; exit 1; }
cd "$HERE"; rm -rf build; mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 29 --target-sdk-version 34 build/res.zip
javac -nowarn -encoding UTF-8 --release 11 -cp "$AJ" -d build/classes $(find java build/gen -name '*.java')
"$BT/d8" --release --min-api 29 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
[ -f ds2navbar.jks ] || keytool -genkeypair -keystore ds2navbar.jks -storepass ds2nav -keypass ds2nav \
    -alias ds2nav -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ds2navbar" >/dev/null
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks ds2navbar.jks --ks-pass pass:ds2nav --key-pass pass:ds2nav \
    --min-sdk-version 29 --v1-signing-enabled true --v2-signing-enabled true \
    --out build/DS2NavBar.apk build/aligned.apk
echo "built $HERE/build/DS2NavBar.apk"
