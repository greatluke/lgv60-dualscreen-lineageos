package com.android.server.display;

import android.os.Handler;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Draws the DS2 cover window (the small strip visible when the case is folded shut).
 *
 * Layout follows the Stock reference: a large left-aligned clock, and to its right a two-line
 * block with the weekday/date above and a battery readout below, white on black. The panel
 * renders it in its own tint.
 *
 * Panel geometry matches what the HAL reports (getSubDisplayInfo: 256x64, format 3). Pixels go
 * out via SubLcdController.drawSubDisplay(int[]) -> IDualScreen.drawSubDisplay().
 *
 * Text is drawn with {@link TinyFont} rather than android.graphics: Paint/Canvas text requires
 * the system font configuration, which a bare app_process never loads (Typeface.DEFAULT throws,
 * Typeface.createFromFile() fails, and Paint.getFontMetrics() then aborts the process natively).
 */
public class CoverWindowRenderer {

    private static final String TAG = "CoverWindowRenderer";

    private static final int WIDTH = 256;
    private static final int HEIGHT = 64;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    /** Redraw cadence; the clock only needs minute resolution. */
    private static final long INTERVAL_MS = 20_000L;

    private static final String BATTERY_CAPACITY = "/sys/class/power_supply/battery/capacity";
    private static final String BATTERY_STATUS   = "/sys/class/power_supply/battery/status";

    private final SubLcdController mController;
    private final Handler mHandler;
    private final int[] mPixels = new int[WIDTH * HEIGHT];

    private final SimpleDateFormat mClockFmt = new SimpleDateFormat("HH:mm", Locale.US);

    /**
     * Date formats in preference order, longest first. The panel is only 256px wide and the
     * clock takes most of it, so a fixed format overflows on longer dates -- "Sat, Aug 22" was
     * clipped to "Sat, Aug 2". render() picks the first of these that actually fits the space
     * left over, so this stays correct for every weekday/month combination.
     */
    private final SimpleDateFormat[] mDateFmts = {
        new SimpleDateFormat("EEE, MMM d", Locale.US),
        new SimpleDateFormat("EEE MMM d", Locale.US),
        new SimpleDateFormat("MMM d", Locale.US),
        new SimpleDateFormat("M/d", Locale.US),
    };

    private boolean mRunning;
    private String mLastRendered;
    /**
     * Whether the case is currently shut. The strip is on the outside of the DS2 half, so it is
     * only visible while folded; drawing to it while open would light an unseen OLED. Starts
     * true so a freshly started daemon shows something until the first hinge event arrives.
     */
    private volatile boolean mCoverClosed = true;

