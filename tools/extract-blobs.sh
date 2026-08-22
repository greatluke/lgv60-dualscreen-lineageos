#!/usr/bin/env bash
# Extract the proprietary LG blobs this module needs.
#
# They are NOT redistributed in this repository -- they are LG's copyrighted binaries. You
# must supply them from a device or firmware image you own.
#
# Two sources, in order of convenience:
#
#   1. A mounted stock vendor partition (from a KDZ, a vendor.img, or a backup you made
#      before converting to LineageOS):
#         ./tools/extract-blobs.sh /path/to/mounted/vendor
#
#   2. A device still running stock LG firmware, over adb (needs root):
#         ./tools/extract-blobs.sh --adb [serial]
#
# Note: extracting from a device already running LineageOS will NOT work -- these files only
# exist in LG's stock vendor partition. If you have already converted, use a KDZ.
set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/module/system/vendor"

# 64-bit only: the HAL services this module runs are all 64-bit.
BINS=(
	bin/hw/vendor.lge.hardware.dualscreen@1.1-service
	bin/hw/vendor.lge.hardware.accessory@1.1-service
	bin/hw/vendor.lge.hardware.coverdisplay@1.0-service
)
IMPLS=(
	lib64/hw/vendor.lge.hardware.dualscreen@1.1-impl.so
	lib64/hw/vendor.lge.hardware.accessory@1.1-impl.so
	lib64/hw/vendor.lge.hardware.accessory.uevent@1.2-impl.so
	lib64/hw/vendor.lge.hardware.coverdisplay@1.0-impl.so
)
# Dependency closure, derived with `readelf -d` rather than guessed.
LIBS=(
	lib64/vendor.lge.hardware.dualscreen@1.0.so
	lib64/vendor.lge.hardware.dualscreen@1.1.so
	lib64/vendor.lge.hardware.accessory@1.0.so
	lib64/vendor.lge.hardware.accessory@1.1.so
	lib64/vendor.lge.hardware.accessory.uevent@1.0.so
	lib64/vendor.lge.hardware.accessory.uevent@1.1.so
	lib64/vendor.lge.hardware.accessory.uevent@1.2.so
	lib64/vendor.lge.hardware.coverdisplay@1.0.so
	lib64/vendor.lge.hardware.lpwg@1.0.so
	lib64/vendor.lge.hardware.lpwg@1.1.so
	lib64/vendor.lge.hardware.lpwg@1.2.so
	lib64/vendor.lge.hardware.lpwg@1.3.so
	lib64/vendor.lge.hardware.lpwg@1.4.so
)

ALL=("${BINS[@]}" "${IMPLS[@]}" "${LIBS[@]}")

copy_from_dir() {
	local src="$1" missing=0
	for f in "${ALL[@]}"; do
		if [ -f "$src/$f" ]; then
			mkdir -p "$OUT/$(dirname "$f")"
			cp "$src/$f" "$OUT/$f"
		else
			echo "MISSING: $f" >&2
			missing=$((missing + 1))
		fi
	done
	return $missing
}

pull_from_adb() {
	local serial="${1:-}" args=() missing=0
	[ -n "$serial" ] && args=(-s "$serial")
	for f in "${ALL[@]}"; do
		mkdir -p "$OUT/$(dirname "$f")"
		if ! adb "${args[@]}" shell "su -c 'cat /vendor/$f'" > "$OUT/$f" 2>/dev/null \
			|| [ ! -s "$OUT/$f" ]; then
			echo "MISSING: /vendor/$f" >&2
			rm -f "$OUT/$f"
			missing=$((missing + 1))
		fi
	done
	return $missing
}

if [ "${1:-}" = "--adb" ]; then
	pull_from_adb "${2:-}" || true
elif [ -n "${1:-}" ] && [ -d "$1" ]; then
	copy_from_dir "$1" || true
else
	sed -n '2,20p' "$0"
	exit 1
fi

# libusbhost.so is AOSP (Apache-2.0), not an LG blob, but the module still needs a private
# copy: binaries under /vendor run in a linker namespace that does not expose the /system one
# on LineageOS. Any working copy will do, including from the LineageOS device itself.
if [ ! -f "$OUT/lib64/libusbhost.so" ]; then
	echo
	echo "NOTE: lib64/libusbhost.so not yet present."
	echo "      Grab it from any device running this LineageOS build:"
	echo "        adb pull /system/lib64/libusbhost.so module/system/vendor/lib64/"
fi

echo
echo "Extracted $(find "$OUT" -type f \( -name '*.so' -o -name '*-service' \) | wc -l)/$(( ${#ALL[@]} )) blobs into module/system/vendor/"
echo "Next: ./tools/build.sh"
