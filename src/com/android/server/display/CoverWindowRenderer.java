package com.android.server.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
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
 * Text is real android.graphics text in LG's own font, matching Stock's metrics (48px clock,
 * 20px date, 13px battery, 18x18 battery icon, 2px margins, 128px right block).
 *
 * That is only possible because of the Typeface bootstrap below. In a bare app_process the font
 * map is never installed -- Typeface.createFromFile() throws "The Typeface is not fully
 * initialized" and Paint.measureText() then *aborts the process natively*. Calling the hidden
 * Typeface.loadPreinstalledSystemFontMap() first fixes it. This project previously worked around
 * the problem with a hand-rolled 5x7 bitmap font (TinyFont), which is no longer needed here.
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

    /** LG's own clock font, lifted from Stock's LGSubDisplay. Only 3.4KB -- digits and colon. */
    /**
     * Optional caption, e.g. "LineageOS". Read from a file so it can be changed without a
     * rebuild; empty or missing means the layout is exactly as before.
     */
    private static final String LABEL_FILE =
            "/data/adb/modules/lge_ds2_hal_shim/cover_label.txt";

    private static final String LG_NUMBER_FONT =
            "/data/adb/modules/lge_ds2_hal_shim/fonts/font_lg_smart_ui_number_regular.ttf";

    // Stock's metrics, from LGSubDisplay's dimens.xml.
    private static final float FONT_TIME = 48f;
    /** Clock shrinks slightly when a caption is shown, to make vertical room for it. */
    private static final float FONT_TIME_WITH_LABEL = 38f;
    private static final float FONT_LABEL = 13f;
    private static final float FONT_DATE = 20f;
    private static final float FONT_BATTERY = 13f;
    private static final int MARGIN_START = 2;
    private static final int MARGIN_END = 2;
    private static final int DATE_BOTTOM_GAP = 3;
    private static final int BATT_ICON_W = 18;
    private static final int BATT_ICON_H = 18;

    /**
     * Installs the system font map. MUST run before any Paint/Typeface use: without it
     * Paint.measureText() does not throw, it aborts the process. Hidden API, so reflection.
     */
    private static void bootstrapTypeface() {
        try {
            java.lang.reflect.Method m =
                    Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable t) {
            Slog.e(TAG, "Typeface bootstrap failed; text rendering will not work", t);
        }
    }

    /** Paint.Style constants, resolved reflectively for the same reason as Bitmap.Config. */
    private static Object sStyleFill, sStyleStroke;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void resolveStyles() {
        if (sStyleFill != null) {
            return;
        }
        try {
            Class st = Class.forName("android.graphics.Paint$Style");
            sStyleFill = Enum.valueOf(st, "FILL");
            sStyleStroke = Enum.valueOf(st, "STROKE");
        } catch (Throwable t) {
            Slog.e(TAG, "cannot resolve Paint.Style", t);
        }
    }

    private void setStyle(Paint p, Object style) {
        try {
            java.lang.reflect.Method m = Paint.class.getMethod("setStyle",
                    Class.forName("android.graphics.Paint$Style"));
            m.invoke(p, style);
        } catch (Throwable t) {
            // leave the paint as-is; worst case the icon is filled rather than stroked
        }
    }

    private Bitmap mBitmap;
    private Canvas mCanvas;
    private Paint mClockPaint, mClockSmallPaint, mDatePaint, mBattPaint, mIconPaint;
    private Paint mLabelPaint;

    private void initGraphics() {
        if (mCanvas != null) {
            return;
        }
        bootstrapTypeface();
        resolveStyles();

        Typeface number = Typeface.DEFAULT;
        try {
            Typeface t = Typeface.createFromFile(LG_NUMBER_FONT);
            if (t != null) {
                number = t;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "LG number font unavailable, falling back to the system font", t);
        }

        mBitmap = createBitmap(WIDTH, HEIGHT);
        mCanvas = new Canvas(mBitmap);

        mClockPaint = textPaint(FONT_TIME, number);
        mClockSmallPaint = textPaint(FONT_TIME_WITH_LABEL, number);
        mLabelPaint = textPaint(FONT_LABEL, Typeface.DEFAULT);
        mDatePaint = textPaint(FONT_DATE, Typeface.DEFAULT);
        mBattPaint = textPaint(FONT_BATTERY, Typeface.DEFAULT);
        mIconPaint = new Paint();
        mIconPaint.setAntiAlias(true);
        mIconPaint.setColor(WHITE);
    }

    private static Paint textPaint(float size, Typeface tf) {
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setSubpixelText(true);
        p.setColor(WHITE);
        p.setTextSize(size);
        p.setTypeface(tf);
        return p;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Bitmap createBitmap(int w, int h) {
        try {
            // Bitmap.Config cannot be named at compile time: the enjarify-converted framework
            // jars drop nested enums.
            Class cfg = Class.forName("android.graphics.Bitmap$Config");
            Object argb = Enum.valueOf(cfg, "ARGB_8888");
            java.lang.reflect.Method create =
                    Bitmap.class.getMethod("createBitmap", int.class, int.class, cfg);
            return (Bitmap) create.invoke(null, w, h, argb);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create bitmap", t);
        }
    }

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
        if (!drawFrame()) {
            return;
        }
        pushFrame();
    }

    /** Builds the frame. @return false if nothing visible changed. */
    boolean drawFrame() {
        initGraphics();

        Date now = new Date();
        String clock = mClockFmt.format(now);
        String label = coverLabel();
        boolean hasLabel = !label.isEmpty();

        // A caption steals vertical space, so the clock drops to the smaller size when one is set.
        Paint clockPaint = hasLabel ? mClockSmallPaint : mClockPaint;

        // Space left for the right-hand block, measured from the clock actually rendered.
        float clockW = clockPaint.measureText(clock);
        int blockLeft = (int) Math.ceil(MARGIN_START + clockW) + 6;
        int avail = WIDTH - blockLeft - MARGIN_END;

        // Longest date that fits the space the clock leaves. Stock shows it uppercase.
        String date = mDateFmts[mDateFmts.length - 1].format(now).toUpperCase(Locale.US);
        for (SimpleDateFormat f : mDateFmts) {
            String candidate = f.format(now).toUpperCase(Locale.US);
            if (mDatePaint.measureText(candidate) <= avail) {
                date = candidate;
                break;
            }
        }

        int battery = readInt(BATTERY_CAPACITY, -1);
        boolean charging = "Charging".equalsIgnoreCase(readString(BATTERY_STATUS, ""));
        boolean noSim = isSimAbsent();
        String status = (battery >= 0 ? battery + "%" : "--") + (charging ? "+" : "");
        int notifs = activeNotifications();

        String signature = clock + "|" + date + "|" + status + "|" + noSim + "|" + notifs
                + "|" + label;
        if (signature.equals(mLastRendered)) {
            return false;
        }
        mPendingSignature = signature;

        mCanvas.drawColor(BLACK);

        // Clock: vertically centred on the real font metrics, not on the glyph box.
        // Paint.FontMetrics cannot be named here (enjarify drops nested classes); ascent() and
        // descent() give the same values.
        //
        // With a caption set, the caption and clock are centred as a pair, caption on top.
        float clockH = -clockPaint.ascent() + clockPaint.descent();
        if (hasLabel) {
            float labelH = -mLabelPaint.ascent() + mLabelPaint.descent();
            float pairTop = (HEIGHT - (labelH + clockH)) / 2f;
            mCanvas.drawText(label, MARGIN_START, pairTop - mLabelPaint.ascent(), mLabelPaint);
            mCanvas.drawText(clock, MARGIN_START,
                    pairTop + labelH - clockPaint.ascent(), clockPaint);
        } else {
            float clockBaseline = (HEIGHT - (clockPaint.ascent() + clockPaint.descent())) / 2f;
            mCanvas.drawText(clock, MARGIN_START, clockBaseline, clockPaint);
        }

        // Right-hand block: date above, status row below, both right-aligned like Stock.
        final int rightEdge = WIDTH - MARGIN_END;
        float dateAsc = mDatePaint.ascent(), dateDesc = mDatePaint.descent();
        float battAsc = mBattPaint.ascent(), battDesc = mBattPaint.descent();
        float dateH = -dateAsc + dateDesc;
        float rowH = Math.max(-battAsc + battDesc, BATT_ICON_H);
        float blockH = dateH + DATE_BOTTOM_GAP + rowH;
        float blockTop = (HEIGHT - blockH) / 2f;

        float dateBaseline = blockTop - dateAsc;
        float dateW = mDatePaint.measureText(date);
        mCanvas.drawText(date, rightEdge - dateW, dateBaseline, mDatePaint);

        // Status row, right-aligned: [notif] [no-SIM] NN% [battery]
        float rowTop = blockTop + dateH + DATE_BOTTOM_GAP;
        float rowMid = rowTop + rowH / 2f;
        float x = rightEdge;

        x -= BATT_ICON_W;
        drawBatteryIcon(x, rowMid - BATT_ICON_H / 2f, BATT_ICON_W, BATT_ICON_H, battery);

        float statusW = mBattPaint.measureText(status);
        x -= 4 + statusW;
        float statusBaseline = rowMid - (battAsc + battDesc) / 2f;
        mCanvas.drawText(status, x, statusBaseline, mBattPaint);

        if (noSim) {
            float d = rowH * 0.8f;
            x -= 5 + d;
            drawNoSimIcon(x, rowMid - d / 2f, d, d);
        }

        if (notifs > 0) {
            String n = (notifs > 9) ? "9+" : String.valueOf(notifs);
            float nW = mBattPaint.measureText(n);
            float bellH = rowH * 0.85f;
            float bellW = bellH * 0.8f;
            float need = bellW + 2 + nW + 5;
            if (x - need >= blockLeft - 40) {   // the block may spill left of the date if needed
                x -= 5 + nW;
                mCanvas.drawText(n, x, statusBaseline, mBattPaint);
                x -= 2 + bellW;
                drawBell(x, rowMid - bellH / 2f, bellW, bellH);
            }
        }

        mBitmap.getPixels(mPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        return true;
    }

    private String mPendingSignature;

    /** Exposed for the offline preview harness. */
    Bitmap frameBitmap() {
        return mBitmap;
    }

    private void pushFrame() {
        String signature = mPendingSignature;
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


    private String mLabel = "";
    private long mLabelCheckedAt = 0L;
    private static final long LABEL_TTL_MS = 10_000L;

    /** Caption text, or "" when unset. Re-read periodically so edits show up without a restart. */
    private String coverLabel() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLabelCheckedAt < LABEL_TTL_MS) {
            return mLabel;
        }
        mLabelCheckedAt = now;
        String v = readString(LABEL_FILE, "").trim();
        // One line only, and short enough not to crowd the clock.
        int nl = v.indexOf('\n');
        if (nl >= 0) {
            v = v.substring(0, nl).trim();
        }
        if (v.length() > 16) {
            v = v.substring(0, 16);
        }
        mLabel = v;
        return mLabel;
    }

    /** Logged once: the SystemProperties fallback is noisy if it warns every render. */
    private static boolean sSimPropWarned = false;

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
    /**
     * Battery outline with a proportional fill, Stock's 18x18.
     */
    private void drawBatteryIcon(float x, float y, float w, float h, int pct) {
        float bodyW = w * 0.82f;
        float capW = w - bodyW;
        float bodyH = h * 0.55f;
        float top = y + (h - bodyH) / 2f;

        setStyle(mIconPaint, sStyleStroke);
        mIconPaint.setStrokeWidth(1.4f);
        RectF body = new RectF(x, top, x + bodyW, top + bodyH);
        mCanvas.drawRoundRect(body, 2f, 2f, mIconPaint);

        setStyle(mIconPaint, sStyleFill);
        // terminal nub
        float nubH = bodyH * 0.45f;
        mCanvas.drawRect(x + bodyW, top + (bodyH - nubH) / 2f,
                x + bodyW + capW * 0.7f, top + (bodyH + nubH) / 2f, mIconPaint);

        if (pct > 0) {
            float inset = 2.2f;
            float maxW = bodyW - inset * 2;
            float fill = Math.max(1f, maxW * Math.min(100, pct) / 100f);
            mCanvas.drawRect(x + inset, top + inset,
                    x + inset + fill, top + bodyH - inset, mIconPaint);
        }
    }

    /** A SIM outline with a slash through it: no SIM present. */
    private void drawNoSimIcon(float x, float y, float w, float h) {
        setStyle(mIconPaint, sStyleStroke);
        mIconPaint.setStrokeWidth(1.3f);

        // SIM body with a clipped top-right corner
        float cut = w * 0.34f;
        Path sim = new Path();
        sim.moveTo(x, y);
        sim.lineTo(x + w - cut, y);
        sim.lineTo(x + w, y + cut);
        sim.lineTo(x + w, y + h);
        sim.lineTo(x, y + h);
        sim.close();
        mCanvas.drawPath(sim, mIconPaint);

        // the slash
        mCanvas.drawLine(x - 1f, y + h + 1f, x + w + 1f, y - 1f, mIconPaint);
    }

    /**
     * Notification bell.
     *
     * Drawn with curves rather than as a small bitmap. Three earlier bitmap attempts read as an
     * umbrella, a mushroom, and Stock's own calendar-ish glyph; at this size the recognisable
     * cues are the shoulders curving out from a narrow crown, a flat rim wider than the body,
     * and a separate clapper below it.
     */
    private void drawBell(float x, float y, float w, float h) {
        setStyle(mIconPaint, sStyleFill);

        float cx = x + w / 2f;
        float rimY = y + h * 0.74f;
        float bodyTop = y + h * 0.12f;

        // Crown
        float crownR = w * 0.09f;
        mCanvas.drawCircle(cx, y + crownR, crownR, mIconPaint);

        // Body: narrow at the top, flaring to the rim.
        Path body = new Path();
        body.moveTo(cx - w * 0.16f, bodyTop);
        body.cubicTo(cx - w * 0.50f, y + h * 0.30f,
                     cx - w * 0.46f, rimY - h * 0.06f,
                     cx - w * 0.50f, rimY);
        body.lineTo(cx + w * 0.50f, rimY);
        body.cubicTo(cx + w * 0.46f, rimY - h * 0.06f,
                     cx + w * 0.50f, y + h * 0.30f,
                     cx + w * 0.16f, bodyTop);
        body.close();
        mCanvas.drawPath(body, mIconPaint);

        // Flat rim, slightly wider than the body.
        mCanvas.drawRect(x - w * 0.04f, rimY, x + w * 1.04f, rimY + Math.max(1f, h * 0.07f),
                mIconPaint);

        // Clapper
        mCanvas.drawCircle(cx, rimY + h * 0.16f, Math.max(1f, w * 0.13f), mIconPaint);
    }



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
