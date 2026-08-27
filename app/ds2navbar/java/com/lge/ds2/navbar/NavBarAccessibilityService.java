package com.lge.ds2.navbar;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Navigation bar for the LG Dual Screen.
 *
 * The DS2 has no navigation of its own once desktop-experience features are off: its buttons are
 * drawn by Launcher3's Taskbar, which only exists when desktop mode is on -- and desktop mode
 * either forces apps into freeform windows or breaks launching outright. With it off, apps launch
 * fullscreen (what we want) and the panel loses back/home/recents. In 3-button mode the system
 * does create a NavigationBar window on the DS2, but it reserves ~166px and draws nothing.
 *
 * Two constraints shaped this design, both found the hard way on hardware:
 *
 *  - The bar cannot live in DualScreenBridgeDaemon. WindowManagerService looks the caller up in
 *    ActivityManager's process map and rejects anything it doesn't know -- "Unknown pid=... uid=0"
 *    for every bare app_process, root or not. Adding a window needs a real app process.
 *
 *  - The button presses cannot be handed to that root daemon either. Magisk's root runs as
 *    u:r:magisk:s0, which SELinux denies access to app_data_file and media_rw_data_file, so
 *    "app writes a command file, root reads it" fails in every location; a loopback socket needs
 *    INTERNET; app->root binder needs a policy rule.
 *
 * An AccessibilityService sidesteps both: it is an app process (so it may add windows), and
 * performGlobalAction() delivers back/home/recents with no root, no signature permission and no
 * SELinux exception. It is also how third-party navigation-bar apps have always done this.
 */
public class NavBarAccessibilityService extends AccessibilityService
        implements NavBarView.OnNavAction {

    private static final String TAG = "DS2NavBar";

    /** AOSP's navigation bar is 48dp; a little more is easier to hit on the DS2. */
    private static final int BAR_HEIGHT_DP = 52;

    private DisplayManager mDisplayManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private NavBarView mView;
    private WindowManager mAttachedWm;
    private int mAttachedDisplayId = Display.INVALID_DISPLAY;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id)   { mHandler.post(NavBarAccessibilityService.this::sync); }
        @Override public void onDisplayRemoved(int id) { mHandler.post(NavBarAccessibilityService.this::sync); }
        @Override public void onDisplayChanged(int id) { mHandler.post(NavBarAccessibilityService.this::sync); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        sync();
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (mDisplayManager != null) mDisplayManager.unregisterDisplayListener(mDisplayListener);
        detach();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Diagnostic: View.performClick() emits TYPE_VIEW_CLICKED. Logging it answers, without
        // patching Launcher3, whether a tap on a Taskbar All Apps icon is converted into a click
        // at all -- separating a touch->click failure from a click->ItemClickHandler failure.
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_VIEW_CLICKED
                || t == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            Log.i(TAG, "A11Y " + AccessibilityEvent.eventTypeToString(t)
                    + " pkg=" + event.getPackageName()
                    + " cls=" + event.getClassName()
                    + " text=" + event.getText()
                    + " displayId=" + event.getDisplayId());
        }
    }
    @Override public void onInterrupt() { }

    /** Attach the bar to the DS2 if present, detach it if gone. Safe to call repeatedly. */
    private void sync() {
        Display target = findDualScreen();
        if (target == null) {
            if (mAttachedDisplayId != Display.INVALID_DISPLAY) {
                Log.i(TAG, "DS2 gone; removing bar");
                detach();
            }
            return;
        }
        // The DS2's display id is not stable -- observed as 2, 3, 4, 5 and 6 across attaches,
        // sometimes changing twice in a minute -- so re-attach on change instead of caching it.
        if (target.getDisplayId() == mAttachedDisplayId) return;
        detach();
        attach(target);
    }

    /**
     * The bar is opt-in. It is an overlay pinned to the bottom of the DS2, which covers the
     * bottom row of the launcher's app drawer -- the same overlap that made the stock Taskbar's
     * bottom row untappable. Until the drawer geometry is handled, default to not drawing it, so
     * the service can still be used purely to observe accessibility events.
     *
     * Enable with:  setprop persist.ds2navbar.enabled 1   (then toggle the service)
     */
    private static boolean barEnabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(
                    sp.getMethod("getBoolean", String.class, boolean.class)
                      .invoke(null, "persist.ds2navbar.enabled", false));
        } catch (Throwable t) {
            return false;
        }
    }

    private Display findDualScreen() {
        if (!barEnabled()) return null;
        for (Display d : mDisplayManager.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if (d.getState() != Display.STATE_ON) continue;
            return d;
        }
        return null;
    }

    private void attach(Display display) {
        try {
            Context dc = createDisplayContext(display);
            WindowManager wm = dc.getSystemService(WindowManager.class);

            int h = Math.round(BAR_HEIGHT_DP * dc.getResources().getDisplayMetrics().density);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, h,
                    // Permitted to accessibility services without SYSTEM_ALERT_WINDOW.
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // No FLAG_LAYOUT_NO_LIMITS: with it the window may sit outside the display
                    // bounds and floats clear of the bottom edge.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM;
            lp.setTitle("DS2NavBar");
            // Sit exactly on the bottom edge; otherwise the window absorbs the (empty) system
            // navigation inset the DS2 reserves and lays out taller than requested.
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

            NavBarView view = new NavBarView(dc, this);
            wm.addView(view, lp);

            mView = view;
            mAttachedWm = wm;
            mAttachedDisplayId = display.getDisplayId();
            Log.i(TAG, "bar attached to display " + mAttachedDisplayId + " height=" + h);
        } catch (Throwable t) {
            Log.e(TAG, "failed to attach bar to display " + display.getDisplayId(), t);
        }
    }

    private void detach() {
        if (mView != null && mAttachedWm != null) {
            try { mAttachedWm.removeViewImmediate(mView); }
            catch (Throwable t) { Log.w(TAG, "removeView failed", t); }
        }
        mView = null;
        mAttachedWm = null;
        mAttachedDisplayId = Display.INVALID_DISPLAY;
    }

    @Override
    public void onNav(int action) {
        int global;
        switch (action) {
            case NavBarView.OnNavAction.BACK:    global = GLOBAL_ACTION_BACK;    break;
            case NavBarView.OnNavAction.HOME:    global = GLOBAL_ACTION_HOME;    break;
            case NavBarView.OnNavAction.RECENTS: global = GLOBAL_ACTION_RECENTS; break;
            default: return;
        }
        boolean ok = performGlobalAction(global);
        Log.i(TAG, "nav action " + action + " -> globalAction " + global + " accepted=" + ok);
    }
}
