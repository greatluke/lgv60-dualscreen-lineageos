#!/system/bin/sh
# The APK is replaced wholesale, so its dex checksums no longer match anything ART has cached
# for it. Drop the profile-guided artifacts so the first launch recompiles cleanly.
rm -rf /data/dalvik-cache/arm64/system_ext@priv-app@Launcher3QuickStep@*
rm -rf /data/misc/profiles/ref/com.android.launcher3

# com.android.window.flags.enable_taskbar_connected_displays is not in this build's aconfig
# storage, so it compiles to a hardcoded false with no Developer-options route to turn it on.
# Its one override path activates purely from this property -- without it the Taskbar is never
# created for a connected display at all (no nav buttons, no All Apps, nothing).
resetprop persist.wm.debug.desktop_experience_devopts 1
