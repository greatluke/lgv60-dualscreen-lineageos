# LG Dual Screen (DS2) on LineageOS — LG V60 ThinQ

Brings LG's Dual Screen accessory partly back to life on LineageOS for the LG V60 ThinQ
(`timelm`), as a Magisk module.

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
- Everything starts automatically at boot

## What does not work

**The DS2's main panel stays blank.** This is the honest headline limitation.

The DisplayPort link to the second screen never completes: HPD is asserted, the DP state machine
reaches `CONFIGURED | INITIALIZED | READY | CONNECTED`, the PHY is programmed identically to
stock — and then every AUX transaction times out (`DP_AUX_ERR_TOUT`).

This was investigated extensively and is **unresolved**. Notably, on the same phone, same kernel
and same boot, an external DisplayPort sink completes DPCD and EDID reads successfully, so the
DP controller, PHY and AUX engine all work; the failure is specific to the DS2 path. Every
software-visible difference we could compare — mux routing, GPIOs, regulators, VDM negotiation,
PHY registers, and the full stock userspace call sequence — is identical between the working and
failing cases.

See [`docs/dualscreen-attach-flow.md`](docs/dualscreen-attach-flow.md) for the full
investigation, including a long list of hypotheses that were tested and ruled out. If you are
thinking of picking this up, please read the frozen status block at the top of that file first —
it will save you re-deriving several dead ends.

## Known issue: intermittent attach

The DS2 sometimes fails to enumerate, leaving `hub failed to enable device, error -108` in the
kernel log. **Workaround: reboot.** Nothing lighter is known to clear it.

Root cause is traced but unfixed: `dwc3_otg_start_host()` is never invoked for the second USB
controller, so xHCI is never re-initialised and keeps stale halted state. It reproduces on a
completely stock LineageOS kernel, on two different phones, so it is inherent to LineageOS on
this device rather than caused by anything here.

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
until the boot animation — that is Magisk safe mode and disables all modules for one boot. adb
also stays reachable during such a hang, so you can `touch /data/adb/modules/lge_ds2_hal_shim/disable`
remotely instead.

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
