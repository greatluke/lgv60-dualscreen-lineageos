# Kernel patches

These are **optional** and separate from the Magisk module. The module works on a completely
stock LineageOS kernel; this patch fixes a kernel-side bug that userspace cannot reach.

## `0001-lge_ds3-retry-ds_dp_config-on-ENODEV.patch`

Fixes the intermittent-attach failure described in the main README: the DS2 sometimes never
enumerates, the log fills with `hub failed to enable device, error -108`, and only a reboot
clears it.

It is a genuine deadlock, not a flaky cable or a slow device. When the DS2 is already attached at
power-on, `lge_ds3` reaches `DS_Startup` a few milliseconds before `dp_display_probe()` registers
the DisplayPort USBPD SVID handler. `ds_dp_config()` fails with `-ENODEV`, and its error path
tears the secondary USB host back down 82µs after bringing it up — while the hub is still
enumerating. `hub_quiesce()` then blocks forever, wedging `dwc3_otg_sm_work`, and
`dwc3_resume_work` blocks flushing it. Because `dwc3_wq` is an *ordered* workqueue, every
subsequent USB ID event queues behind the blocked one and never runs.

The patch retries across the registration window instead of tearing the host down. The full
derivation, with timestamps and the blocked-task stacks, is in
[`../docs/dualscreen-attach-flow.md`](../docs/dualscreen-attach-flow.md).

**Status: validated on hardware.** Tested on an LG V60 ThinQ (`timelm`) running LineageOS across
repeated cold boots with the Dual Screen attached from power-on — the condition that triggers the
bug. Every run reached `DS_Ready` with zero `error -108`, no `DS_Recovery_*` cycling, no blocked
workers, and a complete DisplayPort link.

### Applying

Against the LineageOS `timelm` kernel tree:

```sh
cd /path/to/kernel
patch -p1 < /path/to/0001-lge_ds3-retry-ds_dp_config-on-ENODEV.patch
```

Then build, repack `boot.img`, Magisk-patch it, and flash.

> When repacking `boot.img` by hand, `mkbootimg` **must** be given `--cmdline` including
> `androidboot.hardware=timelm`. Omitting it produces an image that flashes fine and then fails
> in first-stage init with `ReadDefaultFstab(): failed to find device default fstab`, which looks
> exactly like a kernel bug and is not one.

If the phone will not enter fastboot via `adb reboot bootloader` (it doesn't on some V60s), you
can flash the active slot directly from a rooted shell — this is what Magisk's own Direct Install
does. **Back the partition up first:**

```sh
getprop ro.boot.slot_suffix                      # e.g. _b
dd if=/dev/block/bootdevice/by-name/boot_b of=/sdcard/boot_backup.img bs=4M
dd if=/data/adb/magisk/new-boot.img of=/dev/block/bootdevice/by-name/boot_b bs=4M
sync
```

### Confirming it worked

Boot **with the DS2 attached from power-on** — that is the condition that loses the race; booting
with it detached does not exercise the bug at all.

```sh
# dmesg returns empty on these devices, so read /dev/kmsg on-device
adb shell "su -c 'timeout 45 cat /dev/kmsg > /data/local/tmp/kmsg.txt'"
adb pull /data/local/tmp/kmsg.txt
```

Expect:

```text
ds_dp_config: No DP handler found
ds3_sm: DP handler not ready, retry 1/40 in 50ms
ds_dp_config: config:1              <-- succeeds on the retry
EDID read successed, count=1
link training #2 successful
```

with no `hub failed to enable device, error -108`, `DS_Ready` reached rather than `DS_Recovery_*`
cycling, and no blocked workers:

```sh
adb shell "su -c 'echo 1 > /proc/sys/kernel/sysrq; echo w > /proc/sysrq-trigger'"
```

A D-state `k_sm_usb` or `dwc3_wq` kworker is the deadlock signature. Neither should appear.
