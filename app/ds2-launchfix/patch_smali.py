#!/usr/bin/env python3
"""Apply the DS2 Taskbar fixes to baksmali trees of Trebuchet's classes.dex and classes2.dex.

Most edits guard a point where a Dual Screen tap was discarded -- all of those are needed
together, with any one missing the click still ends in a silent return. A few later edits (the
DS2 all-apps grid) are independent cosmetic fixes. See docs/taskbar-launch-blocker.md for how
each was traced.

Idempotent -- re-running on an already-patched tree is a no-op.
"""

import sys
from pathlib import Path

TB = "com/android/launcher3/taskbar"


def edit(path: Path, old: str, new: str, what: str) -> bool:
    s = path.read_text()
    if new in s:
        print(f"  = {what} (already applied)")
        return False
    n = s.count(old)
    if n != 1:
        sys.exit(f"  ! {what}: expected 1 match, found {n} in {path}")
    path.write_text(s.replace(old, new))
    print(f"  + {what}")
    return True


def main(root: str) -> None:
    r = Path(root)
    ctx = r / TB / "TaskbarActivityContext.smali"
    flags = r / TB / "TaskbarDesktopExperienceFlags.smali"
    supplier = r / TB / "TaskbarDesktopExperienceFlags$enableAutoStashConnectedDisplayTaskbar$1.smali"
    view = r / TB / "TaskbarView.smali"

    for p in (ctx, flags, supplier, view):
        if not p.exists():
            sys.exit(f"missing {p} -- is {root} a baksmali tree of classes2.dex?")

    # 1. launchFromInAppTaskbar: the guard reads ENABLE_TASKBAR_CONNECTED_DISPLAYS, which this
    #    build does not ship in its aconfig storage, so isTrue() returns the compiled default of
    #    false and the method returns without launching. Its dev-option override is gated on
    #    config_isDesktopModeSupported, which the RRO must hold false to stop desk creation.
    #    Dropping the flag test leaves z = !isPrimaryDisplay(); the primary display is unchanged.
    edit(ctx, """    .line 1914
    sget-object v0, Landroid/window/DesktopExperienceFlags;->ENABLE_TASKBAR_CONNECTED_DISPLAYS:Landroid/window/DesktopExperienceFlags;

    .line 1915
    invoke-virtual {v0}, Landroid/window/DesktopExperienceFlags;->isTrue()Z

    move-result v0

    const/4 v1, 0x0

    if-eqz v0, :cond_11

    .line 1916
""", """    .line 1914
    const/4 v1, 0x0

    .line 1916
""", "launchFromInAppTaskbar: drop absent-flag guard")

    # 2. launchFromTaskbar: a secondary display runs FallbackTaskbarUIController, whose
    #    isInOverviewUi() reports the fallback RecentsState -- true by default when a third-party
    #    launcher is home. That routes the click to launchFromOverviewTaskbar, which returns
    #    immediately on a null RecentsView, and the DS2 never has one.
    edit(ctx, """.method public final launchFromTaskbar(Lcom/android/quickstep/views/RecentsView;Landroid/view/View;Ljava/util/List;)V
    .registers 5

    .line 1902
""", """.method public final launchFromTaskbar(Lcom/android/quickstep/views/RecentsView;Landroid/view/View;Ljava/util/List;)V
    .registers 5

    if-eqz p1, :cond_e

    .line 1902
""", "launchFromTaskbar: skip overview branch without a RecentsView")

    # 3. shouldLaunchInDesktop: treats any external display as desktop, sending every DS2 launch
    #    into launchDesktopApp() and a desk this hardware cannot create -- failing with no
    #    activity, no exception and no log. What remains is the original intent.
    edit(ctx, """    .line 2057
    :cond_4a
    sget-object p2, Landroid/window/DesktopExperienceFlags;->ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS:Landroid/window/DesktopExperienceFlags;

    invoke-virtual {p2}, Landroid/window/DesktopExperienceFlags;->isTrue()Z

    move-result p2

    if-eqz p2, :cond_58

    .line 2058
    invoke-static {p1}, Lcom/android/quickstep/util/ExternalDisplaysKt;->isExternalDisplay(I)Z

    move-result p1

    if-nez p1, :cond_5e

    :cond_58
""", """    .line 2057
    :cond_4a
    :cond_58
""", "shouldLaunchInDesktop: drop external-display-implies-desktop clause")

    # 4. Connected-display taskbar auto-stash collapses the DS2 taskbar to mStashedHeight=0 with
    #    isStashedHandleVisible=false: it disappears with no handle, and neither a tap nor a swipe
    #    at the bottom edge brings it back. Reporting the flag absent restores the pre-flag
    #    behaviour -- supportsVisualStashing() is false off the primary display, so it stays put.
    #    The supplier alone is not enough: isFlagTrue() short-circuits to true whenever the flag
    #    is overridable and persist.wm.debug.desktop_experience_devopts is set, which it is here.
    edit(supplier, """    const/4 p0, 0x1

    return p0""", """    const/4 p0, 0x0

    return p0""", "auto-stash supplier: return false")

    s = flags.read_text()
    key = '    const-string v2, "com.android.launcher3.enable_auto_stash_connected_display_taskbar"\n'
    marker = "    const/4 v3, 0x0\n\n    invoke-direct {v0, v1, v3, v2}"
    if marker in s:
        print("  = auto-stash flag: opt out of dev-option override (already applied)")
    else:
        i = s.index(key)
        j = s.index("    invoke-direct {v0, v1, v3, v2}", i)
        flags.write_text(s[:j] + "    const/4 v3, 0x0\n\n" + s[j:])
        print("  + auto-stash flag: opt out of dev-option override")

    # 5. TaskbarView renders the same hotseat pins on every TaskbarView, because all of them read
    #    the one shared favorites model -- the 3 folder icons on the DS2 taskbar are the exact
    #    folders pinned in Lawnchair's own home-screen hotseat. The nav buttons (back/home/
    #    recents) are individually interleaved between hotseat icons for centering, keyed off the
    #    real item count; two earlier attempts changed that count (by emptying the array, or by
    #    skipping updateHotseatItems so no views existed at the indices the interleaving expected)
    #    and both desynced the interleaving enough to swallow the home button entirely.
    #
    #    Instead: let updateItems() run completely untouched, so every count and every insertion
    #    index stays exactly as stock computes them, then hide -- View.GONE, after layout already
    #    ran -- exactly the child views whose tag is one of the hotseat items. p1 holds that exact
    #    filtered array early in the method (before later reuses of the p1/p0 registers overwrite
    #    it for unrelated locals), so it is stashed into a spare register (bumping the method's
    #    register count by one) and passed to a new helper called at the end, while p0 still means
    #    `this`. The primary-display taskbar is unaffected: the helper returns immediately there.
    edit(view, """.method public updateItems([Lcom/android/launcher3/model/data/ItemInfo;Ljava/util/List;Ljava/util/List;)V
    .registers 7
""", """.method public updateItems([Lcom/android/launcher3/model/data/ItemInfo;Ljava/util/List;Ljava/util/List;)V
    .registers 8
""", "updateItems: reserve a spare register")

    edit(view, """    check-cast p1, [Lcom/android/launcher3/model/data/ItemInfo;

    .line 455
""", """    check-cast p1, [Lcom/android/launcher3/model/data/ItemInfo;

    move-object v3, p1

    .line 455
""", "updateItems: stash the filtered hotseat array")

    edit(view, """    :cond_d2
    :goto_d2
    iget-object p1, p0, Lcom/android/launcher3/taskbar/TaskbarView;->mAllAppsButtonContainer:Lcom/android/launcher3/taskbar/customization/TaskbarAllAppsButtonContainer;
""", """    :cond_d2
    :goto_d2
    invoke-direct {p0, v3}, Lcom/android/launcher3/taskbar/TaskbarView;->ds2HideHotseatIfSecondary([Lcom/android/launcher3/model/data/ItemInfo;)V

    iget-object p1, p0, Lcom/android/launcher3/taskbar/TaskbarView;->mAllAppsButtonContainer:Lcom/android/launcher3/taskbar/customization/TaskbarAllAppsButtonContainer;
""", "updateItems: call the hide-hotseat helper before final reassignments")

    helper = """
.method private final ds2HideHotseatIfSecondary([Lcom/android/launcher3/model/data/ItemInfo;)V
    .registers 8

    iget-object v0, p0, Lcom/android/launcher3/taskbar/TaskbarView;->mActivityContext:Lcom/android/launcher3/taskbar/TaskbarActivityContext;

    invoke-virtual {v0}, Lcom/android/launcher3/taskbar/TaskbarActivityContext;->isPrimaryDisplay()Z

    move-result v0

    if-eqz v0, :cond_start

    return-void

    :cond_start
    const/4 v1, 0x0

    invoke-virtual {p0}, Landroid/widget/FrameLayout;->getChildCount()I

    move-result v2

    :loop_outer
    if-ge v1, v2, :cond_done

    invoke-virtual {p0, v1}, Landroid/widget/FrameLayout;->getChildAt(I)Landroid/view/View;

    move-result-object v3

    invoke-virtual {v3}, Landroid/view/View;->getTag()Ljava/lang/Object;

    move-result-object v4

    if-eqz v4, :cond_next_outer

    const/4 v5, 0x0

    :loop_inner
    array-length v0, p1

    if-ge v5, v0, :cond_next_outer

    aget-object v0, p1, v5

    if-ne v0, v4, :cond_inner_continue

    const/16 v0, 0x8

    invoke-virtual {v3, v0}, Landroid/view/View;->setVisibility(I)V

    goto :cond_next_outer

    :cond_inner_continue
    add-int/lit8 v5, v5, 0x1

    goto :loop_inner

    :cond_next_outer
    add-int/lit8 v1, v1, 0x1

    goto :loop_outer

    :cond_done
    return-void
.end method
"""
    if "ds2HideHotseatIfSecondary" in view.read_text() and helper.strip() in view.read_text():
        print("  = ds2HideHotseatIfSecondary helper (already applied)")
    else:
        view.write_text(view.read_text().rstrip("\n") + "\n" + helper)
        print("  + ds2HideHotseatIfSecondary helper appended")

    # 6. Home does nothing on the DS2. Its handler calls SystemUiProxy.onKeyEvent(KEYCODE_HOME,
    #    mDisplayId) -- a binder call into SystemUI, which (unlike back/recents) resolves home key
    #    events without regard to the displayId argument, so the "go home" always targets the
    #    default display and the DS2 is left showing whatever was already in front. SystemUI is
    #    platform-signed and unpatchable, so the fix lives entirely on the Trebuchet side instead:
    #    OverviewComponentObserver.getHomeIntent(displayId) already resolves the right home
    #    Intent per display (SecondaryDisplayLauncher for anything non-primary -- the same object
    #    used to seed that display at boot/hinge-attach), so starting it directly here bypasses
    #    the SystemUI call for a non-primary display and leaves the primary display's stock
    #    onKeyEvent path completely untouched. Wrapped in a try/catch that falls back to that
    #    stock path on any failure -- Trebuchet hosts the taskbar for both displays in one
    #    process, so an uncaught exception here would take down navigation on both at once.
    #    v0 is deliberately left untouched throughout (it holds a boolean the stock code below
    #    still reads); every new local uses v1-v4 instead.
    nb = r / TB / "TaskbarNavButtonController.smali"
    edit(nb, """.method public onButtonClick(ILandroid/view/View;)V
    .registers 4
""", """.method public onButtonClick(ILandroid/view/View;)V
    .registers 8
""", "onButtonClick: reserve spare registers")

    edit(nb, """    .line 155
    :cond_56
    sget-object p1, Lcom/android/launcher3/logging/StatsLogManager$LauncherEvent;->LAUNCHER_TASKBAR_HOME_BUTTON_TAP:Lcom/android/launcher3/logging/StatsLogManager$LauncherEvent;
""", """    .line 155
    :cond_56
    iget v1, p0, Lcom/android/launcher3/taskbar/TaskbarNavButtonController;->mDisplayId:I

    if-eqz v1, :cond_ds2_stock_home

    :try_start_ds2home
    iget-object v2, p0, Lcom/android/launcher3/taskbar/TaskbarNavButtonController;->mControllers:Lcom/android/launcher3/taskbar/TaskbarControllers;

    iget-object v2, v2, Lcom/android/launcher3/taskbar/TaskbarControllers;->taskbarActivityContext:Lcom/android/launcher3/taskbar/TaskbarActivityContext;

    sget-object v3, Lcom/android/quickstep/OverviewComponentObserver;->INSTANCE:Lcom/android/launcher3/util/DaggerSingletonObject;

    invoke-virtual {v3, v2}, Lcom/android/launcher3/util/DaggerSingletonObject;->get(Landroid/content/Context;)Ljava/lang/Object;

    move-result-object v3

    check-cast v3, Lcom/android/quickstep/OverviewComponentObserver;

    invoke-virtual {v3, v1}, Lcom/android/quickstep/OverviewComponentObserver;->getHomeIntent(I)Landroid/content/Intent;

    move-result-object v4

    const/high16 v3, 0x10000000

    invoke-virtual {v4, v3}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    invoke-static {}, Landroid/app/ActivityOptions;->makeBasic()Landroid/app/ActivityOptions;

    move-result-object v3

    invoke-virtual {v3, v1}, Landroid/app/ActivityOptions;->setLaunchDisplayId(I)Landroid/app/ActivityOptions;

    move-result-object v3

    invoke-virtual {v3}, Landroid/app/ActivityOptions;->toBundle()Landroid/os/Bundle;

    move-result-object v3

    invoke-virtual {v2, v4, v3}, Landroid/content/Context;->startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V
    :try_end_ds2home
    .catch Ljava/lang/Throwable; {:try_start_ds2home .. :try_end_ds2home} :catch_ds2home

    return-void

    :catch_ds2home
    move-exception v2

    const-string v3, "TaskbarNavButtonController"

    const-string v4, "DS2 direct-home-intent failed, falling back to onKeyEvent"

    invoke-static {v3, v4, v2}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    :cond_ds2_stock_home
    sget-object p1, Lcom/android/launcher3/logging/StatsLogManager$LauncherEvent;->LAUNCHER_TASKBAR_HOME_BUTTON_TAP:Lcom/android/launcher3/logging/StatsLogManager$LauncherEvent;
""", "onButtonClick: start the per-display home intent directly on a non-primary display")


