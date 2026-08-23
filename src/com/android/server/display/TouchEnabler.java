package com.android.server.display;

import android.os.RemoteException;
import android.util.Slog;

import vendor.lge.hardware.dualscreen.V1_0.IDualScreen;
import vendor.lge.hardware.dualscreen.V1_0.LpwgMode;
import vendor.lge.hardware.dualscreen.V1_0.LpwgStatus;
import vendor.lge.hardware.dualscreen.V1_0.ScreenStatus;

/**
 * Takes the DS2 digitizer out of LPWG (gesture-only) mode so it reports ordinary touches.
 *
 * LG's touch controllers run in U0 (sleep / knock-on gestures only) or U3 (normal reporting).
 * The DS2's controller comes up in U0 on every attach, and nothing in LineageOS ever tells it
 * otherwise, so the panel lights up and the digitizer stays mute.
 *
 * The symptoms are thoroughly misleading, which is why this took so long to find:
 *
 *   - getTouchFirmwareVersion() answers with real data (v3.34, product id B3W68DS3)
 *   - getSelfTest() reports "Raw Data : Pass / Channel Status : Pass"
 *   - getGpiopin() shows reset_pin=1, i.e. out of reset
 *   - hid-multitouch binds the device and creates input nodes with correct axis ranges
 *   - the IDC binds those nodes to the external display
 *   - and not one HID report is ever emitted
 *
 * DoTouchReset(), set_touch_perf(true) and ds_update_state() all return success and change
 * nothing. Handing the controller screenStatus=ON with LPWG disabled is what actually moves it
 * to U3.
 *
 * This lives outside SubLcdController deliberately: that file cannot be recompiled against the
 * enjarify-converted framework jars, because enjarify strips the generic signature from
 * RemoteCallbackList. Keeping this separate means the prebuilt SubLcdController class can be
 * reused untouched.
 */
public final class TouchEnabler {

    private static final String TAG = "TouchEnabler";

    private TouchEnabler() {
    }

    /**
     * @return true if the HAL accepted the mode change
     */
    public static boolean enableTouchReporting() {
        try {
            IDualScreen hal = IDualScreen.getService(true);
            if (hal == null) {
                Slog.w(TAG, "IDualScreen@1.0 unavailable; cannot enable touch reporting");
                return false;
            }
            LpwgStatus status = new LpwgStatus();
            status.lpwgMode = LpwgMode.DISABLE;
            status.screenStatus = ScreenStatus.ON;
            int err = hal.setStatus(status);
            Slog.i(TAG, "setStatus(lpwg=DISABLE, screen=ON) -> " + err);
            return err == 0;
        } catch (RemoteException e) {
            Slog.e(TAG, "enableTouchReporting failed", e);
            return false;
        } catch (Throwable t) {
            Slog.e(TAG, "enableTouchReporting threw", t);
            return false;
        }
    }
}