    public CoverWindowRenderer(SubLcdController controller, Handler handler) {
        mController = controller;
        mHandler = handler;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        // Powered once here rather than per-frame; see blank().
        try {
            mController.setSubDisplayPowerState(true);
        } catch (Throwable t) {
            Slog.e(TAG, "initial power-on failed", t);
        }
        mHandler.post(mTick);
        Slog.i(TAG, "cover window renderer started");
    }

    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mTick);
    }

    /** Follows the hinge: draw while shut, blank and power down the strip while open. */
    public void setCoverClosed(boolean closed) {
        if (mCoverClosed == closed) {
            return;
        }
        mCoverClosed = closed;
        Slog.i(TAG, "cover " + (closed ? "closed -> showing strip" : "open -> blanking strip"));
        if (closed) {
            mLastRendered = null;   // force a redraw of current time
            mHandler.post(mTick);
        } else {
            mHandler.removeCallbacks(mTick);
            blank();
        }
    }

    /**
     * Clears the panel to black while the case is open.
     *
     * Deliberately does NOT drop sub-display power: cutting and restoring it made the strip
     * take ~5s to reappear on close. On an OLED strip, black pixels cost essentially nothing,
     * so leaving it powered and blank is the better trade.
     */
    private void blank() {
        try {
            java.util.Arrays.fill(mPixels, BLACK);
            mController.drawSubDisplay(mPixels);
            mLastRendered = null;
        } catch (Throwable t) {
            Slog.e(TAG, "blank failed", t);
        }
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || !mCoverClosed) {
                return;
            }
            try {
                render();
            } catch (Throwable t) {
                Slog.e(TAG, "render failed", t);
            }
            mHandler.postDelayed(this, INTERVAL_MS);
        }
    };

    /** Renders and pushes a frame, skipping the HAL round-trip when nothing visible changed. */
    public void render() {
        Date now = new Date();
        String clock = mClockFmt.format(now);

        // Space left for the right-hand block, given the clock actually rendered.
        final int CLOCK_SCALE = 4, SMALL_SCALE = 2;
        final int LEFT_MARGIN = 6, GAP = 10, RIGHT_MARGIN = 4;
        int blockX = LEFT_MARGIN + TinyFont.measure(clock, CLOCK_SCALE) + GAP;
        int avail = WIDTH - blockX - RIGHT_MARGIN;

        String date = mDateFmts[mDateFmts.length - 1].format(now);
        for (SimpleDateFormat f : mDateFmts) {
            String candidate = f.format(now);
            if (TinyFont.measure(candidate, SMALL_SCALE) <= avail) {
                date = candidate;
                break;
            }
        }
        int battery = readInt(BATTERY_CAPACITY, -1);
        boolean charging = "Charging".equalsIgnoreCase(readString(BATTERY_STATUS, ""));
        boolean noSim = isSimAbsent();
        String status = (battery >= 0 ? battery + "%" : "--") + (charging ? "+" : "");

        int notifs = activeNotifications();

        String signature = clock + "|" + date + "|" + status + "|" + noSim + "|" + notifs;
        if (signature.equals(mLastRendered)) {
            return;
        }

        java.util.Arrays.fill(mPixels, BLACK);

        // Clock, scale 4 -> 20x28 px glyphs, vertically centred.
        final int clockScale = CLOCK_SCALE;
        final int smallScale = SMALL_SCALE;
        int clockY = (HEIGHT - TinyFont.GLYPH_H * clockScale) / 2;
        TinyFont.draw(mPixels, WIDTH, HEIGHT, LEFT_MARGIN, clockY, clock, clockScale, WHITE);

        int right = blockX;
        TinyFont.draw(mPixels, WIDTH, HEIGHT, right, 14, date, smallScale, WHITE);

        // Status row, laid out like Stock: [no-SIM] | NN% [battery]
        int sx = right;
        final int rowY = 36;
        final int iconH = TinyFont.GLYPH_H * smallScale;   // align icons to the text height
        if (noSim) {
            drawNoSim(sx, rowY, 11, iconH, WHITE);
            sx += 15;
            fillRect(sx, rowY, 1, iconH, WHITE);           // the "|" separator
            sx += 6;
        }
        TinyFont.draw(mPixels, WIDTH, HEIGHT, sx, rowY, status, smallScale, WHITE);
        sx += TinyFont.measure(status, smallScale) + 6;
        drawBattery(sx, rowY, 9, iconH, battery, WHITE);
        sx += 9 + 7;

        // Notification count, if there is room left on the row. Stock shows notification icons
        // here; at 256x64 with a scale-4 clock there is nowhere near enough space for per-app
        // icons, so this is a bell plus a count.
        if (notifs > 0) {
            String n = (notifs > 9) ? "9+" : String.valueOf(notifs);
            final int bellW = 6 * smallScale;                 // 12px at scale 2
            int need = bellW + 3 + TinyFont.measure(n, smallScale);
            if (sx + need <= WIDTH - RIGHT_MARGIN) {
                drawBell(sx, rowY, smallScale, WHITE);
                TinyFont.draw(mPixels, WIDTH, HEIGHT, sx + bellW + 3, rowY, n, smallScale, WHITE);
            }
        }

        boolean ok = mController.drawSubDisplay(mPixels);
        if (ok) {
            mLastRendered = signature;
            Slog.i(TAG, "drew cover window: " + signature);
        } else {
            // SubLcdController.setSubDisplayPowerState() is asynchronous, so an early frame can
            // land before the panel is ready. Re-assert power and let the next tick retry;
            // mLastRendered stays unset, so the retry is not suppressed.
            Slog.w(TAG, "drawSubDisplay rejected the frame; re-asserting panel power");
            try {
                mController.setSubDisplayPowerState(true);
            } catch (Throwable t) {
                Slog.e(TAG, "power re-assert failed", t);
            }
        }
    }


    // ---- status-row icons, matching the Stock layout: [no-SIM] | NN% [battery] ----

    /** Fills an axis-aligned rectangle, clipped to the buffer. */
    private void fillRect(int x, int y, int w, int h, int color) {
        for (int py = y; py < y + h; py++) {
            if (py < 0 || py >= HEIGHT) continue;
            int row = py * WIDTH;
            for (int px = x; px < x + w; px++) {
                if (px < 0 || px >= WIDTH) continue;
                mPixels[row + px] = color;
            }
        }
    }

    /** One-pixel-thick rectangle outline. */
    private void strokeRect(int x, int y, int w, int h, int color) {
        fillRect(x, y, w, 1, color);
        fillRect(x, y + h - 1, w, 1, color);
        fillRect(x, y, 1, h, color);
        fillRect(x + w - 1, y, 1, h, color);
    }

    /**
     * Vertical battery with a nub on top and a fill proportional to {@code pct}, like Stock's.
     * Occupies {@code w} x {@code h} starting at (x, y), nub included.
     */
    /**
     * Number of active notifications, via dumpsys because a NotificationListenerService would
     * mean shipping an app. Cached briefly: the render tick runs every second and this dump is
     * not cheap, while a couple of seconds of lag on a count is imperceptible.
     *
     * Deliberately short-lived, unlike an earlier SIM-state cache here that was long enough to
     * leave a freshly inserted SIM showing as absent.
     */
    private int mNotifCount = 0;
    private long mNotifCheckedAt = 0L;
    private static final long NOTIF_TTL_MS = 3000L;

    private int activeNotifications() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mNotifCheckedAt < NOTIF_TTL_MS) {
            return mNotifCount;
        }
        mNotifCheckedAt = now;
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c",
                    "dumpsys notification 2>/dev/null | grep -c '^    NotificationRecord'")
                    .redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null) {
                    mNotifCount = Integer.parseInt(line.trim());
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            // leave the previous count in place rather than flapping to zero
        } finally {
            if (p != null) p.destroy();
        }
        return mNotifCount;
    }

    /**
     * Bell glyph, 6x7 so it scales to 12x14 and lines up with the scale-2 text beside it.
     *
     * Drawn as an explicit bitmap rather than computed from a taper: the computed version came
     * out as two diagonal side-strokes under a dome, which reads as an umbrella, not a bell.
     * At this size the silhouette has to be stated outright -- the flat rim and the clapper
     * hanging below it are what make it legible.
     */
    private static final String[] BELL = {
        "  ##  ",
        " #### ",
        " #  # ",
        "#    #",
        "#    #",
        "######",
        "  ##  ",
    };

    private void drawBell(int x, int y, int scale, int color) {
        for (int row = 0; row < BELL.length; row++) {
            String line = BELL[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '#') {
                    fillRect(x + col * scale, y + row * scale, scale, scale, color);
                }
            }
        }
    }

    private void drawBattery(int x, int y, int w, int h, int pct, int color) {
        int nubW = Math.max(2, w / 3);
        int nubH = 2;
        fillRect(x + (w - nubW) / 2, y, nubW, nubH, color);

        int bodyY = y + nubH;
        int bodyH = h - nubH;
        strokeRect(x, bodyY, w, bodyH, color);

        if (pct > 0) {
            int innerH = bodyH - 4;
            int fillH = Math.max(1, Math.round(innerH * Math.min(100, pct) / 100f));
            // Batteries fill from the bottom.
            fillRect(x + 2, bodyY + 2 + (innerH - fillH), w - 4, fillH, color);
        }
    }

    /** SIM-card outline (notched top-right corner) with a slash: the "no SIM" indicator. */
    private void drawNoSim(int x, int y, int w, int h, int color) {
        int notch = Math.max(2, w / 3);
        // Body, leaving the notch corner empty.
        fillRect(x, y, w - notch, 1, color);                 // top, up to the notch
        fillRect(x + w - 1, y + notch, 1, h - notch, color); // right, below the notch
        fillRect(x, y + h - 1, w, 1, color);                 // bottom
        fillRect(x, y, 1, h, color);                         // left
        // The chamfer across the notched corner.
        for (int i = 0; i < notch; i++) {
            int px = x + w - notch + i;
            int py = y + i;
            fillRect(px, py, 1, 1, color);
        }
        // Slash, bottom-left to top-right.
        int steps = Math.max(w, h);
        for (int i = 0; i <= steps; i++) {
            int px = x + Math.round((w - 1) * (i / (float) steps));
            int py = y + (h - 1) - Math.round((h - 1) * (i / (float) steps));
            fillRect(px, py, 1, 1, color);
        }
    }

    private static boolean sSimPropWarned;

    /**
     * True when no SIM is present.
     *
     * Deliberately NOT cached: an earlier revision cached this for 5 minutes, which meant
     * inserting a SIM left the "no SIM" icon on screen until the cache expired. This is read
     * once per redraw (~20s), and the fast path is a cheap property lookup.
     */
    private static boolean isSimAbsent() {
        String state = readSimState();
        return state.isEmpty() || state.startsWith("ABSENT") || state.startsWith("UNKNOWN");
    }

    private static String readSimState() {
        // Fast path. SystemProperties is a hidden API and has been observed to throw here, so
        // its failure must not be mistaken for "SIM present".
        try {
            return android.os.SystemProperties.get("gsm.sim.state", "");
        } catch (Throwable t) {
            if (!sSimPropWarned) {
                sSimPropWarned = true;
                Slog.w(TAG, "SystemProperties unavailable, falling back to getprop", t);
            }
        }
        // Fallback: ask getprop directly.
        java.io.BufferedReader r = null;
        try {
            Process p = new ProcessBuilder("/system/bin/getprop", "gsm.sim.state")
                    .redirectErrorStream(true).start();
            r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            Slog.w(TAG, "getprop fallback failed", t);
            return "";
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String readString(String path, String def) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            return line == null ? def : line.trim();
        } catch (Throwable t) {
            return def;
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static int readInt(String path, int def) {
        try {
            return Integer.parseInt(readString(path, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }
}
