package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * Explicitly powers the DS2's main panel on or off to follow the hinge, independently of the
 * cover strip's own power state (see {@link CoverWindowRenderer}) and of the built-in panel's.
 *
 * Before this, folding the case only blanked the outer cover strip -- {@link
 * CoverDisplayPowerBridge}'s {@code mSmartCoverCallback} deliberately never touched the main
 * panel's own power, per its comment, because power-cycling the *whole accessory* (which is what
 * {@code setCoverDisplayButtonStatus} does) made the strip take ~5s to reappear on close. That
 * was the right call for the strip, but it also meant the DS2's main panel stayed fully powered
 * the entire time the accessory was attached, fold or not -- a real, needless battery drain
 * whenever the case sits closed.
 *
 * This works at the Android display-power layer instead of the accessory HAL layer, so it only
 * touches the main panel's {@code Display}, leaving the strip's own HAL-level power path (and
 * its fast comeback) completely alone. The two calls are the direct sleep/wake counterparts of
 * each other, both per-displayId overloads of the same hidden {@code IPowerManager} methods
 * {@link ScreenWakeWatcher} already uses for the wake side -- see its javadoc for why these need
 * reflection here (android.jar shadows the real framework class for the members this daemon's
 * classpath needs).
 */
public final class Ds2PanelPower {

    private static final String TAG = "Ds2PanelPower";

    /** Matches ScreenWakeWatcher's identification of the DS2; see its javadoc for why. */
    private static final String DS2_UNIQUE_ID = "local:4";

    private static final int WAKE_REASON_DISPLAY_GROUP_TURNED_ON = 11;
    /** PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD -- not on the SDK stub, see class javadoc. */
    private static final int GO_TO_SLEEP_REASON_DEVICE_FOLD = 13;

    private Ds2PanelPower() {
    }

    public static void powerOn(Context context) {
        Display ds2 = findDs2(context);
        if (ds2 == null) {
            return;
        }
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            Method wakeUp = PowerManager.class.getMethod(
                    "wakeUp", long.class, int.class, String.class, int.class);
            wakeUp.invoke(pm, SystemClock.uptimeMillis(), WAKE_REASON_DISPLAY_GROUP_TURNED_ON,
                    "ds2-hinge-open", ds2.getDisplayId());
            Slog.i(TAG, "hinge open: powering DS2 (displayId=" + ds2.getDisplayId() + ") on");
        } catch (Throwable t) {
            Slog.e(TAG, "powerOn failed", t);
        }
    }

    public static void powerOff(Context context) {
        Display ds2 = findDs2(context);
        if (ds2 == null) {
            return;
        }
        try {
            IBinder powerBinder = ServiceManager.getService("power");
            Class<?> iPowerManagerClass = Class.forName("android.os.IPowerManager");
            Object powerService = Class.forName("android.os.IPowerManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, powerBinder);
            Method goToSleepWithDisplayId = iPowerManagerClass.getMethod(
                    "goToSleepWithDisplayId", int.class, long.class, int.class, int.class);
            goToSleepWithDisplayId.invoke(powerService, ds2.getDisplayId(),
                    SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_DEVICE_FOLD, 0);
            Slog.i(TAG, "hinge closed: powering DS2 (displayId=" + ds2.getDisplayId() + ") off");
        } catch (Throwable t) {
            Slog.e(TAG, "powerOff failed", t);
        }
    }

    /** Same DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED / getUniqueId reflection as ScreenWakeWatcher. */
    private static Display findDs2(Context context) {
        try {
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            Method getUniqueId = Display.class.getMethod("getUniqueId");
            String category = (String) DisplayManager.class
                    .getField("DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED").get(null);
            Method getDisplaysByCategory = DisplayManager.class.getMethod("getDisplays", String.class);
            for (Display d : (Display[]) getDisplaysByCategory.invoke(dm, category)) {
                if (DS2_UNIQUE_ID.equals(getUniqueId.invoke(d))) {
                    return d;
                }
            }
            return null;
        } catch (Throwable t) {
            Slog.e(TAG, "findDs2 failed", t);
            return null;
        }
    }
}
