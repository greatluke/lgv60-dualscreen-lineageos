package com.android.server.display;

import android.os.RemoteException;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;

import vendor.lge.hardware.dualscreen.V1_0.IDualScreen;

/**
 * Keeps the DS2's backlight in step with the built-in panel's.
 *
 * Android's brightness slider only drives the built-in display; the DS2 is an ordinary external
 * display with no control surface of its own, so without this it sits at whatever level it
 * powered on with. Mirroring the built-in panel means the normal UI slider (and adaptive
 * brightness) effectively control both.
 *
 * The built-in panel's live value is readable from panel0-backlight. Note panel0-backlight-ex is
 * vestigial -- it accepts writes and its actual_brightness stays 0 -- so it is not a usable source
 * or sink. The DS2 side goes through IDualScreen.setBrightness().
 *
 * Scale: the built-in panel runs 0..max_brightness (365 on this device). The DS2's range is not
 * documented and the HAL range-checks nothing -- it returns 0 for values well past any plausible
 * maximum -- so DS2_MAX below is an empirical constant. Adjust it if the panels do not match.
 */
public final class BrightnessSync {

    private static final String TAG = "BrightnessSync";

    private static final String MAIN_BRIGHTNESS = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String MAIN_MAX        = "/sys/class/backlight/panel0-backlight/max_brightness";

    /** Empirical top of the DS2's brightness range. See the class comment. */
    private static final int DS2_MAX = 255;

    /**
     * Poll interval. This is deliberately short: the panel is being followed by eye, and a
     * second of lag is very visible when the slider moves. Reading two small sysfs files at this
     * rate is negligible, and the HAL is only called when the value actually changes.
     */
    private static final long POLL_MS = 120L;

    /** Cached HAL proxy. Resolving it per push cost a service-manager lookup each time. */
    private static volatile IDualScreen sHal;

    /**
     * Forces the next poll to push even if the computed value has not changed. The DS2 forgets
     * its brightness across a power cycle while lastPushed does not, so the two fall out of step
     * after an attach unless this is called.
     */
    private static volatile boolean sForcePush;

    public static void resync() {
        sForcePush = true;
    }

    private BrightnessSync() {
    }

    public static void start() {
        Thread t = new Thread(BrightnessSync::loop, "ds2-brightness-sync");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        int mainMax = readInt(MAIN_MAX, 365);
        if (mainMax <= 0) {
            mainMax = 365;
        }
        Slog.i(TAG, "started; main panel max=" + mainMax + ", DS2 max=" + DS2_MAX);

        int lastPushed = -1;
        while (true) {
            try {
                int main = readInt(MAIN_BRIGHTNESS, -1);
                if (main >= 0) {
                    int target = Math.round((main * (float) DS2_MAX) / mainMax);
                    if (target < 0) target = 0;
                    if (target > DS2_MAX) target = DS2_MAX;

                    // Only talk to the HAL when the value actually moves; adaptive brightness
                    // nudges the panel constantly and there is no point relaying every step.
                    if (target != lastPushed || sForcePush) {
                        if (push(target)) {
                            lastPushed = target;
                            sForcePush = false;
                        }
                    }
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Slog.e(TAG, "sync loop error", t);
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static boolean push(int value) {
        try {
            IDualScreen hal = sHal;
            if (hal == null) {
                hal = IDualScreen.getService(true);
                sHal = hal;
            }
            if (hal == null) {
                return false;
            }
            int err = hal.setBrightness(value);
            if (err != 0) {
                Slog.w(TAG, "setBrightness(" + value + ") -> " + err);
                return false;
            }
            return true;
        } catch (RemoteException e) {
            sHal = null;   // HAL restarted; re-resolve on the next push
            return false;
        } catch (Throwable t) {
            sHal = null;
            return false;
        }
    }

    private static int readInt(String path, int fallback) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return (line == null) ? fallback : Integer.parseInt(line.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
