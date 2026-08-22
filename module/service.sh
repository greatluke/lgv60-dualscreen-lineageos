#!/system/bin/sh
# lge_ds2_hal_shim -- late_start service launcher.
#
# CRITICAL DESIGN CONSTRAINT (learned the hard way -- v2.0 hung boot at the LG splash):
# this script MUST return immediately and MUST NOT leave children in its process group.
# Magisk's boot stage waits on service.sh and its descendants, so:
#   - blocking here waiting for sys.boot_completed deadlocks (boot can't complete while
#     we hold the stage, so the property we're waiting for never gets set), and
#   - leaving `while true` supervisor loops attached keeps the stage held forever.
# Everything below therefore runs inside a single setsid-detached child, and the main
# script exits within milliseconds.
#
# Why this exists at all instead of the shipped init.rc service definition:
# /vendor/etc/init/*.rc fragments installed through the Magisk overlay are not picked up
# by init on this build (files mount and are readable post-boot, but
# init.svc.vendor.lge-dualscreen-hal-1-1 stays empty and the service never starts).

MODDIR=${0%/*}

# Invoked through sh explicitly: Magisk's module extraction does not preserve the
# executable bit (files land as 0644), so exec'ing the script directly fails.
setsid /system/bin/sh "$MODDIR/ds2_supervise.sh" "$MODDIR" < /dev/null > /dev/null 2>&1 &

exit 0
