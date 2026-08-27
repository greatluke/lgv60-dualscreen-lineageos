# Why personal-profile apps don't launch from the DS2 Taskbar

Traced 2026-08-26 on LG V60 (timelm), LineageOS 23.2 (Android 16), builds 20260816 and 20260823.

## Symptom

Tapping an app icon in the Taskbar's All Apps drawer on the Dual Screen does nothing.
No launch, no exception, no log line. Long-press works. Work-profile apps launch.

## Measured facts

    TYPE_VIEW_CLICKED fires for every icon, displayId=2   (observed via an AccessibilityService,
                                                           no patching required)
    work profile (u10)  -> launches, "Displayed ... for user 10: +235ms"
    personal (u0)       -> nothing
    ActivityContext.startActivitySafely  never entered  (neither its catch nor its success log fire)
    LineageOS Trust protected-apps       ruled out, trust_apps table empty

## The code path

Taskbar does NOT use ItemClickHandler. It overrides the listener:

    TaskbarActivityContext.getItemOnClickListener() -> onTaskbarIconClicked(view)
    onTaskbarIconClicked: tag instanceof AppInfo -> launchFromTaskbar(...)
                                                 -> launchFromInAppTaskbar(...)

    public final void launchFromInAppTaskbar(RecentsView recentsView, View view, List list) {
        boolean z = DesktopExperienceFlags.ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()
                    && !isPrimaryDisplay();
        if (recentsView != null || z) {
            ...
            startItemInfoActivity((ItemInfo) list.get(0), null);
        }
        // no else: silently does nothing
    }

On the DS2 there is no Overview, so recentsView == null. The launch depends entirely on `z`.

## Why z is false

    ENABLE_TASKBAR_CONNECTED_DISPLAYS -> Flags.enableTaskbarConnectedDisplays()

`com.android.window.flags.enable_taskbar_connected_displays` is NOT among the 2850 flags
`aflags list` reports on this build, so the supplier returns its compiled default (false).

The constant is declared overridable (3rd ctor arg = true), so the dev-option override could
force it true:

    isFlagTrue(supplier, overridable) {
        if (overridable && getToggleOverride()) return true;
        return supplier.getAsBoolean();
    }

but getToggleOverride() -> isToggleOverriddenBySystem() is itself gated on
isDesktopModeDevOptionSupported(), which reads bool/config_isDesktopModeSupported.

## The conflict, and why it turned out not to matter

    stop desk creation (Failed to add desk)  needs config_isDesktopModeSupported = false
    enable Taskbar launch on the DS2         needs config_isDesktopModeSupported = true

One resource, two opposite requirements -- so the original plan was to find a way to satisfy
both. Setting `persist.wm.debug.desktop_experience_devopts = 1` looked like it should: it flips
`isToggleOverriddenBySystem()` true independent of the resource, whenever
`show_desktop_experience_dev_option` is compiled in (confirmed via `aflags list` -- it is, on
this build), so `ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()` should short-circuit true even with
the RRO's `config_isDesktopModeSupported = false` in place.

Tested directly on hardware, prop set, RRO in place, patch disabled: **the tap still did
nothing.** The dev-option override was a red herring for this specific flag -- see below for
what was actually stopping it, at three more points past `launchFromInAppTaskbar` alone. Setting
the prop is not part of the shipped fix.

## The three more blockers past the flag check

Patching out the `ENABLE_TASKBAR_CONNECTED_DISPLAYS` check in `launchFromInAppTaskbar` (so `z`
is simply `!isPrimaryDisplay()`) was necessary but not sufficient -- the tap still did nothing,
because three more places independently drop a DS2 launch before it reaches that method or after
it decides to proceed:

1. **`launchFromTaskbar` picks the wrong branch.** It calls `isInOverviewUi()` on the taskbar's
   `TaskbarUIController` to decide between `launchFromOverviewTaskbar` and
   `launchFromInAppTaskbar`. The DS2's controller is `FallbackTaskbarUIController` (used because
   the default home app is third-party, Lawnchair), whose `isInOverviewUi()` reports the
   *fallback* `RecentsState`, true by default -- so every click routed into
   `launchFromOverviewTaskbar`, which returns immediately on `recentsView == null`, which the DS2
   always has. Fix: skip that branch when there's no `RecentsView` to act on, since it can only
   ever no-op without one.

2. **`shouldLaunchInDesktop` treats "external display" as "desktop".** Once the click reaches
   `startItemInfoActivity`, this check routes the launch through `launchDesktopApp()` for any
   external display with `ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS` set (it is) -- straight into a
   desk this hardware cannot create, with no exception and no log line, matching the original
   symptom exactly. Fix: drop the external-display clause; what's left still takes the desktop
   path when the taskbar is genuinely showing desktop tasks.

