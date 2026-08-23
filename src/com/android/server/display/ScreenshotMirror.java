package com.android.server.display;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.FileObserver;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes Power+VolDown capture both panels as a single image.
 *
 * The screenshot key combo is handled inside the framework and is hardcoded to the default
 * display; nothing a Magisk module overlays can change that. What we can do is react to it: watch
 * the screenshots directory, and when one appears while the Dual Screen is attached, capture the
 * second panel, join the two side by side, and replace the original with the combined image. The
 * user ends up with one screenshot per press, showing both screens.
 *
 * With no Dual Screen attached the screenshot is left exactly as the framework wrote it.
 *
 * Note on android.graphics: bitmap decode/draw/compress work fine under bare app_process, unlike
 * text rendering, which aborts because Typeface is never initialised (hence TinyFont elsewhere in
 * this project). Bitmap.Config and Bitmap.CompressFormat are reached reflectively because the
 * enjarify-converted framework jars drop those nested enums, so they cannot be named at compile
 * time.
 */
public final class ScreenshotMirror {

    private static final String TAG = "ScreenshotMirror";

    private static final String DIR = "/sdcard/Pictures/Screenshots";
    private static final String TMP_DS2 = "/data/local/tmp/_ds2_shot.png";

    /** Space between the two panels in the combined image. */
    private static final int GAP = 24;
    private static final int BACKDROP = 0xFF121212;

    private static FileObserver sObserver;   // held so it is not collected

    /** Filenames we are about to write ourselves, so our own writes do not re-trigger. */
    private static final Set<String> sSuppress =
            Collections.synchronizedSet(new HashSet<String>());

    private ScreenshotMirror() {
    }

    public static void start() {
        File dir = new File(DIR);
        if (!dir.isDirectory()) {
            Slog.w(TAG, DIR + " does not exist; not watching for screenshots");
            return;
        }

        // CLOSE_WRITE rather than CREATE: on CREATE the framework is still writing the file.
        sObserver = new FileObserver(DIR, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String name) {
                if (name == null || !name.toLowerCase().endsWith(".png")) {
                    return;
                }
                if (sSuppress.remove(name)) {
                    return;   // this was our own combined image landing
                }
                handle(new File(DIR, name));
            }
        };
        sObserver.startWatching();
        Slog.i(TAG, "watching " + DIR + "; screenshots will include the DS2 when attached");
    }

    private static void handle(File shot) {
        Bitmap main = null, ds2 = null, joined = null;
        File tmp = new File(TMP_DS2);
        try {
            String displayId = findSecondDisplayId();
            if (displayId == null) {
                return;   // single screen: leave the framework's screenshot untouched
            }

            tmp.delete();
            // Capture outside the watched directory so this does not trip the observer.
            if (run("screencap", "-p", "-d", displayId, TMP_DS2) != 0
                    || !tmp.isFile() || tmp.length() == 0) {
                Slog.w(TAG, "screencap of display " + displayId + " failed; leaving original");
                return;
            }

            main = BitmapFactory.decodeFile(shot.getAbsolutePath());
            ds2 = BitmapFactory.decodeFile(TMP_DS2);
            if (main == null || ds2 == null) {
                Slog.w(TAG, "could not decode one of the captures; leaving original");
                return;
            }

            joined = join(ds2, main);
            if (joined == null) {
                return;
            }

            // Replace the original in place, so the gallery shows one screenshot per press
            // rather than the original plus an extra.
            sSuppress.add(shot.getName());
            boolean ok = false;
            try (OutputStream os = new FileOutputStream(shot)) {
                ok = compressPng(joined, os);
            }
            if (!ok) {
                sSuppress.remove(shot.getName());
                Slog.w(TAG, "failed to write combined image");
                return;
            }

            // The daemon runs as root; without this the file is root-owned and the gallery
            // cannot read it. Re-scan so MediaStore picks up the new dimensions.
            run("sh", "-c", "chown media_rw:media_rw '" + shot.getAbsolutePath() + "' 2>/dev/null");
            run("sh", "-c", "chmod 660 '" + shot.getAbsolutePath() + "' 2>/dev/null");
            run("sh", "-c", "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
                    + " -d file://" + shot.getAbsolutePath() + " >/dev/null 2>&1");

            Slog.i(TAG, "combined both panels into " + shot.getName()
                    + " (" + joined.getWidth() + "x" + joined.getHeight() + ")");
        } catch (Throwable t) {
            Slog.e(TAG, "failed to combine screenshot", t);
        } finally {
            tmp.delete();
            recycle(main);
            recycle(ds2);
            recycle(joined);
        }
    }

    /** Joins two panels side by side. The DS2 goes on the left, matching an open case. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Bitmap join(Bitmap left, Bitmap right) {
        try {
            Class cfg = Class.forName("android.graphics.Bitmap$Config");
            Object argb = Enum.valueOf(cfg, "ARGB_8888");
            Method create = Bitmap.class.getMethod("createBitmap", int.class, int.class, cfg);

            int w = left.getWidth() + GAP + right.getWidth();
            int h = Math.max(left.getHeight(), right.getHeight());
            Bitmap out = (Bitmap) create.invoke(null, w, h, argb);

            Canvas c = new Canvas(out);
            c.drawColor(BACKDROP);
            c.drawBitmap(left, 0, 0, null);
            c.drawBitmap(right, left.getWidth() + GAP, 0, null);
            return out;
        } catch (Throwable t) {
            Slog.e(TAG, "join failed", t);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean compressPng(Bitmap bmp, OutputStream os) {
        try {
            Class fmt = Class.forName("android.graphics.Bitmap$CompressFormat");
            Object png = Enum.valueOf(fmt, "PNG");
            Method compress = Bitmap.class.getMethod("compress", fmt, int.class, OutputStream.class);
            Object r = compress.invoke(bmp, png, 100, os);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            Slog.e(TAG, "compress failed", t);
            return false;
        }
    }

    private static void recycle(Bitmap b) {
        try {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * @return the SurfaceFlinger display id of the second panel, or null if there is not one.
     *         Resolved fresh each time: the DS2 is hotplugged, so caching the id would break the
     *         first time it came back with a different one.
     */
    private static String findSecondDisplayId() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "SurfaceFlinger", "--display-id")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // e.g. "Display 4 (HWC display 1): invalid EDID"
                    if (line.contains("HWC display 1")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            return parts[1];
                        }
                    }
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            Slog.e(TAG, "could not resolve second display id", t);
        } finally {
            if (p != null) p.destroy();
        }
        return null;
    }

    private static int run(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().close();
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