def main_classes1(root: str) -> None:
    r = Path(root)
    all_apps_profile = r / "com/android/launcher3/deviceprofile/AllAppsProfile.smali"
    display_option_spec = r / "com/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec.smali"
    inv_device_profile = r / "com/android/launcher3/InvariantDeviceProfile.smali"
    builder = r / "com/android/launcher3/DeviceProfile$Builder.smali"

    for p in (all_apps_profile, display_option_spec, inv_device_profile, builder):
        if not p.exists():
            sys.exit(f"missing {p} -- is {root} a baksmali tree of classes.dex?")

    # 7. The DS2 all-apps drawer showed 6 columns to the main screen's 5, and per-icon labels the
    #    main screen doesn't have room to avoid either -- both come from a DeviceProfile picked
    #    per display by nearest-match against that display's *dp* dimensions. The DS2 reports a
    #    higher densityDpi than the main panel for the same physical pixels (552 vs 420), so
    #    despite being the identical panel it resolves to a narrower dp width and a different,
    #    denser named grid profile. Forcing the DS2's OS-level density to match (`wm density`)
    #    was tried and reverted: it also reflows the taskbar's nav-button touch-forwarding zones,
    #    which then swallow the All Apps button's hit area entirely -- confirmed broken even
    #    after a full reboot, not a stale-state artifact.
    #
    #    numShownAllAppsColumns is declared `final` on DeviceProfile, assigned inside the same
    #    ~1500-line, fully register-exhausted constructor that every other DeviceProfile field
    #    goes through -- not safe to patch directly (no spare register anywhere in that method,
    #    and it uses raw, non-symbolic register numbers throughout, so growing the register file
    #    would silently renumber every parameter reference in the method). The value it's
    #    assigned from, DisplayOptionSpec.numAllAppsColumns, is *also* final -- but only within
    #    its own class, so it CAN be overridden from inside DisplayOptionSpec's own constructor,
    #    which is small, self-contained, and has exactly one caller.
    edit(display_option_spec, """.method public constructor <init>(Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;Z)V
    .registers 4
""", """.method public constructor <init>(Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;ZZ)V
    .registers 5
""", "DisplayOptionSpec.<init>: add an isPrimaryDisplay parameter")

    edit(display_option_spec, """    invoke-static {v0}, Lcom/android/launcher3/InvariantDeviceProfile$GridOption;->-$$Nest$fgetnumAllAppsColumns(Lcom/android/launcher3/InvariantDeviceProfile$GridOption;)I

    move-result v0

    iput v0, p0, Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;->numAllAppsColumns:I
""", """    invoke-static {v0}, Lcom/android/launcher3/InvariantDeviceProfile$GridOption;->-$$Nest$fgetnumAllAppsColumns(Lcom/android/launcher3/InvariantDeviceProfile$GridOption;)I

    move-result v0

    if-nez p3, :cond_ds2_keep_allapps_columns

    const/4 v0, 0x5

    :cond_ds2_keep_allapps_columns
    iput v0, p0, Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;->numAllAppsColumns:I
""", "DisplayOptionSpec.<init>: match the main screen's column count on a non-primary display")

    # createDisplayOptionSpec is the constructor's one caller. Its Info parameter (p0) is
    # overwritten with the interpolated DisplayOption partway through -- so the display id has to
    # be read out before that happens, into a register nothing later in the method reads again.
    edit(inv_device_profile, """.method public static createDisplayOptionSpec(Lcom/android/launcher3/util/DisplayController$Info;Z)Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;
    .registers 5

    const/4 v0, 0x0
""", """.method public static createDisplayOptionSpec(Lcom/android/launcher3/util/DisplayController$Info;Z)Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;
    .registers 6

    iget-object v3, p0, Lcom/android/launcher3/util/DisplayController$Info;->context:Landroid/content/Context;

    invoke-virtual {v3}, Landroid/content/Context;->getDisplayId()I

    move-result v3

    if-nez v3, :cond_ds2_not_primary

    const/4 v3, 0x1

    goto :cond_ds2_primary_computed

    :cond_ds2_not_primary
    const/4 v3, 0x0

    :cond_ds2_primary_computed
    const/4 v0, 0x0
""", "createDisplayOptionSpec: compute isPrimaryDisplay before the Info register is overwritten")

    edit(inv_device_profile, """    invoke-direct {v1, p0, p1}, Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;-><init>(Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;Z)V
""", """    invoke-direct {v1, p0, p1, v3}, Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;-><init>(Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;ZZ)V
""", "createDisplayOptionSpec: pass isPrimaryDisplay to DisplayOptionSpec")

    # 8. The all-apps icon LABEL is a separate field (AllAppsProfile.iconTextSizePx, also final,
    #    also built deep in the same exhausted DeviceProfile constructor) -- but the DeviceProfile
    #    field that HOLDS an AllAppsProfile (mAllAppsProfile) is not itself final, and has a public
    #    setter already (setAllAppsProfile). So rather than touching the constructor, this builds
    #    a modified copy with the label hidden and installs it from DeviceProfile$Builder.build(),
    #    right after constructing the DeviceProfile (mIsExternalDisplay is already sitting in a
    #    register there, read earlier in the same method for the constructor call) -- a ~170-line
    #    method with registers to spare, not the constructor itself.
    #
    #    AllAppsProfile is a Kotlin data class; copyWithCellHeightPx already shows the pattern for
    #    overriding one field through the generated copy$default bridge (a bitmask selects which
    #    constructor argument positions are actually applied). copyWithIconTextSizePx mirrors it,
    #    overriding position 3 (iconTextSizePx) instead of position 1 (cellHeightPx) -- mask 0x77
    #    vs 0x7d, i.e. every bit set except bit 3.
    helper = """
.method public final copyWithIconTextSizePx(F)Lcom/android/launcher3/deviceprofile/AllAppsProfile;
    .registers 12

    const/16 v8, 0x77

    const/4 v9, 0x0

    const/4 v1, 0x0

    const/4 v2, 0x0

    const/4 v3, 0x0

    const/4 v5, 0x0

    const/4 v6, 0x0

    const/4 v7, 0x0

    move-object v0, p0

    move v4, p1

    invoke-static/range {v0 .. v9}, Lcom/android/launcher3/deviceprofile/AllAppsProfile;->copy$default(Lcom/android/launcher3/deviceprofile/AllAppsProfile;Landroid/graphics/Point;IIFIIIILjava/lang/Object;)Lcom/android/launcher3/deviceprofile/AllAppsProfile;

    move-result-object p0

    return-object p0
.end method
"""
    if "copyWithIconTextSizePx" in all_apps_profile.read_text():
        print("  = copyWithIconTextSizePx helper (already applied)")
    else:
        all_apps_profile.write_text(all_apps_profile.read_text().rstrip("\n") + "\n" + helper)
        print("  + copyWithIconTextSizePx helper appended")

    edit(builder, """    invoke-direct/range {v1 .. v13}, Lcom/android/launcher3/DeviceProfile;-><init>(Lcom/android/launcher3/InvariantDeviceProfile;Lcom/android/launcher3/util/DisplayController$Info;Lcom/android/launcher3/util/window/WindowManagerProxy;Lcom/android/launcher3/util/WindowBounds;Landroid/util/SparseArray;ZZZZLcom/android/launcher3/DeviceProfile$ViewScaleProvider;Ljava/util/function/Consumer;Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;)V

    return-object v1
""", """    invoke-direct/range {v1 .. v13}, Lcom/android/launcher3/DeviceProfile;-><init>(Lcom/android/launcher3/InvariantDeviceProfile;Lcom/android/launcher3/util/DisplayController$Info;Lcom/android/launcher3/util/window/WindowManagerProxy;Lcom/android/launcher3/util/WindowBounds;Landroid/util/SparseArray;ZZZZLcom/android/launcher3/DeviceProfile$ViewScaleProvider;Ljava/util/function/Consumer;Lcom/android/launcher3/InvariantDeviceProfile$DisplayOptionSpec;)V

    if-eqz v7, :cond_ds2_skip_hide_allapps_labels

    invoke-virtual {v1}, Lcom/android/launcher3/DeviceProfile;->getAllAppsProfile()Lcom/android/launcher3/deviceprofile/AllAppsProfile;

    move-result-object v0

    const/4 v2, 0x0

    invoke-virtual {v0, v2}, Lcom/android/launcher3/deviceprofile/AllAppsProfile;->copyWithIconTextSizePx(F)Lcom/android/launcher3/deviceprofile/AllAppsProfile;

    move-result-object v0

    invoke-virtual {v1, v0}, Lcom/android/launcher3/DeviceProfile;->setAllAppsProfile(Lcom/android/launcher3/deviceprofile/AllAppsProfile;)V

    :cond_ds2_skip_hide_allapps_labels
    return-object v1
""", "Builder.build(): hide all-apps labels on a non-primary (mIsExternalDisplay) display")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: patch_smali.py <classes-baksmali-dir> <classes2-baksmali-dir>")
    main_classes1(sys.argv[1])
    main(sys.argv[2])
