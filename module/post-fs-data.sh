#!/system/bin/sh
# Launcher3QuickStep.apk is replaced wholesale, so its dex checksums no longer match anything
# ART has cached for it. Drop the profile-guided artifacts so the first launch recompiles cleanly.
rm -rf /data/dalvik-cache/arm64/system_ext@priv-app@Launcher3QuickStep@*
rm -rf /data/misc/profiles/ref/com.android.launcher3

# com.android.window.flags.enable_taskbar_connected_displays is not in this build's aconfig
# storage at all, so it compiles to a hardcoded false with no Settings/Developer-options route to
# turn it on. Its ONE override path is DesktopExperienceFlags.isToggleOverriddenBySystem(), which
# activates purely from this raw property -- Developer options being enabled is not part of the
# check at all, and config_isDesktopModeSupported (which our RRO sets false) is not consulted
# either, because Flags.enableDisplayContentModeManagement() is itself absent from this build.
# Without this, the Taskbar never gets created for a connected display in the first place: no
# nav buttons, no All Apps, nothing -- not merely "can't launch", the whole DS2 UI is missing.
# Runs every boot rather than being set once with `resetprop -p`, since post-fs-data.sh always
# runs before system_server, so there's no reason to persist it to disk as well.
resetprop persist.wm.debug.desktop_experience_devopts 1
