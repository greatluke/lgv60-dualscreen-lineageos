package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Detects the device waking from sleep and, if the DS2's display is stuck {@code state OFF} as a
 * result, wakes it directly.
 *
 * The DS2 lives in its own {@code displayGroupId}, separate from the built-in panel's default
 * group (confirmed via {@code dumpsys display}: {@code displayGroupId 1} vs {@code 0}), and each
 * group tracks its own wakefulness independently ({@code dumpsys power}'s "Power Group User
 * Activity" section shows {@code groupId: 1} sitting at {@code wakefulness=0} / asleep with zero
 * user-activity events ever recorded, unrelated to whatever the default group is doing). Waking
 * the device only wakes the default group; nothing else ever wakes group 1, so it can be left
 * asleep indefinitely after any lock/unlock cycle even though the accessory itself never lost
 * power and the panel's own hotplug state never changed.
 *
 * Two things were tried and ruled out before landing on the real fix, in case this needs
 * revisiting: (1) {@link CoverDisplayPowerBridge#forceReassertPower()}, a genuine off-then-on
 * cycle of the accessory HAL -- confirmed on hardware to succeed at the HAL/HPD level
 * (`cover power-on result: 1`) without moving the framework's display state at all, meaning the
 * DP link itself was never the problem. (2) {@code cmd power set-wakelock acquire -d <id>
 * FULL_WAKE_LOCK} -- confirmed the lock attaches to the right display
 * ({@code dumpsys power}'s "Wakelocks:" list shows it), but its summary bit on the power group
 * stays zero and the display stays off; the shell command's bookkeeping doesn't route through
 * the same wake path a real call does. What actually works: calling
 * {@link PowerManager#wakeUp(long, int, String, int)} directly with that display's id, the same
 * multi-display wake entry point {@code IPowerManager.wakeUpWithDisplayId} exists for.
 *
 * No sysfs node reliably reflects screen sleep on this panel to poll cheaply either: both
 * {@code panel0-backlight/brightness} and {@code .../actual_brightness} were tested directly and
 * neither changes across a lock/unlock cycle. So detecting the wake transition itself still
 * follows this bridge's usual fallback -- poll a shell command -- mirroring
 * {@link BrightnessSync}'s {@code settings get} pattern with {@code dumpsys power} instead.
 *
 * {@link PowerManager#wakeUp(long, int, String, int)} alone was confirmed on-device to actually
 * flip {@code PowerGroup groupId=1}'s wakefulness to Awake (visible in {@code dumpsys power}'s
 * "Wakefulness Session Power Group" section) -- real progress over the two ruled-out approaches
 * above. But the display's own {@code DisplayPowerController} policy immediately fell back
 * BRIGHT -> DIM -> OFF within seconds anyway, because that group's last recorded *user activity*
 * timestamp was still whatever it was before sleep (tens of seconds old by the time this runs),
 * so its own screen-off timeout fires again almost immediately. wakeUp() bumps wakefulness but
 * doesn't count as user activity for timeout purposes. The public {@link PowerManager} class
 * only exposes {@code userActivity(long, int, int)} for the *default* display; the hidden
 * {@code IPowerManager.userActivity(int displayId, long, int, int)} overload that targets a
 * specific display's group has no public wrapper, so it's called directly on the raw binder via
 * reflection, immediately after the wakeUp() call, to reset that group's timeout clock too.
 *
 * This watches for the whole *device* waking, which also fires when the case is folded shut
 * (the built-in panel's own lock/wake cycle is unrelated to the hinge). Without checking the
 * hinge first, this would immediately re-wake a DS2 that {@link Ds2PanelPower#powerOff} just
 * deliberately put to sleep, defeating that fix outright -- so {@code coverPower} is passed in
 * purely to ask {@link CoverDisplayPowerBridge#isCaseOpen()} before touching the DS2 at all.
 */
public final class ScreenWakeWatcher {

    private static final String TAG = "ScreenWakeWatcher";

    /** The DS2's stable hardware identity; the logical displayId is not (seen as 2, 3, and 4). */
    private static final String DS2_UNIQUE_ID = "local:4";

    /** Wakefulness is not latency-sensitive here; this only needs to catch up within a beat. */
    private static final long POLL_MS = 500L;

    private ScreenWakeWatcher() {
    }

    /**
     * {@code context} must come from a thread that has already called
     * {@code Looper.prepareMainLooper()} or {@code Looper.prepare()} -- {@code
     * ActivityThread.systemMain()} requires one on whichever thread creates it. The daemon's
     * main thread already prepares one for its own event loop, so the context is obtained there
     * and handed in, rather than each retried here on this background thread, which has none.
     */
    public static void start(Context context, CoverDisplayPowerBridge coverPower) {
        Thread t = new Thread(() -> loop(context, coverPower), "ds2-wake-watcher");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(Context context, CoverDisplayPowerBridge coverPower) {
        Slog.i(TAG, "started");
        boolean wasAwake = true;   // assume awake at daemon start; boot already handles attach

        while (true) {
            try {
                boolean awake = isAwake();
                if (awake && !wasAwake) {
                    if (coverPower.isCaseOpen()) {
                        Slog.i(TAG, "device woke up; checking DS2 display power");
                        onWake(context);
                    } else {
                        Slog.i(TAG, "device woke up but the case is folded shut; leaving DS2 off");
                    }
                }
                wasAwake = awake;
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Slog.e(TAG, "watch loop error", t);
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * Android's own {@code PowerManager.WAKE_REASON_DISPLAY_GROUP_TURNED_ON} -- not visible at
     * compile time here. android.jar has to be first on the compile classpath for the rest of
     * this daemon to build at all (see tools/build.sh's comment), which means its SDK-stub
     * version of {@link Display} and {@link PowerManager} wins over the real platform classes in
     * FRAMEWORK_JAR for any single class present in both -- including this constant and
     * {@link Display#getUniqueId()} below, neither of which the SDK stub declares. Reflection
     * sidesteps needing either at compile time without disturbing that ordering.
     */
    private static final int WAKE_REASON_DISPLAY_GROUP_TURNED_ON = 11;

    /** PowerManager.USER_ACTIVITY_EVENT_OTHER -- also not on the SDK stub, see above. */
    private static final int USER_ACTIVITY_EVENT_OTHER = 0;

    private static void onWake(Context context) {
        try {
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            Method getUniqueId = Display.class.getMethod("getUniqueId");
            // Same android.jar-shadowing issue as WAKE_REASON_DISPLAY_GROUP_TURNED_ON above --
            // this constant isn't in the SDK stub either, so it's read via reflection too rather
            // than referenced directly.
            String category = (String) DisplayManager.class
                    .getField("DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED").get(null);
            Method getDisplaysByCategory = DisplayManager.class.getMethod("getDisplays", String.class);
            Display ds2 = null;
            // Plain getDisplays() excludes a display that is currently OFF -- exactly the one
            // case this needs to find.
            for (Display d : (Display[]) getDisplaysByCategory.invoke(dm, category)) {
                if (DS2_UNIQUE_ID.equals(getUniqueId.invoke(d))) {
                    ds2 = d;
                    break;
                }
            }
            if (ds2 == null) {
                Slog.i(TAG, "DS2 not currently enumerated; nothing to wake");
                return;
            }
            if (ds2.getState() != Display.STATE_OFF) {
                return;
            }
            Slog.i(TAG, "DS2 (displayId=" + ds2.getDisplayId() + ") left OFF after wake; waking it");
            PowerManager pm = context.getSystemService(PowerManager.class);
            Method wakeUp = PowerManager.class.getMethod(
                    "wakeUp", long.class, int.class, String.class, int.class);
            wakeUp.invoke(pm, SystemClock.uptimeMillis(), WAKE_REASON_DISPLAY_GROUP_TURNED_ON,
                    "ds2-wake-watcher", ds2.getDisplayId());

            // wakeUp() alone doesn't reset this group's activity timeout clock (see class
            // javadoc) -- immediately mark the display's own group as freshly active too, via
            // the hidden per-display IPowerManager.userActivity overload, so its own timeout
            // doesn't fire again a moment later.
            IBinder powerBinder = ServiceManager.getService("power");
            Class<?> iPowerManagerClass = Class.forName("android.os.IPowerManager");
            Class<?> stubClass = Class.forName("android.os.IPowerManager$Stub");
            Object powerService = stubClass.getMethod("asInterface", IBinder.class)
                    .invoke(null, powerBinder);
            Method userActivity = iPowerManagerClass.getMethod(
                    "userActivity", int.class, long.class, int.class, int.class);
            userActivity.invoke(powerService, ds2.getDisplayId(), SystemClock.uptimeMillis(),
                    USER_ACTIVITY_EVENT_OTHER, 0);
        } catch (Throwable t) {
            Slog.e(TAG, "onWake failed", t);
        }
    }

    private static boolean isAwake() {
        String out = exec("dumpsys", "power");
        // "mWakefulness=Awake" vs Asleep/Dozing/DreamingWake.
        return out != null && out.contains("mWakefulness=Awake");
    }

    private static String exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
