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

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "== compiling =="
javac -nowarn -cp "$ANDROID_JAR:$FRAMEWORK_JAR" -d "$OUT/classes" \
	$(find "$ROOT/src" -name '*.java')

echo "== dexing =="
"$D8" --lib "$FRAMEWORK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/dex/classes.dex" "$MODULE/dualscreen-bridge.dex"

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

missing=$(find "$MODULE/system/vendor" -name '*.so' | wc -l)
[ "$missing" -ge 13 ] || { echo "Only $missing libs present -- run ./tools/extract-blobs.sh first" >&2; exit 1; }

# The IDC binds the DS2 digitizer to the external display; without it touch lands on the
# built-in screen and the second screen looks unresponsive.
[ -f "$MODULE/system/vendor/usr/idc/Vendor_1004_Product_637a.idc" ] \
	|| { echo "missing DS2 touchscreen IDC" >&2; exit 1; }

echo "== packaging =="
rm -f "$ZIP"
(cd "$MODULE" && zip -r -X "$ZIP" . -x '.*') > /dev/null
echo "OK: $ZIP"
