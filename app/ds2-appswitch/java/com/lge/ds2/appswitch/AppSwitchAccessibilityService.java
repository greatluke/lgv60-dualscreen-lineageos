package com.lge.ds2.appswitch;

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

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;

/**
 * A floating button on each screen that sends the current app to the other one.
 *
 * Structured exactly like ds2navbar's NavBarAccessibilityService, for the same reason: a bare
 * root app_process cannot add a window at all -- WindowManagerService rejects it with "Unknown
 * pid=... uid=0" regardless of uid, because Session's constructor looks the caller up in
 * ActivityManager's process map, which a hand-started app_process was never registered into.
 * An AccessibilityService is a real, Zygote-forked app process, so it passes that check, and its
 * TYPE_ACCESSIBILITY_OVERLAY window type needs no SYSTEM_ALERT_WINDOW grant on top of that.
 *
 * The other half of the wall ds2navbar hit -- getting a button press back to root -- has a
 * different answer here than performGlobalAction() (there is no GLOBAL_ACTION for "move this
 * task to another display"; the actual operation, IActivityTaskManager.moveRootTaskToDisplay(),
 * is privileged and lives only in the root daemon, in AppSwitchServer). Reaching it needs real
 * IPC, and per ds2navbar's javadoc a file handoff and a raw Binder call both die to SELinux or a
 * missing policy rule -- the one thing that does work is a loopback TCP socket, which needs
 * nothing more than the ordinary INTERNET permission.
 */
public class AppSwitchAccessibilityService extends AccessibilityService {

    private static final String TAG = "DS2AppSwitch";

    private static final int BUTTON_DP = 48;
    private static final int SOCKET_PORT = 41889;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DisplayManager mDisplayManager;

    // The built-in screen's button: added once and left up for the service's lifetime.
    private SwitchButtonView mMainView;
    private WindowManager mMainWm;

    // The DS2's button: comes and goes with the accessory, mirroring ds2navbar's sync() pattern.
    private SwitchButtonView mDs2View;
    private WindowManager mDs2Wm;
    private int mAttachedDs2DisplayId = Display.INVALID_DISPLAY;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id)   { mHandler.post(AppSwitchAccessibilityService.this::syncDs2); }
        @Override public void onDisplayRemoved(int id) { mHandler.post(AppSwitchAccessibilityService.this::syncDs2); }
        @Override public void onDisplayChanged(int id) { mHandler.post(AppSwitchAccessibilityService.this::syncDs2); }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        attachMain();
        syncDs2();
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        detachMain();
        detachDs2();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override
    public void onInterrupt() { }

    // ---- built-in screen ----

    private void attachMain() {
        Display main = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (main == null) {
            Log.w(TAG, "no default display?!");
            return;
        }
        try {
            mMainWm = getSystemService(WindowManager.class);
            mMainView = newButton(this, Display.DEFAULT_DISPLAY);
            mMainWm.addView(mMainView, layoutParams(this));
            Log.i(TAG, "button attached to built-in screen");
        } catch (Throwable t) {
            Log.e(TAG, "failed to attach button to built-in screen", t);
        }
    }

    private void detachMain() {
        if (mMainView != null && mMainWm != null) {
            try {
                mMainWm.removeViewImmediate(mMainView);
            } catch (Throwable t) {
                Log.w(TAG, "removeView (main) failed", t);
            }
        }
        mMainView = null;
        mMainWm = null;
    }

    // ---- DS2: comes and goes with the accessory ----

    /** Attach the button to the DS2 if present, detach it if gone. Safe to call repeatedly. */
    private void syncDs2() {
        Display target = findDualScreen();
        if (target == null) {
            if (mAttachedDs2DisplayId != Display.INVALID_DISPLAY) {
                Log.i(TAG, "DS2 gone; removing button");
                detachDs2();
            }
            return;
        }
        // The DS2's display id is not stable across attaches (see ds2navbar's javadoc for the
        // same observation), so re-attach on change instead of caching it.
        if (target.getDisplayId() == mAttachedDs2DisplayId) {
            return;
        }
        detachDs2();
        attachDs2(target);
    }

    private Display findDualScreen() {
        for (Display d : mDisplayManager.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                continue;
            }
            if (d.getState() != Display.STATE_ON) {
                continue;
            }
            return d;
        }
        return null;
    }

    private void attachDs2(Display display) {
        try {
            Context dc = createDisplayContext(display);
            WindowManager wm = dc.getSystemService(WindowManager.class);
            SwitchButtonView view = newButton(dc, display.getDisplayId());
            wm.addView(view, layoutParams(dc));

            mDs2View = view;
            mDs2Wm = wm;
            mAttachedDs2DisplayId = display.getDisplayId();
            Log.i(TAG, "button attached to DS2 (displayId=" + mAttachedDs2DisplayId + ")");
        } catch (Throwable t) {
            Log.e(TAG, "failed to attach button to DS2 (displayId=" + display.getDisplayId() + ")", t);
        }
    }

    private void detachDs2() {
        if (mDs2View != null && mDs2Wm != null) {
            try {
                mDs2Wm.removeViewImmediate(mDs2View);
            } catch (Throwable t) {
                Log.w(TAG, "removeView (DS2) failed", t);
            }
        }
        mDs2View = null;
        mDs2Wm = null;
        mAttachedDs2DisplayId = Display.INVALID_DISPLAY;
    }

    // ---- the button and the tap it sends ----

    private SwitchButtonView newButton(Context displayContext, int hostDisplayId) {
        return new SwitchButtonView(displayContext, () -> sendSwitchRequest(hostDisplayId));
    }

    private WindowManager.LayoutParams layoutParams(Context displayContext) {
        float density = displayContext.getResources().getDisplayMetrics().density;
        int px = Math.round(BUTTON_DP * density);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                px, px,
                // Permitted to accessibility services without SYSTEM_ALERT_WINDOW.
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Bottom-end corner: clear of the taskbar/hotseat on both screens.
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = Math.round(16 * density);
        lp.y = Math.round(96 * density);
        lp.setTitle("DS2AppSwitch");
        return lp;
    }

    /**
     * Fire-and-forget: connects to AppSwitchServer in the root daemon, sends the displayId this
     * button lives on, disconnects. Must not run on the main thread (NetworkOnMainThreadException
     * even for loopback), so this always dispatches to a fresh background thread.
     */
    private void sendSwitchRequest(int fromDisplayId) {
        new Thread(() -> {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), SOCKET_PORT)) {
                s.setTcpNoDelay(true);
                new DataOutputStream(s.getOutputStream()).writeInt(fromDisplayId);
            } catch (Throwable t) {
                Log.e(TAG, "switch request failed (is the module's daemon running?)", t);
            }
        }, "ds2-appswitch-client").start();
    }
}