3. **Connected-display taskbar auto-stash hides the whole bar with no way back.** Once launches
   worked, the DS2 taskbar would stash itself (`mStashedHeight=0`,
   `isStashedHandleVisible=false`) after the very first launch and never reappear -- no handle,
   no response to a tap or a swipe at the bottom edge. `TaskbarDesktopExperienceFlags
   .enableAutoStashConnectedDisplayTaskbar`'s supplier is a hardcoded `return true`, and the flag
   is *also* overridable, so `persist.wm.debug.desktop_experience_devopts = 1` (already set on
   this device) forced it true regardless of the supplier. Fix: flip the supplier to `false` and
   opt this one flag out of the dev-option override, restoring `supportsVisualStashing() ==
   false` off the primary display -- the pre-flag behaviour.

Confirmed by instrumenting each of these four points with a one-line static logger
(`Log.i("DS2LAUNCH", ...)`, no registers needed) and reading logcat after a tap: the full call
chain -- `onTaskbarIconClicked` -> `launchFromTaskbar` -> `launchFromInAppTaskbar` ->
`startItemInfoActivity` -- executed and still produced nothing until fix #2 went in, which is
what pointed at `shouldLaunchInDesktop`.

## Hotseat folders mirrored onto the DS2 taskbar

Once launching worked, the DS2 taskbar showed 3 folder icons interleaved with the nav buttons
(`hotseat items count=3` in the taskbar dump) -- the exact folders pinned in Lawnchair's own
home-screen hotseat, since every `TaskbarView` reads the same shared favorites model regardless
of display or which app is home.

The nav buttons are individually interleaved between hotseat icons for centering, keyed off the
real item count. Two attempts at removing the folders by changing that count -- blanking the
array before `updateItems()`'s layout math ran, and skipping `updateHotseatItems()` entirely --
both desynced the interleaving enough to swallow the home button. The fix that worked: let
`updateItems()` run completely untouched (every count and insertion index computed exactly as
stock does), then hide -- `View.GONE`, after layout already ran -- exactly the child views whose
tag matches an entry in the hotseat array, on a non-primary display only. Lawnchair's own hotseat
and the primary-display taskbar are untouched; only the DS2 taskbar loses the icons.

## The Taskbar didn't exist at all without one more property

All of the above assumed the DS2 Taskbar was present and just failing to launch or navigate
correctly. It was, for every test in this document -- but only because
`persist.wm.debug.desktop_experience_devopts` was already `1` on the test phone, left over from
earlier, unrelated debugging in this same investigation. Tested by clearing it (`resetprop -p
--delete`, confirmed gone after reboot, Developer options also off): the DS2 goes back to bare
wallpaper. No Taskbar, no All Apps, no nav buttons, nothing -- not a launch failure, no UI at
all. Re-setting only that one property (still with Developer options off) brings it straight back.

`com.android.window.flags.enable_taskbar_connected_displays` isn't in this build's aconfig
storage, so `ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()` compiles to a hardcoded `false` with no
Settings-backed route to turn it on -- its only override path is
`DesktopExperienceFlags.isToggleOverriddenBySystem()`, which activates from this raw property
alone. Developer options being enabled is not part of that check, and
`config_isDesktopModeSupported` (which the RRO sets `false`) isn't consulted either, because
`Flags.enableDisplayContentModeManagement()` is itself absent from this build the same way. This
is presumably also what makes `ENABLE_TASKBAR_CONNECTED_DISPLAYS.isTrue()` resolve true for
`launchFromInAppTaskbar`'s own check before it was patched out -- the property being left set is
very likely why every fix in this document could be developed and tested at all.

Fixed by having the module set the property itself, every boot, in `post-fs-data.sh` (which runs
before `system_server`, so no persistence to disk is needed) -- see `module/post-fs-data.sh`.
Without this a fresh install would boot to a DS2 with no Taskbar and no way to know why.

## Home button silently did nothing

Back and recents worked correctly from the DS2 taskbar the whole time; home did not, even after
every fix above -- tapping it left whatever was in front untouched, on both displays.

`TaskbarNavButtonController.onButtonClick`'s home case calls
`SystemUiProxy.onKeyEvent(KEYCODE_HOME, mDisplayId)` -- a binder call into SystemUI passing the
originating display id. Confirmed on hardware: the key event is dispatched (occasionally visible
as a focus change on the *primary* display, e.g. bringing a backgrounded app back to the front on
display 0), but SystemUI's key-event home handling doesn't route on `mDisplayId` -- home always
resolves against the default display, so a tap on the DS2 taskbar's home button had no way to
affect what was on the DS2. SystemUI is platform-signed (852a750c) and out of reach for a dex
patch, the same wall that rules out fixing anything else in it.

