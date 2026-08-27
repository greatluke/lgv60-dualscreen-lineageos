package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Slog;
import android.view.Display;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * Privileged half of the "switch app to the other screen" feature. The visible button lives in a
 * separate app, {@code app/ds2-appswitch} (an AccessibilityService, like ds2navbar) -- a bare
 * app_process daemon cannot add a window at all (WindowManagerService rejects it with "Unknown
 * pid=... uid=0", the same wall ds2navbar's javadoc documents), so this side of the feature only
 * does the one thing that actually needs root: moving the task.
 *
 * IPC from that app to this root daemon has exactly one path that works, worked out the hard way
 * for ds2navbar's button-forwarding first: a plain loopback TCP socket. A shared file doesn't --
 * Magisk's root context (u:r:magisk:s0) can't read app_data_file/media_rw_data_file under
 * SELinux -- and a raw Binder/ServiceManager path needs a policy rule this project isn't adding.
 * A loopback socket needs only the ordinary, install-time-granted INTERNET permission on the
 * app's side; nothing on this side beyond opening the socket.
 *
 * Protocol is deliberately the smallest thing that works: connect, write one big-endian int (the
 * displayId the tapped button lives on), disconnect. No response is read back -- the app has no
 * use for one, and keeping this fire-and-forget avoids a second point where a wedged connection
 * could hang the accept loop.
 *
 * NOTE: currently unwired from DualScreenBridgeDaemon.main() -- shelved to prioritize the DS2
 * rotation fix (DisplayRotationFix). The app/ds2-appswitch companion app was working (confirmed
 * on hardware: a button on each screen, tapping it moved the foreground task across), so this is
 * parked rather than deleted; see docs/taskbar-launch-blocker.md's "App-switch button" section
 * for the full design writeup.
 */
public final class AppSwitchServer {

    private static final String TAG = "AppSwitchServer";

    /** Matches ScreenWakeWatcher's identification of the DS2; see its javadoc for why. */
    private static final String DS2_UNIQUE_ID = "local:4";

    /** Arbitrary, just needs to match app/ds2-appswitch's client. Loopback-only, so no exposure. */
    private static final int PORT = 41889;

    private final Context mSystemContext;

    public AppSwitchServer(Context systemContext) {
        mSystemContext = systemContext;
    }

    public void start() {
        Thread t = new Thread(this::acceptLoop, "ds2-appswitch-server");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        ServerSocket server;
        try {
            // Bound explicitly to loopback: this must never be reachable from anywhere but this
            // device's own processes.
            server = new ServerSocket(PORT, 4, InetAddress.getLoopbackAddress());
        } catch (Throwable t) {
            Slog.e(TAG, "could not open loopback socket on port " + PORT, t);
            return;
        }
        Slog.i(TAG, "listening on 127.0.0.1:" + PORT);
        while (true) {
            try (Socket s = server.accept()) {
                int fromDisplayId = new DataInputStream(s.getInputStream()).readInt();
                switchAwayFrom(fromDisplayId);
            } catch (Throwable t) {
                Slog.e(TAG, "connection handling failed", t);
            }
        }
    }

    /** Moves the topmost visible task on {@code fromDisplayId} to whichever screen is the other one. */
    private void switchAwayFrom(int fromDisplayId) {
        try {
            int toDisplayId = (fromDisplayId == Display.DEFAULT_DISPLAY)
                    ? findDs2DisplayIdOrWarn()
                    : Display.DEFAULT_DISPLAY;
            if (toDisplayId < 0) {
                return;
            }

            Object atm = activityTaskManagerService();
            Class<?> iatmClass = Class.forName("android.app.IActivityTaskManager");

            List<?> infos = (List<?>) iatmClass
                    .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(atm, fromDisplayId);

            // Pick the topmost visible root task on that display. RootTaskInfo/TaskInfo are
            // hidden (not on the SDK stub android.jar shadows in), so their fields are read
            // reflectively, same reasoning as ScreenWakeWatcher's use of reflection throughout.
            Class<?> rootTaskInfoClass = Class.forName("android.app.ActivityTaskManager$RootTaskInfo");
            Field visibleField = rootTaskInfoClass.getField("visible");
            Field positionField = rootTaskInfoClass.getField("position");
            Field taskIdField = rootTaskInfoClass.getField("taskId");

            Object topVisible = null;
            int bestPosition = Integer.MIN_VALUE;
            for (Object info : infos) {
                if (!visibleField.getBoolean(info)) {
                    continue;
                }
                int position = positionField.getInt(info);
                if (position >= bestPosition) {
                    bestPosition = position;
                    topVisible = info;
                }
            }
            if (topVisible == null) {
                Slog.i(TAG, "no visible task on display " + fromDisplayId + "; nothing to switch");
                return;
            }
            int taskId = taskIdField.getInt(topVisible);

            Slog.i(TAG, "moving task " + taskId + " from display " + fromDisplayId
                    + " to display " + toDisplayId);
            iatmClass.getMethod("moveRootTaskToDisplay", int.class, int.class)
                    .invoke(atm, taskId, toDisplayId);
        } catch (Throwable t) {
            Slog.e(TAG, "switchAwayFrom(" + fromDisplayId + ") failed", t);
        }
    }

    private int findDs2DisplayIdOrWarn() {
        try {
            DisplayManager dm = mSystemContext.getSystemService(DisplayManager.class);
            Method getUniqueId = Display.class.getMethod("getUniqueId");
            String category = (String) DisplayManager.class
                    .getField("DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED").get(null);
            Method getDisplaysByCategory = DisplayManager.class.getMethod("getDisplays", String.class);
            for (Display d : (Display[]) getDisplaysByCategory.invoke(dm, category)) {
                if (DS2_UNIQUE_ID.equals(getUniqueId.invoke(d))) {
                    return d.getDisplayId();
                }
            }
            Slog.w(TAG, "DS2 not attached; nothing to switch to");
            return -1;
        } catch (Throwable t) {
            Slog.e(TAG, "findDs2DisplayIdOrWarn failed", t);
            return -1;
        }
    }

    private static Object sAtm;

    private static Object activityTaskManagerService() throws Throwable {
        if (sAtm == null) {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            sAtm = atmClass.getMethod("getService").invoke(null);
        }
        return sAtm;
    }
}
