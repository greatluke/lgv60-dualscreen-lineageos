#!/usr/bin/env bash
# Compile the framework bridge and assemble the flashable Magisk module.
#
# Prerequisites:
#   - JDK (javac)
#   - Android SDK build-tools (for d8)          -> set ANDROID_SDK or D8
#   - framework stubs to compile against        -> see FRAMEWORK_JAR below
#   - blobs already extracted                   -> ./tools/extract-blobs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build"
MODULE="$ROOT/module"
ZIP="$ROOT/lge_ds2_hal_shim.zip"

# d8 from Android build-tools.
D8="${D8:-$(ls -d "${ANDROID_SDK:-$HOME/Android/Sdk}"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)}"
[ -x "${D8:-}" ] || { echo "d8 not found. Set D8=/path/to/d8 or ANDROID_SDK=/path/to/sdk" >&2; exit 1; }

# Compiling needs the device's framework classes: SubLcdController and the daemon use hidden
# APIs (ServiceManager, Slog, HwBinder, the vendor HIDL stubs) that android.jar does not carry.
# Convert the device's framework.jar/services.jar to a classes jar (e.g. with enjarify) and
# point FRAMEWORK_JAR at it. android.jar is listed FIRST so android.os.* resolves from there:
# some enjarify output carries classfile versions javac rejects.
FRAMEWORK_JAR="${FRAMEWORK_JAR:-}"
ANDROID_JAR="${ANDROID_JAR:-$(ls -d "${ANDROID_SDK:-$HOME/Android/Sdk}"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1 || true)}"
[ -n "$FRAMEWORK_JAR" ] || { echo "Set FRAMEWORK_JAR to a classes-format framework/services jar (see comment above)" >&2; exit 1; }

# SubLcdController and DualScreenBridgeDaemon's BinderServiceEx also need LG's own
# AIDL-derived interfaces (IDisplayManagerEx, ISubDisplayCallback, IDualScreenSubDisplayCallback,
# ICoverDisplayEnabledCallback, IHBMCallback, IDsAirDisplayStateCallback, and the HIDL base
# classes) that stock framework/services.jar does not carry at all -- LOS never shipped LG's
# dual-screen framework surface. STUBS points at a source tree providing them; only the ones
# actually referenced get compiled in (no collision with this project's own same-named classes,
# since explicit compile targets always win over sourcepath-resolved ones).
STUBS="${STUBS:-}"
[ -n "$STUBS" ] || { echo "Set STUBS=/path/to/dualscreen-port/src (LG's AIDL-derived interface stubs)" >&2; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "== compiling =="
javac -nowarn -cp "$ANDROID_JAR:$FRAMEWORK_JAR" -sourcepath "$ROOT/src:$STUBS" -d "$OUT/classes" \
	$(find "$ROOT/src" -name '*.java')

echo "== dexing =="
# d8 needs one --lib per jar; it does not accept a colon-joined path the way javac's -cp does.
D8_LIBS=()
IFS=':' read -ra _fw_jars <<< "$FRAMEWORK_JAR"
for jar in "${_fw_jars[@]}"; do
	D8_LIBS+=(--lib "$jar")
done
"$D8" "${D8_LIBS[@]}" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/dex/classes.dex" "$MODULE/dualscreen-bridge.dex"

echo "== building the DS2 desktop-experience fix (RRO + patched Trebuchet) =="
# Fully reproducible from source; the Trebuchet dex patch additionally needs a rooted phone
# connected over adb the first time, to pull the stock apk it patches -- see
# app/ds2-launchfix/build.sh. Cached afterward.
"$ROOT/app/ds2-desktopfix-rro/build.sh"
"$ROOT/app/ds2-launchfix/build.sh"
mkdir -p "$MODULE/system/product/overlay" \
	"$MODULE/system/system_ext/priv-app/Launcher3QuickStep/oat"
cp "$ROOT/app/ds2-desktopfix-rro/build/DS2DesktopFix.apk" "$MODULE/system/product/overlay/"
cp "$ROOT/app/ds2-launchfix/build/Launcher3QuickStep.apk" \
	"$MODULE/system/system_ext/priv-app/Launcher3QuickStep/"
# The stock odex was built for the unpatched dex; shadow the directory so it is not considered.
touch "$MODULE/system/system_ext/priv-app/Launcher3QuickStep/oat/.replace"

echo "== sanity checks =="
# An illformed VINTF fragment invalidates the ENTIRE device manifest and hangs boot at the LG
# logo, so validate before packaging. xmllint catches malformed XML; the python check catches
# the semantic trap that xmllint cannot see (see docs).
for f in "$MODULE"/system/vendor/etc/vintf/manifest/*.xml; do
	xmllint --noout "$f"
	python3 - "$f" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for hal in root.findall('hal'):
    kids = [c.tag for c in hal]
    if kids != ['name', 'transport', 'fqname']:
        sys.exit(f"{sys.argv[1]}: <hal> must be exactly name/transport/fqname, got {kids}")
PY
done
sh -n "$MODULE/service.sh"
sh -n "$MODULE/ds2_supervise.sh"
sh -n "$MODULE/post-fs-data.sh"

missing=$(find "$MODULE/system/vendor" -name '*.so' | wc -l)
[ "$missing" -ge 13 ] || { echo "Only $missing libs present -- run ./tools/extract-blobs.sh first" >&2; exit 1; }

# The IDC binds the DS2 digitizer to the external display; without it touch lands on the
# built-in screen and the second screen looks unresponsive.
[ -f "$MODULE/system/vendor/usr/idc/Vendor_1004_Product_637a.idc" ] \
	|| { echo "missing DS2 touchscreen IDC" >&2; exit 1; }

[ -f "$MODULE/system/product/overlay/DS2DesktopFix.apk" ] \
	|| { echo "missing DS2DesktopFix.apk -- the RRO build step above should have produced it" >&2; exit 1; }
[ -f "$MODULE/system/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk" ] \
	|| { echo "missing patched Launcher3QuickStep.apk -- the launchfix build step above should have produced it" >&2; exit 1; }

echo "== packaging =="
rm -f "$ZIP"
(cd "$MODULE" && zip -r -X "$ZIP" . -x '.*') > /dev/null
echo "OK: $ZIP"
