# LG Dual Screen (DS2) on LineageOS — LG V60 ThinQ

Brings LG's Dual Screen accessory back to life on LineageOS for the LG V60 ThinQ (`timelm`), as
a Magisk module: both the cover window and the second screen itself.

LineageOS ships none of LG's dual-screen userspace, so out of the box the DS2 does nothing: the
kernel enumerates it over USB and then stalls, because the process that would take it further
(`vendor.lge.hardware.dualscreen@1.1-service`) does not exist on LineageOS.

## What works

- DS2 detection, USB enumeration, and the kernel state machine reaching `DS_Ready`
- The stock LG HALs running on LineageOS: `dualscreen@1.1`, `accessory@1.1` (+ `uevent@1.2`),
  `coverdisplay@1.0`
- A framework bridge exposing `IDisplayManagerEx` as the `dualscreen_ex` binder service
- **The cover window** — the strip visible when the case is folded shut — showing clock,
  weekday/date, battery percentage with a proportional battery icon, and a no-SIM indicator
- **Hinge-driven power sequencing**: unfolding the case powers the accessory up, folding it
  powers it down, automatically
- **The DS2's main panel** — the DisplayPort link comes up and Android enumerates the second
  screen as a 1080x2460@60 external display. Both mirror and desktop mode work.
- Everything starts automatically at boot


## Screenshots

The cover window on LineageOS — clock, weekday/date, battery percentage with a proportional
battery icon, and a no-SIM indicator (this unit has no SIM installed):

![DS2 cover window running on LineageOS](images/front_cover.jpg)

Running on LineageOS 23.2 (Android 16):

![LineageOS version](images/android_version.png)

The kernel string in that screenshot ends in `-dirty` because that unit runs a debug kernel used
during the investigation. **The module does not require it** — it is confirmed working on a
second V60 running a completely stock LineageOS kernel.

## What does not work yet

With the second screen in **desktop mode**, some integration is still missing:

- Touch input on the DS2 is not yet routed to it (an IDC file binding the digitizer to the
  external display is included but not yet confirmed working)
- Launching apps directly onto the second screen needs the `INTERNAL_SYSTEM_WINDOW` signature
  permission, which root alone does not grant
- DS2 brightness is not yet tied to the main screen's brightness


## Known issue: intermittent attach

The DS2 sometimes fails to enumerate, leaving `hub failed to enable device, error -108` in the
kernel log. **Workaround: reboot.** Nothing lighter is known to clear it.

Root cause is now known, and it is a **kernel workqueue deadlock**, confirmed from live blocked-task
stacks rather than inferred:

When the DS2 is already attached at power-on, `lge_ds3` reaches `DS_Startup` at ~1.041s, but
`dp_display_probe()` only registers the DisplayPort USBPD SVID handler at ~1.045s. That 4ms miss
makes `ds_dp_config()` return `-ENODEV`, and its error path calls `stop_2nd_usb_host()` just 82µs
after `start_2nd_usb_host()`. dwc3 therefore tears the xHCI host down while the hub is still
enumerating the DS2; `hub_quiesce()` blocks forever, `dwc3_otg_sm_work` wedges, and
`dwc3_resume_work` then blocks flushing it. Because `dwc3_wq` is an *ordered* workqueue, every
subsequent USB ID event is queued behind the blocked one and never runs — so the controller is
permanently deaf until reboot.

A fix is included as a kernel patch — [`kernel/0001-lge_ds3-retry-ds_dp_config-on-ENODEV.patch`](kernel/0001-lge_ds3-retry-ds_dp_config-on-ENODEV.patch)
— which retries across the registration window instead of tearing the host down. It is **not part
of the Magisk module**: the module runs fine on a stock kernel, and this bug is not reachable from
userspace.

**The patch is built and verified to apply cleanly, but is not yet confirmed on hardware.** Treat
it as unproven. The raw evidence is in
[`docs/problem-a-blocked-tasks.txt`](docs/problem-a-blocked-tasks.txt); reproduction and
verification steps are in [`kernel/README.md`](kernel/README.md).

## Installing

Requires an LG V60 ThinQ (`timelm`) running LineageOS, rooted with Magisk.

1. Get `lge_ds2_hal_shim.zip` — from the Releases page, or build it yourself (below)
2. Install it: Magisk app → Modules → Install from storage, or
   `adb shell su -c 'magisk --install-module /sdcard/Download/lge_ds2_hal_shim.zip'`
