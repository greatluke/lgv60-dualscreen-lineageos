# DS2 (Dual Screen) attach/detect flow — vendor.lge.hardware.dualscreen@1.1-service

---

## PROJECT STATUS (updated 2026-08-22, evening) — read before reopening anything

```text
Problem A — DS2 attach reliability (-108) .................. ROOT-CAUSED, fix built, untested
Problem B — DS2 DP link to the main panel .................. SOLVED
Framework / userspace integration .......................... SOLVED and shipping
```

**This block replaces an earlier "frozen / Problem B UNRESOLVED" status. That status was an
artifact of a measurement error, described below. Do not restore it.**

### Problem B — SOLVED

The DP link to the DS2 main panel comes up completely on the daily driver (stock LOS kernel +
`lge_ds2_hal_shim` v0.1): `card0-DP-1: connected`, `extcon6 DP=1`, SINK DPCD read, `EDID read
successed`, `bw_code=10 lane_count=2`, both link-training phases successful, `DP_STATE_ENABLED`.
Android enumerates a 1080x2460@60 external display; mirror and desktop mode both work.

**Why it looked unsolvable for so long.** The *test* phone had an experimental un-inverted
`aux-sel` patch flashed into `techpack/display/msm/dp/dp_power.c`. Every AUX measurement taken on
that phone after that flash used the wrong polarity, producing a permanent, perfectly
reproducible `DP_AUX_ERR_TOUT` that was mistaken for a deep unsolved defect. The source was
reverted at 16:11 but the phone was still running a kernel built at 15:44, so the confound
survived invisibly. It announced itself in the log the whole time:

```text
ds3 connected. RE-DEBUG: testing un-inverted aux-sel=1
```

**Lesson worth keeping:** when a failure is 100% reproducible on one device and absent on
another, check what is actually *flashed* on each — compare `/proc/version` build time against
the source revert time — before theorising about the failure itself.

### Problem A — root-caused (dwc3 workqueue deadlock)

Symptom: DS2 intermittently fails to enumerate, `hub failed to enable device, error -108`
repeating, `lge_ds3` looping `DS_USB_Wait -> DS_Recovery_*` forever, and only a reboot clears it.

Proven from live kernel stacks via `echo w > /proc/sysrq-trigger`:

```text
kworker/u16:10 +k_sm_usb   D    kworker/u16:11 +dwc3_wq   D
  dwc3_otg_sm_work                Workqueue: dwc3_wq dwc3_resume_work
  dwc3_otg_start_host             dwc3_resume_work
  dwc3_host_exit                  dwc3_ext_event_notify
  xhci_plat_remove                flush_delayed_work
  usb_remove_hcd                  __flush_work
  usb_disconnect                  wait_for_completion   <-- blocked on the left-hand worker
  usb_disable_device
  hub_disconnect
  hub_quiesce
  flush_delayed_work
  wait_for_completion             <-- blocked
```

Causal chain, all timestamps from one capture:

```text
DS2 attached at power-on -> lge_ds3 reaches DS_Startup at 1.041s
  ds_dp_config(): usbpd_find_dp_handler() == NULL  ("No DP handler found") at 1.041240
  ...because dp_display_probe() only lands at     1.045274   <-- a 4 ms miss
  error path: start_2nd_usb_host(1.041164) then stop_2nd_usb_host(1.041246), 82us apart
  dwc3 sees host:1 then host:0 -> host ON at 1.041361, xhci registers 1.096, host OFF 1.099259
  xhci teardown runs while the hub is still enumerating the DS2
  hub_quiesce() blocks forever -> sm_work wedged
  dwc3_resume_work blocks in flush_delayed_work(&mdwc->sm_work) at 1.108273 and never returns
  dwc3_wq is alloc_ordered_workqueue(..., 0) -> max_active 1 -> everything behind it never runs
  all later host:1/host:0 events are accepted by the notifier and silently never processed
  every attach thereafter fails -108, until reboot
```

Note the notifier's `host:%ld (id:%d) event received` print sits *after* its
`if (mdwc->id_state == id) return` guard, so each of those lines proves `queue_work()` really was
called — the work simply never ran.

**Corrected earlier claim.** A previous model held that `dwc3_otg_start_host()` was never called
and xHCI stayed halted. That is wrong: `start_host` is called exactly twice (on, then off) and
xhci is alive and retrying. The failure is a deadlock, not a missing call. `UsbService host` and
`dualscreen@1.1-service` also end up in D state behind it, which is the "phone seems to hang"
symptom.

**Fix (built, not yet validated on hardware):** `drivers/usb/misc/lge_ds3.c` now treats `-ENODEV`
from `ds_dp_config()` as "DP handler not up yet" and re-kicks the state machine
(`DS_DP_CONFIG_RETRY_MS` 50ms, `DS_DP_CONFIG_MAX_RETRY` 40 = 2s budget against a ~4ms race)
instead of running the teardown path. Any other error still takes the original path.

**Validation still owed:** boot with the DS2 attached from power-on and confirm (a) the
`DP handler not ready, retry N/40` line appears, (b) `ds_dp_config` then succeeds, (c) no
`stop_2nd_usb_host` immediately follows `start_2nd_usb_host`, (d) `DS_Ready` is reached, and
(e) `sysrq-w` shows no D-state `k_sm_usb`/`dwc3_wq` workers.

### Reading the kernel log on these devices

`dmesg` returns empty (not a permissions problem — `dmesg_restrict` is 0). Piping `/dev/kmsg`
straight over adb truncates after a fraction of a second of boot. What works:

```sh
adb shell "su -c 'timeout 45 cat /dev/kmsg > /data/local/tmp/kmsg_full.txt'"
adb pull /data/local/tmp/kmsg_full.txt
```

**Framework port shipped**: `CoverDisplayPowerBridge` (in `dualscreen-bridge.dex`, started by
`DualScreenBridgeDaemon`) is a working LOS port of Stock's `CoverDisplayPowerManagerService`. It
binds `IAccessory` + `IAccessoryUevent`, observes accessory uevents, and drives
`setCoverDisplayButtonStatus()` automatically. Verified on device over two hinge cycles:

```text
smart cover (hinge) state changed: 1 (closed) -> setCoverDisplayButtonStatus(false, true)
smart cover (hinge) state changed: 0 (open)   -> setCoverDisplayButtonStatus(true, true)
AT%HPD (ending open) = On
```

Observer types come from `AccessoryType`: **0 = SMARTCOVER (hinge fold/unfold)**, 5 = COVERDISPLAY
(DS2 attach/detach), 6 = DD_LT_STATUS, 8 = COVER_RECOVERY. Stock observes 5/6/8 only and gets the
hinge via a separate `SmartCoverService` feeding its display power policy; since LOS has no
equivalent, this port also observes type 0 directly — that is what makes the hinge actually drive
DS2 power here. Hinge polarity was determined empirically on-device (closed reports `1`, open
reports `0`), so the DS2 powers up when the case is unfolded.

**Regression tool**: `com.test.CoverButton` reproduces the framework's critical action
(`IAccessory.setCoverDisplayButtonStatus(true, true)` → `cover_button` → `ds2_pd`/HPD) in one
command, deterministically, without physically cycling the hinge.

---

Reverse-engineered from `vendor.lge.hardware.dualscreen@1.1-service` (aarch64, HIDL) using
`radare2` (`aaa` + string xrefs via `izz`/`axt`) and the `r2ghidra` decompiler (`pdg`).
Binary and raw disassembly: `vendor.lge.hardware.dualscreen@1.1-service`, `dualscreen.disasm`,
`dualscreen.rodata` (this directory).

Java-side consumer: `framework/services-src/sources/com/android/server/display/SubLcdController.java`.

## TL;DR

Three independent channels exist in the HAL. Only one of them drives display attach/detach.
The kernel `LGE_DS2` uevent — the original prime suspect — is **not** it.

| Channel | Trigger | Purpose |
|---|---|---|
| USB hotplug (libusbhost) | VID:PID `1004:637a` enumerates | logs "DualScreen Attached", sets a coarse presence flag |
| **`/dev` inotify watcher** | `hidraw*` / `ttyACM*` node create/delete | **the real attach/detach state machine** |
| Kernel `LGE_DS2` uevent socket | `SUBSYSTEM=LGE_DS2` uevents | pen wake-gestures + HID AES-mode switch, unrelated to attach/detach |

The hard blocker for LineageOS is a replacement `IDualScreen` HIDL provider implementing the
inotify-driven state machine below — not the LGE_DS2 uevent channel, and not the Android-side
`SubLcdController`/`LGSubDisplay` consumers, which already exist.

## Proven end-to-end state transition

### Attach

```
USB 1004:637a enumerates
    │
    ▼  (libusbhost hotplug cb)
fcn.000098e0
    log "========== DualScreen Attached", strdup(devpath) → *0x1a398, set presence flag
    │
    ▼
kernel creates /dev/hidraw*, /dev/ttyACM*
    │
    ▼  (inotify thread watching "/dev", IN_CREATE|IN_DELETE)
fcn.00009c48
    basename prefix "hidraw" → confirm via sysfs:
      /sys/class/hidraw/%s/device/../../idVendor  == 0x1004 (LG)
      /sys/class/hidraw/%s/device/../../idProduct == 0x637a (DS2)
    │
    ▼
fcn.0000c4fc  hidDisplayDeviceAdded(hal)
    mutex-guarded: mHidDisplayAttached (hal+0xc7c) = 1
    │
    ▼
fcn.0000bf7c  getDisplayInfo
    open(hidraw_path, O_NONBLOCK)
    ioctl(fd, 0xC0074807, &buf)      ← non-standard, see below
    log "HID-DISPLAY: x:%d y:%d format:%d ready:%d"
    │
    ▼
fcn.00014ec0  initSubDisplay
    stash geometry globals, start sub-display worker thread (fcn.00014de8(hal,1) queued)
    call vtable+0x140 on `hal` itself (a self-status refresh, HIDL return_status wrapper)
    │
    ▼
fcn.00014de8(hal, state=1)
    piVar4 = *(hal+0x10)             // IDualScreenSubDisplayCallback registered via
                                      // setSubDisplayCallback()
    *0x1a998 = 1                     // global "current display state", checked later by
                                      // hidDisplayDeviceRemoved before tearing down
    (*(*piVar4 + 0x68))(&ret, piVar4, 1)   // vtable slot 0x68 == notifyStateChanged(int)
    │
    ▼
IDualScreenSubDisplayCallback::notifyStateChanged(1)
    │
    ▼  (Java, SubLcdController.java)
mDualScreenSubDisplayCallback.notifyStateChanged(state)
    → ISubDisplayCallback.onSubDisplayCallback(1)
    → LGSubDisplay.onCoverAttached()
```

### Detach — exact inverse

```
/dev inotify: IN_DELETE on the current hidraw node basename
    │
    ▼
fcn.0000c408  hidDisplayDeviceRemoved(hal)
    mHidDisplayAttached (hal+0xc7c) = 0
    read *0x1a998 (was display actually up?) → if nonzero:
        │
        ▼
    deinitSubDisplay:
        fcn.00014de8(hal, state=0)
            (*(*piVar4 + 0x68))(&ret, piVar4, 0)   // notifyStateChanged(0)
        set destroyThread flags, pthread_cond_signal, pthread_join the worker thread
        │
        ▼
    IDualScreenSubDisplayCallback::notifyStateChanged(0)
        → SubLcdController → LGSubDisplay.onCoverDettached()
```

## The ioctl finding

