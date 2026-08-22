#!/system/bin/sh
# Detached supervisor for lge_ds2_hal_shim. Launched via setsid from service.sh so that
# nothing here can hold Magisk's boot stage open -- see the note in service.sh.
#
# Starts and keeps alive, in order:
#   1. accessory@1.1    -- accessory detection (hinge/cover events)
#   2. coverdisplay@1.0 -- cover-display control; on stock this is the path that issues
#                          the cover_button write, which is the trigger for ds2_pd/hpd_high
#                          and therefore the DP link
#   3. dualscreen@1.1   -- the HAL that writes ds2_hal_ready, driving DS_USB_Wait -> DS_Ready
#   4. DualScreenBridgeDaemon -- Java framework bridge, registers "dualscreen_ex".
#                          Started last: it binds IDualScreen during construction.
#
# All binaries must run from their real /vendor/bin/hw paths so the vendor linker
# namespace resolves; the module ships a private /vendor/lib64/libusbhost.so because
# LOS's vendor namespace does not expose the /system copy.

MODDIR="${1:?missing MODDIR}"
LOG=/data/local/tmp/ds2_shim.log
BRIDGE_CLASS=com.android.server.display.DualScreenBridgeDaemon
BRIDGE_DEX=$MODDIR/dualscreen-bridge.dex

HAL_ACCESSORY=/vendor/bin/hw/vendor.lge.hardware.accessory@1.1-service
HAL_COVERDISP=/vendor/bin/hw/vendor.lge.hardware.coverdisplay@1.0-service
HAL_DUALSCREEN=/vendor/bin/hw/vendor.lge.hardware.dualscreen@1.1-service

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

# True once the system has started shutting down or rebooting.
#
# This matters: on a graceful shutdown init kills services and waits for them to STAY dead.
# A supervisor that faithfully restarts them holds the shutdown open forever, which showed up
# as "reboot from the power menu never completes" (adb reboot still worked, being more
# forceful). Every restart loop below must therefore stop as soon as shutdown begins.
shutting_down() {
	[ -n "$(getprop sys.shutdown.requested)" ] && return 0
	[ -n "$(getprop sys.powerctl)" ] && return 0
	return 1
}

# Match on the full binary path: each HAL's argv[0] contains it, while this script's argv
# does not, which avoids the classic pgrep -f self-match.
running() { pgrep -f "^$1" > /dev/null 2>&1; }

[ -f "$LOG" ] && [ "$(stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 262144 ] && rm -f "$LOG"
log "=== supervisor start ($(grep '^version=' "$MODDIR/module.prop" | cut -d= -f2)) ==="

# Safe to wait here: we are detached, so boot proceeds independently.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
	sleep 1
	i=$((i + 1))
done
log "sys.boot_completed=$(getprop sys.boot_completed) after ${i}s"

# Let the framework settle past boot_completed before touching hwservicemanager.
sleep 5

# supervise <label> <binary> [args...]
supervise() {
	label="$1"; bin="$2"; shift 2
	if [ ! -x "$bin" ]; then
		log "SKIP $label: $bin missing or not executable"
		return 1
	fi
	(
		fails=0
		while true; do
			if shutting_down; then
				log "$label supervisor exiting: shutdown in progress"
				exit 0
			fi
			if ! running "$bin"; then
				log "starting $label"
				start_ts=$(date +%s)
				"$bin" "$@" >> "$LOG" 2>&1
				run=$(( $(date +%s) - start_ts ))
				if [ "$run" -lt 5 ]; then
					fails=$((fails + 1))
					log "$label exited after ${run}s (rapid failure $fails/5)"
					if [ "$fails" -ge 5 ]; then
						log "giving up on $label after 5 rapid failures"
						exit 1
					fi
					sleep $((fails * 5))
				else
					log "$label exited after ${run}s, restarting"
					fails=0
				fi
			fi
			sleep 5
		done
	) &
}

wait_up() {
	label="$1"; bin="$2"
	n=0
	while [ $n -lt 20 ] && ! running "$bin"; do
		sleep 1
		n=$((n + 1))
	done
	if running "$bin"; then
		log "$label up (pid $(pgrep -f "^$bin" | head -1)) after ${n}s"
	else
		log "WARN: $label not up after ${n}s"
	fi
}

# The dualscreen HAL is the proven-safe one: it boots reliably and is what drives
# DS_USB_Wait -> DS_Ready. Always start it.
supervise "dualscreen HAL"   "$HAL_DUALSCREEN"
wait_up   "dualscreen HAL"   "$HAL_DUALSCREEN"

# accessory + coverdisplay: required, not optional.
#
# These were briefly gated behind a flag file after a boot hang was wrongly attributed to
# them. The hang was actually a malformed VINTF fragment (two <fqname> entries sharing a
# major version, and later <interface><instance> duplicated against <fqname>); with that
# fixed the device boots cleanly with all three HALs running. CoverDisplayPowerBridge binds
# IAccessory + IAccessoryUevent, so gating these would silently disable hinge-driven DS2
# power and the cover-window show/hide.
supervise "accessory HAL"    "$HAL_ACCESSORY"
wait_up   "accessory HAL"    "$HAL_ACCESSORY"
supervise "coverdisplay HAL" "$HAL_COVERDISP"
wait_up   "coverdisplay HAL" "$HAL_COVERDISP"

sleep 3

if [ ! -f "$BRIDGE_DEX" ]; then
	log "WARN: $BRIDGE_DEX missing -- skipping framework bridge (HALs still supervised)"
	wait
	exit 0
fi

(
	fails=0
	while true; do
		if shutting_down; then
			log "bridge supervisor exiting: shutdown in progress"
			exit 0
		fi
		if ! pgrep -f "$BRIDGE_CLASS" > /dev/null 2>&1; then
			log "starting bridge daemon"
			start_ts=$(date +%s)
			CLASSPATH="$BRIDGE_DEX" /system/bin/app_process64 \
				/system/bin "$BRIDGE_CLASS" >> "$LOG" 2>&1
			run=$(( $(date +%s) - start_ts ))
			if [ "$run" -lt 5 ]; then
				fails=$((fails + 1))
				log "bridge exited after ${run}s (rapid failure $fails/5)"
				if [ "$fails" -ge 5 ]; then
					log "giving up on bridge after 5 rapid failures"
					exit 1
				fi
				sleep $((fails * 5))
			else
				log "bridge exited after ${run}s, restarting"
				fails=0
			fi
		fi
		sleep 5
	done
) &

log "all supervisors launched"
wait
