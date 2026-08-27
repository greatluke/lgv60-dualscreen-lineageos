#!/usr/bin/env bash
# Build the DS2 Taskbar launch fix: pull Trebuchet off the phone, patch its dex, repack, sign,
# and wrap it in a Magisk module that shadows /system_ext/priv-app/Launcher3QuickStep.
#
# Trebuchet is signed with its own key (6a1bd8a4), not the platform cert, and declares no
# sharedUserId -- its privileged permissions come from the allowlist by package name, so
# re-signing costs it nothing. That is what makes this patchable at all; SystemUI is not.
#
# Needs: adb + root on the phone, smali/baksmali, and Android build-tools on PATH or in BT.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BT="${BT:-$HOME/Softwares/Android/sdk/build-tools/36.1.0}"
OUT="${OUT:-$HERE/build}"
KS="${KS:-$OUT/ds2launchfix.jks}"
KSPASS="${KSPASS:-ds2l3key}"
APK_PATH=/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk

mkdir -p "$OUT"
cd "$OUT"

if [ -s Launcher3.orig.apk ] && [ -z "${REFRESH_STOCK:-}" ]; then
    echo "==> using cached Launcher3.orig.apk (set REFRESH_STOCK=1 to re-pull)"
else
    # Pulling while our own module is active would re-patch an already-patched apk -- it lives
    # at this same install path, shadowing the original. Disable it (and any other module that
    # touches this path) and reboot before the first pull; after that this is cached.
    echo "==> pulling stock Trebuchet"
    adb shell "su -c 'cat $APK_PATH'" > Launcher3.orig.apk
    [ -s Launcher3.orig.apk ] || { echo "empty pull -- is the phone rooted and connected?" >&2; exit 1; }
fi

echo "==> disassembling classes.dex and classes2.dex"
rm -rf smali1 smali2 classes.dex classes2.dex
unzip -qo Launcher3.orig.apk classes.dex classes2.dex
baksmali d classes.dex -o smali1
baksmali d classes2.dex -o smali2

echo "==> patching"
python3 "$HERE/patch_smali.py" smali1 smali2

echo "==> reassembling"
smali a -a 36 smali1 -o classes.dex
smali a -a 36 smali2 -o classes2.dex

echo "==> repacking"
# Every entry in the stock APK is STORED so ART can mmap the dex directly; -Z store keeps that.
rm -f work.apk aligned.apk Launcher3QuickStep.apk
cp Launcher3.orig.apk work.apk
zip -q -Z store work.apk classes.dex classes2.dex
zip -q -d work.apk 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/MANIFEST.MF' >/dev/null 2>&1 || true
"$BT/zipalign" -p -f 4 work.apk aligned.apk

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KSPASS" -keypass "$KSPASS" \
    -alias l3 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ds2-launcher3"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --min-sdk-version 29 --v1-signing-enabled true --v2-signing-enabled true \
    --v3-signing-enabled true --out Launcher3QuickStep.apk aligned.apk
"$BT/apksigner" verify Launcher3QuickStep.apk

echo "==> building Magisk module"
MOD=module/system/system_ext/priv-app/Launcher3QuickStep
rm -rf module && mkdir -p "$MOD/oat"
cp Launcher3QuickStep.apk "$MOD/"
# The stock odex was built for the unpatched dex; shadow the directory so it is not considered.
touch "$MOD/oat/.replace"
cp "$HERE/module.prop" module/
cp "$HERE/post-fs-data.sh" module/
chmod 755 module/post-fs-data.sh
(cd module && zip -qr ../v60_ds2_launchfix.zip . -x '.*')

echo
echo "built $OUT/v60_ds2_launchfix.zip"
echo "install:  adb push $OUT/v60_ds2_launchfix.zip /data/local/tmp/ &&"
echo "          adb shell su -c 'magisk --install-module /data/local/tmp/v60_ds2_launchfix.zip' && adb reboot"
echo "rollback: adb shell su -c 'rm -rf /data/adb/modules/v60_ds2_launchfix' && adb reboot"