The fix lives entirely on the Trebuchet side instead:
`OverviewComponentObserver.getHomeIntent(displayId)` already resolves the correct home `Intent`
per display -- `SecondaryDisplayLauncher` for anything non-primary, the same one used to seed
that display at boot and on hinge-attach -- so the patch starts it directly for a non-primary
display, bypassing the SystemUI call (and its onKeyEvent path) entirely. The primary display's
stock behavior is untouched; a try/catch falls back to the original `onKeyEvent` call on any
failure, since Trebuchet hosts the taskbar for both displays in one process and an uncaught
exception here would take out navigation on both at once.

## DS2 all-apps grid: 6 tight columns with labels, main screen's 5 without

Once launching, navigation and the hotseat were all fixed, the DS2's app drawer still looked
different from the main screen's: 6 columns with a name label under every icon, against the main
screen's 5 with no labels. Both come from a `DeviceProfile` picked per display by nearest-match
against that display's *dp* dimensions -- and the DS2 reports a higher `densityDpi` than the main
panel for the exact same physical pixels (552 vs 420), so despite being the identical panel it
resolves to a narrower dp width and a different, denser named grid profile.

**Tried and reverted: forcing the DS2's OS-level density to match** (`wm density 420 -d 2`).
This does make the grid match, but it also reflows the taskbar's nav-button touch-forwarding
zones, which then swallow the All Apps button's hit area entirely -- confirmed broken even after
a full reboot, not a stale-state artifact. The button becomes physically unreachable. Not shipped.

**The two real fixes are narrow, and deliberately avoid the DeviceProfile constructor.**
`numShownAllAppsColumns` and `AllAppsProfile.iconTextSizePx` are both assigned inside the same
~1500-line `DeviceProfile` constructor that every other per-display field goes through -- not
safe to patch there: every register is already in use, and the method uses raw (non-symbolic)
register numbers throughout, so growing the register file would silently renumber every existing
parameter reference in it. Both fields are also `final`, which rules out fixing them up
afterward from any other method -- final instance fields can only legally be written from
inside their own declaring class's own constructor.

- **Column count** is fixed one level up the call chain instead. `numShownAllAppsColumns` is
  just a copy of `DisplayOptionSpec.numAllAppsColumns`, and `DisplayOptionSpec`'s own two-line
  constructor -- small, self-contained, exactly one caller (`createDisplayOptionSpec`) -- is
  where that field is actually assigned, so it's a legal place to override it. The caller reads
  the originating display's id off `DisplayController.Info.context.getDisplayId()` *before* that
  same register gets overwritten with the interpolated grid result partway through the method,
  and passes it down as a new boolean parameter.
- **Labels** go through a different, safer lever: `DeviceProfile.mAllAppsProfile` is *not*
  final, and already has a public setter (`setAllAppsProfile`). `AllAppsProfile` is a Kotlin data
  class with a generated `copy$default` bridge -- `copyWithCellHeightPx` already showed the
  pattern for overriding one field through it via a bitmask, mirrored here for `iconTextSizePx`
  (mask `0x77` vs `0x7d`, i.e. every bit set except the one being overridden). The whole thing
  runs from `DeviceProfile$Builder.build()`, right after the constructor call succeeds, gated on
  `mIsExternalDisplay` -- a ~170-line method with registers to spare, not the constructor itself.

Confirmed on hardware: DS2 drawer now shows 5 columns with no labels; the main screen's own
`DeviceProfile` (`numShownAllAppsColumns=5`, `allAppsIconTextSizePx=32.0px`) is unchanged.

## Tooling notes

- Launcher3 is signed 6a1bd8a4 -- its own key, NOT the platform cert (852a750c), and has no
  sharedUser. Unlike SystemUI it is re-signable, so a dex patch is viable. smali 2.5.2 is
  packaged in Arch extra/. jadx (already installed) is enough to read the code.
- Framework resources ARE overridable without the platform key via a static RRO in
  /product/overlay shipped by a Magisk module.
- The shipped fix combines the RRO and the dex patch into one Magisk module,
  `app/ds2-desktop-fix/` (`build.sh` drives both `app/ds2-desktopfix-rro/` and
  `app/ds2-launchfix/`) -- they only work together, so a split install can leave the phone in
  either half-fixed state described above. `app/ds2-launchfix/patch_smali.py` documents all six
  edits inline, applied to a fresh baksmali tree of Trebuchet's `classes2.dex` and reassembled
  with `smali`.
