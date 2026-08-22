# Kernel patches

These are **optional** and separate from the Magisk module. The module works on a completely
stock LineageOS kernel; these patches fix a kernel-side bug that the module cannot reach from
userspace.

## `0001-lge_ds3-retry-ds_dp_config-on-ENODEV.patch`

Fixes the intermittent-attach failure described in the main README: the DS2 sometimes never
enumerates, the log fills with `hub failed to enable device, error -108`, and only a reboot
clears it.

It is a genuine deadlock, not a flaky cable or a slow device. When the DS2 is already attached at
power-on, `lge_ds3` reaches `DS_Startup` about 4ms before `dp_display_probe()` registers the
DisplayPort USBPD SVID handler. `ds_dp_config()` fails with `-ENODEV`, and its error path tears
the secondary USB host back down 82µs after bringing it up — while the hub is still enumerating.
`hub_quiesce()` then blocks forever, wedging `dwc3_otg_sm_work`, and `dwc3_resume_work` blocks
flushing it. Because `dwc3_wq` is an *ordered* workqueue, every subsequent USB ID event queues
behind the blocked one and never runs.

The patch retries across the registration window instead of tearing the host down. The full
derivation, with timestamps and the blocked-task stacks, is in
[`../docs/dualscreen-attach-flow.md`](../docs/dualscreen-attach-flow.md).

**Status: built and verified to apply cleanly, but not yet confirmed on hardware.** Treat it as
unproven until someone reproduces the before/after.

### Applying

Against the LineageOS `timelm` kernel tree:

```sh
cd /path/to/kernel
patch -p1 < /path/to/kernel/0001-lge_ds3-retry-ds_dp_config-on-ENODEV.patch
```

Then build, repack `boot.img`, Magisk-patch it, and flash.

> When repacking `boot.img` by hand, `mkbootimg` **must** be given `--cmdline` including
> `androidboot.hardware=timelm`. Omitting it produces an image that flashes fine and then fails
> in first-stage init with `ReadDefaultFstab(): failed to find device default fstab`, which looks
> exactly like a kernel bug and is not one.

### Confirming it worked

Boot **with the DS2 attached from power-on** — that is the condition that loses the race; booting
with it detached does not exercise the bug at all.

```sh
# kernel log: dmesg returns empty on these devices, so read /dev/kmsg on-device
adb shell "su -c 'timeout 45 cat /dev/kmsg > /data/local/tmp/kmsg.txt'"
adb pull /data/local/tmp/kmsg.txt
```

Expect to see:

- `ds_dp_config: DP handler not ready, retry N/40 in 50ms`, then a successful config
- **no** `stop_2nd_usb_host` immediately following `start_2nd_usb_host`
- the state machine reaching `DS_Ready` instead of looping `DS_Recovery_*`
- no `hub failed to enable device, error -108`

And no blocked workers:

```sh
adb shell "su -c 'echo 1 > /proc/sys/kernel/sysrq; echo w > /proc/sysrq-trigger'"
```

A healthy system lists no D-state `k_sm_usb` or `dwc3_wq` kworker. Seeing either of those two in
`D` state is the deadlock signature.
