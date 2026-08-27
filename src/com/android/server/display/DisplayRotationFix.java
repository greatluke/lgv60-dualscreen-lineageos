package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * Lets the DS2 actually rotate with the foreground app's requested orientation (e.g. a fullscreen
 * video going landscape), instead of being permanently pinned to one rotation regardless of what
 * is showing.
 *
 * `LocalDisplayAdapter` only ever sets `FLAG_ROTATES_WITH_CONTENT` on the built-in panel --
 * every "external"-type local display, DS2 included, is unconditionally left without it (see
 * "DS2 does not rotate with content" in docs/taskbar-launch-blocker.md for the full trace through
 * `LocalDisplayAdapter` -> `LogicalDisplay` -> `DisplayContent.shouldRotateWithContent()`).
 * `DisplayRotation` reads that flag exactly once, at construction, into
 * `mDefaultFixedToUserRotation`: without it, `isFixedToUserRotation()` returns `true` for the
 * DS2's `DisplayRotation` for the rest of that boot, meaning the DS2 only ever rotates to match
 * its own (nonexistent) rotation sensor / the user's manual rotation-lock choice -- never the
 * foreground app's requested orientation.
 *
 * `IWindowManager.setFixedToUserRotation(displayId, FIXED_TO_USER_ROTATION_DISABLED)` overrides
 * that per display, at runtime, with no framework/services.jar patch needed: it writes straight
 * into `DisplayRotation.mFixedToUserRotation`, which `isFixedToUserRotation()` checks *before*
 * ever falling back to the flag-derived default (mode `DEFAULT` is the only one that reaches the
 * default). Guarded by `android.permission.SET_ORIENTATION`, a signature permission that
 * `WindowManagerService`'s own `checkCallingPermission` grants unconditionally to this daemon's
 * uid 0, the same way every other privileged call in this project does.
 *
 * `IWindowManager` is hidden (not on the SDK stub android.jar shadows in for this daemon's
 * classpath), so it is resolved via reflection off the raw `"window"` binder, the same reasoning
 * as the reflection throughout `ScreenWakeWatcher` and `AppSwitchServer`.
 */
public final class DisplayRotationFix {

    private static final String TAG = "DisplayRotationFix";

    /** IWindowManager.FIXED_TO_USER_ROTATION_DISABLED -- not on the SDK stub, see class javadoc. */
    private static final int FIXED_TO_USER_ROTATION_DISABLED = 1;

    /** Matches ScreenWakeWatcher's identification of the DS2; see its javadoc for why. */
    private static final String DS2_UNIQUE_ID = "local:4";

    private DisplayRotationFix() {
    }

    /**
     * Registers a {@link DisplayManager.DisplayListener} that (re)applies the fix whenever a
     * display shows up or changes, and applies it once immediately for the case where the DS2 is
     * already attached and already enumerated.
     *
     * A single call at boot right after {@code CoverDisplayPowerBridge.start()} is not enough:
     * that only confirms the accessory HAL has powered the panel, not that
     * {@code DisplayManagerService} has finished enumerating it as a {@link Display} object yet
     * -- confirmed on hardware to race and miss it (logged "DS2 not currently attached" while
     * {@code TouchEnabler} was already successfully talking to the same panel over AT commands).
     * The listener is what actually closes that race, for every future attach too, not just the
     * first; the one-shot call below is just a fast path for the common case where the display
     * is already there by the time this runs.
     */
    public static void watch(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id)   { applyToDs2(context); }
            @Override public void onDisplayChanged(int id) { applyToDs2(context); }
            @Override public void onDisplayRemoved(int id) { }
        };
        dm.registerDisplayListener(listener, new android.os.Handler(android.os.Looper.myLooper()));
        applyToDs2(context);
    }

    /** Finds the DS2 if it is currently attached and applies the fix to it. Safe to call repeatedly. */
    private static void applyToDs2(Context context) {
        try {
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            Method getUniqueId = Display.class.getMethod("getUniqueId");
            String category = (String) DisplayManager.class
                    .getField("DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED").get(null);
            Method getDisplaysByCategory = DisplayManager.class.getMethod("getDisplays", String.class);
            for (Display d : (Display[]) getDisplaysByCategory.invoke(dm, category)) {
                if (DS2_UNIQUE_ID.equals(getUniqueId.invoke(d))) {
                    apply(d.getDisplayId());
                    return;
                }
            }
            Slog.i(TAG, "DS2 not currently attached; nothing to do yet");
        } catch (Throwable t) {
            Slog.e(TAG, "applyToDs2 failed", t);
        }
    }

    private static void apply(int displayId) {
        try {
            IBinder windowBinder = ServiceManager.getService("window");
            Class<?> iwmClass = Class.forName("android.view.IWindowManager");
            Object iwm = Class.forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, windowBinder);
            iwmClass.getMethod("setFixedToUserRotation", int.class, int.class)
                    .invoke(iwm, displayId, FIXED_TO_USER_ROTATION_DISABLED);
            Slog.i(TAG, "display " + displayId + " no longer fixed to user rotation; "
                    + "it will now follow the foreground app's requested orientation");
        } catch (Throwable t) {
            Slog.e(TAG, "apply(" + displayId + ") failed", t);
        }
    }
}