Raw disassembly of `fcn.0000bf7c` (not the decompiler's decimal guess):

```asm
mov  w1, 0x4807
movk w1, 0xc007, lsl 16      ; w1 = 0xC0074807
mov  w8, 2
strb w8, [x19]               ; seed buf[0] = 2 before the call
bl   ioctl                   ; ioctl(fd, 0xC0074807, &buf)
```

Decoded (`_IOC` layout: dir@31:30, size@29:16, type@15:8, nr@7:0):

```
dir  = 3   (READ|WRITE)
type = 'H'
nr   = 0x07
size = 7
```

This is **not** the standard `HIDIOCGRAWINFO` (`_IOR('H',0x03,8)` = `0x80084803`, size 8,
read-only). `0xC0074807` is a proprietary `_IOWR('H',0x07,<7 bytes>)` — almost certainly an
LG kernel HID-driver patch specific to the DS2, feeding the `x/y/format/ready` values logged
immediately after the call.

**Open question / next verification step:** does this ioctl work on the LOS-booted kernel?
Since this is built from the stock V60 kernel, it likely already has the patch, but this needs
confirming on-device:

1. On **stock**, with DS2 attached, identify the `hidraw` node for `1004:637a`
   (`/sys/class/hidraw/*/device/../../idVendor|idProduct`) and issue `ioctl(fd, 0xC0074807, buf)`
   directly (e.g. via a small test binary or `python`/`ioctl` from a shell) — confirm it returns
   sane x/y/format/ready values.
2. Repeat the same node enumeration + ioctl on **LOS**. If the node exists and the ioctl
   succeeds, this is purely a userspace-HAL-resurrection problem. If the ioctl fails
   (`ENOTTY`/`EINVAL`), part of the stock kernel HID patch is missing from the LOS kernel and
   needs porting first.

## The ttyACM (serial/AT) channel — configuration, not attach/detect

```
fcn.0000bc0c  (ttyACM create)
    open(ttyACM_path, O_RDWR|O_NOCTTY), apply cached termios
    mSerialAttached (hal+0x878) = 1
    fcn.0000a8d0(hal, "ATE0\r", ...)              // disable local echo
    if ro.product.device == "L-52A":              // NTT Docomo LG V60 ThinQ — skip for
        fcn.0000a8d0(hal, "AT%MODEL=DCM\r", ...)  //   a normal (non-Docomo) V60
    fcn.0001487c(hal)
    fcn.0000971c(hal)                              // write "1" → ds2_hal_ready (see below)
    fcn.0000b89c(hal, 1)
    vtable+0x188 call on hal                       // status/refresh, no notifyStateChanged

fcn.0000bad4  (ttyACM delete) — mirror image
    mSerialAttached = 0
    vtable+0x98 call, fcn.000149fc(hal), fcn.0000b89c(hal, 0)
```

No `notifyStateChanged()` call anywhere on this path. Treat it as accessory configuration/branding,
not part of the display attach state machine.

`fcn.0000971c` (also called standalone at HAL init) is a one-shot readiness handshake:

```c
fd = open("/sys/class/dualscreen/ds2/ds2_hal_ready", O_WRONLY);
write(fd, "1", 1);
close(fd);
```

## The `LGE_DS2` kernel uevent channel — NOT attach/detect

`fcn.00010b0c` (`uevent_kernel_multicast_recv` loop) matches `SUBSYSTEM=LGE_DS2` uevents, then
looks for a second token whose value is matched by `fcn.00010788` against literal action strings:

```
WAKEUP            → 0
DS_PEN_WAKEUP     → 1
DS_PEN_WAKEUP_BTN → 2
DS_PEN_DETECTION  → 3
SWITCH_AES_BOTH   → 4
SWITCH_AES_TO_1   → 5
SWITCH_AES_TO_2   → 6
DS_UPDATE_STATE   → 7
```

Dispatched to `IDualScreenLpwgCallback` vtable slots (+0x68/+0x70/+0x178/+0x180/+0x188).
Actions 5/6 additionally write `"0"`/`"2"` to `/sys/module/usbhid/parameters/aes_mode`
(via the sysfs-write helper `fcn.0000e650`, a `std::ofstream`-based writer).

This is the pen digitizer wake-gesture and HID-AES-mode-switch transport — safe to defer for a
minimal bring-up; not required for basic display attach/detect.

## Recovery/DFU mode (informational, same `fcn.000098e0` as USB hotplug)

VID:PID `0483:df11` (STM32 DFU bootloader) is also handled in the USB hotplug callback, gated on
`usb_device_get_product_name()` matching `LMV515N` / `LMV600N` / `LMG905N` and serial
`00000000001A` (normal) / `00000000001B` (error, triggers `doRecoveryUpgrade`). Not relevant to
normal attach; useful context if the DS2 is ever seen stuck in this mode.

## HIDL surface actually exercised by the Android side

From `SubLcdController.java` — this is the method set a minimal replacement HAL needs to
implement; `LGSubDisplay`/`SubLcdController` themselves do **not** need to be reimplemented:

```
setSubDisplayCallback(IDualScreenSubDisplayCallback)
notifyStateChanged(int state)                 → callback, not a call the HAL exposes
getSubDisplayPowerState()
setSubDisplayPowerState(...)
setSubDisplayBrightness(...)
getSubDisplayInfo(...)
drawSubDisplay(...)
getCoverDisplayCutout()
```

## Function catalog (this binary)

| addr | name (assigned) | role |
|---|---|---|
| `0x000098e0` | usbHotplugCallback | libusbhost VID:PID match → "DualScreen Attached" / DFU-recovery path |
| `0x0000971c` | dsHalReadySignal | write `1` → `/sys/class/dualscreen/ds2/ds2_hal_ready` |
| `0x00009c48` | devInotifyThread | `inotify` on `/dev`, dispatches hidraw/ttyACM create+delete |
| `0x0000bc0c` | ttyAcmAdded | open+termios, `ATE0`, optional `AT%MODEL=DCM`, ready signal, status refresh |
| `0x0000bad4` | ttyAcmRemoved | clear `mSerialAttached`, status refresh |
| `0x0000bf7c` | hidGetDisplayInfo | open hidraw, `ioctl(0xC0074807)` → x/y/format/ready |
| `0x0000c4fc` | hidDisplayDeviceAdded | set `mHidDisplayAttached`, call `hidGetDisplayInfo` + `initSubDisplay` |
| `0x0000c408` | hidDisplayDeviceRemoved | clear `mHidDisplayAttached`, `deinitSubDisplay`, join worker thread |
| `0x00014ec0` | initSubDisplay | start worker thread, `notifyStateChanged(1)` |
| `0x00014de8` | notifyStateChangedBridge | calls registered `IDualScreenSubDisplayCallback` vtable+0x68 |
| `0x00010b0c` | ueventListener | `SUBSYSTEM=LGE_DS2` uevent parser (pen/AES channel, not attach) |
| `0x00010788` | ueventActionParse | action-string → int (`WAKEUP`, `DS_PEN_*`, `SWITCH_AES_*`, `DS_UPDATE_STATE`) |
| `0x0000e650` | sysfsWrite | `ofstream`-based single-value sysfs writer |

## On-device verification (stock, LMV600TM0c831041, DS2 physically attached)

Confirmed live with a small freestanding aarch64 probe (`open`+`ioctl`+`write`, no libc,
built with `clang -target aarch64-linux-gnu -nostdlib -static -fuse-ld=lld`, run as root via
`adb shell su -c`), against `/dev/hidraw0` (verified `1004:637a` via
`/sys/class/hidraw/hidraw0/device/../../idVendor|idProduct`):

```
path=/dev/hidraw0
open fd=3
ioctl(0xC0074807) ret=7
buf=02 00 01 40 00 03 01 00 00 00 00 00 00 00 00 00
```

Stable across repeated runs. **`0xC0074807` works on the stock kernel and returns a
non-zero, structured 7-byte reply** — resolving open question #1 from the previous pass.

Byte layout (7 = declared ioctl size, matches exactly):

```
buf[0]    u8  mode   = 2      (echoes the seed byte the HAL writes before the call)
buf[1:3]  u16 x (LE) = 0x0100 = 256
buf[3:5]  u16 y (LE) = 0x0040 = 64
buf[5]    u8  format = 3
buf[6]    u8  ready  = 1
```

`u8/u16` fields explain the small total size while still matching the HAL's
`"x:%d y:%d format:%d ready:%d"` log (char/short promote to `int` in a variadic call, so `%d`
doesn't imply 4-byte struct members). 256×64 is far too small to be the main DS2 OLED panel —
plausibly this hidraw interface (or this particular `mode=2` query) reports the small outer
**cover-window** strip rather than the full secondary panel; worth re-querying with other seed
values in `buf[0]` and cross-checking against `vendor.lge.hardware.coverdisplay@1.0-service`,
which is a separate HAL in this same directory.

### Bonus finding: a standard `extcon` device already carries the attach signal

`dmesg` on attach shows the kernel driver (`lge_usb_ds3`) firing a standard extcon notifier,
*not* going through the custom `LGE_DS2` uevent HAL path at all:

```
usb 1-1: New USB device found, idVendor=1004, idProduct=637a
lge_usb_ds3: ds3_usb_notify: USB_DEVICE_ADD: idVendor:1004 idProduct:637a
prm_log: ds state is changed from 0 to 2.(ds1(0), ds2(1), hallic(0))
[Display][lge-cover-ctrl:dd_extcon_hpd_notifier:65] DS2 CONNECTED
dualscreen@1.1-: ds2_hal_ready_store: ready:1, recovery:0     ← matches fcn.0000971c exactly
```

And live on-device, with DS2 attached:

```
/sys/class/extcon/extcon5  name: vendor:lge_usb_ds3       state: USB-HOST=1
/sys/class/extcon/extcon6  name: ae90000.qcom,dp_display  state: DP=0 DualDisplay=0 DS2=1
/sys/class/extcon/extcon7  name: ae90000.qcom,dp_display  state: DP=0 DualDisplay=0 DS2=0
/sys/class/extcon/extcon8  name: ae90000.qcom,dp_display  state: DP=0 DualDisplay=0 DS2=0
```

`extcon` is a generic Linux subsystem: the kernel core itself fires a `uevent` with
`NAME=`/`STATE=` on every cable-state change, and the `DS2=` key is plain sysfs text —
**no vendor-specific uevent parsing (and none of the custom `LGE_DS2` netlink-socket
machinery in `fcn.00010b0c`) is needed just to detect attach/detach.** This is a materially
simpler primitive for LOS to build on than replicating the HAL's USB-hotplug +
`/dev`-inotify + sysfs-idVendor chain.

`/sys/class/dualscreen/ds2/ds2_pd` read `0` (`hpd_high:0` per dmesg) even while attached —
likely tracks the physical case open/closed (hinge) state separately from USB-level presence,
not yet confirmed.

## On-device verification, LOS (LMV600TMff3529c1, `lineage_timelm`, DS2 attached)

Same probe (`/data/local/tmp/ds2ioctl`), same DS2 unit, run as root via magisk `su`:

```
path=/dev/hidraw0
open fd=3
ioctl(0xC0074807) ret=7
buf=02 00 01 40 00 03 01 00 00 00 00 00 00 00 00 00
```

**Byte-for-byte identical to the stock result.** Also identical on LOS: `hidraw0` at
`1004:637a`, `/dev/ttyACM0`, `/sys/class/extcon/extcon6` reporting `DS2=1` live while attached,
and the full `/sys/class/dualscreen/ds2/{ds2_hal_ready,ds2_pd,ds2_recovery,uevent}` tree from
the `lge_usb_ds3` kernel driver.

The one and only difference: `ps -A | grep dualscreen` is **empty** on LOS — no
`vendor.lge.hardware.dualscreen@1.1-service` (or any replacement) is running, versus stock's
`pid 1211`.

### Conclusion

**Every kernel-side dependency the stock HAL uses is already present and functionally
identical in the LOS kernel** — the custom HID ioctl, the `extcon` DS2 notifier, and the
`lge_usb_ds3` sysfs tree all need zero porting work. This is a pure userspace problem: nothing
is running to call `hidDisplayDeviceAdded` → `initSubDisplay` → `notifyStateChanged`, so
`SubLcdController`/`LGSubDisplay` never hear about the attach. The path forward is a
replacement `vendor.lge.hardware.dualscreen@1.1` HIDL service (stock binary run directly, or a
clean-room reimplementation) driving exactly the sequence documented above — no kernel changes
required.

## The rest of the HIDL surface — decompiled

| Method | fcn | Behavior |
|---|---|---|
| `setSubDisplayCallback` | `0x00014ce8` | mutex-guarded `sp<>` store/swap at `hal+0x10` (the `IDualScreenSubDisplayCallback` — exactly what `fcn.00014de8`/notifyStateChanged reads) |
| `getDS2Status` | `0x000145e4` | reads/computes a boolean via `fcn.00012ca4` into a global, returns via async HIDL callback |
| `setSubDisplayBrightness` | `0x00015344` | rejects if `*0x1a998` state == 0 ("not initialized"); rejects `value >= 0x100`; else `fcn.0000c090(hal, 0, state!=2, value)` |
| `getCoverDisplayCutout` | `0x000140c8` | sends AT command `"AT%GETDSCUTOUT\r"` over `/dev/ttyACM0`, caches the text response, returns as `hidl_string` |
| `setSubDisplayPowerState` | `0x0001569c` | full state machine, see below |
| `drawSubDisplay` | `0x00015fdc` | writes into a double-buffered 4bpp framebuffer, see below |

### `fcn.0000c090` — `hidDisplaySetConfig`, the shared power/brightness ioctl

Confirmed by its own embedded log string. Third custom ioctl on the same `hidraw0` node used
by `hidDisplayGetInfo`, opened `O_WRONLY|O_NONBLOCK` (`0x801`) this time:

```
0xC0034806  =  _IOWR('H', 0x06, 3)     ; dir=RW type='H' nr=0x06 size=3
```

3-byte payload, built directly on the stack (raw disasm, not decompiler guesswork — `size=3`
matches the construction exactly):

```c
buf[0] = 3;                              // constant command-type tag
buf[1] = (arg3 & 1 ? 2 : 0) | (arg2 & 1); // packed flag byte
buf[2] = arg4 & 0xff;                     // raw value (brightness 0-255, or a power-transition default)
ioctl(hidraw_fd, 0xC0034806, buf);
```

Both `setSubDisplayPowerState` and `setSubDisplayBrightness` call this **same function** —
`fcn.0000c090(hal, 0, powerOrNotOff, value)`. No AT-command channel involved for power/brightness
at all; it's purely this ioctl.

### `setSubDisplayPowerState` — the `*0x1a998` state enum, fully resolved

```
0 = detached / uninitialized             (hidDisplayDeviceRemoved → deinitSubDisplay)
1 = attached, hidraw present, no explicit power call yet   (initSubDisplay: fcn.00014de8(hal,1))
2 = explicitly powered OFF               (setSubDisplayPowerState(false): fcn.00014de8(hal,2))
3 = explicitly powered ON                (setSubDisplayPowerState(true):  fcn.00014de8(hal,3))
```

`setSubDisplayPowerState(enable)`:
1. Guards against concurrent transitions (`*0x1aa20` in-progress flag).
2. Rejects if state==0 ("not initialized yet"), or no-ops if the requested state already
   matches current ON/OFF (`(state==3) XOR enable`).
3. Calls `fcn.0000c090(hal, 0, enable, enable ? 0x5a : 0)` — turning on sends value `0x5a`
   (90, a default brightness applied on power-up unless a later `setSubDisplayBrightness`
   overrides it).
4. On success: turning **off** tears down the draw worker thread (same condvar/join dance as
   `deinitSubDisplay`); turning **on** spawns it (`pthread_create(..., fcn.00015bb8)`, not yet
   decompiled — this is presumably the buffer-flush loop that actually writes frames to
   hardware).
5. `usleep(500000)` (panel settle time), then `fcn.00014de8(hal, enable?3:2)` →
   `notifyStateChanged(2 or 3)`.

Note `drawSubDisplay` only accepts state ∈ {1, 3} (`(state|2)==2` rejects 0 and 2) — i.e. the
panel is drawable both right after attach (state 1, before any explicit power call) and once
explicitly powered on (state 3), but not once explicitly powered off (state 2) or detached (0).

### `drawSubDisplay` — reveals the actual target panel: NOT the main secondary OLED

The pixel-packing loop (`x & 0xf0` / `(x ^ (x^y>>4)) & 0xf` pairs, two source pixels compressed
per output byte) is a **4-bit-per-pixel packer**. Output buffer size = `width * height / 2`,
where `width`/`height` (`*0x1a990`/`*0x1a992`) are populated directly from the
`hidDisplayGetInfo` ioctl's `x`/`y` fields — the same **256 × 64** measured live on both stock
and LOS.

That is far too small to be the real secondary OLED (LG DS2's actual second panel is a full
phone-sized display). **`drawSubDisplay()`/`getCoverDisplayCutout()` target the small
monochrome/4bpp outer *cover-window* indicator strip, not the main panel.** The main panel is
almost certainly brought up through Android's standard DRM/HWC multi-display pipeline, driven
by the `DP=`/`DualDisplay=` keys sitting right next to `DS2=` on the same `extcon6` device —
a mechanism entirely separate from this HIDL service.

Draw path uses double-buffering: buffer index `*0x1aa24` (0 or 1), each buffer is a 0x40-byte
metadata struct at `0x1a9a0 + idx*0x40`, state machine values {1,2,3,4} guarded by per-buffer
mutexes, `fcn.00016934(idx, state)` transitions buffer state, `pthread_cond_signal(0x1a958)`
wakes the consumer (the `fcn.00015bb8` worker thread started by `setSubDisplayPowerState(true)`)
once a buffer is ready to flush to hardware.

## Scope implication for a minimal LOS replacement

This settles the "tiny shim vs. reproduce more of LG's stack" question from the previous pass:

- **Attach/detach + enabling the real secondary screen**: needs only watching `extcon6`'s
  `DS2=` key and calling `notifyStateChanged()` — the kernel does the rest, proven identical
  on LOS already. The big panel is very likely handled entirely by the standard Android
  multi-display pipeline once the kernel-side `DP`/`DualDisplay` extcon keys go live (untested —
  would need the case physically open during a stock-device check, since our attach test above
  measured `DP=0 DualDisplay=0` alongside `DS2=1`, i.e. only USB/HID-level attach, not
  necessarily "case open" state).
- **`hidDisplayGetInfo` (`0xC0074807`) / `hidDisplaySetConfig` (`0xC0034806`)**: two small,
  now fully-decoded ioctls on `/dev/hidraw0` — trivial to reimplement standalone.
- **`drawSubDisplay`/`getCoverDisplayCutout`/the buffer-flush worker thread**: only needed for
  the small 256×64 cover-window indicator strip — a separate, deferrable feature, not required
  to get the main secondary screen usable.

A first bring-up shim can plausibly skip the AT-command cutout query and the draw worker thread
entirely and still get the main use case (secondary screen turns on and is usable) working.

## Case-open test, LOS (case physically open, DS2 attached) — hypothesis DISPROVEN

`extcon6` did **not** change from the case-closed reading: still `DP=0 DualDisplay=0 DS2=1`.
`/sys/class/dualscreen/ds2/ds2_pd` still reads `0`. Confirmed independently at the DRM level,
not just extcon:

```
/sys/class/drm/card0-DP-1/status  →  disconnected
```

**So the earlier hypothesis ("main panel comes up for free via the kernel once the case is
open") is wrong.** The kernel's DP controller genuinely does not see a sink — this isn't a
userspace/extcon reporting quirk. Something else has to actively trigger DP link-up; it is not
purely a function of hall-sensor state at the kernel level, or that trigger isn't firing on
this bench setup.

### `lge_ds3` module has a hall-sensor override — tried, no effect

`/sys/module/lge_ds3/parameters/force_set_hallic` (module `lge_ds3` — same driver as
`lge_usb_ds3` seen in dmesg strings, likely a build-name mismatch, not two drivers) is a
writable boolean debug hook, presumably meant to fake the case-open hall-sensor signal for QA
without needing the physical magnet aligned. Root-writable (`rw-r--r-- root root`).

Tested: wrote `Y`, waited, re-read all three signals (`extcon6`, `ds2_pd`, DRM status) —
**no change**. Reverted to `N` (original state) afterward; device left clean.

Also present but permission-denied even as root: `/sys/devices/virtual/panel_cover/cover_ctrl/cover_ds3_invert_hallic`
(SELinux-gated, presumably to a vendor domain the HAL itself runs in — another sign this state
is meant to be touched by the HAL process, not by hand).

**Inconclusive, not negative-confirmed** — two caveats:
1. `dmesg` returned **0 lines** on this LOS build even as root (`dmesg | wc -l` → `0`), vs.
   full kernel log access on stock. Likely `dmesg_restrict`/logging-disabled in this build. This
   means the force-hallic write could have produced kernel-side log output we simply couldn't
   see — the sysfs-state check is real, but we have no visibility into *why* nothing changed.
2. The parameter may only be sampled at the next hallic IRQ/USB re-enumeration event rather than
   applied live to already-attached state — an unplug/replug of the DS2 while
   `force_set_hallic=Y` is set was not tried (didn't want to disrupt the live session without
   asking first).

## Same test repeated on stock (case open, HAL running, pid 1211) — resolves the question

```
extcon6:  DP=0 DualDisplay=0 DS2=1        (identical to LOS)
ds2_pd:   0                                (identical to LOS)
DRM DP-1: disconnected                     (identical to LOS)
dumpsys display: only displayId=0, "Built-in Screen" 1080x2460  (identical to LOS)
```

**Stock does not auto-enable the second panel from case-open + USB attach either, even with the
full HAL running.** This resolves the case-open test as a non-issue for LOS parity: the earlier
"no reaction" result wasn't a missing-kernel-feature or missing-HAL problem — it's simply not
how DP link-up gets triggered on this hardware in the first place. Whatever the real trigger is
(most plausibly a user-facing "enable Dual Screen" toggle in LG's DSManager app, gated behind
app/HAL logic well beyond simple attach detection — not investigated further here), it behaves
identically on both platforms under passive conditions.

**Net result of the entire on-device verification pass: no evidence of any LOS kernel
deficiency anywhere.** Every attach-driven signal checked (`hidraw` ioctls x2, `extcon DS2=`,
the full `/sys/class/dualscreen/ds2` tree) is proven byte-identical between stock and LOS. The
one signal that didn't move (`DP=`) didn't move on stock under the same passive conditions
either, so it was never a parity gap.

## The exact HIDL transaction surface `SubLcdController` requires — pinned down from source

Read in full: `com/android/server/display/SubLcdController.java` (335 lines, the only caller),
`vendor/lge/hardware/dualscreen/{V1_0,V1_1}/IDualScreen.java` (recovered HIDL proxy/stub source,
gives exact `transact()` codes). Confirmed by grep across the whole `framework/services-src`
tree: **`SubLcdController` is the only caller of `IDualScreen` methods anywhere in the
framework**, and it calls exactly six of them:

| transact # | Method | Direction | Called from | When |
|---|---|---|---|---|
| 32 | `setSubDisplayCallback(IDualScreenSubDisplayCallback)` | oneway | `connectToIDualScreenProxy()` | once, on HAL connect |
| 27 | `setSubDisplayBrightness(int) → int` | sync | `setSubDisplayBrightnessImpl` | on brightness change, **and unconditionally right after every power-on** |
| 28 | `setSubDisplayPowerState(boolean) → int` | sync | `setSubDisplayPowerStateImpl` | on power state change |
| 31 | `getSubDisplayPowerState(cb) → (int, int state)` | sync | `getSubDisplayPowerState()` | polled (e.g. at boot) |
| 29 | `getSubDisplayInfo(cb) → (int, SubScreenInfo{width,height,format})` | sync | `getSubDisplayInfo(outInfo)` | on demand |
| 30 | `drawSubDisplay(ArrayList<Integer>) → int` | sync | `drawSubDisplay(pixels)` | per frame |
| — | `IDualScreenSubDisplayCallback.notifyStateChanged(int)` | **HAL → framework**, oneway | implemented by `SubLcdController` | attach/detach/power transitions |

Everything else `IDualScreen` exposes (`getDS2Status`, `getDS2Connect`, `getCoverDisplayCutout`,
`sendAtCommand`, `setLpwgCallback`, AES/touch-perf, firmware-upgrade methods, V1.1's
`ds_update_state`/`setAesMode`/`manageAesMode`/`set_touch_perf`) is dead weight for this specific
consumer — not called by `SubLcdController`, hence not required for a minimal shim targeting it.
`DEFAULT_SUB_LCD_BRIGHTNESS = 90` in `SubLcdController.java` is the source of the `0x5a` value
found hardcoded in the HAL's power-on path — same constant, flowing straight through from Java
down into the `hidDisplaySetConfig` ioctl payload.

### `SubLcdController`'s caller: `DisplayManagerServiceEx`, not `LocalDisplayAdapter`

`grep -rln SubLcdController` across the framework tree hits `DisplayManagerService.java` (owns
`mSubLcdController = new SubLcdController(mContext)`), `DisplayManagerServiceEx.java` (the actual
caller of all six methods above, as pure 1:1 pass-throughs — no extra logic), and
`DisplayPowerController(Ex).java` (field declared, `LocalDisplayAdapter` passed into the
constructor alongside it). **`LocalDisplayAdapter` is a constructor parameter sitting next to
`SubLcdController` but never calls into it or is called by it** — confirming the two are
parallel, unrelated systems: `LocalDisplayAdapter` drives the real DRM/HWC-backed displays,
`SubLcdController`/`IDualScreen` drives only the small HIDL-managed cover-window surface.

## Minimal shim: final scope (corrected)

All six methods `SubLcdController` calls get real implementations — none stubbed. Two of them
(`getSubDisplayPowerState`, `drawSubDisplay`) sit outside the *attach-notification* proof but
are still required for a *usable* SubDisplay implementation, since `SubLcdController` calls both
unconditionally (boot-time poll, and every `LGSubDisplay` pixel flush respectively):

```
setSubDisplayCallback()        → store sp<>, exactly per fcn.00014ce8
getSubDisplayInfo()             → ioctl(hidraw_fd, 0xC0074807, &buf); parse into SubScreenInfo
setSubDisplayPowerState(bool)   → ioctl(hidraw_fd, 0xC0034806, {3, enable?2:0, enable?90:0})
setSubDisplayBrightness(int)    → ioctl(hidraw_fd, 0xC0034806, {3, (state!=2 ? 2:0), value})   // per fcn.00015344: arg2=0,arg3=(state!=2)
getSubDisplayPowerState()       → return cached last-known state (from own setSubDisplayPowerState calls)
drawSubDisplay(vector<int32_t>) → 4bpp-pack into the 256x64 buffer, write to hidraw (exact write path = fcn.00015bb8, not yet decompiled)

DS2 watcher (own thread, not tied to a specific extcon index):
  scan /sys/class/extcon/*/name for the value "vendor:lge_usb_ds3"
  poll (or inotify+poll(2) on) its state file for the DS2= key
  on 0→1: notifyStateChanged(1)
  on 1→0: notifyStateChanged(0)
```

Explicitly deferred, confirmed unused by `SubLcdController` (not required for this consumer):
`getDS2Status`, `getDS2Connect`, `getCoverDisplayCutout`, `sendAtCommand`, `setLpwgCallback`,
V1.1's `setAesMode`/`manageAesMode`/`ds_update_state`/`set_touch_perf`, all firmware-upgrade
methods.

### Three distinct state layers — do not conflate

```
USB/DS2 attachment      → notifyStateChanged(1/0), driven by extcon DS2=
Sub-display power       → setSubDisplayPowerState(true/false), driven by hidraw ioctl 0xC0034806
Main Android display    → DRM/HWC logical display creation — proven NOT triggered by either of
                           the above (case-open test showed DP=0/disconnected on both stock and
                           LOS even with the real HAL running)
```

### Two well-bounded, separate milestones going forward

1. **Shim parity test**: does the replacement HAL reproduce stock `SubLcdController` behavior
   end-to-end when DS2 is attached — callback delivery, `getSubDisplayInfo` returning real
   256×64/format/ready, power/brightness round-tripping? This is fully specified by everything
   above and needs no further disassembly to attempt.
2. **DP-trigger question** (separate, unresolved): what userspace action flips DRM `DP-1` from
   `disconnected` to `connected`? Out of scope for milestone 1; revisit only once the shim is
   proven and the goal shifts to the main secondary panel specifically.

## Live test: stock HAL binary via Magisk module — SUCCEEDS at the native layer

Built a minimal Magisk module (`lge_ds2_hal_shim`, ~1.5MB, 11 files) overlaying:
- the stock `vendor.lge.hardware.dualscreen@1.1-service` binary
- its full proven runtime dependency closure (9 vendor libs: `dualscreen@{1.0,1.1}`,
  `accessory@{1.0,1.1}`, `lpwg@{1.0,1.1,1.2,1.3}`, plus `libusbhost.so` — see below)
- the stock `init.rc` service definition, unmodified
- a **new VINTF manifest fragment** at
  `/vendor/etc/vintf/manifest/vendor.lge.hardware.dualscreen@1.1-service.xml`

No changes to `/vendor/etc/vintf/manifest.xml` itself — this device's build already uses the
fragment-directory mechanism actively (30 existing per-HAL fragment files present at
`/vendor/etc/vintf/manifest/`), confirmed by pulling an existing `vendor.lge.hardware.*`
fragment as an exact schema template. This is the smallest possible delta; the fragment file
uses the identical schema convention already in use on this build.

### Round 1: registration fails on cross-namespace linking, not SELinux or VINTF

Installed, rebooted (clean boot, no bootloop). Module mounted correctly (`ls` on the real
`/vendor` paths post-boot showed the binary and manifest fragment present). Manually starting
the binary from `/data/local/tmp` (ad-hoc linker namespace) still worked exactly as in the
pre-module test. But starting it from its **real** installed path,
`/vendor/bin/hw/vendor.lge.hardware.dualscreen@1.1-service`, failed:

```
CANNOT LINK EXECUTABLE ".../vendor.lge.hardware.dualscreen@1.1-service":
library "libusbhost.so" not found: needed by main executable
```

This is a Treble **vendor linker namespace** issue, not a missing file — `libusbhost.so` exists
at `/system/lib64/libusbhost.so` on LOS, but binaries executing from `/vendor/*` are confined to
a vendor linker namespace that only sees an allowlisted set of system libs (VNDK/LLNDK), and
`libusbhost.so` isn't part of that set on LOS's build. Stock firmware's own linker config
allowlists it; LOS's doesn't. **The correct fix — and how a real vendor image ships it — is a
private copy of `libusbhost.so` inside `/vendor/lib64/`**, not cross-namespace linking. Pulled
LOS's own `/system/lib64/libusbhost.so` (any working copy suffices, it's a generic AOSP lib) into
the module, repackaged, reinstalled, rebooted again — clean boot, no bootloop.

Also confirmed independently in this round: `vintf` (the on-device manifest inspection tool) now
lists `DM vendor.lge.hardware.dualscreen@1.1::IDualScreen/default` in the device manifest —
**the fragment merge itself worked correctly** even before the linker issue was fixed.

### Round 2: full success

```
lshal:
DM    Y vendor.lge.hardware.dualscreen@1.0::IDualScreen/default   <pid>
DM    Y vendor.lge.hardware.dualscreen@1.1::IDualScreen/default   <pid>
```

Manually starting the binary from its real `/vendor/bin/hw/` path now **registers cleanly with
`hwservicemanager`**, exactly matching the manifest declaration. This is full, definitive proof:
**the stock DS2 HAL runs correctly against the LOS kernel, completely unmodified, driven by a
10-file Magisk overlay** (binary + 9 libs + init.rc, unchanged, + one new manifest fragment).
No sepolicy.rule was needed at any point — the binary ran fine under the domain Magisk's overlay
files inherit by default; the earlier concern about needing custom SELinux domain/hwservice
rules turned out to be unnecessary for getting this far.

Two items left open from this round:
1. `init` does not auto-start the service (`init.svc.vendor.lge-dualscreen-hal-1-1` prop stays
   empty even after the fix) — a known class of Magisk timing issue: `/vendor`'s own
   `init.rc` scripts can be parsed before Magisk's early-boot overlay mount is in place for that
   specific directory, on some system-as-root layouts. Currently worked around by starting the
   service manually after boot; not yet root-caused or fixed.
2. **No Java-side consumer exists on LOS at all** — see below, this is the more significant
   finding.

### The Java framework consumer is completely absent from LOS — not stripped, never compiled in

Checked for `SubLcdController`/`DisplayManagerServiceEx`/any DS2-related class in LOS's actual
running framework:

```
strings /system/framework/services.jar        | grep -c SubLcdController  →  0
strings /system/framework/oat/arm64/services.odex | grep -c SubLcdController  →  0
strings /system/framework/services.jar        | grep -i dualscreen        →  (nothing)
```

Confirmed independently by behavior: manually starting the now-correctly-registering HAL
produced **zero** log activity from `SubLcdController` (no connect attempt, no failure, nothing)
— not a timing artifact, a genuine absence. **`SubLcdController`, `DisplayManagerServiceEx`, and
the entire Java-side bridge documented earlier in this file do not exist in LOS's framework at
all.** This makes sense in retrospect: LOS's `services.jar` is built fresh from AOSP+Lineage
source, not derived from LG's modified/decompiled framework the way the kernel and vendor blobs
were — OEM Java framework patches don't carry over via device/vendor tree the way kernel drivers
and HAL binaries do.

### Revised scope: two independent halves, only one of which is done

```
Native HAL (vendor.lge.hardware.dualscreen@1.1)
    ✅ PROVEN WORKING on LOS via the Magisk module above — registers, runs, drives real
       hardware, byte-identical HID-DISPLAY output to the manual ioctl probe.

Java framework consumer (SubLcdController + DisplayManagerServiceEx + whatever AIDL surface
LGSubDisplay/LGDSManager call, e.g. IDisplayManagerEx)
    ❌ ENTIRELY ABSENT from LOS. Not a Magisk-module-sized problem — this is
       frameworks/base (services.jar) source, meaning a real port needs these classes
       reintroduced into LOS's framework source tree and system.img rebuilt from source,
       not a live-device overlay experiment.
```

The original plan ("does the stock HAL register and does `SubLcdController` receive
`notifyStateChanged`") is now half-answered with full confidence (yes, and — untestable, no
consumer exists) rather than blocked on an unknown. The path to an actual working dual-screen
experience on LOS runs through porting the framework Java classes, not further native-side or
live-device work.

## Phase 2 scope: framework porting (source-level, not live-device)

Source-level comparison of stock's decompiled framework (`stock/framework/`) against LOS's own
decompiled `services.jar`/`framework.jar` (`los/framework/` — already present locally, no fresh
decompile needed).

### Confirmed missing from LOS's `framework.jar` entirely (client-facing AIDL interfaces)

```
IDisplayManagerEx.java            — the app-facing binder interface DisplayManagerServiceEx implements
ISubDisplayCallback.java          — the callback interface SubLcdController implements
CoverDisplayManagerInternal.java
ICoverDisplayEnabledCallback.java
IDsAirDisplayStateCallback.java
IHBMCallback.java
SecondaryDisplayManagerInternal.java
```

Present in both, unchanged in kind: `DisplayManagerGlobal`, `DisplayManagerInternal`,
`DisplayManager`, `IDisplayManagerCallback`, `IDisplayManager` — standard AOSP core, LOS's
copies are simply newer-AOSP-version variants.

### `IDisplayManagerEx` — 37 methods total, only ~7 relevant to the proven minimal path

Read in full (`stock/framework/framework-src/sources/android/hardware/display/IDisplayManagerEx.java`,
standard `aidl`-generated Stub/Proxy, transact codes 1-37). Relevant subset (matches the six
`IDualScreen` HIDL methods already fully specified, plus registration):

```
registerSubDisplayCallback(String, ISubDisplayCallback)   #26
unregisterSubDisplayCallback(String)                       #27
getSubDisplayInfo(SubDisplayInfo)                           #21
drawSubDisplay(int[])                                       #22
setSubDisplayPowerState(boolean)                             #23
setSubDisplayBrightness(int)                                 #24
getSubDisplayPowerState()                                    #25
```

The other ~30 methods (`IsHBMState`, `setBlackMode`, `setColorFadeLevelWithNoPrepare`,
`setBacklightDimmingWhenLowBattery`, `getALCRate`, `getWideScreenMode`, `requestForceMirrorMode`,
all the `CoverDisplay`/`DsAirDisplay`/`HBM` callback registration methods, etc.) belong to
entirely separate LG display features unrelated to DS2 — high-brightness-mode, backlight
dimming policy, color fade, wide-screen mode, force-mirror. **Not required for a minimal DS2
port**; a first-pass `IDisplayManagerEx` implementation can stub these (return
false/0/no-op, matching the `Default` inner class stock itself ships as the fallback
implementation for exactly this purpose).

### `DisplayManagerServiceEx` — clean subclass pattern, but against an incompatible base version

```java
public class DisplayManagerServiceEx extends DisplayManagerService {
    protected final class LocalServiceEx extends DisplayManagerService.LocalService { ... }
    protected class BinderServiceEx extends DisplayManagerService.BinderService {
        class ExtendedBinderInternal extends IDisplayManagerEx.Stub { ... }
    }
}
```

Architecturally this is a clean, additive OEM subclass — not surgery on `DisplayManagerService`
itself. But **LOS's `DisplayManagerService.java` is 5580 lines vs. stock's 3509** — a
substantially newer/different AOSP base (LOS tracks current AOSP; stock is frozen at its
original Android 11/HIDL-era release). `DisplayManagerServiceEx` was written against stock's
older internal API surface (protected methods/fields/constructor signatures it overrides). It
will **not** compile unmodified against LOS's newer `DisplayManagerService` — real adaptation
work is needed, not a drop-in port. Same caution applies to `BinderServiceEx`/`LocalServiceEx`
inner-class overrides.

### `LocalDisplayAdapter` — a second, independent `IDualScreen`/`IDisplayManagerEx` consumer

Not previously known — found via source diff, not native RE. Stock's copy (1583 lines) differs
substantially from LOS's (1537 lines); the diff is **not** generic AOSP drift, it's real DS2
content, entirely absent from LOS. Mechanism (see `LocalDisplayDevice` constructor, ~line 316):

```
LocalDisplayAdapter enumerates a second physical DRM display (standard AOSP hotplug path,
present in LOS too — this part is NOT LG-specific)
    → gated by Features2.multi_display() feature flag
    → getDisplayCableStatus(true)
         → IDisplayManagerEx.getDisplayCableStatus()
         → DisplayManagerServiceEx
         → mPowerManagerEx.getDisplayCableStatus()      ← a DIFFERENT LG service, not IDualScreen
    → if result ∈ {2,3}: mIsCoverDisplayDevice = true, named "Built-in Cover-Screen"
    → IDualScreen.getCoverDisplayCutout()                ← same AT-command path already traced
         → shapes the cutout of this new LogicalDisplay
```

Important distinction: this explains what stock does **after** DRM reports a connected second
display — it does **not** explain why `DP-1` stayed `disconnected` in the earlier physical
case-open test on stock (that remains a kernel/link-training-level question, untouched by any of
this Java code, which only reacts to what DRM already reports). What it does establish: a
**second missing LG service dependency** (`PowerManagerEx`, referenced as `mPowerManagerEx`)
beyond `IDualScreen`/`SubLcdController`, and that the DS2 "cover" classification is wired into
the *generic* multi-display hotplug path via a feature flag, not a special-cased trigger.

### Revised porting shape

```
Minimal, tractable slice (proven native path, one clean callback chain):
    IDualScreen (native, ✅ proven) → SubLcdController → ISubDisplayCallback → LGSubDisplay
    Needs: ISubDisplayCallback.java + IDisplayManagerEx.java (stub ~30 unrelated methods)
           + IDisplayManagerEx.Stub subset wired into a DisplayManagerServiceEx adapted to
           LOS's current DisplayManagerService + SubLcdController.java (already read in full,
           335 lines, unchanged from what's documented above)

Separate, larger, NOT required for the minimal slice:
    LocalDisplayAdapter's cover-display classification (needs mPowerManagerEx, a second LG
    service not yet investigated at all)
    The ~30 unrelated IDisplayManagerEx methods (HBM/backlight-dimming/color-fade/wide-screen/
    force-mirror — separate LG display features)
    The still-unresolved DP-1 kernel-level link-training trigger (orthogonal to all of the above)
```

### `DisplayManagerService` construction/startup: exact insertion points, stock vs. LOS

Direct line-level comparison, both files read/grepped in full for these sections.

**`onStart()` hook — confirmed correct, one mechanical ABI fix needed.** Stock's
`DisplayManagerServiceEx.onStart()` (line 131) overrides the base and does:
```java
publishBinderService("display", new BinderServiceEx(), true);
publishLocalService(DisplayManagerInternal.class, new LocalServiceEx());
```
LOS's own `onStart()` (line 506) calls a **4-arg** `publishBinderService(String, IBinder, boolean, int)`
— LOS's `SystemService` base added a trailing priority param that stock's 3-arg call predates.
This is the one unavoidable, purely mechanical adaptation at the hook point:
```java
publishBinderService("display", new BinderServiceEx(), true, 1);   // match LOS's own trailing arg
publishLocalService(DisplayManagerInternal.class, new LocalServiceEx());
```

**Constructor: no adaptation needed.** LOS added an `Injector`-pattern overload
(`DisplayManagerService(Context, Injector)`) but kept `DisplayManagerService(Context context) {
this(context, new Injector()); }` as a delegating single-arg constructor. Stock's
`DisplayManagerServiceEx(Context context) throws XmlPullParserException, IOException` calls
`super(context)`, which still resolves correctly on LOS unmodified.

**`mSubLcdController` is a field on the base `DisplayManagerService` class itself** (stock line
166, `protected SubLcdController mSubLcdController;`), not injected purely via the `Ex`
subclass — LG's patch modifies the base class directly here, not just additively. Instantiated
inside `createMultiDisplayPowerController()` (stock ~line 2340), itself called only during
**primary display** (`displayId==0`) power-controller setup, gated by
`DisplayManagerHelper.isMultiDisplayDevice()`. Confirms: `SubLcdController` is created early/
eagerly at boot alongside the main display — not reactively when DS2 connects — matching
everything traced natively (the HAL registers early too; `SubLcdController` just waits on the
HIDL service-notification + `notifyStateChanged` callback afterward).

**`DisplayManagerHelper`** (`com.lge.display`, in `framework.jar`) is small (~425 lines) and
cleanly separable. Backed by `com.lge.config.Features2`: `Features2.multi_display()` (bool) +
`Features2.multi_display_type()` (string, `"cover"` vs `"swivel"`). The `"swivel"` branch pulls
in `SmartCoverManager`/`PostureManager` for a **different** LG device family (rotating-cover
phones) — irrelevant to V60/DS2, which is exclusively the `"cover"`/type-0 path. For a minimal
port, `isMultiDisplayDevice()`/`getMultiDisplayType()` can be trivially reimplemented (hardcode
`true`/`"cover"`, or back with one system property) without porting `Features2`,
`SmartCoverManager`, or `PostureManager` at all.

## Visually confirmed on real hardware: the full chain works end-to-end

Built a standalone Java HIDL test client (`com.test.Main`, in the port tree) using the same
compiled `vendor.lge.hardware.dualscreen.V1_0.IDualScreen` Java bindings from the framework port
work above. Run via `app_process64` — no `system_server`/`services.jar` integration needed, no
device modification, just a direct HIDL client call against the already-running HAL:

```
IDualScreen hal = IDualScreen.getService(true);
hal.getSubDisplayInfo(...)               → err=0, width=256 height=64 format=3  (matches the
                                            manual ioctl probe from the very start of this
                                            investigation, byte-for-byte)
hal.setSubDisplayPowerState(false)        → result=0
hal.setSubDisplayPowerState(true)         → result=0
hal.setSubDisplayBrightness(255)          → result=0
```

**User visually confirmed the physical DS2 display responded** to the power-off → power-on →
brightness-255 sequence.

This closes the loop on the entire session: raw ARM64 disassembly of an unknown ioctl constant →
decoded byte-level protocol → verified against real hardware with a hand-written syscall-only
probe → full HIDL transaction surface mapped from decompiled Java sources → a fresh Java client
compiled against reconstructed LOS-internal stub classes → calling that exact ioctl, through the
real native HAL, and visibly changing the state of a physical secondary display. Every layer in
between was independently verified against ground truth at least once.

## Full Java framework port: written, auto-starts at boot, visually confirmed on real hardware

Following the native-HAL-only proof above, the complete minimal Java framework port was written,
compiled against LOS's real internal classes (via `enjarify`-converted `framework.jar`/
`services.jar` stubs — see "Phase 2 scope" above for the compile toolchain), and wired into boot.

### Architecture, as actually deployed (two pivots from the original plan)

1. **`DisplayManagerService` is `final` on LOS** — the clean subclass pattern
   (`DisplayManagerServiceEx extends DisplayManagerService`) stock used is not reachable at all.
   Pivoted to composition: a standalone daemon process (`DualScreenBridgeDaemon`, plain
   `public static void main`) that constructs `SubLcdController` directly and registers its
   `IDisplayManagerEx` binder via `ServiceManager.addService("dualscreen_ex", ...)` — a general
   IPC primitive, not exclusive to `system_server`. Runs via `app_process64`, exactly like the
   native HAL itself.
2. **`/vendor/etc/init/*.rc` fragments added via the Magisk module are not picked up by `init`**
   on this LOS build (confirmed for both the native HAL's own `.rc` and a new one written for the
   daemon — files mount correctly, content verified post-boot, but `init.svc.*` stays empty and
   the service never starts; root cause not identified). Pivoted to Magisk's own `service.sh`
   mechanism (a script at the module root, auto-executed by Magisk itself at the `late_start`
   service boot stage on every boot) to launch both the native HAL and the daemon, sidestepping
   `init.rc` parsing entirely.

### Verified end-to-end after a clean, unattended reboot

```
service.sh (Magisk, late_start, every boot)
    → vendor.lge.hardware.dualscreen@1.1-service (native HAL)      registers, lshal: Y
    → DualScreenBridgeDaemon (app_process64)                       registers "dualscreen_ex"
         → SubLcdController → IDualScreen (native HIDL, real hardware)
```

A separate standalone test client (`com.test.BridgeClient`, `ServiceManager.getService
("dualscreen_ex")` → `IDisplayManagerEx` → calls) confirmed the full round trip with zero manual
process launches required:

```
getSubDisplayInfo: ok=true width=256 height=64 format=3
setSubDisplayPowerState(false/true) = true
setSubDisplayBrightness(255) called
```

logcat during these calls showed the **full bidirectional chain**, not just outbound success:

```
SET SubLcd Power state = false
mReceivedSubDisplayState state : 2     ← notifyStateChanged() called BACK from the native HAL
SET SubLcd Power state = true
mReceivedSubDisplayState state : 3     ← confirms the round trip, not just a queued message
Set SubLcd brightness = 255
```

### The power/brightness-alone puzzle, resolved

Power and brightness toggles alone produced no visible change on the LOS-daemon path, unlike the
earlier stock test (`fcn.0000c090`/`Main.java` direct-HAL calls). Root cause, confirmed by the
above logs: `SubLcdController.setSubDisplayPowerState`/`setSubDisplayBrightness` are
**async** (`Handler.sendMessage`, return before the native call runs) — every call up to and
including the successful `notifyStateChanged` round trip genuinely executed; what was missing
was **content**. Power/brightness only control the backlight of whatever's already in the
panel's framebuffer; nothing had ever called `drawSubDisplay()` on this daemon, so the panel was
illuminating a blank frame — visually indistinguishable from off. Stock's continuously-visible
result is almost certainly because the real `LGSubDisplay`/DSManager stack keeps drawing content
(clock/icon/idle pattern) to that panel continuously.

**Confirmed fix: calling `drawSubDisplay()` with a solid-fill 256×64 buffer (`0xFFFFFFFF`)
through the full daemon chain produced a visible result on the physical cover window**, with the
case closed. This is the final, complete proof of the entire investigation: raw ioctl bytes
found in disassembly at the very start of the session → decoded → verified with a hand-written
probe → full Java framework port written from decompiled sources and reconstructed
internal-API stubs → wired into boot → visually confirmed driving real pixels on real hardware.

## DP trigger, revisited: connection-quality theory disproven, real cause found — different kernels

The earlier working theory ("DP=0 was probably just a marginal connection, not a missing
software trigger") is **wrong**, corrected by a controlled A/B test tonight: with the exact same
physical connection (verified firm, case open) in the same session —

```
LOS:   DP=0  DualDisplay=0  DS2=1   |  DRM card0-DP-1: disconnected
stock: DP=1  DualDisplay=0  DS2=1   |  DRM card0-DP-1: connected   |  displayId=1 present
```

Swapping the identical cable/DS2/phone-in-case setup between devices in the same sitting
produced `DP=1` on stock and `DP=0` on LOS, immediately and reproducibly each time. Connection
quality is not the variable — the device is.

**Root cause: LOS runs a genuinely different kernel build, not a repackaged copy of stock's.**

```
stock: Linux 4.19.157-perf+   built by jenkins@MCSBS9R16 (LG's own build farm), March 2023
LOS:   Linux 4.19.325-cip133-st17-perf   built on a googleplex-android CI container, Aug 2026
```

Different kernel version, different toolchain (LG's clang-10 NDK build vs LOS's clang-21/LLD
21 AOSP toolchain build), different build pipeline entirely — LOS compiles its own kernel from
source (a CIP/GKI-derived tree), it does not reuse stock's prebuilt kernel binary. This is
consistent with, and explains, everything else found tonight: the `lge_usb_ds3` **kernel
module** (evidently a prebuilt vendor `.ko` blob, loaded identically on both) and all the
userspace/vendor blobs are byte-identical and behave identically — but DisplayPort alt-mode link
training lives deeper in the kernel's own USB-C/DRM driver stack (PHY lane configuration,
`drivers/usb/typec`, the DRM DP driver), code that **is** rebuilt from LOS's own kernel source
and evidently missing whatever config/patch stock's kernel carries for DP alt-mode negotiation
on this SoC.

This closes out the question left open in the earlier session: it is not a missing userspace
trigger (nothing in `SubLcdController`/`DisplayManagerServiceEx`/`LocalDisplayAdapter` needs to
run for DP to come up — vanilla AOSP `LocalDisplayAdapter` would pick up a real DRM hotplug
automatically), and it is not connection quality. It is a genuine LOS kernel-build gap.

### Kconfig diff: ruled out as the direct cause, but genuinely narrowed the search

Pulled `/proc/config.gz` from both devices (both expose it) and diffed. **Zero differences** in
every `TYPEC`/`DP_`/`DRM_MSM`/`EXTCON`/`DISPLAYPORT`/`PHY_QCOM`/`ALTMODE`-matching config symbol
— both kernels enable the identical set of driver support for this hardware. 1045 total config
lines differ overall, but nothing in this area; the two closest candidates
(`CONFIG_QCOM_FSA4480_I2C`, enabled only on LOS — an SBU-pin analog switch chip relevant to DP
AUX routing) and `CONFIG_LGE_DUAL_SCREEN` (checked specifically after finding it gates
`techpack/display/msm/lge/cover/lge_cover_ctrl.c` — the exact file containing the
`dd_extcon_hpd_notifier` function named in stock's own dmesg output) are both **identical
between the two** (`=y` on both) or don't explain the runtime difference in the direction
observed. Kconfig is not where this bug lives.

### Live kernel Type-C state, both devices, same physical connection: the gap is VDM/altmode discovery specifically

```
                          stock              LOS
port0-partner exists?     yes                yes
power_operation_mode      3.0A               3.0A     (real PD power contract, negotiated)
identity/id_header         (not checked)      0x00000000
identity/product            (not checked)      0x00000000
supports_usb_power_delivery (not checked)      no
DP altmode subdir under
  port0-partner?          (not checked)      absent
```

Basic USB Type-C **partner detection and power-contract negotiation work identically** on both
(the `port0-partner` node exists, real negotiated current). But on LOS, the VDM
(Vendor-Defined-Message) **identity/alternate-mode discovery sequence — Discover Identity →
Discover SVIDs → Discover Modes → Enter DP Mode — never completes**: `identity/id_header` and
`identity/product` are all-zero, `supports_usb_power_delivery` reads `no` at the partner level
despite real power negotiation having occurred at the port level, and no DP-altmode subdirectory
exists under `port0-partner` at all. This is a materially more specific, more actionable finding
than "DP doesn't come up": **the fault is isolated to the VDM/alternate-mode discovery layer**,
one level above basic Type-C/PD partner detection (which works) and one level below the
LG-specific cover-display glue code (`lge_cover_ctrl.c`, confirmed compiled into both kernels
identically, so not itself the cause — it simply never gets triggered because the VDM layer
beneath it never signals DP-mode entry).

The actual driver responsible sits in `drivers/usb/pd/qpnp-pdphy.c` (Qualcomm's PMIC-integrated
PD-PHY driver — this board uses `pm8150b...usb-pdphy`, not the generic upstream
`tcpm.c`/discrete-TCPC-chip stack that also happens to exist in-tree) and/or
`techpack/display/msm/dp/dp_usbpd.c` (the USB-PD-to-DisplayPort glue). Not yet root-caused
further — candidates going forward: a genuine source-level regression/missing patch in one of
those two files (LOS rebuilds them from source; stock's binary might carry a fix or vendor
quirk not present upstream), a devicetree property difference (SVID/VDO matching table, an
"accessory-mode" or multi-DP quirk flag) between stock's shipped DTB and whatever LOS's kernel
build produces, or a firmware/microcontroller-side dependency. Would need either instrumented
kernel logging at the VDM exchange (LOS's `dmesg`/`/proc/kmsg` is restricted, complicating this)
or literal source-level comparison against stock's kernel source (which we do not have — only
LOS's kernel source is available locally, at `/home/tmmh/v60-re/kernel`, a
`kernel_lge_sm8250`/`lineage-21.0` checkout; stock's kernel exists only as the prebuilt on-device
binary).

## The DP "negotiation" is not real Type-C VDM at all — it's a proprietary LG simulation

Read stock's `dmesg` directly (stock allows this; LOS does not — see below) for the actual
sequence around DS2 connect. The critical, repeated line, once per SVDM step:

```
usbpd usbpd0: don't send vdm to DS
[drm:dp_usbpd_response_cb][msm-dp-debug] callback -> cmd: USBPD_SVDM_DISCOVER_MODES, ...
usbpd usbpd0: don't send vdm to DS
[drm:dp_usbpd_response_cb] callback -> cmd: USBPD_SVDM_ENTER_MODE, ...
usbpd usbpd0: don't send vdm to DS
[drm:dp_usbpd_response_cb] callback -> cmd: DP_USBPD_VDM_STATUS, ...
usbpd usbpd0: don't send vdm to DS
[drm:dp_usbpd_response_cb] callback -> cmd: DP_USBPD_VDM_CONFIGURE, ...
[drm:dp_display_usbpd_configure_cb][msm-dp-info] default Orientation is CC2 for DS
[drm:dp_display_usbpd_configure_cb] add DP_STATE_CONFIGURED
usbpd usbpd0: set state Dualscreen Connected      ← a non-standard, LG-custom PD state name
```

**The DS2 does not do real USB-PD structured-VDM (SVDM) negotiation.** LG's kernel driver
explicitly skips transmitting the VDM requests over the wire (`"don't send vdm to DS"`, logged
before every single SVDM step) and instead **internally synthesizes the entire DP alt-mode
handshake** — Discover Modes, Enter Mode, VDM Status, VDM Configure — as a software simulation.
This explains why `identity/id_header`/`identity/product` read all-zero even on a fully-working
stock connection: there was never a real Discover Identity VDM sent to get a real answer from.
The whole thing is a proprietary LG mechanism riding on top of the generic Qualcomm `usbpd`/PD-
PHY driver, gated by a custom state (literally named `Dualscreen_Connected` in the log) — not
standards-based DP alt-mode discovery.

The actual trigger, visible immediately before the fake-VDM sequence begins:

```
lge_usb_ds3 ...: set_hallic_status: typec:0 vbus:0 pd:0 ds3:0 hallic:1 accid:1 usb:0 ...
```

Driven by **`hallic`** (case-open hall sensor) and **`accid`** (an accessory-ID resistor pin on
the dock connector identifying it specifically as a genuine LG DS2) — not by any real Type-C
alternate-mode discovery at all.

### On LOS: confirmed three independent ways that this sequence never fires

1. **`dmesg` is fully restricted on LOS** (0 lines, even as root) — the same restriction found
   earlier in this investigation, confirmed again. Unlike stock, **LOS also does not forward any
   kernel `printk` output into `logcat`** (checked all buffers, all common kernel-log tag
   patterns — a handful of unrelated kernel lines were visible via `logcat` on stock earlier in
   this session, but that bridging mechanism appears absent or configured differently on LOS).
   This closes off both of the two channels used to observe the sequence directly on stock.
2. **`/sys/class/typec/port0/port0-partner/identity/{id_header,product}`**: both `0x00000000`,
   no DP-altmode subdirectory ever appears under `port0-partner` — checked earlier this session.
3. **`/sys/class/usbpd/usbpd0`** (the same node named throughout stock's `dmesg` output),
   checked directly on LOS:
   ```
   contract: implicit
   pdo1..pdo7: 00000000        ← no real PD Source Capabilities ever received
   src_cap_id: 0
   uevent: ... ALT_MODE=0       ← explicit, straight from the driver's own uevent
   ```
   All three interfaces agree independently: on LOS, `usbpd0` never progresses past a bare
   implicit Type-C default connection. Also notable: `current_dr: ufp` / `current_pr: sink`
   (phone is in peripheral/sink *PD* role on this port) even though the phone is simultaneously
   acting as genuine USB *host* for HID/serial (hidraw0/ttyACM0 enumerate fine) — plausible for a
   dock accessory with its own power/USB path decoupled from the primary PD-negotiating link,
   worth keeping in mind if this is picked up again.

### Where this leaves the investigation

Everything reachable without either stock's kernel **source** (only the on-device binary is
available; `/home/tmmh/v60-re/kernel` is LOS's own kernel tree, confirmed via
`kernel_lge_sm8250`/`lineage-21.0` git remote) or working kernel-log access on LOS has now been
checked. The fault is conclusively isolated to `lge_usb_ds3`'s `hallic`+`accid` detection (or
whatever gates its call into the fake-VDM sequence) never firing correctly on LOS's kernel
build, despite `CONFIG_LGE_DUAL_SCREEN=y` and all other relevant Kconfig options being identical
to stock. Two remaining avenues, neither attempted yet:
- Check whether `/proc/sys/kernel/dmesg_restrict` (or equivalent) can simply be toggled off via
  the existing root/Magisk access, unlocking full kernel log visibility on LOS for direct
  before/after comparison against the stock sequence above.
- Obtain stock's kernel source (LG is GPL-obligated to publish it for this device) for a literal
  side-by-side diff of `drivers/usb/pd/qpnp-pdphy.c` / the `lge_usb_ds3` driver /
  `techpack/display/msm/dp/dp_usbpd.c` against LOS's copies of the same files.

## Full kernel log access unlocked, and two distinct root causes isolated

### `dmesg` is blocked, but `/dev/kmsg` is not — a real, useful workaround

Web research confirmed the mechanism: modern Android (LineageOS included) gates kernel log
access via **SELinux**, not the classic `dmesg_restrict` sysctl + `CAP_SYSLOG` model — so root
alone doesn't bypass it, since SELinux is mandatory access control independent of Unix DAC/UID.
`getenforce` → `Enforcing`, our shell's domain is `u:r:magisk:s0`. The `dmesg`/`klogctl()` syscall
path is denied for that domain (explaining the silent `0` lines, not an error), but **reading
`/dev/kmsg` directly as a character device is a separate SELinux permission class, and it is not
denied**. `su -c 'timeout N cat /dev/kmsg'` gives full, live kernel log access on LOS — this
should be the standard technique going forward instead of `dmesg`.
[Sources: [Stack Pointer](https://stackpointer.io/unix/linux-restrict-unprivileged-users-from-using-dmesg/635/), [LineageOS engineering docs](https://lineageos.org/engineering/HowTo-Debugging/)]

### Root cause #1: the `hallic` (case-open hall sensor) reading is wrong on LOS — confirmed, and workaroundable

Live `/dev/kmsg` capture with the case genuinely open showed, repeatedly:
```
DS3 cover hallic disconnected
lge_usb_ds3: set_hallic_status: 0
lge_usb_ds3: is_ds_connected: 0
```
The kernel continuously reads the hall sensor as "disconnected" regardless of actual case state.
**Confirmed fixable in-session**: writing `Y` to `/sys/module/lge_ds3/parameters/force_set_hallic`
(the same debug override found in the previous pass, but its effect was invisible back then
because `dmesg`/`kmsg` access wasn't yet unlocked) immediately produces, live:
```
is_ds_connected: 0 → is_ds_connected: 1
hallic_state_notify: SWITCH_NAME=coverdisplay / SWITCH_STATE=1
set_hallic_status: typec:0 vbus:0 pd:0 ds3:0 hallic:1 accid:1 usb:0 ...   ← identical trigger
                                                                              condition to stock
```
This single override does correctly kick off the entire downstream fake-VDM sequence — confirmed
by watching it happen live: `ds_set_state: Unknown -> DS_Startup`, `dp_usbpd_connect_cb`, all
four SVDM steps (`DISCOVER_MODES`/`ENTER_MODE`/`VDM_STATUS`/`VDM_CONFIGURE`), real GPIO writes
(`aux_enable`, `aux_sel`, `usbplug_cc`), `dp_display_host_init [OK]`, and
`usbpd usbpd0: set state Dualscreen Connected` — all matching stock's sequence essentially
line-for-line. **`accid` detection was never the problem** — it read `accid:1` correctly in both
captures; only `hallic` was wrong.

### `hpd_high=0` during the fake-VDM sequence: a false lead, corrected

Initial read of the log flagged `hpd_high=0` throughout the `dp_usbpd_get_status()`
calls (`DP_USBPD_VDM_STATUS`/`DP_USBPD_VDM_CONFIGURE`) as "the blocker," backed by finding a
plausible-looking source line (`status->base.hpd_high = (buf & BIT(7)) ? true : false;` in
`techpack/display/msm/dp/dp_usbpd.c`) and a genuine debug hook
(`dp_usbpd_simulate_connect()`, wired to a debugfs file `/sys/kernel/debug/drm_dp/hpd` via
`dp_debug.c`). **This was wrong** — re-checking, stock's own working capture shows the identical
`hpd_high=0` at this exact same point in the sequence. It's not a differentiator; the fake-VDM
`vdo` status parsing legitimately reports `hpd_high=0` on both devices at this stage, and that's
apparently normal/expected, not a fault. The debugfs path is moot regardless: confirmed
`CONFIG_DEBUG_FS` is `# not set` on **both** kernels identically, so `dp_debug_write_hpd`/
`simulate_connect()` were never reachable on either device — stock's real success has nothing to
do with that debug hook.

### A longer capture, and a correction: the DSI panel activity was misattributed

A longer `/dev/kmsg` capture (60s) around `force_set_hallic=Y` was initially read as showing the
cover panel physically powering on (`dsi_panel_enable`, GPIO resets, `hactive=1080,vactive=2460`
mode-set, backlight ramp). **On closer inspection this conclusion doesn't hold** — the same
`dsi_panel_enable`/backlight-ramp pattern recurs again ~12 minutes later in the same capture with
no relation to the `hallic` write, and the capture also contains `fp_lhbm_store`/
`lge_set_fp_lhbm_sw43103` (**LHBM = Local High Brightness Mode, used exclusively for
under-display fingerprint-unlock flash on the main screen**) and `sw43103`-branded DDIC ops
(`lge_ddic_ops_sw43103.c`, tied to the phone's own main-panel driver in the earlier file listing,
not the cover display). `hactive=1080,vactive=2460` is also just the main screen's own native
resolution, so it's not distinguishing evidence either. This is very likely just the phone's own
screen waking/dimming/fingerprint-unlocking normally, coincidentally captured in the same long
window — not something the `hallic` override caused. No causal DSI-panel-bring-up evidence
survives closer scrutiny; withdrawing that specific claim rather than let it stand unverified.

### Net result — revised again, back to what's actually solid

What's genuinely confirmed, re-narrowed to only what the evidence directly supports:
1. **`hallic` misdetection is real**, and `force_set_hallic=Y` measurably changes kernel
   *software* state — `is_ds_connected: 0→1`, `hallic_state_notify: SWITCH_STATE=1`, and the
   full fake-VDM software sequence runs through to `"set state Dualscreen Connected"` and
   `DP_STATE_CONFIGURED`, matching stock's sequence closely. This part is solid, reproduced
   twice.
2. **Not yet established**: whether any of that actually reaches real cover-panel hardware or a
   DRM connector/hotplug event, since the DSI panel activity that looked like confirmation of
   this turned out to be an unrelated coincidence (see correction above). `dumpsys display` and
   `extcon6` still show no second display / `DP=0` after the software sequence completes, same
   as before — that negative result stands, but the *reason* is genuinely open again.
3. **A cleaner next step than chasing DSI-panel logs**: capture `/dev/kmsg` filtered
   specifically to `lge_usb_ds3`/`lge_cover_ctrl`/`dp_usbpd`/`prm_log` tags only (excluding the
   generic `[Display]`/`crtc_commit` tags that turned out to be main-screen noise), immediately
   around a fresh `force_set_hallic` toggle, to see what — if anything — the DS2-specific code
   paths do right after `"set state Dualscreen Connected"`, without the main-screen activity
   contaminating the read.

### A third correction: `extcon_set_state()`'s debug print is mislabeled for non-DS2 cables

Chasing `"DualDisplay updated, id:2, state:1"` across three separate captures (looked like DS1's
extcon flag flipping, then reverting 3m41s later, then flipping again — a plausible-looking
timeline) turned out to be another false lead. `extcon.h` gives the real enum values:
`EXTCON_DISP_DS1 = 46`, `EXTCON_DISP_DS2 = 47` — **not** 1 or 2. The LG-added `pr_err()` inside
`extcon_set_state()` only special-cases the literal `EXTCON_DISP_DS2` ID (printing
`"DualDisplay2 updated"`); *every other* cable ID change on *any* extcon device system-wide falls
into the generic `else` branch and gets mislabeled `"DualDisplay updated"` regardless of what it
actually is. `id:2` was `EXTCON_USB_HOST` toggling (matches `extcon5`'s `USB-HOST=1`), `id:1` was
`EXTCON_USB` — both unrelated to any display. Also: `extcon6`'s state file has a `DS2=` key
distinct from `DualDisplay=`, and that `DS2=` key had been sitting at `1` the entire time we were
staring at the (unrelated) `DualDisplay=0` field.

### The real signal, and the actual root cause

Grepping for the *correct* IDs (`id:46`/`id:47`) in the same captures shows a clean, genuine,
correctly-labeled sequence: `"DualDisplay2 updated, id:47, state:1"` fires on every
`force_set_hallic=Y` trigger, one log line before `dd_extcon_hpd_notifier`'s `"DS2 CONNECTED"` —
this part was always real. Following the trail further into `dp_usbpd.c` and `lge_usb_ds3.c`
turned up the actual blocker:

- `dp_usbpd_get_status()` derives `hpd_high` from bit 7 of a "status VDO" that the *fake* VDM
  responder in `lge_usb_ds3.c` constructs for the `DP_USBPD_VDM_STATUS` step (~line 859–874). That
  struct literal sets `.conn`, `.multi_func`, `.adaptor_func` — but never `.hpd_state`, so it's
  implicitly 0. Right below it, `DP_USBPD_VDM_CONFIGURE`'s handler even has `//ds3_dp_hpd(ds3,
  true);` sitting commented out. `hpd_high` is confirmed persistently `0` live via
  `cat /sys/devices/virtual/dualscreen/ds2/ds2_pd`.
- That same file exposes a **live, writable** sysfs node for this exact bit:
  `/sys/devices/virtual/dualscreen/ds2/ds2_pd` (`DEVICE_ATTR_RW(ds2_pd)`), gated only on
  `ds3->is_dp_configured` (set right after the fake VDM_CONFIGURE step, i.e. already true after a
  normal `force_set_hallic=Y` trigger). Writing `1` calls `ds3_dp_hpd()`/`ds_dp_hpd_direct()`
  directly.
- **Tested live, with a clean `/dev/kmsg` capture running throughout**: after
  `force_set_hallic=Y`, `echo 1 > .../ds2_pd` genuinely drives the *real* DP driver state machine
  — `dp_display_process_hpd_high` → adds `DP_STATE_CONNECTED` → `dp_display_host_init [OK]` →
  `dp_display_host_ready [OK]`. This is real progress, not another internal-flag echo: it goes on
  to attempt actual AUX-channel EDID reads (`dp_panel_read_edid`, 31 attempts, all failing →
  `"panel edid read failed, set failsafe mode"`), then genuine electrical link training
  (`dp_ctrl_on`, `dp_ctrl_link_setup bw_code=6 lane_count=1`, `dp_ctrl_enable_link_clock
  rate=162000`), which **downshifts to the minimum rate (`bw_code=0xa`) and then loops forever**
  — `link_rate_down_shift` → `link_setup` → `update_sink_pattern` → repeat, every ~1.8s,
  indefinitely, never converging, never fully aborting. `card0-DP-1/status` stays `disconnected`
  throughout because the connector never reaches a validated/trained state.

**Conclusion**: the phone's own DP controller can genuinely be pushed into attempting a real link
by forcing `hpd_high` via `ds2_pd` — that part of the stack is real hardware, not simulation, and
now confirmed reachable from LOS. But the AUX/link-training signals it sends have no real DP sink
listening on the other end, because the DS2 case's own internal DP-receiver chip is never told to
switch itself into DP-sink mode — that would normally happen via a genuine USB-PD structured VDM
("Enter Mode: DisplayPort") sent over the physical CC wire to the accessory's own controller, and
the driver explicitly skips sending that (`"don't send vdm to DS"`, noted earlier in this doc).
The entire negotiation prior to this point is a purely internal software simulation on the phone's
side only — nothing is transmitted to DS2 itself. This cleanly explains the shape of everything
seen so far: the small cover-window strip works because it's plain USB-HID data requiring no DP
mode switch at all, while the big panel — genuinely DP-fed — can't come up through internal-state
faking alone, because that faking never reaches the accessory.

The concrete next step, if pursued, is not more state-faking: it's getting a real SVDM "Enter
Mode: DisplayPort" message actually transmitted over the CC wire to the DS2 case (removing/
bypassing whatever currently causes `"don't send vdm to DS"`), so the accessory's own receiver
mux genuinely switches. That's a materially bigger undertaking than anything done so far — real
PD-PHY/CC-line transmission, not a sysfs write — and hasn't been attempted yet.

**Confirmed the test itself is valid**: the DS2 case was physically attached (phone seated in the
case) throughout the `ds2_pd=1` link-training test above, not just connected to the PC standalone
— so the EDID-read failure and endless retry loop reflect a real electrical AUX channel with a
real (non-responding) receiver on the other end, not an open/floating pin with nothing attached.

### Where the real SVDM suppression actually lives, and why it's probably not a bug to "just remove"

Traced the exact gate: `usbpd_send_svdm()` in `drivers/usb/pd/policy_engine.c` (the **real**
Qualcomm PD policy engine, not `lge_usb_ds3.c`) — line ~1923:
```c
#ifdef CONFIG_LGE_DUAL_SCREEN
if (check_ds_connect_state() >= DS_STATE_ACC_ID_CONNECTED && svid == 0xFF01)
{
    usbpd_info(&pd->dev, "don't send vdm to DS\n");
    return 0;
}
#endif
```
`DS_STATE_ACC_ID_CONNECTED` is the *lowest* of the "DS2 present" states (`lge_ds3.h`:
`DISCONNECTED < HALLIC_CONNECTED < ACC_ID_CONNECTED < USB_CONNECTED < DP_CONNECTED <
HPD_ENABLED`) — so this blocks real DisplayPort-SID (`0xFF01`) SVDM transmission for the *entire*
time DS2 is recognized as present at all, unconditionally, by design. This isn't an incidental
side effect of some other check; it's a deliberate, permanent suppression specific to DS2.

Checked whether the physical mux/power GPIOs the DP AUX signal would need are even being driven
by the existing fake-state-machine path (`drivers/usb/misc/lge_ds3.c`'s devicetree node,
`kona-timelm_common-usb.dtsi`, exposes two real GPIOs: `lge,load-sw-on-gpio` (TLMM 112, a load
switch — gates power to the connector) and `lge,dd-sw-sel-gpio` (TLMM 48, a USB-host mux select
used for the "2nd USB host"/dock-passthrough feature, guarded by `USE_2ND_USB`)). Both are
**already** asserted by the exact same `ds3_sm()` state-machine path that `force_set_hallic=Y`
drives (`load_sw_on` → 1 at `STATE_DS_STARTUP`, alongside `vconn`/`ds_en` power-up) — so a missing
power/mux GPIO isn't the gap; VCONN and the load switch are live during our test.

Put together, this points at DS2 using a fundamentally different negotiation model than a generic
Type-C DP Alt-Mode dongle: presence is decided by hall-sensor (`hallic`) + resistor accessory-ID
(`accid`), and once that's detected, the kernel deliberately stops using structured PD/SVDM for
DP-SID entirely and drives the DP AUX/lanes itself — meaning the accessory's own internal
DP-receiver chip most likely expects to be switched into sink mode by something other than a PD
VDM (electrical accid/hallic signaling itself, or a separate in-band mechanism not yet found), and
that "something else" is what stock's real attach path provides but `force_set_hallic` doesn't.
That mechanism hasn't been located yet — the current best next step is comparing this exact
sequence (GPIO writes, `ds_dp_config`, `check_ds_connect_state` transitions) against a **real**
physical attach on stock (not LOS) to see whether stock's own kmsg shows anything past
`ds_dp_config: config:1` that `force_set_hallic` doesn't reproduce.

## Remaining open questions

1. ~~What real mechanism switches DS2's own internal DP-receiver mux into sink mode on stock~~ —
   **answered below**: it's `vendor.lge.hardware.accessory@1.1-service`, a HAL we'd never deployed
   at all until this round.

## The missing HAL: `vendor.lge.hardware.accessory@1.1-service`

Root-caused via `HwBinder:1184_1` in the stock trace above: PID 1184 is
`vendor.lge.hardware.accessory@1.1-service`, a completely separate HAL from `dualscreen@1.1-
service` that had never been deployed on LOS at all — the Magisk module only ever shipped the
dualscreen HAL and its client-library dependencies (which happen to include the *interface stub*
`vendor.lge.hardware.accessory@1.0/1.1.so`, easily confused with the actual service).

**Deploying it exposed a second, real bug, now fixed.** Dropping the stock
`vendor.lge.hardware.accessory@1.1-service` binary in alongside its libs made it register
(`IAccessory/default` — success, logged) but then exit almost immediately every time
(`exited with status 234`, ~6ms after registering, 100%-reproducible regardless of physical
state, confirmed via three independent methods: foreground+timeout, bare `&` matching `service.sh`'s
own pattern, and a `/proc` scan). Cause: the binary also tries `registerPassthroughServiceImplementation<IAccessoryUevent>()`
for v1.0/1.1/1.2, all three logging `"Could not get passthrough implementation"` — because the
actual implementation lives in a **separate, differently-named file**
(`vendor.lge.hardware.accessory.uevent@1.2-impl.so`, in `/vendor/lib64/hw/`, distinct from the
interface-stub `.so` in plain `/vendor/lib64/`) that was never copied. All three failing lookups
appear to make `main()` return early, skipping the `joinRpcThreadpool()` call that would otherwise
block forever. **Fix**: pulled `vendor.lge.hardware.accessory.uevent@1.2-impl.so` and
`vendor.lge.hardware.accessory@1.1-impl.so` from stock's `/vendor/lib64/hw/`, added them to the
module at the same path, added a VINTF fragment for both `IAccessory` and `IAccessoryUevent`, and
wired `service.sh` to launch it. Confirmed after reboot: both HALs (plus the Java bridge daemon)
now stay alive indefinitely, registered, idle in `binder_ioctl_write_read` — the healthy
long-running state. No more `exited with status 234`, reproduced across multiple reboots.

**Current blocker is physical, not software.** With both HALs genuinely fixed and alive, live
testing on a real (non-simulated) attach still hasn't reached `cover_button_set`/`ds2_pd_store`.
Two distinct physical-layer problems observed on separate attempts:
- A bouncing/flapping connection: `ds3_smart_cover` (the case hinge/fold sensor) cycling
  OPEN/CLOSE repeatedly over minutes, `lge_usb_ds3`'s state machine correspondingly bouncing
  through `DS_Recovery_Power_Off`/`DS_Recovery_Power_On`/`DS_Recovery_USB_Wait` in lockstep,
  never settling into a stable `DS_Ready` window long enough for the accessory HAL to act.
- On a later, more careful reseat: `ds3_smart_cover` correctly saw one clean CLOSE→OPEN
  mechanical click, but `lge_usb_ds3` — the driver responsible for the actual USB-C electrical
  hallic/accid detection — logged **nothing**, meaning the case's mechanical hinge-sensor and the
  phone's USB-C port electrical mating are two independent signals, and only the mechanical one
  was registering. `extcon6` stayed `DS2=0`/`disconnected` throughout.

This means `ds3_smart_cover` firing is not sufficient proof of a good connection — only
`DS3 cover hallic connected` in kmsg (from `lge_usb_ds3`, not `lge-cover-ctrl`) confirms the
actual USB-C electrical link the rest of the chain depends on.

**Isolated to this specific phone's port, not the case or the module.** Confirmed a normal USB-C
cable charges/data-connects fine in the same port — but that only exercises VBUS/GND/D+/D-/CC;
hallic/accid detection almost certainly runs over the SBU (sideband-use) pins, which a plain
cable never touches, so this doesn't clear the port on its own. The decisive test: this is the
*same physical DS2 case* that got a full real DP link-up on the stock device earlier in this same
session (see "Stock's real success trace" above) — ruling out the case itself.

**Progress after physically cleaning the port**: hallic/accid detection started working reliably
(`ACC_DETECT Vadc result=...`, `DS3 cover hallic connected`, `ds_dp_config: config:1` — all firing
cleanly and consistently, unlike the earlier bouncing). But this exposed a further, more specific,
**100% reproducible** failure, identical across 6+ separate attempts (reseat, firm click,
orientation flip, revert): `ds_set_state: DS_Startup -> DS_USB_Wait`, then ~3.1s later,
unconditionally, `DS_USB_Wait -> DS_Recovery_Power_Off -> ... -> DS_Recovery` — the DS2 case's own
internal USB device never enumerates in time.

**Root cause of that specific failure, found in generic (non-LG) kernel log lines**:
```
usb 1-1: new full-speed USB device number N using xhci-hcd
usb 1-1: hub failed to enable device, error -108
```
This is the **raw Linux USB core** (`hub_port_init()`, `error -108` = `-ESHUTDOWN`) failing to
reset/enable the port for the DS2 case's internal USB device — logged twice per attach attempt
(one retry, same failure). This is not `lge_usb_ds3` or any LG-specific code; the generic kernel
can't get a clean USB descriptor read at all. This is the textbook signature of marginal/unstable
USB signal integrity during port reset, and it reproduced identically across 6 separate physical
interventions spanning ~20 minutes.

**Conclusion**: the SBU/accessory-sense pins (hallic/accid) are now making good contact after
cleaning, but the D+/D- data contact for this specific case-and-port pairing is still marginal
enough that the generic kernel USB core can't complete a basic port reset. Given the same case
works cleanly on stock, and the failure is generic-kernel-level and fully reproducible regardless
of software, this is a genuine physical/electrical contact issue with this phone's port — not
something further software or remote troubleshooting can resolve. The module itself (both HALs,
manifest fragments, boot wiring) is confirmed complete and correct as of this session; everything
downstream of a clean USB enumeration (`cover_button` → `ds2_pd` → real DP link training) is
already proven to work end-to-end on stock with these exact binaries. The remaining gap is
entirely this one phone's physical port.

## Decisive confirmation: flashed LOS onto the (known-good) former-stock phone

The user proposed the correct controlled experiment to settle "is this LOS or hardware":
back up the stock phone (full `boot_a`/`dtbo_a`/`vbmeta_a`/`vbmeta_system_a`/`super.img` dump —
saved to `stock-backup-20260820/`, all sizes verified exact), then flash LOS onto that exact
phone — the one already proven, on stock, to get a full real DP link-up with this exact DS2 case.
Same phone, same case, only the software changes.

Flashed via the standard recovery/sideload flow (`fastboot boot recovery.img` →
`adb sideload copy-partitions-20220613-signed.zip` → `adb sideload lineage-23.2-20260816-
nightly-timelm-signed.zip` → `fastboot flash boot <magisk-patched boot.img>`). Rooted with Magisk
v30.7, deployed the exact same `lge_ds2_hal_shim.zip` module (both HALs + manifest fragments +
`service.sh` boot wiring) used throughout this investigation.

**Result, DS2 attached, fresh boot, no `force_set_hallic` or other manual override**:
```
usb 1-1: new full-speed USB device number 2 using xhci-hcd
```
— clean enumeration, **no** `hub failed to enable device, error -108` this time. The state
machine proceeded past the point that always failed on the other phone:
`ds3_sm: DS_USB_Wait` → (17s later) `ds_set_state: DS_USB_Wait -> DS_Ready`,
`hallic_state_notify: SWITCH_STATE=1`. Final live state: **`extcon6`: `DS2=1`** — the first time
in this entire investigation `DS2=1` has been reached via a genuine physical attach (not a
`force_set_hallic` software simulation) on *any* LOS install.

**This is decisive**: identical LOS build, identical Magisk module, identical physical DS2 case —
succeeds on this phone, and reliably failed (100% reproducible, `error -108`) on the other LOS
phone across 6+ attempts with various physical interventions. The only variable that changed is
the phone itself. This rules out LOS/software as the cause and confirms the earlier hardware-fault
conclusion for the *other* phone's port specifically — it was never a LOS-vs-stock difference.

`DP` still shows `0` and `card0-DP-1` still `disconnected` at this point — DS2 (cover/HID path) is
up, but the real DP video link needs the further `cover_button`/`ds2_pd` trigger chain (physical
case close→open gesture, per the stock trace earlier in this doc) which hasn't been attempted yet
on this device. That's the natural next step now that a genuinely working DS2 connection exists on
LOS for the first time.

## Correction: reliability tested with a real sample, "decisive" was premature

Testing the cover-close→open trigger surfaced a third missing HAL first (see next section), but
also exposed something more important: repeating the physical reattach on this exact
"known-good" phone did **not** reproduce success reliably. Full tally across 5 real
detach/reattach trials on this phone (not counting the very first one): **1 success, 4 failures**
— same `hub failed to enable device, error -108` signature as the other phone, just less
frequently. That's a materially different picture from "this phone works, the other doesn't" —
it's "both phones fail often, this one fails somewhat less often." The earlier "decisive,
hardware-only" conclusion was drawn from a single data point and doesn't survive a real sample.

## The actual failure mechanism, traced to source

`hub failed to enable device, error %d` (`drivers/usb/core/hub.c:4739`) comes from
`hub_enable_device()` → `hcd->driver->enable_device()` → (for xHCI) `xhci_enable_device()` →
`xhci_setup_device()`. The very first check in `xhci_setup_device()`
(`drivers/usb/host/xhci.c:4076`):
```c
if (xhci->xhc_state) {	/* dying, removing or halted */
    ret = -ESHUTDOWN;
    goto out;
}
```
`-ESHUTDOWN` is exactly `-108` — this fires **before any communication with the device at all**,
purely because the driver's own `xhc_state` flag is non-zero. Checked which specific bit:
`xhci_hc_died()` (the fatal "host controller not responding, assume dead" path,
`XHCI_STATE_DYING`) — **never appears in any capture**, ruling that out; the log message for it
never fires. That leaves `XHCI_STATE_HALTED`, set by the ordinary `xhci_halt()` and expected to be
cleared again by `xhci_start()` on resume — a normal, routine transition (used for e.g. runtime PM
suspend/resume or a role-switch reset), not a fatal condition.

**This fits the observed pattern precisely**: works once right after a fresh boot (clean
controller state), then fails on repeated replugs within the *same* boot session without a reboot
in between (controller stuck `HALTED`, not properly restarted before the next enumeration
attempt). The phone's USB-C port needs to switch into host mode for the DS2 case specifically (a
dual-role/OTG transition, unlike a plain cable which only ever needs device mode) — that's the
most likely trigger for a `xhci_halt()`/`xhci_start()` cycle that isn't being handled cleanly on
repeated transitions. This points at something in the kernel's dwc3/xhci-plat role-switch glue or
its runtime-PM interaction being timing-sensitive on LOS, not a simple physical pin fault — though
a marginal physical connection could still be a contributing factor we can't fully rule out
without much larger samples on stock (no longer possible — both physical units are on LOS now).

**Honest state of the theory**: this is a well-evidenced mechanism (verified reading the exact
source path, not speculation), but *why* `xhci_start()` isn't cleanly restoring the controller
between DS2 replugs on LOS specifically — and whether stock's older kernel/driver simply retries
or recovers this differently — hasn't been confirmed. That would need either kernel-side
instrumentation (a `printk` in the halted-check path, requiring a custom kernel build) or a closer
read of the dwc3 role-switch driver's own state machine.

## Root cause, fully traced: a stuck `EXTCON_USB_HOST` flag prevents host-controller re-init

Pursued exactly that closer read. `dwc3-msm.c` (the Qualcomm DWC3 USB controller glue) only
calls `dwc3_otg_start_host(mdwc, 1)` — the function that actually re-initializes/re-probes the
xHCI host controller via `dwc3_host_init()`, giving it a fresh, zeroed `xhc_state` — in response
to an `extcon_register_notifier(edev, EXTCON_USB_HOST, ...)` callback. This is a completely
generic, standard USB-OTG "ID pin" style host/device role switch, unrelated to DS2 on its own.

**The connecting piece, confirmed present in the source (`drivers/usb/misc/lge_ds3.c`,
`#define USE_2ND_USB` unconditional, compiled identically on both kernels)**: on every DS2
hallic-connect, `ds3_sm()`'s `STATE_DS_STARTUP` handling calls `start_2nd_usb_host()`, which —
*only if* `extcon_get_state(ds3->extcon, EXTCON_USB_HOST) == 0` — calls
`extcon_set_state_sync(ds3->extcon, EXTCON_USB_HOST, 1)`, the exact trigger `dwc3-msm.c` is
listening for. On disconnect, the symmetric `stop_2nd_usb_host()` is supposed to set it back to 0.

**Confirmed with direct log evidence, stock vs. LOS**:
- **Stock**: `dwc3_otg_start_host: turn on host` fires **fresh**, ~60ms after `DS3 cover hallic
  connected`, on *every single* attach captured (checked two independent attach events in the
  stock trace, both fire cleanly). Host controller gets a genuinely new session every time.
- **LOS**: `turn on host` fires exactly **once**, at boot (~1s uptime, paired with a `turn off
  host` 57ms later — ordinary boot-time housekeeping, unrelated to DS2), and **never again** for
  any subsequent DS2 attach across the entire session (checked every capture from this whole
  investigation — zero re-occurrences after boot, across dozens of attach events).
- **Confirmed live**, right now, DS2 fully disconnected: `cat /sys/class/extcon/extcon5/state` →
  `USB-HOST=1` — **stuck at 1**, not reverted to 0. `stop_2nd_usb_host()`'s
  `extcon_set_state_sync(..., 0)` should have cleared this on the last disconnect and evidently
  did not take effect (or wasn't reached/completed).

**This fully explains the intermittent failure pattern**: with `EXTCON_USB_HOST` stuck at 1 after
the very first boot-time toggle, `start_2nd_usb_host()`'s `== 0` gate never passes again, so the
xHCI host controller is never freshly re-initialized for any later DS2 attach — every subsequent
attach reuses whatever stale controller state happens to exist from that one boot-time cycle,
which is why enumeration succeeds only occasionally (when that stale state happens to still be
clean) and fails the rest of the time with `hub failed to enable device, error -108`
(`xhc_state` non-zero, `XHCI_STATE_HALTED`, matching the earlier finding).

**This is a confirmed LOS-specific software defect, not a hardware fault.** The mechanism that
makes stock reliable — fresh host-controller re-init on every physical attach — genuinely does not
run on LOS after the first boot. Both physical V60 units showing similar (not identical, but
similarly poor) reliability once this bug is present is consistent with this: neither phone gets a
working per-attach host re-init on LOS, so both are left depending on the same fragile stale-state
reuse, at whatever rate the specific controller/PHY happens to tolerate it.

**Not yet found**: the exact reason `extcon_set_state_sync(ds3->extcon, EXTCON_USB_HOST, 0)`
inside `stop_2nd_usb_host()` fails to stick on disconnect (a race with `dwc3-msm.c`'s own notifier
processing, an extcon-subsystem behavior change between the kernel versions, or something else
entirely). Confirming that — and any real fix — needs either a kernel source patch to
`lge_ds3.c`/`dwc3-msm.c` plus a full rebuild-and-reflash cycle (much larger scope than tonight's
session), or further live instrumentation (`ftrace`/`printk` in the extcon notifier chain) that
CONFIG_DEBUG_FS being disabled on this kernel currently blocks the easiest form of.
2. Compare this exact sequence (GPIO writes, `ds_dp_config`, state transitions, AUX/EDID
   behavior) against a **real physical attach on stock** — capture stock's own kmsg with the same
   DS2-only filter to see whether it shows anything past `ds_dp_config: config:1` that
   `force_set_hallic` on LOS doesn't reproduce, and whether stock's own EDID read ever succeeds
   or also fails/retries silently without surfacing an error to the user.

   **Answered.** Captured a real, currently-live DP session on stock (no simulation — DS2 was
   physically attached and `card0-DP-1/status` read `connected` at the time of capture). Full
   trace below.

## Stock's real success trace — the missing piece was never the SVDM/mux theory

With a stock device physically connected to DS2, the kmsg backlog still contained the entire real
attach-to-video sequence. Key sequence, chronological:

1. Physical attach → `ds_set_state: Unknown -> DS_Startup`, `DS3 cover hallic connected`,
   `ds_dp_config: config:1`, the same fake-VDM handshake seen on LOS (`DISCOVER_MODES` →
   `ENTER_MODE` → `VDM_STATUS *vdos=0x1a` → `VDM_CONFIGURE`), `ds_set_state: DS_Startup ->
   DS_USB_Wait`. `hpd_high=0` throughout — **identical to LOS so far**, confirming (again) this
   part was never the differentiator.
2. `ds2_hal_ready_store: ready:1` (native HAL announces readiness) → `ds_set_state: DS_USB_Wait ->
   DS_Ready`. Also identical to what our LOS port already does.
3. ~13 seconds of idle (`ds3_smart_cover` switches `CLOSE` then `OPEN` — the case being folded
   shut and reopened, most likely the user unfolding it into an actual usable position).
4. **The real trigger, in two steps, ~0.23s apart:**
   - `[Display][lge-cover-ctrl:cover_button_set:290] cover_button_set : 2 1` — a **userspace
     write** to the `cover_button` sysfs device attribute (`lge_cover_ctrl.c`, not a kernel-
     internal callback). Source-read: `cover_button_set()` parses `"%d %d"` as `onoff
     skip_uevent`, and (among other bookkeeping) calls `extcon_set_state_sync(...,
     EXTCON_DISP_DS1, onoff & BIT(0))` — this is the actual, correct call site for the real
     `"DualDisplay"` extcon flag chased (and correctly identified) earlier in this doc. Logged
     from a `HwBinder:1184_1` thread — a HIDL server thread in some other process, not the
     dualscreen HAL itself; which process owns PID 1184 wasn't identified before the capture
     window ended.
   - `lge_usb_ds3 vendor:lge_usb_ds3: ds2_pd_store: hpd_high:1 refresh_layer:1` — logged from
     process `dualscreen@1.1-` — **the native HAL service itself**, the exact same binary already
     deployed and running on LOS via the Magisk module. This is a write to
     `/sys/devices/virtual/dualscreen/ds2/ds2_pd`, the **exact same sysfs node** manually tested
     earlier in this doc. `ds_dp_hpd_direct()` → real `USBPD_SVDM_ATTENTION` with `*vdos = 0x9a`
     (vs `0x1a` before — bit 7, the `hpd_state` bit, now set, confirming the earlier bit-position
     read of `dp_usbpd_get_status()` was correct).
5. From there, everything is genuine and succeeds cleanly, first try, no retries: real AUX PHY
   register programming (`dp_catalog_aux_setup_v420`, `PHY_AUX_CFG0..9`), `dp_display_host_ready
   [OK]`, `dp_panel_read_dpcd: version:1.2, rate:270000, lanes:2` (real DPCD capability read over
   AUX), **`dp_panel_read_edid 2034 EDID read successed, count=1`**, `dp_ctrl_on bw_code=10,
   lane_count=2` (full HBR2, 2 lanes — not the degraded RBR/1-lane fallback LOS's test settled
   into), **`dp_ctrl_link_train: link training #1 successful`**, **`#2 successful`**,
   `dp_display_send_hpd_event`, `dp_display_validate_mode: DP ok mode 1080x2460@174110` (the
   panel's real native resolution), `dp_display_enable: add DP_STATE_ENABLED`. A genuine second
   display, fully up.

**Conclusion — revises the previous section's theory.** The blocker was never a missing real
SVDM-over-CC-wire negotiation with DS2's own chip (the `"don't send vdm to DS"` policy-engine
suppression is real and present in stock's trace too, and stock's own fake-VDM handshake looks
identical to LOS's). The actual missing piece is **entirely userspace-side, later, and much
simpler**: something (most plausibly whatever process owns `HwBinder:1184_1`, possibly reacting
to the smart-cover close/open transition, i.e. the physical hinge being folded flat) tells the
native HAL to write `hpd_high=1` to `ds2_pd` — likely gated on the `cover_button` write happening
first. We already reproduced the `ds2_pd` write manually on LOS and got a failed/looping link
train instead of a clean one — the leading hypothesis now is **timing/ordering**: our manual test
never wrote to `cover_button` first, and/or didn't wait through an equivalent `CLOSE`→`OPEN`
smart-cover cycle beforehand. That's the next concrete thing to try on LOS: replicate the same
two-step sequence (`echo "2 1" > cover_button` if that node is reachable, or replicate whatever
`CLOSE`/`OPEN` `ds3_smart_cover` transition precedes it, then `ds2_pd`) with the same ~13s pacing,
and see if EDID/link-training succeeds cleanly this time.
3. Not yet decompiled: `fcn.00015bb8` (draw-buffer-flush worker thread), `fcn.0001487c`,
   `fcn.0000b89c`, `fcn.000149fc`, `fcn.0000a8d0` (AT-command helper) — all still lower priority
   given the cover-strip-only scope conclusion from the previous section.
4. Can the stock `vendor.lge.hardware.dualscreen@1.1-service` binary simply be dropped onto LOS
   and run as-is (manifest/SEPolicy entries added, linked libs checked)? Already done — see the
   Magisk module section above; this is confirmed working for the cover-window path.

## Correction: two separate problems were conflated in the closing summary

External review of this document caught a real error in how the last two sections' findings were
being summarized. Restating cleanly, because the earlier "the actual blocker is the broken xHCI
re-init trigger" framing was too broad and papers over an untested gap:

**These are two independently bounded problems, not one:**

```text
A. DS2 USB attach reliability            B. Main-panel DP video activation
   lge_ds3 hallic/accid detect              userspace cover_button action
      ↓                                        ↓
   EXTCON_USB_HOST flag                     ds2_pd = hpd_high:1
      ↓                                        ↓
   dwc3/xHCI host re-init                   DP AUX / DPCD / EDID / link training
      ↓                                        ↓
   reliable USB enumeration                 real second-panel video (displayId=1)
```

**Problem A is solidly proven**: stock generates a fresh `dwc3_otg_start_host: turn on host` on
every single DS2 attach; LOS does it once at boot and never again; `EXTCON_USB_HOST` is
confirmed stuck at `1` live. This part of the investigation is correct and doesn't need revisiting.

**Problem B was never actually tested post-fix, and shouldn't have been folded into A's
conclusion.** The sequence of events in this session was:
1. First successful attach (`DS_Ready`, `DS2=1`) happened *before* the missing `coverdisplay` HAL
   was found — `cover_button` correctly never fired, because the HAL responsible for driving it
   wasn't installed yet. That's not evidence against the trigger chain; the chain was incomplete.
2. After deploying `coverdisplay` and rebooting, every subsequent attach attempt failed at the
   USB-enumeration stage (problem A) before ever reaching a stable `DS2=1` state again.
3. So the `cover_button`/`ds2_pd` trigger chain has **never been tested with all three HALs
   present and a stable connection simultaneously**. Jumping into the xHCI investigation before
   closing this loop is exactly the conflation the review caught.

**Corrected summary**: there is a demonstrated LOS-specific intermittent USB-host
reinitialization problem affecting repeated DS2 enumeration (problem A, solid). Separately, the
main-panel DP bring-up has its own later, userspace-driven trigger path — stock's trace shows
`cover_button` (from the accessory HAL, reacting to a physical close→open) followed ~0.23s later
by `ds2_pd`/`hpd_high:1` (from the dualscreen HAL) — and whether LOS reproduces that correctly is
still an **open, untested question**, not something the xHCI finding explains or predicts either
way.

**The right next experiment, once a stable DS2 connection exists again** (either luck with a real
attach, or `force_set_hallic=Y` to sidestep problem A entirely and isolate problem B): replicate
the stock ordering directly —
```sh
echo "2 1" > /sys/devices/virtual/panel_cover/cover_ctrl/cover_button
# then, matching stock's ~0.23s gap:
echo "1 1" > /sys/devices/virtual/dualscreen/ds2/ds2_pd
```
and watch for `dp_panel_read_edid`/`link training successful`/`dp_display_enable`. This cleanly
separates "LOS cannot train DP at all" from "LOS's DP stack works, but nothing is issuing the
stock trigger sequence yet" — and everything in the stock trace favors the second explanation.

### Ran it. Partial result — ordering matters, but isn't sufficient on its own

DS2 physically attached, `force_set_hallic=Y` used to reach a connected state (sidesteps problem
A, doesn't require a lucky real USB enumeration), then manually replayed the stock ordering:
`echo "2 1" > cover_button`, ~1s later `echo "1 1" > ds2_pd`. Both writes landed correctly
(`cover_button_set : 2 1` then `ds2_pd_store: hpd_high:1 refresh_layer:1` — the same log lines
stock produces) and the real DP driver responded genuinely: `DP_STATE_CONNECTED` →
`host_init [OK]` → `host_ready [OK]` → `dp_panel_read_dpcd: version:1.2, rate:540000, lanes:2` —
this time negotiating **HBR3** (540000), a *higher* capability than stock's HBR2 (270000), and
`dp_ctrl_link_setup` starting at the full `bw_code=10, lane_count=2` immediately (not the
degraded RBR/1-lane fallback from the pre-ordering-fix attempts). So the ordering fix is real and
does change behavior for the better.

**But it still doesn't reach success.** `dp_panel_read_edid` failed 63 times in a row,
`dp_panel_read_sink_caps` gave up into `"set failsafe mode"`, and actual link training then loops
forever exactly like the original (pre-`cover_button`) attempts: `dp_ctrl_link_train: link
training #1 failed` → `dp_ctrl_link_rate_down_shift` → retry → fail, indefinitely, eventually
`remove DP_STATE_CONNECTED` (giving up). Same fundamental AUX/EDID failure as the very first
`hpd_high` experiments much earlier in this document, not resolved by fixing the trigger order.

**A real confound this test doesn't rule out**: `force_set_hallic=Y` was used to reach the
connected state, not a genuine organic hallic/accid GPIO detection from physical insertion. The
one time this investigation got an organic, non-forced physical attach to succeed (`DS_Ready`,
`DS2=1`, real USB enumeration), the `coverdisplay` HAL wasn't deployed yet and `cover_button`/
`ds2_pd` were never tried on that occasion — so "organic attach + correct trigger ordering
together" has still never actually been tested. It's not yet known whether `force_set_hallic`'s
simulated power-up path reaches the same electrical/timing state as a real insertion closely
enough for this specific AUX-channel test to be representative, or whether that's itself a
missing variable. That combination — a real attach (not forced) that survives problem A, followed
immediately by the correct `cover_button`/`ds2_pd` ordering — is the one test that would actually
settle whether problem B is fully explained by trigger ordering or has its own separate cause.

### Correction to problem A: not a stuck flag, a runaway retry loop

Switched to wireless adb (`adb tcpip`/pairing over WiFi) specifically so a continuous filtered
`/dev/kmsg` watch survives physical detach/reattach cycles without losing the connection, and ran
several fresh organic (real, not `force_set_hallic`) reattach attempts against it. The result
contradicts the earlier "`EXTCON_USB_HOST` gets stuck at 1 once at boot and never re-triggers"
explanation — that was **also premature**, drawn from too small a sample (the absence of a
refired `"turn on host"` line in the specific captures checked at the time, which happened to
all be from sessions where it genuinely didn't refire, not proof that it structurally can't).

What a fresh organic capture actually shows: `start_2nd_usb_host`/`stop_2nd_usb_host` fire
repeatedly — **34 cycles in one capture window**, alternating every 1-5 seconds — and each one
does correctly reach the dwc3 notifier (`msm-dwc3: host:X (id:Y) event received` logs every
single time, immediately after each call). So the extcon plumbing itself is not broken or stuck.
But **not one** of those 34 cycles ever produces `dwc3_otg_start_host`'s own `"turn on host"`
line — the function that actually does the controller re-init apparently never completes (or
never even starts) before the next `stop` arrives and cancels it. This looks like the driver's
own `DS_Recovery_Power_Off`/`DS_Recovery_Power_On` retry logic (in `lge_usb_ds3.c`) is retrying
fast enough to repeatedly interrupt the dwc3 OTG state machine before it can finish a real
bring-up — a race, not a stuck flag.

This is a materially different (and more specific) bug than what was written above, though it's
consistent with the same overall symptom (no working host-controller re-init on LOS after the
first boot). Correcting rather than leaving the earlier claim standing, per this document's
established pattern. Live state check during this test also reconfirmed `EXTCON_USB_HOST=1`
persisting after the flapping settles — consistent with the loop ending mid-cycle on a `start`
rather than a `stop`, not with the flag being immutably stuck from a single early event.

No organic success (`DS_Ready`) was reached in this round of reattach attempts — still needed for
the problem-B organic-vs-forced comparison the review proposed. After the `force_set_hallic`
contamination was cleared, ran several clean physical reattach cycles (each producing its own
auto-retry batch — the driver retries on its own roughly every ~5.7s, `DS_USB_Wait` timeout →
`DS_Recovery_Power_Off/On` → retry — for a limited number of attempts before giving up and
requiring a fresh physical reattach to restart). Total across this session: **~50+ real attach
attempts, 0 reaching `DS_Ready`**. Purely by the ~20% single-attempt success rate measured
earlier, this run of bad luck is well within normal variance (a run of 50 straight misses at 20%
odds has meaningful but not vanishingly small probability), so this does not on its own suggest
the success rate has changed — but it does mean the organic-vs-forced AUX/EDID comparison the
review proposed remains untested as of the end of this session. Next session should resume by
re-running this same wireless-adb-plus-continuous-capture setup and continuing reattach cycles
until a `DS_Ready` lands, then immediately following with `cover_button`/`ds2_pd` on that organic
connection.

### Further correction to problem A: not a race either — the re-init is never reached at all

Wrote a small parser (`parse_dwc3_retries.py`, kept alongside this doc) to extract every
`start_2nd_usb_host`/`stop_2nd_usb_host`/`dwc3 notify`/`xhci new-device`/`turn_on_host` event from
a filtered capture, group them into per-attempt cycles, and measure the intervals. Run against the
cleanest capture from this session (13 real physical attach cycles, `force_set_hallic`
contamination already cleared):

```
start_2nd_usb_host -> dwc3 notify ack:            n=25  min=0.0ms    max=228400.1ms  avg=16257.1ms
start_2nd_usb_host -> xhci new-device attempt:    n=12  min=308.2ms  max=312.0ms     avg=310.4ms
stop_2nd_usb_host -> next start_2nd_usb_host:      n=12  min=100.7ms  max=7741.8ms    avg=1980.0ms

turn_on_host actually fired: 0 / 13 start_2nd_usb_host calls
```

Two things stand out. First, `turn_on_host` (the real `dwc3_otg_start_host()` re-init) fired
**zero** times, confirmed again and now on a clean, uncontaminated sample. Second — and this is
the correction — `xhci new-device attempt` fires **every single time** regardless, at an almost
perfectly fixed **310ms** delay (essentially zero variance across 12 samples: 308.2–312.0ms). That
fixed, non-jittery timing is inconsistent with two software paths racing each other (a race would
show variable timing depending on scheduler/workqueue load); it's consistent with a fixed hardware
settling delay (load-switch/regulator + `dd_sw_sel` GPIO mux propagation).

**Revised understanding**: this isn't `dwc3_otg_start_host()` getting interrupted mid-flight by a
competing `stop` — it's never being reached or invoked at all for these later attempts. The
`dd_sw_sel` GPIO mux is routing the DS2 device onto whatever xHCI controller instance already
exists (from the one-time boot-time toggle) without any accompanying controller re-init, so
enumeration proceeds against a controller that may still be carrying stale/halted state from
earlier — directly explaining `error -108` without needing a race/timing-window explanation at
all. This supersedes the "race condition" framing from the previous correction; that framing was
itself still one correction short of the actual mechanism.

Still not established: *why* the notifier chain stops invoking the real re-init function after the
first boot-time instance, when the extcon notification (`"host:X (id:Y) event received"`) itself
demonstrably fires correctly on every attempt. That's the next thing worth reading in
`dwc3-msm.c`'s notifier/workqueue handler — specifically whether it conditions the actual
`dwc3_otg_start_host()` call on some state (e.g. `mdwc->drd_state`) that only permits it once
per boot, or requires a real ID-pin GPIO transition, not the extcon-only path DS2 uses.

## Problem B, mined further: native AUX works, I2C-over-AUX (EDID) fails specifically

Asked for actual kernel-level fault injection to probe the AUX/EDID failure — not attempted, since
it needs either `CONFIG_FAULT_INJECTION` (unconfirmed, and `CONFIG_DEBUG_FS` — usually a
co-requirement for the injection debugfs knobs — is already confirmed off on this kernel) or a
source patch plus a full rebuild-and-reflash, out of scope for tonight. Mined the existing
captures instead, which got further than expected.

Found a raw AUX transaction dump immediately before the very first EDID failure in the
`cover_button`→`ds2_pd` capture from earlier:
```
[drm-dp] SINK DPCD: 12 14 c4 01 01 00 01 00 02 02 04 00 00 00 00 00
```
This is a **real, plausible DPCD capability block**, read successfully over AUX (rev 1.2, HBR3
rate, reasonable capability bits) — a genuine responding sink, not silence or garbage. Every EDID
read attempt fails immediately afterward and unconditionally, retrying every ~76ms, 63 times, before
falling back to failsafe mode. **DPCD reads use native AUX transactions; EDID reads use a
different transaction type, I2C-over-AUX** (`sde_get_edid()` → the DRM `ddc` I2C adapter → 
`dp_aux_transfer()` in `dp_aux.c`). So the failure is narrower than "AUX doesn't work": native AUX
register access succeeds, I2C-over-AUX specifically does not.

Read `dp_aux.c`'s actual transfer/retry logic. Two things confirmed:
- `dp_aux_transfer()`'s classification of each transaction (ACK vs DEFER-to-retry) comes from
  `aux->aux_error_num`, set inside the hardware AUX interrupt handler from real ISR status bits
  (`DP_AUX_ERR_NONE` / `_ADDR` / `_TOUT` / `_NACK` / `_NACK_DEFER` / `_PHY`) — a real per-attempt
  hardware classification, not a driver-level guess.
- The driver's only debug print for a genuine timeout (`"aux %s timeout\n"`, from a *different*,
  slower `wait_for_completion_timeout(&aux->comp, HZ*2)` code path) **never appears** in any
  capture. That path would only permit one attempt every 2 seconds; ours repeat every ~76ms — so
  the failures are not hitting that timeout path, meaning the fast ISR-driven classification path
  is what's firing, and firing fast, every single attempt.

**What this narrows the question to**: each I2C-over-AUX attempt is being classified via real
hardware interrupt status as something other than `DP_AUX_ERR_NONE` (NACK, DEFER, address error,
or PHY error) very quickly and consistently — not silently timing out. The driver logs *that* it
failed, but not *which* of those four it was; getting that specific classification needs either a
one-line debug print added to the ISR handler (`aux->aux_error_num` after each interrupt) or
`CONFIG_DEBUG_FS` enabled for `dp_debug.c`'s own richer AUX tracing — both requiring a kernel
source change and a rebuild-and-reflash cycle, not attempted this session.

## Attempted the full rebuild-and-reflash cycle

Went ahead and did it. Full account below, including a real, currently-unresolved blocker.

**Toolchain setup**: `build.sh` needs `clang-r498229b` (pruned from Google's prebuilts repo
history — only newer versions remain) plus `aarch64-linux-android-4.9`/`arm-linux-androideabi-4.9`
GCC cross-compilers (also pruned from the repo's `main` branch, deprecated in favor of clang, only
present on old archived per-device kernel branches). Substituted the closest still-available clang
(`clang-r547379`, same LLVM-based-toolchain lineage) and pulled the GCC prebuilts from an archived
`android-msm-barbet-4.19-android12-qpr1` branch (any device's GCC 4.9 toolchain is interchangeable
— it's a generic, unpatched cross-compiler). All three fetched via gitiles' `+archive` tarball
endpoint (avoids full-history clones), extracted to `/home/tmmh/v60-re/{clang-r498229b,
aarch64-linux-android-4.9,arm-linux-androideabi-4.9}` matching `build.sh`'s relative paths.

**Applied the patch**: added an unconditional `pr_err` in `dp_aux_transfer()`
(`techpack/display/msm/dp/dp_aux.c`) printing `aux->aux_error_num`'s classified string on every
AUX transaction — the debug instrumentation this whole exercise was for.

**Two genuine, unrelated build-environment bugs found and fixed along the way** (both real,
both worth knowing about for any future rebuild on this machine):
1. `scripts/mkcompile_h`'s `CC_VERSION=$($CC -v 2>&1 | grep ' version ')` — this system's clang
   auto-probes for a local CUDA installation (`/opt/cuda` exists on this machine) and prints
   `"Found CUDA installation: ..., version"` as part of `-v` output; since that diagnostic line
   also contains the substring `" version "`, the ungetting `grep` catches both lines, embedding
   a newline into the generated `include/generated/compile.h` and breaking the C parse for
   *every* subsequent build (not related to our patch at all). Fixed by tightening the grep to
   `-m1 -E 'clang version|gcc version'` (and the equivalent for `LD_VERSION`/`grep -E 'GNU ld|LLD'`).
2. `scripts/Makefile.lib`'s generic `cmd_dtc` recipe failed on a genuine, pre-existing
   duplicate-DT-label bug in one specific *irrelevant* device-tree overlay
   (`kona-timelm_dcm_jp_rev-0.0-overlay.dts`, a Japan-carrier variant our device doesn't use, but
   which `vendor/timelm-perf_defconfig` still builds as part of a broader multi-variant "perf"
   config) — two duplicate WSA-audio-codec node labels between two fragments. Not our bug, not
   fixable by us without breaking that unrelated overlay's audio config further; worked around by
   adding `-f` to the dtc invocation to force output despite the (non-fatal for other files)
   validation warning.

**Both fixed, kernel built successfully**: 41.7MB `arch/arm64/boot/Image`, all vendor `.ko`
modules linked cleanly, our `dp_aux.c` patch compiled without errors.

**Repackaging**: unpacked the known-working `los/latest-build/boot.img` with `unpack_bootimg` to
get its exact header parameters (base/offsets/pagesize/os-version/cmdline, header v2), gzip'd our
new `Image`, and repacked with `mkbootimg` reusing the *original* `ramdisk` and `dtb` unchanged
(our patch is pure kernel C code, doesn't touch hardware description or userspace). Verified the
new header matched the original exactly except kernel size (expected).

**Flashed. Bootloader rejects it — before ever reaching the kernel.** Both the Magisk-patched and
raw unpatched versions of our custom-kernel boot.img land back on the fastboot menu instantly on
`fastboot reboot`, with `fastboot getvar slot-retry-count:b` staying at its full, unconsumed
value (`7`) both before and after the attempt — meaning the bootloader never actually handed off
to the kernel at all; this isn't a runtime crash, it's an early bootloader-level rejection.

**Isolated the cause precisely with one more test**: repacked `los/latest-build`'s *original*,
known-good kernel binary through our exact same `mkbootimg` pipeline (identical header params,
same ramdisk/dtb) and flashed that. **It booted normally.** This rules out the repackaging
process, the header parameters, AVB/verification in general (a strictly-enforced boot-partition
hash would have also rejected the Magisk-patched-ramdisk boot.img that's worked fine all session)
and Magisk's patching step — all of those are now confirmed not at fault. The problem is narrowed
to one specific thing: **our own compiled kernel `Image` binary itself is what the bootloader is
rejecting**, for a reason not yet identified.

**Leading suspect, not yet confirmed**: the toolchain substitution. `clang-r547379` is the closest
still-available version to the exact `clang-r498229b` this kernel's `build.sh` was written for,
but it's not the same build — LLVM version drift across a heavily vendor-patched Qualcomm/LG 4.19
tree is a known, real source of subtly-broken-but-cleanly-compiling kernels (inline asm codegen
differences, exception-vector layout, structure packing), and this device's early boot path (ABL/
bootloader kernel-image validation, before Linux itself ever runs) is exactly the kind of place
such a difference could manifest as an instant, pre-kernel rejection rather than a visible crash.
Not confirmed — would need either the exact original clang version (still not available from
Google's pruned repo history without a deeper archived-branch search) or a bisection of which
specific compiled component/config differs enough to matter.

**Current device state**: back on the original working LOS kernel + Magisk (root-only test image,
not yet re-patched with Magisk after this diagnostic round) — no functional loss, phone is in a
known-good state. The `dp_aux.c` debug patch and everything needed to retry the build is preserved
in the source tree and this session's toolchain directories for a future attempt.

---

## 2026-08-21 update: correct source found, custom kernel booted, decisive AUX finding

Everything above this line predates finding the actual correct kernel source. Summary of what
changed and the new result:

**Wrong source corrected.** All prior source-reading (this whole doc, up to here) was done against
`AlcatrazDev-Android-Devices/kernel_lge_sm8250` (`lineage-21.0` — the wrong LOS version). The real
source is `LineageOS/android_kernel_lge_sm8250` (`lineage-23.2`), confirmed via byte-exact match of
`git log -1`'s commit hash (`29902cf733dc...`) against the `-g29902cf733dc` suffix in the real
device's running kernel version string. Diffed every file this doc's root-cause narrative depends
on (`extcon.h`, `lge_ds3.c`, `lge_cover_ctrl.c`, `dwc3-msm.c`, `dp_aux.c`) between the two trees:
all identical except one unrelated single-line `vbus_reg` defensive-check refinement in
`dwc3-msm.c` and our own patch in `dp_aux.c`. **No correction to the diagnosis was needed** — only
to the build/toolchain, which had been silently using the wrong tree the whole time.

**Toolchain also corrected.** Exact match confirmed: `clang-r563880c` (clang 21.0.0) — verified via
byte-exact `clang -v` string match (including internal build ID and git commit hash) against
`CONFIG_CC_VERSION_TEXT` pulled fresh from the device's `/proc/config.gz`. GCC is not used anywhere
in this build — confirmed structurally (`LLVM=1` redefines every build tool including the 32-bit
compat vDSO path, which falls back to `clang --target=arm-linux-gnueabi` + `ld.lld` rather than a
separate GCC cross-compiler, since `CONFIG_CC_IS_CLANG=y` and `CONFIG_LD_IS_LLD=y`). Config
reconstructed from `arch/arm64/configs/vendor/kona-perf_defconfig` (base, 729 lines — not the
zero-byte stub of the same name one directory up, a path mistake caught mid-session) merged with
`arch/arm64/configs/vendor/timelm.config` — diffed against the real device's actual `/proc/config.gz`
and got it to **0 differences across 5360 symbols**, after two deliberate corrections
(`CONFIG_LFS_COMMON` and `CONFIG_NLS_UTF8`, both Kconfig-default `y` but `n` on the real device).

**Custom kernel booted successfully.** `4.19.325-cip133-st17-perf-g29902cf733dc-dirty` — clean
boot, no loop, `/vendor` mounts correctly (the earlier `/vendor: No such device` failure from the
wrong-source build attempts did not recur), root and all three HAL services running.

**Decisive AUX finding.** Physical attach/detach cycles alone (three consecutive cycles captured)
never got past `dp_display_host_init` → immediate `dp_ctrl_host_deinit` — no AUX transaction ever
attempted via the organic/automatic extcon path in any of the three cycles. Manually triggering
`cover_button` then `ds2_pd` via sysfs (same ordering as the earlier ordering test) got real
`hpd_high=1` and a full attempt at DP training, which failed. The stock kernel's own existing
(rate-limited) `dp_aux_cmd_fifo_tx` logging already answered the question the `RE-DEBUG` patch was
built for, without needing the patch: **20/20 captured AUX errors in the failing window were
`DP_AUX_ERR_TOUT`** (genuine bus timeout) — not NACK, DEFER, ADDR, or PHY. Notably this run's
timeout wasn't confined to the I2C-over-AUX EDID read the way earlier sessions characterized it —
the *native* AUX DPCD read (`DP_TRAINING_AUX_RD_INTERVAL`) timed out too, forcing a fallback to
`link_rate=162000 num_lanes=1` (lowest possible RBR/1-lane) before the EDID read then also timed
out and failed outright. Full trace preserved at `los/captures/aux_tout_decisive_capture_20260821.log`
(and the full raw capture at `los/captures/full_capture3_20260821.log`).

**Why `RE-DEBUG` never printed — a real bug in the patch's placement, not evidence of anything.**
`dp_aux_transfer()`'s negative-return branch (`if ((ret < 0) && ...) { ...; goto unlock_exit; }`,
hit on every timeout) exits *before* reaching the `pr_err` I'd placed just above the
`aux_error_num == DP_AUX_ERR_NONE` check. Since every observed transaction in this window took that
early-exit path, the patch simply never executed — not a sign the failure mode is something else.
If a future session wants the added native-vs-I2C-over-AUX granularity per transaction (beyond what
the stock TOUT logging already gives), the print needs to move earlier, e.g. right after
`ret = dp_aux_cmd_fifo_tx(aux, msg);`, before the early-exit branches.

**Net effect**: with a genuine `DP_AUX_ERR_TOUT` confirmed as the failure mode (not NACK/DEFER),
this points toward a hardware/timing/PHY-level cause on the AUX channel itself — the retry loop
(every 5th failure triggers a `PHY_AUX_CFG1` register cycle: `0x23→0x13→0x1d→0x23`, visible
repeating in the capture) is a real recovery attempt that never succeeds, not a software logic bug
in when transactions are attempted.

### Refined conclusion, superseding the earlier native-vs-I2C-over-AUX split

The 20/20-`DP_AUX_ERR_TOUT` result is a stronger, more controlled observation than the earlier
"native AUX works, only I2C-over-AUX/EDID fails" characterization, and supersedes it:

```text
AUX transaction
    │
    ├─ native DPCD read      → TIMEOUT
    └─ I2C-over-AUX EDID     → TIMEOUT
             │
             ▼
PHY_AUX_CFG1 recovery
0x23 → 0x13 → 0x1d → 0x23
             │
             └─ never recovers
```

Recorded conclusions:
- 20/20 AUX attempts are `DP_AUX_ERR_TOUT` — no NACK/DEFER/address/PHY classification ambiguity.
- The failure occurs on **both** native AUX and I2C-over-AUX, not selectively on EDID.
- The driver's own AUX-PHY reset sequence (`PHY_AUX_CFG1` cycling) does not restore communication.
- This looks much more like **AUX electrical/PHY/link-timing failure** than an incorrect EDID
  transaction or a userspace sequencing bug.
- The display-pipeline freeze that followed is plausibly a consequence of the deliberate retry
  storm; clean recovery afterward (no reboot, no stuck kworkers) argues against a kernel panic or
  unrecoverable controller deadlock — it read as a userspace/display-pipeline stall, not a kernel
  crash.
- The `RE-DEBUG` instrumentation patch is not evidence either way — it never executed, because it
  sat after the timeout early-exit path.

This gives a materially cleaner separation of the two problems this investigation has been
conflating:

```text
DS2 USB / HID path
    ✓ LOS kernel support
    ✓ 1004:637a enumeration
    ✓ hidraw ioctl
    ✓ extcon DS2 state
    ✓ stock HAL executable runs

Display enable / DP path
    ?
    └─ AUX communication fails at the hardware/PHY level
       before a usable DP sink can be established
```

**The userspace HAL port and the DP link problem should no longer be treated as the same
blocker.** HAL work (already largely done — `dualscreen`/`accessory`/`coverdisplay` services all
run) can proceed independently of the DP link problem, which now has a concrete kernel-level
target: why the AUX channel cannot complete even a native DPCD transaction, and why the AUX-PHY
recovery sequence cannot restore it.

**Next instrumentation, if resumed**: place the print *before* the timeout early-exit
(`if ((ret < 0) && ...) { ...; goto unlock_exit; }` in `dp_aux_transfer()`), ideally right after
`ret = dp_aux_cmd_fifo_tx(aux, msg);` — capturing the request type/address plus the AUX controller
status registers at the moment the timeout is detected, not just the post-hoc classification the
stock logging already gives.

## 2026-08-21 continued: corrected instrumentation, decisive 215/215 result

Moved the print into `dp_aux_cmd_fifo_tx()` itself (the actual choke point both failure paths go
through, with `msg` in scope) rather than the dead spot in `dp_aux_transfer()`. Two print sites
added: one for the pure software wait-timeout (`wait_for_completion_timeout` giving up with no ISR
ever firing at all), one for an ISR-reported hardware error, each printing request type
(read/write, native/i2c), address, size, retry count, and — critically — the raw hardware ISR
register value (`aux->catalog->isr`, already cached from the ISR at zero extra cost) the
`DP_AUX_ERR_*` classification was derived from. Rebuilt (clean, zero errors, same verified
toolchain/config as before), reflashed, booted clean (build `#2`,
`...g29902cf733dc-dirty`, `/vendor` fine, root fine).

Triggered via the same `cover_button` → `ds2_pd` sysfs sequence as before, captured briefly (~7s),
then reset both attributes back to 0 to cut the retry loop short. Result:

- **215/215 captured attempts failed**, every one `DP_AUX_ERR_TOUT`.
- **Every single one has `isr=0x200`** — the identical raw hardware interrupt-status value, zero
  variance across all 215. No other status bit (ADDR/NACK/DEFER/PHY) was ever seen, not even
  transiently.
- `NO-ISR-TIMEOUT` count: **0** — the hardware ISR fires every single time (the AUX engine is alive
  and does raise an interrupt for each attempt); it just always reports "no reply detected," never
  anything else.
- Three distinct transaction types were attempted, all with the identical result:
  - `addr=0x600 native=1` (DPCD `DP_SET_POWER`, the very first write in link training) — 64/64 TOUT
  - `addr=0x0 native=1` (DPCD capability-block read, `dp_panel_read_dpcd`) — 32/32 TOUT
  - `addr=0x50 native=0` (I2C-over-AUX, EDID's standard I2C address) — 119/119 TOUT

This is about as clean a "smoking gun" as this kind of capture gets: not a single one of 215
attempts, across three different transaction types and two different transaction classes (native
DPCD and I2C-over-AUX), ever got anything back from the far end other than a timeout, and the raw
ISR value never so much as flickered. That rules out a protocol-level or sequencing bug in the
kernel driver (which would be expected to produce at least some ADDR/NACK/PHY variety, or some
successes mixed in) in favor of a hardware/electrical-level explanation: either the AUX channel
itself isn't physically connected/routed correctly between the phone and the DS2 accessory, or the
DS2's own DP receiver isn't powered/ready to answer AUX requests at all.

**Side effect, and a real self-inflicted bug worth flagging**: partway through this capture the
device became unresponsive over adb and WiFi dropped; the phone recovered on its own without
needing the volume-down/USB fastboot recovery. Pulled `/sys/fs/pstore/console-ramoops-0`
afterward and confirmed this was **not** a kernel panic or freeze — it's a clean, orderly
`reboot: Restarting system with command 'bootloader'` via `kernel_restart` →
`__arm64_sys_reboot`, with the touch driver and modem doing their normal orderly shutdown
sequence first. Almost certainly Android's own system watchdog recovering from an unresponsive
state — and the unresponsiveness itself is best explained by changing the debug print from
`pr_err_ratelimited` to a plain unconditional `pr_err`: in a tight retry loop firing roughly every
55-60ms (visible in the timestamps: `186.801`, `186.858`, `186.915`, ...), unconditional `pr_err`
at that rate is enough printk/console overhead to plausibly stall the system on its own, entirely
independent of the underlying AUX hardware problem. **If this instrumentation is reused, keep the
print rate-limited or cap the number of prints per trigger** — the 215 lines captured here already
came from an unthrottled burst and are plenty of data; there's no need to let it run unbounded.

## 2026-08-21 continued: aux-sel/aux-en GPIO hypothesis tested and ruled out

Traced the exact `aux-sel` GPIO computation for the DS2 case: `dp_display_usbpd_configure_cb()`
force-sets `dp->hpd->orientation = ORIENTATION_CC2` whenever `is_ds_connected()`, which makes
`dp_display_host_init()` compute `flip = true`; then `dp_power_set_gpio()` — because
`is_ds_connected()` is checked *again* — inverts it, landing `aux-sel = !flip = 0`. Also noticed
this inversion block was written outside the `if (... "aux-sel")` check, so structurally it was
being applied to every GPIO in the loop (`aux-en`, `aux-sel`, `usbplug-cc` alike), not just
`aux-sel` — a real scope bug, fixed (nested the block inside the `aux-sel` check) and confirmed via
rebuild that it's a **no-op for observed values** in this exact scenario, since `aux-en`/`usbplug-cc`
were never assigned anything but their zero-initialized default regardless of the bug's scope.

Then tested the actual hypothesis directly: removed the DS2 inversion so `aux-sel = flip` (`1`)
instead of `!flip` (`0`), rebuilt, flashed, triggered via the same `cover_button`/`ds2_pd` sysfs
sequence, confirmed via the stock `[drm:dp_power_set_gpio]` log line that `aux-sel` really did read
back as `1` this time (not just a build artifact) — **result: still 182/182 `DP_AUX_ERR_TOUT`, no
change**. `aux-sel`'s polarity, in either direction, is not the cause.

Also worth noting: `dp_power_config_gpios()`'s disable/teardown path explicitly drives `aux-en` to
`1` when *disconnecting* — the opposite of what you'd expect if `aux-en=1` meant "enabled." This
suggests `aux-en` is active-low (`0` = enabled), meaning the `aux-en=0` seen throughout every
connect attempt in this investigation was very likely already correct, not a misconfiguration.

**Net effect**: both plausible phone-side AUX-mux GPIO hypotheses (`aux-sel` polarity, `aux-en`
state) are now ruled out by direct empirical A/B testing on real hardware, not just source reading.
The 100%, zero-variance `DP_AUX_ERR_TOUT` result stands unexplained by anything in the GPIO-mux
configuration path. Remaining candidates, roughly in order of how testable they are from software:
a missing/misconfigured regulator powering the AUX PHY (not yet investigated — `dp_power_init`'s
`vreg` config, look for anything gated similarly to the GPIO path); a completely different
init step or register write specific to the DS2 topology that hasn't been found yet; or a genuine
hardware-level fault (damaged/degraded pogo-pin contact, DS2-side receiver fault, or a DS2 battery
too depleted to power its own DP receiver chip) that no further kernel-side probing can diagnose —
at that point a multimeter continuity check on the AUX pogo pins, or testing against a second DS2
unit if available, would be the next productive step outside the kernel entirely.

## 2026-08-21 continued: regulator/vreg path investigated, final software-side probe — clean negative

Per plan, made this the last software-side experiment: inspected `dp_power_init()` and everything
it calls (`dp_power_regulator_ctrl`, `dp_power_pinctrl_set`, `dp_power_clk_init`) end to end. Result:
**zero `is_ds_connected()`/`CONFIG_LGE_DUAL_SCREEN` branches anywhere in the regulator, pinctrl, or
clock path** — unlike the GPIO-mux code, this is entirely generic; the exact same code runs for
DS2 as for a normal external DP dongle. Ordering is also correct: regulators enable first, then
pinctrl, then the AUX GPIOs, then `pm_runtime`/clocks — power is up well before any AUX GPIO or
transaction activity.

Found one DS2-specific regulator, but it's unrelated: `lge_ds3.c`'s `acc_det_pu`
(`regulator_get(dev, "lge,acc")`), driven by `ds3_enable_acc_regulator()`. Traced its only caller,
`check_ds3_accid()`: it's a brief accessory-ID-detection pulse — enabled, held for
`ds_accid_reg_en_delay_ms`, used to sample either a VADC channel or a GPIO for the accessory-ID
resistor, then **unconditionally disabled again** before the function returns, success or not. It's
off well before any DP/AUX activity could occur, and this detection mechanism is confirmed working
in every single capture (`DS2 CONNECTED` fires reliably every time) — not the AUX power source.

Rather than guess at DT values from source, pulled the live device's actual devicetree node
(`/proc/device-tree/soc/qcom,dp_display@ae90000/`, found via `qcom,aux-en-gpio` — no debugfs
needed) and decoded the real regulator config `dp_power.c` uses:

| supply | voltage | enable load |
|---|---|---|
| `refgen` (core) | n/a | n/a |
| `vdda-1p2` (ctrl) | 1.2V fixed | 33mA |
| `vdda-0p9` (phy) | 0.912V fixed | 126mA |

All standard, sane SM8250 DP PHY values — nothing zeroed-out or obviously wrong in magnitude.
No `qcom,supply-pre-on-sleep`/`post-on-sleep` properties exist on any of the three entries (so
`msm_dss_enable_vreg`'s optional settle-delay logic defaults to zero either way — again, identical
for DS2 and a generic dongle). Combined with never once seeing a
`DP_ERR("failed to enable regulators")` in any capture — every trace shows `dp_display_host_init
[OK]`, meaning `dp_power_regulator_ctrl()` returns success every time — this is a clean negative.

**Software-side investigation is now exhausted, per plan.** Summary:

```text
software mux/config        ruled out  (aux-sel polarity tested both ways, no change)
software AUX transaction   ruled out  (dp_aux_cmd_fifo_tx logic identical to working paths)
AUX controller recovery    ineffective (PHY_AUX_CFG1 cycling never recovers)
regulator/power sequencing ruled out  (generic code, sane DT values, no errors, correct ordering)
hardware/contact/sink      remaining, and now the leading explanation
```

Next productive step, if this is picked back up, is hardware-level: a continuity/voltage
measurement on the DS2 pogo-pin AUX contacts, or testing against a second DS2 unit if one becomes
available — both outside what further kernel patching can resolve.

## 2026-08-21 continued: proprietary LG binaries checked; genuine hardware power-cycle tested

Per request, checked whether something proprietary and LG-authored (not the open kernel driver, not
generic Qualcomm code) might be the missing piece. Comprehensively listed every LG-named binary in
stock's `/vendor/bin/hw/` (using the local stock vendor partition backup, no live device needed) and
searched each one relevant to display for AUX/DP/DS2 references via `strings`:

- `vendor.lge.hardware.display.uevent@1.0-service` — a pure uevent listener (`SWITCH_NAME=dp_notify`,
  `RESOLUTION_SWITCH=`), no sysfs write capability visible. Downstream of the kernel, not a cause.
- `vendor.lge.hardware.display.tune@1.3-service` / `display.brightness@1.2-service` — both are
  clients of the `dualscreen@1.0` HIDL interface (`setRgbTune`/`setScreenTune`/`setScreenMode`),
  not independent triggers.
- `vendor.lge.hardware.display.general@1.0-service` — no relevant references at all.
- `init.qti.display_boot.sh` — generic Qualcomm code that only branches on `bengal`/`lito` SoC
  platforms; no case for `kona` (this device's actual platform) at all, so it's a no-op here.
- The `dualscreen@1.1-service` binary we've already deployed: full `strings` pass confirms its
  sysfs surface is exactly `ds2_hal_ready`, `ds2_pd`, `ds2_recovery`, plus hidraw/tty enumeration
  and an MCU-firmware-write path — nothing beyond what's already been tested, except one new node.

None of the four additional proprietary LG display services do anything relevant to AUX/DP that
isn't already accounted for by the kernel driver and the `cover_button`/`ds2_pd` sysfs nodes.

**`ds2_recovery` — a genuinely new mechanism, tested.** Unlike everything tried so far, this sysfs
node (`ds2_recovery_store()` in `lge_ds3.c`) doesn't just toggle software state — it drives a real
hardware power-cycle of the DS2 accessory itself: `stop_2nd_usb_host()`, then disables the DS2's
actual power-enable GPIO / VCONN regulator and a load-switch, then powers back on. Confirmed via
kernel log that a genuine multi-stage power-cycle happened: `DS_Recovery_Power_Off →
DS_Recovery_Power_On → DS_Recovery_USB_Wait → DS_Recovery`, with real `hallic`/`typec`/`vbus`
reconnection afterward (not just a state-machine relabeling). Retriggered the standard
`cover_button`/`ds2_pd` sequence immediately after this genuine cold restart of the DS2 hardware.

**Result: identical failure.** Still `DP_AUX_ERR_TOUT`, `EDID read failed` climbing past
count=15 within seconds, same as every prior attempt. This rules out "DS2's own chip is in a
transient/stuck state that a power-cycle would clear" — a real hardware reset of the accessory
itself doesn't change the outcome.

This closes out the last readily-testable software-adjacent hypothesis. Summary of the full
investigation's negative space at this point: kernel driver logic, GPIO mux (both polarities),
regulators, device tree, init.rc/permissions, kernel cmdline, the exact userspace trigger
sequence, proprietary LG binaries, and now a genuine hardware power-cycle of DS2 itself — all
ruled out.

## 2026-08-21 continued: full register-level verification of the PHY/AUX init sequence

Per a detailed external review proposing a stock-vs-LOS register-level diff of the DP init path
(`DP power-on → PHY init → controller init → AUX enable/config → HPD → first AUX transaction`):
live stock is no longer bootable on this phone to get a fresh comparison trace from (see above),
so this became option C from that same review — instrument LOS's own driver at the actual
register-programming point and verify it directly, rather than inferring from source alone.

Instrumented `dp_catalog_aux_setup_v420()` (`dp_catalog_v420.c`) — the function that programs
`DP_PHY_PD_CTL`, `QSERDES_COM_BIAS_EN_CLKBUFLR_EN`, all 10 `PHY_AUX_CFG0..9` registers (from the
`qcom,aux-cfgN-settings` DT tables already confirmed byte-identical to stock), and the AUX
interrupt mask — with a **write-then-readback** check on every single one (safe to make
unconditional: this function runs once per connect attempt, not in a retry loop, so no flood
risk). Result, confirmed across two separate attach cycles: **every single register write reads
back exactly as written** — `DP_PHY_PD_CTL` wrote `0x67` read back `0x67676767` (the register's
normal byte-replication behavior, not a mismatch), `PHY_AUX_CFG1` wrote `0x23` read back
`0x23232323`, and so on for all twelve registers, zero discrepancies either time.

Also traced the exact call ordering: `dp_display_host_init()` → `dp->power->init()` (regulators,
clocks) → `dp->ctrl->init()` → `dp_ctrl_host_init()` → `ctrl->aux->init()` → `dp_aux_init()` →
`catalog->setup()` (the function just verified) → `catalog->reset()` → `catalog->enable(true)`.
Clocks are confirmed enabled before this point (a clock-gated register write would fail to read
back correctly, which never happened). Checked whether `late_phy_init()` — the one function that
reprograms lane/TX-driver PHY state — could be clobbering this AUX_CFG programming afterward:
traced it to `dp_ctrl_link_setup()`, part of the *link training* loop, which only runs after AUX/
DPCD/EDID already succeed. Since that point is never reached, `late_phy_init` never executes in
the failing case — not a factor.

Combined with the earlier finding that `dp_catalog_ctrl_wait_for_phy_ready_v420()`'s unconditional
`DP_ERR("PHY status failed...")` has never once appeared in any capture across this entire
investigation (the PHY reaches ready state within its 10ms timeout, every time), and that the AUX
ISR fires on every single attempt reporting a clean, consistent `DP_AUX_ERR_TOUT` (never any other
error code) — **the entire software-visible register-level PHY/AUX initialization sequence on LOS
is now verified correct end-to-end**, not inferred from source, actually confirmed via hardware
readback. This matches outcome **B** from the reviewer's own framework: registers check out, but
AUX still fails — meaning whatever differs is below what a register dump can show: electrical
behavior, clock/reset timing at a finer grain than what's observable this way, or something
neither the kernel driver's own logic nor its register-level effects can surface from software.

### PHY-domain status registers (not just config) — one genuinely new data point, honestly limited

Per the same review's point 2 ("status registers, not just configuration registers"): found that
`get_irq()` — the function populating `aux->catalog->isr`, the value already logged everywhere
this whole session as `isr=0x200` — is **not overridden for v420**, so it only ever reads
`DP_INTR_STATUS` (the controller/AHB-domain interrupt register). The separate PHY-domain AUX
interrupt status (`DP_PHY_AUX_INTERRUPT_STATUS_V420`, offset 0xD8) and the AUX controller's own
status register (`DP_AUX_STATUS`, offset 0x44) had never been read anywhere in this codebase for
this device. Added both to `dp_catalog_aux_get_irq()`, rate-limited (this fires on every
interrupt, not once per connect, unlike the earlier PHY-setup instrumentation).

Result, consistent across 20 consecutive interrupts in one burst: `ahb_isr=0x00000200` (the
familiar TOUT classification) while **`phy_aux_isr=0x00000000` — completely empty, every single
time** — and `aux_status=0x00000004`, also constant throughout.

What this tells us for certain: the AHB-domain controller's TOUT classification isn't being driven
by any bit becoming set in the separate PHY-domain interrupt-status register — whatever declares
the timeout is internal to the controller's own logic, not something visibly reflected in that
particular PHY status register.

What this does **not** tell us, honestly: neither `DP_PHY_AUX_INTERRUPT_STATUS_V420` nor
`DP_AUX_STATUS` have documented per-bit meanings anywhere in this kernel tree (no macros, just the
raw register offsets) — decoding what `phy_aux_isr=0` or `aux_status=0x04` actually *mean* would
need Qualcomm's TRM, which isn't available here. And without a stock baseline for the same two
registers during a *successful* transaction, there's no way to know whether these particular
values are abnormal or simply what these registers always read regardless of outcome. Recorded as
a genuine new data point, not a conclusion — the honest limit of what register-level software
instrumentation alone can determine without either the TRM or a stock comparison.

## 2026-08-21 continued: exact stock trigger sequence replicated byte-for-byte — still fails

Reconsidered the framing: DS2 hardware itself is validated by the stock-phone success trace (see
"Stock's real success trace" above), so the strongest remaining hypothesis was phone-side
state/config differing between stock and LOS, not defective DS2 hardware. That trace already
recorded the *exact* real trigger stock uses — `cover_button_set : 2 1` (two space-separated
values, `onoff=2 skip_uevent=1`, not the single-value `echo 1 > cover_button` used everywhere
earlier this session) followed ~0.23s later by `ds2_pd_store: hpd_high:1 refresh_layer:1` (also
two values, not the single `echo 1 > ds2_pd` used earlier) — and flagged this exact discrepancy as
the next concrete thing to try, which had never actually been done until now.

Reproduced it precisely: `echo "2 1" > cover_button`, ~0.3s pause, `echo "1 1" > ds2_pd`. Kernel
log confirms **byte-for-byte match** against the stock trace: `cover_button_set : 2 1` and
`ds2_pd_store: hpd_high:1 refresh_layer:1`, identical strings. Result: **still `DP_AUX_ERR_TOUT`,
587/587, one continuous run reaching 401 consecutive retries, zero successes.**

This is a clean, decisive negative for the userspace-trigger-sequence hypothesis specifically (the
exact argument values/format, and the two-step ordering, are not the differentiator — both the
single-value and exact-stock-format triggers produce the identical hardware-level AUX timeout).
Combined with the exhausted software-side kernel investigation above (GPIO mux ruled out,
regulators ruled out), the delta between stock-success and LOS-failure does not appear to be
reproducible through any userspace sysfs trigger tried so far. Two possibilities remain open:
either there's a *different* piece of phone-side state/config (not yet identified — something
earlier in the boot/init sequence, before any of these sysfs triggers ever fire, is the next place
to look if this is picked back up) that genuinely differs between stock and LOS; or the earlier
conclusion (hardware/electrical/AUX-channel-level fault) still stands and the "DS2 works on stock"
evidence needs revisiting — e.g. confirming a truly recent, current stock success trace exists
(the one on file may predate other changes made to this specific phone across this project's many
flash/backup/restore cycles), rather than assuming it's still valid on this exact unit today.

## 2026-08-21 continued: stock-vs-LOS phone-side software delta hunt — three more angles, all negative

Per explicit direction: DS2 hardware treated as known-good (validated by the stock-phone success
trace), not to be revisited. Refocused entirely on finding the phone-side software/config
difference between stock and LOS, working outward from the DP init call graph:

**Device tree.** Extracted stock's actual dtb from the real stock boot image backup
(`stock-backup-20260820/boot_a.img`, Android 11, patch level 2023-03) and diffed the
`qcom,dp_display@ae90000` node against LOS's dtb byte-for-byte. Identical except a single
`clocks` phandle-number difference, and that difference is fully explained by one unrelated extra
`mem-offline` node earlier in stock's tree shifting every subsequent phandle number by a constant
offset (a `dtc` renumbering artifact, not a real config change). No AUX-relevant DT divergence.

**Vendor init.rc.** Mounted stock's real `vendor_a.img` (from the same pre-conversion backup) and
diffed `/vendor/etc/init/hw/` file listings against LOS's live vendor. Many stock files are
genuinely absent from LOS (`init.lge.display.rc`, `init.lge.usb.rc`, `init.timelm_vendor.rc`,
etc.) — expected, since LOS builds its own vendor image from source rather than reusing stock's
blobs, consolidating/renaming files in the process. Checked the one that looked most promising,
`init.lge.display.rc` (its "Accessory/Cover Features" section chowns `cover_button`, `cover_led`,
and critically `.../ae90000.qcom,dp_display/extcon/extcon5/state` to `system`) — every single one
of those lines is already present, byte-for-byte, in LOS's consolidated `init.lge.rc`. Not a gap.
Checked the remaining missing files (`init.kona.rc`, `init.lge.audio.rc`, `init.lge.usb.rc`,
`init.lge.usb.configfs.rc`, `init.lge.fingerprints.rc`, `init.lge.power.rc`, `init.lge.sensors.rc`,
`init.qti.ufs.rc`, `init.target.wigig.rc`, `init.timelm_vendor.rc`) for any DP/AUX/DS2/cover/
extcon5/dualscreen mention — zero hits across all of them. `init.lge.usb.rc` in particular is
entirely legacy USB-gadget/peripheral-mode config (adb/mtp/rndis), unrelated to DP altmode source
functionality.

**Kernel cmdline.** Diffed stock's real boot cmdline (from the same `unpack_bootimg` run) against
every LOS cmdline used this session. Everything differs only in things clearly unrelated to DP
(`reboot=panic_warm`, hash-table tuning, anti-rollback version) except one real, untested
difference: stock carries `video=vfb:640x400,bpp=32,memsize=3072000` (a 3MB virtual-framebuffer
reservation), entirely absent from every LOS boot.img built this session. Tested directly — added
it to the existing, already-instrumented kernel `Image` (no rebuild needed, cmdline-only repack),
flashed, confirmed present in the booted `/proc/cmdline`, triggered the exact stock sequence again:
**202/202 `DP_AUX_ERR_TOUT`, zero successes.** No change.

All three angles targeting "what differs between stock and LOS before AUX traffic begins" are now
clean negatives, on top of the kernel-driver and userspace-trigger angles already ruled out. The
phone-side software surface actually inspected so far is now quite thorough (kernel driver logic,
device tree, regulators, GPIO mux, init.rc/permissions, kernel cmdline, exact userspace trigger
sequence) without finding the delta.

**Second physical phone, zero-risk test.** The user has a second LG V60 (daily driver) already
running official (non-custom-patched) LOS. Attached the same DS2 there: **fails too.** Two useful
conclusions: (1) this isn't something introduced by our custom kernel patches specifically —
official mainline LOS has the identical problem; (2) this is now confirmed failing on two separate
physical phone units (with what's presumed to be the same, or at least a working-on-stock, DS2
unit each time going by the user's account), which weighs against "this one phone's AUX hardware
happens to be damaged" as an explanation, on top of DS2 hardware already being treated as
known-good. The problem is reproducible and systemic to LOS, not a one-off unit fault on either
side of the connection.

## 2026-08-22: retrospective — how the problem actually shrank

Looking back across the whole investigation, the most consequential finding wasn't any single
register value — it was how much of the stock-vs-LOS gap turned out **not** to be the explanation,
each time cleanly ruled out with a real A/B result rather than assumed away:

```text
Initial hypothesis:              LOS is missing DS2 support outright
        ↓
Kernel comparison:                DS2 hardware interfaces already present, byte-identical
        ↓
Stock HAL transplanted to LOS:    the native HAL runs fine — not incompatible with LOS
        ↓
Framework comparison:             LOS has zero LG DS2 framework integration (the real HAL gap)
        ↓
Passive display test:             stock itself doesn't bring DP up on attach+open alone either
        ↓
Kernel/AUX register investigation: the remaining failure sits below the HAL/framework layer
                                    entirely, in the AUX transaction itself
```

**1. Kernel/USB DS2 hardware path: effectively identical, confirmed via a clean A/B, not
inference.** `/sys/class/dualscreen/ds2`, `/sys/class/smartcover/*`, `/dev/hidraw0`,
`/dev/ttyACM0`, the `1004:637a` USB device, `lge_usb_ds3`, DS2 extcon state, and the proprietary
HID ioctls all present on both. The clearest single data point: `ioctl(hidraw0, 0xC0074807)`
returned byte-identical output (`ret=7, 02 00 01 40 00 03 01...`) on stock and LOS. The kernel ABI
the stock HAL depends on was never missing from LOS.

**2. The proprietary `dualscreen` HAL looked like the obvious gap — until the stock binary was
proven to run on LOS unmodified.** Stock had `vendor.lge.hardware.{dualscreen,coverdisplay,
accessory}` registered; LOS had none. But once the missing vendor environment was supplied (the
Magisk HAL shim built earlier in this project), the stock binary reproduced its full expected
init sequence on LOS — HAL constructor, `hidraw0`/`ttyACM0` open, `idVendor=1004 idProduct=637a`,
`hidDisplayDeviceAdded`, `HID-DISPLAY: x:256 y:64 format:3 ready:1`, `initSubDisplay`,
`setSubDisplayPowerState`. The native HAL was never fundamentally incompatible with LOS.

**3. The real framework gap was much larger than the HAL gap, and this is the one still-open,
separately-tracked porting problem.** Stock's framework has `IDisplayManagerEx`,
`ISubDisplayCallback`, `SubLcdController`, `DisplayManagerServiceEx`, `DisplayManagerHelper` —
LOS's `services.jar` and framework have zero trace of any of it. So even with the stock HAL
correctly registered and running on LOS, nothing in the LOS framework calls into it. This cleanly
separates the project into three layers: kernel/hardware (✓ working), native HAL (✓ working once
deployed), framework integration (✗ — a real, substantial, separate porting task, tracked
elsewhere in this doc's HAL-port sections, not the subject of the AUX investigation).

**4. Stock's own architecture has two separate DS2-related paths, which matters for interpreting
"nothing appears."** `SubLcdController` → `DisplayManagerServiceEx` → `IDisplayManagerEx` →
`LGSubDisplay` is the HID/cover-window bridge. Separately, `LocalDisplayAdapter` has its own
generic DRM path that would recognize a genuine second physical display as a "Built-in
Cover-Screen" via `getDisplayCableStatus()`. These are not the same subsystem, and conflating them
was an early risk this investigation avoided by testing directly rather than assuming.

**5. The passive "case attached/open" A/B was the finding that redirected the whole
investigation.** With DS2 attached and the case open, stock and LOS produced identical results:
`extcon DS2=1`, `DP=0`, DRM `DP-1` disconnected, DisplayManager sees only display 0, on **both**.
Stock does not bring the DP link up merely from attach+open — it needs the active
`cover_button`→`ds2_pd` trigger sequence documented earlier in this doc. This ruled out "install
the HAL and the screen will appear" before any HAL-porting effort was wasted chasing it.

**6. The AUX investigation then showed the surviving gap sits below all of the above.** With DS2
detection, `hidraw`, the custom HID ioctl, extcon state, GPIO/mux configuration, regulators,
`PHY_READY`, and AUX register programming all confirmed identical or independently verified
correct on LOS, the DP link still fails when the link path is actually exercised. The clearest
remaining data: on the failing LOS path, controller AUX ISR reads `0x200` (TOUT), PHY-domain AUX
ISR reads `0x000`, `AUX_STATUS` reads `0x004` — but **a genuine, contemporaneous successful stock
trace at those exact same three registers was never captured**, since stock could not be gotten to
boot again on this phone by the time this instrumentation existed. That specific comparison — the
one piece that could most directly explain why stock and LOS diverge despite everything else
proven identical — remains the single unfinished experiment, blocked on either a working stock
boot on this phone or Qualcomm's TRM (neither available as of this writing). Everything short of
that has been checked, and checked rigorously: not "the source looks right" but "the actual
hardware state was read back and confirmed."

## 2026-08-22 continued: closing two loose threads, both dead ends; restating the actual ceiling

Re-read this doc end to end looking for anything the exact-trigger-sequence and register-level
work might have left unexamined.

**`dualscreen@1.1-impl.so` gates `ds2_pd`/`ds2_hal_ready` behind an `AT%HPD` command exchange**
with the DS2's own MCU over the AT-command channel (`"%s:HPD status changed :%s, ret:%d,
trial:%d"` / `"%s: HPD Status failed Status:%s, trial:%d"`, both present in the binary alongside
other real `AT%`-prefixed commands to the same MCU — `AT%DB=`, `AT%DS=`, `AT%SHS=`, touch-debug
commands, etc., confirming this is a genuine serial AT-command protocol, not cellular-modem
related). This looked like a candidate missing handshake — but this binary is the exact same
unmodified vendor blob already proven running byte-identical on both stock and LOS (the
Magisk-shim section above), so the code path itself can't be the differentiator. Dead end,
reinforces the existing parity finding rather than adding a new one.

**The "next concrete thing to try" flagged at the end of the stock-success-trace section —
replicate `cover_button_set : 2 1` before `ds2_pd_store: hpd_high:1 refresh_layer:1`, same
two-value format, ~0.2–0.3s apart — was already executed** in the very next section
("exact stock trigger sequence replicated byte-for-byte") and still failed, 587/587,
`DP_AUX_ERR_TOUT`. Restating this clearly here because the success-trace section's own closing
paragraph could otherwise read as an open, untried suggestion to a future pass through this doc —
it was tried, byte-for-byte, and is a clean negative. The gap is not a missing or malformed
userspace trigger; every operation in the real stock trace has been reproduced verbatim on LOS.

**Early-firmware partitions (`xbl`, `xbl_config`, `tz`, `hyp`, `aop`, `devcfg`, `qupfw`,
`cmnlib`/`cmnlib64`) were considered as an "earlier in boot" candidate and ruled out without
needing the device**: these are OEM-signed blobs outside the LineageOS build entirely — no LOS
install path for this device family flashes them, sideload and fastboot instructions only ever
touch `boot`/`dtbo`/`vendor_boot`/`system`/`vendor`/`product`/`vbmeta`-tier partitions. They stay
byte-identical to whatever shipped on the device before conversion, on this phone and on the
second, untouched phone alike. Not the explanation.

**Restated ceiling, unchanged from the previous entry**: every phone-reachable software layer —
kernel driver logic, device tree, init.rc/permissions, kernel cmdline, GPIO mux (both polarities),
regulators, the exact userspace trigger sequence (both loose and byte-exact stock format), and now
the native HAL's own AT-command gating — is a clean, empirically-tested negative, corroborated by
an untouched second phone showing the identical failure. The one experiment that could still move
this forward — a genuine, contemporaneous successful-stock capture of `ahb_isr`/`phy_aux_isr`/
`aux_status` at the same three registers now instrumented on LOS — needs stock booting on real
hardware, which is not currently available and was explicitly not pursued further. Nothing
software-reachable from this side of the link remains unchecked.

## 2026-08-22 continued: scoping the framework-port track (Problem A + the cover-window bridge)

Went back through the full doc to scope next work, since the "start scoping" request assumed the
Java framework consumer needed to be built from scratch. **It doesn't — most of it is already done
and verified on real hardware.** Correcting the record before laying out what's actually left.

**Already done, not part of this scope**: `DualScreenBridgeDaemon`/`SubLcdController`
(`los/patches/dualscreen-port/`) is a complete, compiled, working `IDisplayManagerEx` bridge —
constructs `SubLcdController` standalone (LOS's `DisplayManagerService` is `final`, so the stock
in-process subclass pattern isn't reachable), registers `"dualscreen_ex"` via
`ServiceManager.addService()`, launched at boot via a Magisk `service.sh` (native `init.rc`
autostart doesn't take under this build's Magisk overlay timing — root cause not found, currently
just worked around). **Visually confirmed driving real pixels on the physical cover window**:
`getSubDisplayInfo`/`setSubDisplayPowerState`/`setSubDisplayBrightness`/`drawSubDisplay` all
round-trip through `com.test.BridgeClient` → `notifyStateChanged()` called back from the native
HAL → real backlight and framebuffer changes on the physical display. This is the "framework
integration" gap identified earlier in the doc — for the cover-window/HID path specifically, it's
closed.

This still leaves two independent, bounded workstreams (both concrete, neither blocked on
anything unavailable — unlike Problem B/AUX, which stays parked per the standing decision):

### Workstream 1 — DS2 USB attach reliability (Problem A)

Root cause was already traced to source, earlier in this doc: `EXTCON_USB_HOST` gets set once at
boot by `start_2nd_usb_host()` and then **never clears**, live-confirmed
(`/sys/class/extcon/extcon5/state` reads `USB-HOST=1` even fully disconnected) — so
`dwc3_otg_start_host()`'s fresh xHCI re-init (which stock gets on *every* attach) only ever runs
once, and every later attach reuses stale controller state, hence the intermittent
`hub failed to enable device, error -108`. Confirmed present in `kernel_official`
(`drivers/usb/misc/lge_ds3.c`, `stop_2nd_usb_host()`/`start_2nd_usb_host()` at lines 945-972, same
logic, 9 call sites through the state machine) — the tree this session's verified toolchain
already builds cleanly. Not yet found: *why* `stop_2nd_usb_host()`'s
`extcon_set_state_sync(..., 0)` fails to stick on disconnect.

1. Re-verify live on the current build (this earlier finding predates the kernel-source
   correction this session made) — physical attach/detach cycles under continuous `/dev/kmsg`
   capture.
2. Trace which of the 9 `start_2nd_usb_host`/`stop_2nd_usb_host` call sites should fire on a real
   disconnect, with `pr_err` instrumentation (same technique already proven this session) to see
   whether it's reached at all, or reached but the extcon-core write doesn't take.
3. Fix, rebuild, reflash (the now-standard `boot_b` cycle), validate against a real target: well
   above the ~20% baseline organic-attach success rate measured earlier.

Bounded, real kernel work, using tooling already proven this session. Independent of the
unsolved AUX mystery — reliable attach directly benefits the already-working cover-window path
regardless of whether Problem B ever gets solved.

### Workstream 2 — productize the cover-window bridge

The mechanism works; what exists today is a loose collection of files plus a manual test client,
not something durable.

1. Packaging decision: a polished personal-use Magisk module (small effort, builds directly on
   what's proven working) vs. real `frameworks_base`/device-tree source integration (upstream-
   grade, much larger effort). Default recommendation: (a) first.
2. Root-cause or accept the `init.rc` autostart gap; if accepted, harden the `service.sh` launch
   (respawn on crash, logging).
3. Package as a clean, versioned Magisk module (proper `module.prop`, layout, uninstall-safe) so
   it survives future reflashes without hand-reassembly.
4. Product decision, not engineering: what should `drawSubDisplay()` actually render day to day —
   currently only a hand-built solid-fill test buffer has ever been drawn. Needs the user's input.

### Build-process gotcha found the hard way: `mkbootimg` needs `--cmdline`

Cost five wasted build/flash cycles on 2026-08-22 before being caught, so recording it
prominently. Every boot image repacked that day failed identically — Android's first-stage
`init` aborting ~1.6s into boot with:

```
init: [libfstab] ReadDefaultFstab(): failed to find device default fstab
init: Failed to create FirstStageMount : failed to read default fstab for first stage mount
init: InitFatalReboot: signal 6
```

then rebooting straight to fastboot (not a bootloop — `init`'s own fatal-reboot path). The
failure was identical whether `lge_ds3.c` carried new instrumentation, carried rate-limited
instrumentation, or was **fully reverted to unmodified stock content** — including on a
`rm -rf out_official` clean rebuild. That control result is what finally forced looking outside
the kernel source entirely.

Root cause: the `mkbootimg` invocation omitted `--cmdline`, producing an image with an **empty**
kernel command line. Diffing `unpack_bootimg` output between a known-good image and a failing one
showed it immediately:

```
good: androidboot.memcg=1 lpm_levels.sleep_disabled=1 msm_rtb.filter=0x237
      service_locator.enable=1 swiotlb=2048 loop.max_part=7
      androidboot.usbcontroller=a600000.dwc3 kpti=off cgroup.memory=nokmem,nosocket
      androidboot.hardware=timelm androidboot.init_fatal_reboot_target=recovery
bad:  (empty)
```

`androidboot.hardware=timelm` is what `init` uses to locate the device fstab — without it the
fstab lookup cannot succeed, hence the abort; `androidboot.init_fatal_reboot_target=recovery`
explains landing in fastboot rather than looping. Every other header field (offsets, pagesize,
header_version, os_version/patch_level, dtb address) already matched the known-good image
exactly; cmdline was the only gap.

**Prevention**: repacking now goes through `/home/tmmh/v60-re/repack_boot.sh <label>`, which
hardcodes the verified cmdline and refuses to emit an image whose cmdline doesn't contain
`androidboot.hardware=timelm`. Use it rather than hand-rolling `mkbootimg`.

Two diagnostics that made this findable and are worth reusing: `/sys/fs/pstore/console-ramoops-0`
survives the failed boot and holds the complete console log including the abort and backtrace
(pull it after reverting to a working kernel); and `unpack_bootimg --out <dir>` on both a
known-good and a failing image gives a direct header + per-component checksum comparison.

### Problem A re-tested with real instrumentation: does NOT reproduce — earlier root cause is stale

Added unconditional (rate-limited) `pr_err` instrumentation to `lge_ds3.c` — `set_hallic_status()`
entry + both branches, `start_2nd_usb_host()`/`stop_2nd_usb_host()` entry with `extcon_get_state()`
read **before and after** each `extcon_set_state_sync()`, and the `ds3_sm()` disconnect branch.
This was necessary because those functions' only existing log calls are `dev_dbg()`, and
`CONFIG_DYNAMIC_DEBUG` / `CONFIG_DEBUG_FS` are both **off** in this kernel's verified config —
so every earlier "the function didn't run" inference drawn from their silence was unfounded:
the log statements are compiled to no-ops regardless of what executes.

Captured 4 real physical attach/detach cycles (on-device `cat /dev/kmsg > /data/local/tmp/...`
running detached via `setsid`, so the capture survives the USB port being occupied by DS2 — much
more reliable than wireless adb, which kept dropping).

**Result — the documented "stuck `EXTCON_USB_HOST`" root cause does not reproduce:**

```
attach:  start_2nd_usb_host: ENTRY EXTCON_USB_HOST=0  ->  after set_state_sync(1), readback=1
detach:  stop_2nd_usb_host:  ENTRY EXTCON_USB_HOST=1  ->  after set_state_sync(0), readback=0
```

Clean 0→1→0 on every one of the 4 cycles, with the readback confirming the write took effect.
`stop_2nd_usb_host()`'s clear **does** stick. And the downstream consequence that motivated the
whole workstream is likewise absent:

```
dwc3_otg_start_host: turn on host          <- fires on EVERY attach (doc recorded 0 after boot)
usb 1-1: new full-speed USB device number 2 using xhci-hcd   <- enumerates every time
ds3_usb_notify: USB_DEVICE_ADD: idVendor:1004 idProduct:637a <- detected every time
dwc3_otg_start_host: turn off host         <- clean teardown on every detach
```

**Zero occurrences of `hub failed to enable device, error -108`.** 4/4 successful attaches versus
the ~20% success rate measured earlier.

Honest caveats: 4 cycles is a small sample (though 4/4 at the old 20% rate is ~0.16% likely, so
this is probably a real change, not luck — worth confirming with 10+ more). The most plausible
explanation is that the earlier Problem A analysis predates this project's switch to the correct
LOS kernel source (`kernel_official`, established 2026-08-21) — those observations may have come
from a different kernel tree/build entirely, and some were on the other physical unit.

### Current actual blocker: the DS2 HAL Magisk module is gone from the phone

The state machine now reliably reaches `Unknown -> DS_Startup -> DS_USB_Wait` and enumerates the
DS2 — then stops. `DS_USB_Wait -> DS_Ready` never happens because that transition is driven by
`ds2_hal_ready_store: ready:1`, written by the native HAL, and **`/data/adb/modules/` on the phone
is empty** — the `lge_ds2_hal_shim` module (stock HAL binary + 9 vendor libs + `libusbhost.so` +
manifest fragment + `service.sh`) is no longer installed, lost at some point across this project's
many reflash cycles. The built zip is not on local disk either; it was produced in an earlier
session and never preserved.

So the DS2 kernel/USB path is currently in better shape than this doc previously recorded, and
the immediate gap is a packaging/deployment one, not a kernel one. Everything needed to rebuild
the module still exists (stock `vendor_a.img` backup, the decompiled/compiled `dualscreen-port`
tree including `build_dex/classes.dex`), and the rebuild is tracked as its own task.

### DS_Ready reached on LOS via an organic attach — the module is rebuilt and working

Rebuilt the `lge_ds2_hal_shim` module from scratch (it was gone from the phone and no zip
survived on disk). Source tree now lives at `/home/tmmh/v60-re/magisk_module/lge_ds2_hal_shim/`
with the built zip beside it, so it can't be lost again. Contents: the stock
`vendor.lge.hardware.dualscreen@1.1-service` binary, its impl `.so`, the **verified-complete**
vendor dependency closure (8 libs — derived from `readelf -d` rather than trusting the earlier
hand-written list; the closure came out exactly matching, nothing missing or extraneous), a
private `/vendor/lib64/libusbhost.so`, a VINTF manifest fragment, the stock `.rc` (reference
only), `dualscreen-bridge.dex`, and a detached supervisor launcher.

**Result — the full chain fires on a real physical attach, with no manual intervention:**

```
set_hallic_status: enable=1
ds_set_state: Unknown -> DS_Startup
start_2nd_usb_host: ENTRY EXTCON_USB_HOST=0 -> readback=1
dwc3_otg_start_host: turn on host
ds_set_state: DS_Startup -> DS_USB_Wait
usb 1-1: new full-speed USB device number 2 using xhci-hcd
ds3_usb_notify: USB_DEVICE_ADD: idVendor:1004 idProduct:637a
ds2_hal_ready_store: ready:1, recovery:0      <- the HAL, running from the module
ds_set_state: DS_USB_Wait -> DS_Ready         <- first time on LOS from an organic attach
hallic_state_notify: SWITCH_STATE=1
```

Registration confirmed: `lshal` shows `vendor.lge.hardware.dualscreen@1.0::IDualScreen/default`
**and** `@1.1::…` both `Y` on one pid, and `service list` shows
`dualscreen_ex: [android.hardware.display.IDisplayManagerEx]`. A live client call through the
whole stack returns real hardware data: `getSubDisplayInfo: ok=true width=256 height=64 format=3`.
All of this comes up automatically at boot and survived idle time with no HAL restarts.

Two self-inflicted boot hangs were hit and fixed getting here; both are written up in the
module's own README, and the VINTF one is worth repeating because the failure mode is so
disproportionate: listing `@1.0` and `@1.1` as two `<fqname>` entries under a single `<hal>`
element is illformed (`Duplicated major version: 1.0 vs 1.1`), and an unparseable fragment
invalidates the **entire device manifest** — every HAL on the device stops resolving and the
system hangs at the LG logo. Declaring `@1.1` alone is correct and still serves `@1.0` clients,
which `lshal` confirms. Diagnosis was straightforward because **adb stays up while hung**:
`logcat | grep hwservicemanager` named the file and the reason directly.

**State after this work**: `DS2=1`, `DS_Ready`, HAL + framework bridge live and auto-starting.
Still `DP=0`, `card0-DP-1: disconnected`, `ds2_pd=0`, one display in SurfaceFlinger — i.e. we are
now exactly at the Problem B boundary, with everything upstream of it working. The
`cover_button`/`ds2_pd` trigger never fires on its own here because on stock it comes from the
coverdisplay/accessory HALs reacting to the hinge, which this module does not deploy.

### Full stock userspace stack reproduced; DP reaches READY; AUX is now the *only* failure

Deployed all three stock HALs together (`accessory@1.1` + `accessory.uevent@1.2`,
`coverdisplay@1.0`, `dualscreen@1.1`) via the module. All register cleanly:

```
DM Y vendor.lge.hardware.accessory@1.0 / @1.1 ::IAccessory/default          pid 17506
DM Y vendor.lge.hardware.accessory.uevent@1.0 / @1.1 / @1.2                 pid 17506
DM Y vendor.lge.hardware.coverdisplay@1.0::ICoverDisplay/default            pid 18274
DM Y vendor.lge.hardware.dualscreen@1.0 / @1.1 ::IDualScreen/default        pid 18379
```

**Found the `cover_button` writer**: `grep -rl cover_button` across the stock vendor image
matches only `vendor.lge.hardware.accessory@1.1-service` and its impl `.so`. So the accessory
HAL owns that write, consistent with the stock trace logging it from a `HwBinder:*` *server*
thread (something called into it over HIDL). Running the HAL alone is not enough — the hinge
fires correctly at the kernel level (`ds3_smart_cover state switched to CLOSE`/`OPEN`, captured
5 clean cycles, identical to stock) but no `cover_button` write follows, because on LOS nothing
in the framework calls into the accessory HAL.

Issued the two writes manually instead, with the whole stock HAL stack live. **The entire chain
then executed, for the first time on LOS:**

```
cover_button_set : 2 1
ds2_pd_store: hpd_high:1 refresh_layer:1
ds_dp_hpd_direct: is_dp_hpd_high:0 hpd:1
dp_usbpd_get_status: hpd_high=1                      <- HPD HIGH, never reached before
dp_display_usbpd_attention_cb: hpd_irq:0, hpd_high:1
dp_display_process_hpd_high: add DP_STATE_CONNECTED
dp_display_host_init: add DP_STATE_INITIALIZED
                     remove DP_STATE_SRC_PWRDN
dp_display_host_ready: add DP_STATE_READY
   final state (0xf): |CONFIGURED||INITIALIZED||READY||CONNECTED|
```

Then, unchanged: **328 consecutive `DP_AUX_ERR_TOUT`**, 31 `EDID read failed`, zero successful
transactions, same signature as every prior attempt (`ahb_isr=0x200`, `phy_aux_isr=0x00000000`,
`aux_status=0x00000004`), across native DPCD writes to `0x600` and I2C-over-AUX to `0x50`.

**What this changes.** Every earlier AUX test (including the 587/587 run) was done with *no* LG
HAL running and DP never past `CONFIGURED`. Now the complete stock userspace stack is live, the
DP state machine reaches `READY|CONNECTED`, the PHY init sequence runs in full — and the failure
is isolated purely to the AUX transaction itself. The "maybe the DP stack was never properly
brought up" possibility is now closed off; nothing in the software bring-up path remains
untested.

**Correction to an earlier claim in this doc, now resolved.** The PHY register readbacks were
recorded as verified with "zero discrepancies". They are not equal to what was written; they are
**byte-replicated**: `wrote=0x67 readback=0x67676767`, `wrote=0x23 readback=0x23232323`, etc.,
for every one of `PHY_AUX_CFG0..9`, `DP_PHY_PD_CTL`, `QSERDES_COM_BIAS_EN_CLKBUFLR_EN`, and
`DP_PHY_AUX_INTERRUPT_MASK_V420`.

**This is benign, confirmed by the external-sink A/B**: the *working* external-sink connect shows
the identical byte-replicated readbacks, value for value, across all 13 registers. Replication is
therefore just how these 8-bit PHY registers present on a 32-bit read on this SoC, not evidence
of a bad write. And since the working and failing paths program the PHY **byte-identically**, PHY
configuration is conclusively not the differentiator.

### Two more AUX hypotheses tested and closed: fake-VDM sequence, and the SBU AUX switch

**Fake-VDM negotiation — identical to stock.** Stock's success trace runs
`don't send vdm to DS` (x4) → `USBPD_SVDM_DISCOVER_MODES` → `USBPD_SVDM_ENTER_MODE` →
`DP_USBPD_VDM_STATUS` → `DP_USBPD_VDM_CONFIGURE` → `set state Dualscreen Connected`. Counted the
same commands across three separate LOS captures: **exactly the same set, same counts**, plus
`USBPD_SVDM_ATTENTION` with `*vdos = 0x9a` on the hpd trigger. `dp_usbpd_get_status` reports
`adaptor_dp_en = 1, multi_func = 1, usb_config_req = 0, hpd_high = 1, hpd_irq = 0` and
`dp_usbpd_init_port: port:DP_USBPD_PORT_DFP_D` — `multi_func=1` implying the 2-lane assignment
that matches stock's `lane_count=2`. `VDM_CONFIGURE` (which carries pin assignment / lane
mapping) genuinely runs. Not the differentiator.

**The `lge_sbu_switch` AUX analog switch — a strong-looking lead, cleanly ruled out.** This is
LG's own SBU/CC protection switch driver (`drivers/usb/misc/lge_sbu_switch.c`), and `lge_ds3.c`
calls `lge_sbu_switch_get(inst, LGE_SBU_SWITCH_FLAG_SBU_AUX)` from `ds_dp_config()` to physically
route the AUX lines. Promising because the driver contains a device-tree-controlled inversion:

```c
if (lge_sbu_switch->reverse_switch) {   /* DT: lge,reverse-sbu-switch */
        if (sbu_oe == 0)
                sbu_sel = !sbu_sel;
}
```

and the `SBU_AUX` case's base value is `sbu_sel = 0`, yet the live log shows `SEL(1)` — i.e. the
inversion is active. Exactly the shape of the `aux-sel` inversion bug chased earlier, and never
previously checked (the earlier DT comparison only diffed the `qcom,dp_display@ae90000` node).

Verified end to end and it is **not** a divergence:
- `CONFIG_LGE_USB_SBU_SWITCH=y` and `CONFIG_QCOM_FSA4480_I2C=y` on both the built config and the
  running device.
- The switch demonstrably engages, logged unconditionally by the driver:
  `SBU: OE(0) SEL(0) ... flag "Idle"` → `SBU: OE(0) SEL(1), UART: OE(0) SEL(0), flag "AUX"`.
- Carved every overlay out of both dtbo images (11 in stock's `dtbo_a.img`, 18 in LOS's live
  `dtbo_b`) and decompiled each: **all 11 stock and all 18 LOS overlays carry the
  `lge_sbu_switch` node with `lge,reverse-sbu-switch` set.** Stock inverts SEL identically.
- Full node diff, stock vs LOS: identical except one phandle number (`0x108` vs `0x100`) — the
  same benign renumbering artifact seen in the earlier `dp_display` comparison. Same GPIOs
  (`sel` = tlmm 50, `uart-sbu-sel` = tlmm 62, `uart-edp-oe` = tlmm 84).

So the AUX routing path is configured identically to stock, and engages correctly at runtime.
Another clean negative.

### DECISIVE: DP AUX works on LOS with an external sink — the failure is DS2-path-specific

The experiment that had never been run: attach a **known-good external DP sink** (USB-C hub with
HDMI out, real monitor connected) to the same phone, same LOS kernel, same session, and see
whether AUX works at all. Done over wireless adb with an on-device detached `cat /dev/kmsg`
capture so the cable swap didn't interrupt logging.

**Result — AUX succeeds:**

```
usbpd usbpd0: received vdm cmd: VDM_CONFIGURE (SVID=ff01)     <- real over-the-wire VDM
dp_aux_configure_aux_switch: enable=1, orientation=2, event=2
dp_display_process_hpd_high: add DP_STATE_CONNECTED
[drm-dp] SINK DPCD: 12 14 c2 01 01 15 01 01 02 00 04 00 00 00 00 00   <- DPCD READ OK
dp_panel_read_dpcd: version:1.2, rate:540000, lanes:2
dp_panel_read_edid 2034 EDID read successed, count=1                  <- EDID READ OK
dp_ctrl_on: bw_code=20, lane_count=2
```

Native DPCD reads and I2C-over-AUX EDID reads — **the exact two operations that fail 100% of the
time on DS2** — both complete successfully. Zero AUX timeouts during the DPCD/EDID phase (the 6
`DP_AUX_ERR_TOUT` in the whole capture are ~50s later, after the session had collapsed and was
retrying).

**What this establishes.** The DP controller, the AUX engine, the PHY, and the AUX switch path
are all functional on this LOS kernel build. The 328/328 `DP_AUX_ERR_TOUT` on DS2 is therefore
**not** a generic "LOS DP/AUX is broken" defect — it is specific to the DS2 connection path. This
closes off the largest remaining alternative explanation and is the sharpest narrowing achieved
for Problem B.

Note the parameters are identical between the two paths, so the divergence is not in this
configuration: `dp_aux_configure_aux_switch` is called with **`enable=1, orientation=2, event=2`
in both** the DS2 and external-sink cases, and both report `adaptor_dp_en=1, multi_func=1,
usb_config_req=0, port:DP_USBPD_PORT_DFP_D`. The difference is that the external sink negotiates
a real VDM over the wire (`received vdm cmd: VDM_CONFIGURE (SVID=ff01)`) whereas DS2 uses LG's
synthesized handshake — but both end up driving the same DP configuration.

**Secondary observation, reported without overclaiming**: link training was unstable even with
the external monitor (`link training #1 successful` twice, `#2 failed`, then repeated
`#1 failed`), and DP never fully came up (`card0-DP-1: disconnected`, `DP=0`). This could be hub,
cable, or monitor quality rather than the phone; a different adapter/display would be needed to
attribute it. It does not affect the AUX conclusion above, which rests on completed DPCD and EDID
transactions.

### A/B on the same boot: every software-observable AUX setting is identical

With both a working (external sink) and a failing (DS2) AUX path reproducible on the same boot,
compared the actual state at AUX time. **Everything observable matches:**

| Setting | External sink (AUX works) | DS2 (AUX fails) |
|---|---|---|
| `lge_sbu_switch` at AUX time | `OE(0) SEL(1), UART OE(0) SEL(0)`, flag `"AUX"` | **identical** |
| `qcom,aux-en-gpio` | 0 | 0 |
| `qcom,aux-sel-gpio` | 1 | 1 |
| `qcom,usbplug-cc-gpio` (driven) | 0 | 0 |
| `dp_aux_configure_aux_switch` | `enable=1, orientation=2, event=2` | identical |
| `dp_usbpd_get_status` | `adaptor_dp_en=1, multi_func=1, usb_config_req=0` | identical |
| port | `DP_USBPD_PORT_DFP_D` | identical |
| PHY init regs | `PHY_AUX_CFG0..9` + PD_CTL + BIAS_EN, same values | identical |

Two incidental notes, neither a divergence:

- **`dp_aux_configure_aux_switch` is a no-op on this device.** The actual FSA4480 I2C
  programming sits behind `#if !defined(CONFIG_LGE_DISPLAY_COMMON)`, and
  `CONFIG_LGE_DISPLAY_COMMON=y` here. The debug line prints *before* that guard, so identical
  log output on both paths means nothing was programmed either way. AUX routing on this device
  is done entirely by `lge_sbu_switch`.
- The one raw difference, `[Display DP] usbplug_cc 1165 gpio value : 1` (hub) vs `: 0` (DS2), is
  logged in `dp_power_request_gpios()` and is just `gpio_get_value()` *before* the pin is driven
  — the pre-existing orientation sense, not a configuration difference. Both paths then drive
  `usbplug-cc = 0`, since DS2 force-sets `ORIENTATION_CC2` and the hub negotiated CC2 anyway.

Also worth recording: `aux-sel` is currently **1 for both** paths, because the tree still carries
the experimental patch removing the DS2 inversion. So the working and failing cases now share the
same `aux-sel` value, independently reconfirming that `aux-sel` polarity is not the cause.

**Conclusion**: on the same kernel, same boot, with byte-identical mux, GPIO, PHY and DP
configuration, AUX completes DPCD and EDID transactions against an external sink and times out
100% of the time against DS2. No software-observable configuration difference remains to explain
it.

**The most promising remaining software lead** is the DS2's own microcontroller. The stock
`dualscreen@1.1-impl.so` talks to it over `/dev/ttyACM0` with AT commands, including `AT%HPD`
(with retry/status logging: `"%s:HPD status changed :%s, ret:%d, trial:%d"`). If the DS2-side DP
receiver has to be commanded up over that channel and never is, the sink would never drive AUX
and every transaction would time out exactly as observed. That channel is now testable: the HAL
is running, `/dev/ttyACM0` is present, and AT commands can be issued directly.

### The DS2 microcontroller is reachable, and `setPowerStatus` turns its HPD on

Followed the AT-command lead. Rather than writing to `/dev/ttyACM0` directly (the HAL owns that
fd), drove the channel through the HAL's own `IDualScreen.sendAtCommand()` HIDL method, via a
small client compiled against the existing `dualscreen-port` classes and run under
`app_process64` (`com.test.AtProbe`, `com.test.DsPower`; sources kept in
`los/patches/dualscreen-port/src/com/test/`).

**The DS2's microcontroller answers, and reports its DP receiver was never enabled:**

```
AT [AT%SWV]          -> status=0 resp=[LMV600N-V01m_00]     <- DS2 firmware version
AT [AT%GETDSCUTOUT]  -> status=0 resp=[M 0,0 L -23.23, 0 L ... @dp]
AT [AT%HPD]          -> status=0 resp=[Off]                 <- DS2 side HPD OFF
```

`AT%HPD` is read-only (`AT%HPD=On` just echoes the query result), consistent with the impl's
polling strings (`"HPD status changed :%s"`, `"HPD status: %s"`). The assertion has to come from
elsewhere — and it does:

```
HPD before                    : Off
getDS2Status                  : is_ds2_connected=1
setPowerStatus(true,true)     : 0
HPD after power               : On      <- DS2 now asserts HPD
```

**`IDualScreen.setPowerStatus(true, true)` is a concrete call that LOS never makes and that
demonstrably changes DS2 hardware state.** This is the first time anything on LOS has moved the
DS2's own DP receiver out of its powered-down state.

**It did not fix AUX, and that is worth stating plainly.** With HPD confirmed `On` and `ds2_pd=1`,
re-running the `cover_button` + `ds2_pd` trigger — and again with a full `ds2_pd` 0→1 cycle so the
DP stack got a fresh connect with HPD already asserted — still yields zero successful
transactions: no `SINK DPCD` line, `error reading DP_TRAINING_AUX_RD_INTERVAL`, `EDID read failed`
repeating, all AUX attempts `DP_AUX_ERR_TOUT` with the familiar `isr=0x200`.

What did change is how far the stack gets. Previously AUX died immediately on the `0x600`
(SET_POWER) write. Now the driver walks a much wider address range — `0x0` (DPCD_REV), `0x100`
(LINK_BW_SET), `0x102` (TRAINING_PATTERN_SET), `0x50` (EDID over I2C), `0x30010` — i.e. it
proceeds into link training and retries there (retry counters into the 800s) rather than failing
at the first write. That is consistent with the DP source side now believing it has a live sink.

**Net position**: the DS2 side can now be commanded from LOS (AT channel proven, firmware version
readable, HPD controllable), and a specific missing HAL call has been identified. AUX itself still
does not complete against DS2 while completing normally against an external sink on the same boot.

### DS2-side state fully queried: everything the accessory reports is healthy

Enumerated the whole `IDualScreen` HIDL surface (43 methods in V1_0, plus V1_1's
`ds_update_state`, `setAesMode`, `manageAesMode`, `set_touch_perf`) and queried every read-only
diagnostic, after `setPowerStatus(true,true)`:

```
getGpiopin              : res=0 [reset_pin = 1, int_pin = 1]
getDS2Connect           : true
getSubDisplayPowerState : 3            (SUB_SCREEN_ON)
getFirmwareVersion      : res=0 [LMV600N-V01m_00]
AT%HPD                  : On
```

The accessory reports itself connected, powered, and asserting HPD — and AUX still times out
100% of the time.

Also probed the remaining AT commands seen in the impl (`AT%DS`, `AT%SHS`, `AT%SMODE`, `AT%DB`,
`AT%PRGB`) as bare queries: all return `status=1` with an empty response, i.e. they are setters
requiring `=value`, not queries. Cross-referencing against the HIDL surface, they line up with
the already-exposed setters (`setScreenMode` → `AT%SMODE=`, `setRgbTune` → `AT%PRGB=`,
brightness → `AT%DB=`/`AT%DS=`). **No "enable DisplayPort" AT command exists in the impl** —
`AT%HPD` is the only DP-related one and it is read-only. (`AT%GODLOAD` / `AT%GOSTMDLOAD` were
deliberately never sent: they put the MCU into firmware-download mode.)

### Where Problem B stands after all of this

Every layer of the software path has now been compared between a **working** AUX connect
(external DP sink) and a **failing** one (DS2), on the same kernel and the same boot:

| Layer | Result |
|---|---|
| VDM / alt-mode negotiation | equivalent (real VDM vs LG's synthesized, both reach same DP config) |
| `dp_usbpd_get_status` | identical (`adaptor_dp_en=1, multi_func=1, DFP_D`) |
| `lge_sbu_switch` (AUX routing) | identical (`OE(0) SEL(1)`, flag `"AUX"`) |
| `dp_power` GPIOs | identical (`aux-en=0, aux-sel=1, usbplug-cc=0`) |
| `dp_aux_configure_aux_switch` | identical, and a no-op on this build |
| PHY init registers | **byte-identical**, all 13 |
| DP state machine | reaches `CONFIGURED\|INITIALIZED\|READY\|CONNECTED` |
| DS2 accessory state | connected, powered, HPD `On`, firmware readable |
| **AUX transaction** | **succeeds on external sink, 100% TOUT on DS2** |

No software-observable difference remains. The DS2 side has been driven as far as its own HAL
API allows, and reports itself ready.

### Stock framework orchestration recovered and replayed — AUX still fails

Froze kernel/AUX work and reversed the Stock Java caller graph instead. Result: the missing
component is **`com/android/server/power/CoverDisplayPowerManagerService.java`** (1960 lines in
Stock's `services.jar`), which is entirely absent from LOS.

**Recovered call graph.** `setPowerStatus` has *no* Java caller anywhere in Stock
(`framework.jar`, `services.jar`, `LGDSManager`, `LGSubDisplay`) — searching for it directly is a
dead end. The real entry point is the accessory HAL:

```
CoverDisplayManagerInternal.DisplayPowerRequest (interactive)
  -> CoverDisplayPowerManagerService.requestCoverDisplayPowerState()
       needCommunicateWithHAL && !mIsDeviceAdded
         -> mRequestedWantCallback = true
         -> setCoverDisplayButtonStatusViaHIDL(true, true)
              -> IAccessory.setCoverDisplayButtonStatus(enable=true, skip_uevent=true)
         -> awaits mSyncLatchForTransition (5s) for the HAL callback
```

`setPowerStatus` is invoked **natively**: both `accessory@1.1-impl.so` and
`coverdisplay@1.0-impl.so` carry an undefined reference to
`vendor::lge::hardware::dualscreen::V1_0::IDualScreen::getService`, i.e. they are clients of the
dualscreen HAL. So writing `cover_button` directly by hand — as done in every earlier experiment
— **bypassed the very step that powers the DS2**.

Also recovered: the HIDL parameter names are `setCoverDisplayButtonStatus(boolean enable, boolean
skip_uevent)` and `setPowerStatus(boolean enable, boolean skip_uevent)`, and Stock passes
`(true, true)`, which the HAL turns into the kernel's `cover_button_set : 2 1`.

**Replayed it** (`com.test.CoverButton`, compiled against Stock's `IAccessory` HIDL sources copied
into `dualscreen-port`), from a clean state with HPD confirmed `Off`:

```
BEFORE : AT%HPD=Off, getDS2Connect=true, getSubDisplayPowerState=3
>>> IAccessory.setCoverDisplayButtonStatus(true, true) -> 0
AFTER  : AT%HPD=On
```

and the kernel shows the **complete Stock chain, produced entirely by the HALs**:

```
1432.622721  cover_button_set : 2 1
1432.857732  ds2_pd_store: hpd_high:1 refresh_layer:1     <- 235 ms later
```

235 ms matches Stock's recorded ~0.23 s gap. No manual sysfs writes were involved: one HIDL call
drove the accessory HAL, which powered the DS2 MCU (HPD `Off` -> `On`) and wrote `cover_button`,
after which the dualscreen HAL wrote `ds2_pd` on its own.

**Result: AUX still fails.** Zero `SINK DPCD`, zero successful EDID reads, same
`DP_AUX_ERR_TOUT` / `isr=0x200`.

This resolves the framework-sequencing question cleanly and in the negative:

```
Stock sequence reproduced
        └── AUX still fails
               -> framework sequencing is NOT the remaining difference
```

The Stock userspace orchestration is now fully reproduced on LOS — correct HALs, correct HIDL
entry point, correct native side effects, correct kernel writes, correct relative timing — and
the AUX transaction still does not complete against DS2 while completing normally against an
external DP sink on the same boot.

### Problem A confirmed live, and the earlier "does not reproduce" was wrong

Correcting an over-claim made earlier the same day. Four consecutive clean attach cycles had
suggested Problem A was stale; a later clean boot and two physical reattaches produced the
failure repeatedly, so four cycles was simply too small a sample.

Captured with the `lge_ds3.c` instrumentation already in this kernel:

```
RE-DEBUG start_2nd_usb_host: ENTRY EXTCON_USB_HOST=0
lge_usb_ds3: start_2nd_usb_host
RE-DEBUG start_2nd_usb_host: after set_state_sync(1), readback=1     <- extcon correct
ds_set_state: DS_Recovery_Power_On -> DS_Recovery_USB_Wait
usb 1-1: new full-speed USB device number 19 using xhci-hcd
usb 1-1: hub failed to enable device, error -108                     <- -ESHUTDOWN
ds_set_state: DS_Recovery_USB_Wait -> DS_Recovery
lge_usb_ds3: is_ds_connected: 1
[Display] gpio_keys_gpio_report_event: ds3_smart_cover ... CLOSE     <- case detected fine
```

Counts over one boot with the case attached and two reseats:

```
dwc3_otg_start_host "turn on host" ....  0
dwc3 extcon notifier events ...........  26
hub failed ... error -108 .............  17
enumeration attempts .................. 6+   (device numbers 14..19, incrementing)
```

**This settles the mechanism.** The extcon write is correct and verified by readback; the dwc3
notifier genuinely receives the events (26 of them); the accessory is detected
(`is_ds_connected: 1`, hall sensor firing). What never happens is
`dwc3_otg_start_host()` — so the xHCI controller is never re-initialised, retains stale/halted
state, and `xhci_setup_device()`'s `xhc_state` check returns `-ESHUTDOWN` (`-108`) before any
communication with the device. This also explains the intermittency: an attach succeeds only
when the controller happens to already be in a usable state, e.g. shortly after boot's one-time
host init.

It also independently reconfirms that the old "stuck `EXTCON_USB_HOST` flag" root cause is
wrong: the flag toggles correctly here and the failure occurs anyway.

**Next step if this is picked up**: read `dwc3-msm.c`'s extcon notifier / workqueue handler and
find what gates the `dwc3_otg_start_host()` call — whether it is conditioned on some state
(e.g. `mdwc->drd_state`) that only permits it once per boot, or requires a real ID-pin GPIO
transition rather than the extcon-only path DS2 uses. This is a bounded kernel question and is
independent of the frozen Problem B.

### dwc3 gating narrowed to `dwc3_resume_work`; healthy path captured, failure not yet caught

Instrumented `drivers/usb/dwc3/dwc3-msm.c` (`dwc3_resume_work`) with an entry log
(`drd_state`, `id_state`, `vbus_active`, `resume_pending`, `pm_suspended`), a log on each of the
two early returns, and one on reaching `dwc3_ext_event_notify()`. Built, repacked via
`repack_boot.sh`, flashed to `boot_b`.

**Why `dwc3_resume_work` is the right place.** From the failing capture, for the DS2's controller
`a800000.ssusb`: 26 ID-notifier events received, but **0** `dwc3_otg_sm_work` state logs (the
primary `a600000` logged 13). `dwc3_otg_start_host()` is reachable only from `DRD_STATE_HOST_IDLE`
inside `sm_work`, and `sm_work` is scheduled only via `dwc3_ext_event_notify()` at the end of
`dwc3_resume_work` — which has two early returns before it:

```c
if (mdwc->drd_state == DRD_STATE_UNDEFINED && !edev && !mdwc->resume_pending)
        return;
...
if (atomic_read(&mdwc->pm_suspended)) {          /* let pm resume kick in resume work later */
        return;
}
dwc3_ext_event_notify(mdwc);
```

`drd_state` values, for reading these logs: `0=undefined 1=idle 2=peripheral
3=peripheral_suspend 4=host_idle 5=host`.

**Healthy path, captured over 5+ physical detach/reattach cycles:**

```
detach: ENTRY[a800000] drd_state=5 (host) id_state=1 (FLOAT) pm_suspended=0 -> reached ext_event_notify
attach: ENTRY[a800000] drd_state=1 (idle) id_state=0 (GROUND) pm_suspended=0 -> reached ext_event_notify
counts: error -108 = 0, "turn on host" = 6
```

i.e. the machine cycles `host -> idle -> host_idle -> start_host -> host` exactly as designed, and
every `resume_work` reaches `ext_event_notify`.

**The failure did not reproduce in this session (5/5 attaches succeeded).** So the trigger is not
simply "detach and reattach". In the failing instance the very first attach at boot already failed
and then *stayed* failed across reseats (USB device numbers climbing 14..19) until a reboot
cleared it — so the bad state is entered somewhere and then persists. Notably that boot also had
the case attached at boot time, but the instrumented boot did too and worked, so that alone is not
the trigger either.

The instrumentation is now resident in the running kernel, so the next occurrence will record
which of the two early returns fires (prediction: `pm_suspended`, which bails expecting a PM
resume that may never come for an otherwise-idle second controller). Capture with
`cat /dev/kmsg | grep "RE-DEBUG dwc3_resume_work"` and compare against the healthy path above.

### Problem A reproduces on the STOCK LOS kernel, on a second phone

Installed `lge_ds2_hal_shim` v3.0 on the second V60 (the daily driver, serial
`LMV600TMff3529c1`) to check portability. That phone runs the **same LOS build**
(`23.2-20260816-NIGHTLY-timelm`, Android 16, VINTF schema `9.0`) but an **unmodified LOS
kernel** -- none of this project's instrumentation or patches.

Result: **first reboot the DS2 failed to register; second reboot it worked.**

This matters for scoping Problem A:

- It is **not** caused by our instrumented kernel. The failure occurs on a stock LOS kernel on a
  different physical phone.
- It is **not** specific to one unit or one DS2 case.
- It is inherent to LOS on this device, which is consistent with the mechanism already traced
  (`dwc3_otg_start_host()` never invoked, xHCI left halted, enumeration returns `-108`).

Practical workaround, confirmed on both phones: **reboot**. Nothing lighter is known to clear it.
`/sys/class/dualscreen/ds2/ds2_recovery` is worth a try first but is expected to fail, since it
power-cycles the DS2 via the same `stop`/`start_2nd_usb_host` path that is already failing to
re-init the host controller -- the fault is host-side, not accessory-side.

Note the diagnostic asymmetry: the daily driver has no custom kernel, so a failure there produces
only the symptom, not the `RE-DEBUG dwc3_resume_work` lines. Diagnose on the instrumented test
phone.

### PROBLEM B SOLVED: DP comes up on a stock LineageOS kernel

On 2026-08-22 the DS2 main panel came up on the **daily driver** (second V60, serial
`LMV600TMff3529c1`), running a **completely stock LineageOS kernel** plus the
`lge_ds2_hal_shim` module. Android prompted for desktop-vs-mirror mode.

```
card0-DP-1: connected            extcon6: DP=1
[drm-dp] SINK DPCD: 12 0a c2 01 01 00 01 00 02 02 04 00 00 00 00 00
dp_panel_read_dpcd: version:1.2, rate:270000, lanes:2
dp_panel_read_edid 2034 EDID read successed, count=1
dp_ctrl_on: bw_code=10, lane_count=2
link training #1 successful
link training #2 successful
dp_display_enable: add DP_STATE_ENABLED
```

That matches Stock's recorded success trace line for line. Android enumerates the panel as
`"HDMI Screen"`, `1080x2460 @60`, `type=EXTERNAL`, `(organized)` in WindowManager. Its mode list
also exposes `1148x2460` (span/wide) and `256x204` / `256x549` (cover-window geometries).

**Why it had never worked on the test phone.** The test phone was running a kernel built with the
`dp_power.c` experiment still applied -- the DS2 `aux-sel` inversion removed, so the pin was
driven `1` instead of Stock's `0`. That experiment was reverted in source but never rebuilt or
reflashed, so every AUX measurement on that phone after it was taken with the wrong polarity.

This invalidates the earlier conclusion that `aux-sel` polarity "is not the cause". That test was
run with no LG HALs deployed and the DS2 unpowered (`AT%HPD` Off), so AUX could not have
succeeded regardless of polarity -- the outcome could not distinguish the variable being tested.
The external-sink A/B still worked on that phone because an external sink does not go through the
`is_ds_connected()` inversion at all.

**Working conclusion (not yet confirmed by a controlled retest):** DS2 DP requires *both*
(a) the accessory powered and HPD asserted, which the module now does via
`IAccessory.setCoverDisplayButtonStatus()` -> native `setPowerStatus()`, and (b) `aux-sel` at
Stock polarity. The daily driver has both; the test phone had only (a). Confirming this properly
means rebuilding the test phone's kernel from the reverted source and re-testing.

### DS2 main-panel brightness: use the HAL, not the backlight node

`/sys/class/backlight/panel0-backlight-ex` looks like the DS2 backlight and accepts writes, but
it is **vestigial**: after `echo 365 > brightness`, `actual_brightness` still reads `0`, i.e. the
driver never applies it. No visible change on the panel either.

The working path is the HAL: `IDualScreen.setBrightness(level)` returns 0 and visibly changes the
DS2 panel. `IDualScreen.setDSBrightnessOffset(offset)` is the accompanying trim. (`AT%DB` as a
bare query returns `status=1`; like the other `AT%` verbs it is a setter, not a query.)

Note `panel0-backlight` (no `-ex`) is the main screen and reads a live value, so the two nodes are
easy to confuse.

### Explicitly not in scope

Problem B (main-panel DP/AUX `DP_AUX_ERR_TOUT`) stays parked exactly where the prior entries left
it — exhaustively investigated, root cause not found, blocked on stock booting again, which was
already explicitly declined. Neither workstream above touches it or depends on it.
