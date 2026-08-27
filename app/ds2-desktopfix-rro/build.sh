#!/usr/bin/env bash
# Build DS2DesktopFix.apk: a static RRO overriding two framework bools without the platform key.
#
# config_isDesktopModeSupported=false stops DesktopTasksController from building a freeform desk
# on the DS2 -- this hardware has no android.software.freeform_window_management, and
# createRootTask rejects windowingMode=5 outright, so a desk attempt fails the whole app launch
# instead of falling back to fullscreen. config_isDesktopModeDevOptionSupported=true puts the
# Developer options desktop-mode toggles back, independently of the first resource.
#
# Static overlay (targetPackage="android", isStatic=true, no targetName), same shape as the ROM's
# own com.android.frameworks.overlay.sm8250 -- always enabled, no <overlayable> declaration
# needed on the target resource, and re-signable with any key since RROs aren't checked against
# the target's signature.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BT="${BT:-$HOME/Softwares/Android/sdk/build-tools/36.1.0}"
PLATFORM="${PLATFORM:-$HOME/Softwares/Android/sdk/platforms/android-34/android.jar}"
OUT="${OUT:-$HERE/build}"
KS="${KS:-$HERE/ds2rro.jks}"
KSPASS="${KSPASS:-ds2rro}"

mkdir -p "$OUT"
cd "$OUT"

echo "==> compiling resources"
rm -f res.zip
"$BT/aapt2" compile -o res.zip --dir "$HERE/res"

echo "==> linking"
"$BT/aapt2" link -o unsigned.apk -I "$PLATFORM" --manifest "$HERE/AndroidManifest.xml" res.zip

echo "==> aligning + signing"
"$BT/zipalign" -p -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --out DS2DesktopFix.apk aligned.apk
"$BT/apksigner" verify DS2DesktopFix.apk

echo
echo "built $OUT/DS2DesktopFix.apk"
