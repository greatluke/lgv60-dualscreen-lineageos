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
 *  - Stock's value is the brightness *setting*, 0..255. The backlight register is the same
 *    quantity on a different scale: measured pairs give panel/setting ~1.44..1.58 against a
 *    nominal 365/255 = 1.43, i.e. near-linear. So the register can stand in for the setting, and
 *    an earlier worry here that scaling it would double-apply a gamma curve was unfounded --
 *    there is no second curve.
 *
 * The register is used rather than the setting because it tracks *live*. Android only commits
 * {@code screen_brightness} when the slider is released; during a drag it applies brightness
 * through a temporary override, which is what Stock handles with its
 * {@code MSG_SET_TEMPORARY_BRIGHTNESS} path. Reading the setting therefore lags the drag, while
 * the register follows it. It is also far cheaper -- no shelling out on the hot path.
 */
public final class BrightnessSync {

    private static final String TAG = "BrightnessSync";

    /** Live source for the main panel's level; tracks slider drags, unlike the setting. */
    private static final String MAIN_BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String MAIN_BACKLIGHT_MAX =
            "/sys/class/backlight/panel0-backlight/max_brightness";

    /** Stock's own range for the DS2: MathUtils.constrain(value, 0, 255). */
    private static final int DS2_MIN = 0;
    private static final int DS2_MAX = 255;

    /**
     * Perceptual curve applied on top of Stock's 1:1 mapping.
     *
     * Stock passes the brightness setting straight through, but downstream it goes through the
     * cover display's own nits/gamma mapping, which we do not have -- the
     * vendor.lge.hardware.display.brightness HAL that Stock's CoverDisplayPowerController talks
     * to is not among the blobs and is not registered on LineageOS. Passing the raw setting
     * therefore lands visibly dimmer on the DS2 than the same number does on the built-in panel.
     *
     * gamma 0.5 (a square root) lifts the midtones while pinning both ends: 0 stays off and 255
     * stays maximum, so the slider still reaches both extremes. Tunable via
     * {@code Settings.Secure.coverdisplay_brightness_gamma} expressed in hundredths -- 100 is
     * linear (Stock's literal behaviour), 50 is the default curve.
     */
    private static final int DEFAULT_GAMMA_PCT = 50;

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
        int gammaPct = DEFAULT_GAMMA_PCT;
        long modeCheckedAt = 0L;
        long ownCheckedAt = 0L;
        int mainMax = readInt(MAIN_BACKLIGHT_MAX, 365);
        if (mainMax <= 0) {
            mainMax = 365;
        }

        while (true) {
            try {
                long now = android.os.SystemClock.elapsedRealtime();

                if (now - modeCheckedAt >= MODE_TTL_MS) {
                    modeCheckedAt = now;
                    // Default 1: following the main screen is the sane default when Stock's
                    // settings have never been written, which is the case on LineageOS.
                    globalMode = settingInt("secure", "global_screen_brightness_mode", 1) != 0;
                    gammaPct = settingInt("secure", "coverdisplay_brightness_gamma",
                            DEFAULT_GAMMA_PCT);
                }

                int backlight = readInt(MAIN_BACKLIGHT, -1);
                boolean mainMoved = (backlight != lastBacklight);
                lastBacklight = backlight;

                int target;
                if (globalMode) {
                    if (!mainMoved && !sForcePush && lastPushed >= 0) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    if (backlight < 0) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }
                    // Register -> Stock's 0..255 scale, then the perceptual curve.
                    int equiv = clamp(Math.round(backlight * (float) DS2_MAX / mainMax));
                    target = curve(equiv, gammaPct);

                    // Persist what we applied, as Stock does. Only on change: this shells out.
                    if (target != lastPushed) {
                        putSetting("secure", "screen_brightness_for_coverdisplay", equiv);
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
                    // A value the user chose directly, so no curve.
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

    /** Applies the perceptual curve to a 0..255 setting value. */
    private static int curve(int setting, int gammaPct) {
        if (gammaPct == 100 || setting <= 0) {
            return clamp(setting);
        }
        double norm = Math.min(1.0, Math.max(0.0, setting / (double) DS2_MAX));
        double out = Math.pow(norm, gammaPct / 100.0);
        return clamp((int) Math.round(out * DS2_MAX));
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
