#!/bin/sh
# Build DS2AppSwitch.apk. Needs Android SDK build-tools (aapt2/d8/zipalign/apksigner) and a JDK.
# Same shape as ds2navbar/build.sh -- see its comment and AppSwitchAccessibilityService's javadoc
# for why this is a separate app rather than code in the root daemon.
#
# Currently shelved (see AppSwitchServer's javadoc): not wired into DualScreenBridgeDaemon.main()
# or tools/build.sh, so it is not part of the shipped module. It worked when tested, kept here to
# pick back up later rather than rewritten from scratch.
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
[ -f ds2appswitch.jks ] || keytool -genkeypair -keystore ds2appswitch.jks -storepass ds2appswitch -keypass ds2appswitch \
    -alias ds2appswitch -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ds2appswitch" >/dev/null
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks ds2appswitch.jks --ks-pass pass:ds2appswitch --key-pass pass:ds2appswitch \
    --min-sdk-version 29 --v1-signing-enabled true --v2-signing-enabled true \
    --out build/DS2AppSwitch.apk build/aligned.apk
echo "built $HERE/build/DS2AppSwitch.apk"