3. Reboot

Verify:

```sh
adb shell su -c 'lshal | grep -E "dualscreen|accessory|coverdisplay"'
adb shell 'service list | grep dualscreen_ex'
adb shell su -c 'tail -20 /data/local/tmp/ds2_shim.log'
```

Fold the case shut and the cover window should show the clock.

**If it hangs at the LG logo**: power off, then hold Volume Down from the moment the logo appears
until the boot animation — that is Magisk safe mode and disables all modules for one boot.

A hang caused by a malformed VINTF fragment happens late enough that `adbd` is running, so you can
often `touch /data/adb/modules/lge_ds2_hal_shim/disable` over adb instead. Do not rely on that in
general: a failure earlier in boot leaves no adb at all, and safe mode is the only way back.

## Using the second screen

**Desktop mode must be enabled in Developer options first.** Without it the second screen is
only offered as a mirror, and Android will not treat it as a display that can host its own
windows.

Settings → System → Developer options → **Force desktop mode on secondary displays**, then
reboot. Equivalent over adb:

```sh
adb shell settings put global force_desktop_mode_on_external_displays 1
adb reboot
```

(The exact wording of the toggle varies a little between LineageOS builds — look for "desktop
mode" among the developer options. The `settings` key above is the one that actually matters.)

With that set, attaching the Dual Screen brings up the display prompt:

![Connect to external display prompt](images/external_display.png)

- **Mirror** duplicates the main screen. This works well.
- **Desktop** treats the DS2 as an independent display. The display itself works, but see
  *What does not work yet* above — touch routing and launching apps onto it are still incomplete.

Both panels running under LineageOS, the DS2 on the left showing the clock:

![Dual Screen running on LineageOS](images/dualscreen_on_los.jpg)

## Building

This repository does **not** contain LG's proprietary binaries. You supply them from firmware you
own:

```sh
./tools/extract-blobs.sh /path/to/mounted/stock/vendor   # or: --adb, from a stock-running device
./tools/build.sh
```

`extract-blobs.sh` pulls 21 files (3 HAL services, 4 passthrough impls, 13 vendor libs). Note
they exist only in LG's **stock** vendor partition — if you have already converted to LineageOS,
extract them from an LG KDZ firmware image rather than from the running device.

`build.sh` compiles the Java bridge, dexes it, validates the VINTF fragments and shell scripts,
and assembles the zip. See the comments in it for the two environment variables you need to set.

The optional kernel patch under [`kernel/`](kernel/) is built separately against a LineageOS
`timelm` kernel tree — see [`kernel/README.md`](kernel/README.md).

### Repository layout

```text
module/     the Magisk module: scripts, VINTF fragments, init .rc files, IDC
src/        the framework bridge (Java, compiled to dualscreen-bridge.dex)
tools/      blob extraction and the module build
kernel/     optional kernel patch for the intermittent-attach deadlock
docs/       the full reverse-engineering write-up and raw evidence
```

## Licensing and contents

- Original work here (`CoverDisplayPowerBridge`, `CoverWindowRenderer`, `TinyFont`,
  `DualScreenBridgeDaemon`, the module scripts, the VINTF fragments, the docs) is under the
  license in [`LICENSE`](LICENSE).
- `SubLcdController.java` is **derived from LG's stock `services.jar`** (decompiled and adapted).
  It is included because the bridge cannot work without it. It is not original work and is not
  covered by that license.
- The HAL binaries and `vendor.lge.hardware.*` libraries are **LG proprietary** and are
  deliberately not distributed here.

## Credits

Reverse-engineered from stock LG firmware by inspecting the HAL binaries, decompiling
`framework.jar` / `services.jar`, and comparing kernel behaviour between stock and LineageOS on
real hardware. The key discovery for the power path was that
`CoverDisplayPowerManagerService` — absent from LineageOS entirely — drives
`IAccessory.setCoverDisplayButtonStatus()`, which makes the accessory HAL natively power the DS2.

The attach-reliability bug was then root-caused by reading blocked-task stacks out of a live,
wedged kernel rather than by reasoning about the driver source, which is what finally
distinguished a deadlock from the "controller never started" theory that had held for a long time
and was wrong.
