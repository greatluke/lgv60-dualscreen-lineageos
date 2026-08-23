package com.android.server.display;

import android.util.Slog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves apps between the built-in panel and the DS2.
 *
 * Both primitives turn out to be available to a root process:
 *
 *   am start --display &lt;id&gt; …                 launch onto a given screen
 *   am display move-stack &lt;task&gt; &lt;display&gt;    move a running task to another screen
 *
 * The permission check that blocks this from a shell (uid 2000) does not apply here:
 * ActivityManagerService.checkComponentPermission grants unconditionally to uid 0, and the bridge
 * daemon runs as root. An earlier note in this project claimed root could not do this; that was
 * measured from `adb shell` rather than from a root process, and is wrong.
 *
 * "Swap" moves the foreground app on each screen to the other screen. If only one screen has a
 * real app on it, that app is moved across and nothing comes back the other way.
 *
 * Usage: app_process ... com.android.server.display.DisplaySwap [swap|to-ds2|to-main]
 */
public final class DisplaySwap {

    private static final String TAG = "DisplaySwap";

    /** The built-in panel is always logical display 0. */
    private static final int MAIN_DISPLAY = 0;

    /**
     * Activities that are the screen's own furniture rather than something the user put there.
     * Moving a launcher between displays is never what someone means by "swap".
     */
    private static final String[] FURNITURE = {
        "LawnchairLauncher",
        "SecondaryDisplayLauncher",
        "RecentsActivity",
        "com.android.systemui",
    };

    private DisplaySwap() {
    }

    public static void main(String[] args) {
        String mode = (args != null && args.length > 0) ? args[0] : "swap";
        switch (mode) {
            case "to-ds2":  moveForeground(MAIN_DISPLAY, secondDisplay()); break;
            case "to-main": moveForeground(secondDisplay(), MAIN_DISPLAY); break;
            default:        swap(); break;
        }
    }

    /** @return logical id of the DS2, or -1 if it is not attached. */
    public static int secondDisplay() {
        for (String line : exec("dumpsys", "activity", "activities")) {
            Matcher m = Pattern.compile("Display #(\\d+)").matcher(line);
            if (m.find()) {
                int id = Integer.parseInt(m.group(1));
                if (id != MAIN_DISPLAY) {
                    return id;
                }
            }
        }
        return -1;
    }

    /** Swaps the foreground app on each screen. */
    public static void swap() {
        int ds2 = secondDisplay();
        if (ds2 < 0) {
            Slog.i(TAG, "no second display attached; nothing to swap");
            return;
        }
        int mainTask = topUserTask(MAIN_DISPLAY);
        int ds2Task = topUserTask(ds2);

        if (mainTask < 0 && ds2Task < 0) {
            Slog.i(TAG, "neither screen has a user app in the foreground");
            return;
        }
        // Move both before either lands, so a swap does not briefly stack both apps on one
        // screen and trigger a resize the user can see.
        if (mainTask >= 0) {
            move(mainTask, ds2);
        }
        if (ds2Task >= 0) {
            move(ds2Task, MAIN_DISPLAY);
        }
        Slog.i(TAG, "swapped: main t" + mainTask + " -> d" + ds2
                + ", ds2 t" + ds2Task + " -> d" + MAIN_DISPLAY);
    }

    /** Moves whatever is in the foreground on {@code from} onto {@code to}. */
    public static void moveForeground(int from, int to) {
        if (from < 0 || to < 0) {
            Slog.i(TAG, "second display not attached");
            return;
        }
        int task = topUserTask(from);
        if (task < 0) {
            Slog.i(TAG, "no user app in the foreground on display " + from);
            return;
        }
        move(task, to);
        Slog.i(TAG, "moved t" + task + " from d" + from + " to d" + to);
    }

    private static void move(int task, int display) {
        exec("am", "display", "move-stack", String.valueOf(task), String.valueOf(display));
    }

    /**
     * @return the task id of the topmost non-furniture activity on {@code display}, or -1.
     */
    private static int topUserTask(int display) {
        boolean inSection = false;
        Pattern header = Pattern.compile("Display #(\\d+)");
        Pattern record = Pattern.compile("ActivityRecord\\{[^ ]+ u\\d+ ([^ ]+) t(\\d+)\\}");

        for (String line : exec("dumpsys", "activity", "activities")) {
            Matcher h = header.matcher(line);
            if (h.find()) {
                inSection = (Integer.parseInt(h.group(1)) == display);
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher r = record.matcher(line);
            if (r.find()) {
                String component = r.group(1);
                if (!isFurniture(component)) {
                    return Integer.parseInt(r.group(2));
                }
            }
        }
        return -1;
    }

    private static boolean isFurniture(String component) {
        for (String f : FURNITURE) {
            if (component.contains(f)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> exec(String... cmd) {
        List<String> out = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            Slog.e(TAG, "exec failed: " + String.join(" ", cmd), t);
        } finally {
            if (p != null) p.destroy();
        }
        return out;
    }
}
