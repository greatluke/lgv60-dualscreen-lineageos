package com.android.server.display;

import android.os.RemoteException;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;

import vendor.lge.hardware.dualscreen.V1_0.IDualScreen;

/**
 * Drives the DS2's backlight, following the policy Stock implements in
 * {@code CoverDisplayPowerController}.
 *
 * Stock does not simply mirror the built-in panel. The DS2 gets its own persisted brightness in
 * {@code Settings.Secure.screen_brightness_for_coverdisplay}, and a separate flag,
 * {@code Settings.Secure.global_screen_brightness_mode}, decides whether that value tracks the
 * main screen or is set independently:
 *
 * <pre>
 *   int t = BrightnessSynchronizer.brightnessFloatToInt(target);
 *   if (mIsGlobalScreenBrightnessMode &amp;&amp; ...) {
 *       Settings.Secure.putInt(..., "screen_brightness_for_coverdisplay", t);
 *   }
 * </pre>
 *
 * Two details worth copying rather than guessing:
 *
 *  - The DS2's range is 0..255. Stock's {@code clampAbsoluteBrightness()} is
 *    {@code MathUtils.constrain(value, 0, 255)}. An earlier version of this class used 255 as an
 *    eyeballed constant; it happens to be right, and now it is right for a reason.
 *  - Stock feeds it the *brightness setting*, not the panel's raw backlight register. Those are
 *    not the same number -- {@code panel0-backlight} runs 0..365 and has the main panel's curve
 *    already applied, so scaling it into 0..255 applies that curve twice.
 *
 * Reading the setting means shelling out, since a bare app_process has no Context and therefore
 * no ContentResolver. To keep that off the hot path, the cheap sysfs backlight file is polled for
 * *change detection* only, and the authoritative setting is read just when it actually moves.
 */
public final class BrightnessSync {

    private static final String TAG = "BrightnessSync";

    /** Cheap change-detector. Not used as the brightness value itself -- see the class comment. */
    private static final String MAIN_BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";

    /** Stock's own range for the DS2: MathUtils.constrain(value, 0, 255). */
    private static final int DS2_MIN = 0;
    private static final int DS2_MAX = 255;

    private static final long POLL_MS = 120L;
    /** The mode flag changes rarely; no need to shell out for it often. */
    private static final long MODE_TTL_MS = 5000L;
    /** How often to re-read the DS2's own setting when it is not following the main screen. */
    private static final long OWN_TTL_MS = 1000L;

    private static volatile IDualScreen sHal;
    private static volatile boolean sForcePush;

    /** Forces the next poll to push even if the value has not changed. */
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
        Slog.i(TAG, "started; DS2 range " + DS2_MIN + ".." + DS2_MAX
                + " (Stock clampAbsoluteBrightness)");

        int lastBacklight = -1;
        int lastPushed = -1;
        boolean globalMode = true;
        long modeCheckedAt = 0L;
        long ownCheckedAt = 0L;

        while (true) {
            try {
                long now = android.os.SystemClock.elapsedRealtime();

                if (now - modeCheckedAt >= MODE_TTL_MS) {
                    modeCheckedAt = now;
                    // Default 1: following the main screen is the sane default when Stock's
                    // settings have never been written, which is the case on LineageOS.
                    globalMode = settingInt("secure", "global_screen_brightness_mode", 1) != 0;
                }

                int backlight = readInt(MAIN_BACKLIGHT, -1);
                boolean mainMoved = (backlight != lastBacklight);
                lastBacklight = backlight;

                int target;
                if (globalMode) {
                    // Only shell out when the panel actually moved, or when forced.
                    if (!mainMoved && !sForcePush && lastPushed >= 0) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    int setting = settingInt("system", "screen_brightness", -1);
                    if (setting < 0) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    target = clamp(setting);
                    // Mirror Stock, which persists the value it applied.
                    if (target != lastPushed) {
                        putSetting("secure", "screen_brightness_for_coverdisplay", target);
                    }
                } else {
                    // Independent: whatever the DS2's own setting says. Nothing local signals
                    // when that changes, so it is polled on a timer rather than on an event.
                    if (!sForcePush && lastPushed >= 0 && now - ownCheckedAt < OWN_TTL_MS) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    ownCheckedAt = now;
                    int own = settingInt("secure", "screen_brightness_for_coverdisplay", -1);
                    if (own < 0) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    target = clamp(own);
                }

                if (target != lastPushed || sForcePush) {
                    if (push(target)) {
                        lastPushed = target;
                        sForcePush = false;
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

    /** Stock's clampAbsoluteBrightness. */
    private static int clamp(int v) {
        return Math.max(DS2_MIN, Math.min(DS2_MAX, v));
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
            sHal = null;   // HAL restarted; re-resolve next time
            return false;
        } catch (Throwable t) {
            sHal = null;
            return false;
        }
    }

    private static int settingInt(String namespace, String key, int def) {
        String s = exec("settings", "get", namespace, key);
        if (s == null) {
            return def;
        }
        s = s.trim();
        if (s.isEmpty() || "null".equals(s)) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void putSetting(String namespace, String key, int value) {
        exec("settings", "put", namespace, key, String.valueOf(value));
    }

    private static String exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            p.waitFor();
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
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
