package com.android.server.display;

import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.ICoverDisplayEnabledCallback;
import android.hardware.display.IDisplayManagerEx;
import android.hardware.display.IDsAirDisplayStateCallback;
import android.hardware.display.IHBMCallback;
import android.hardware.display.ISubDisplayCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import com.lge.display.SubDisplayInfo;

/**
 * Standalone daemon entry point wiring the ported {@link SubLcdController} into boot, without
 * touching system_server / SystemServer.class at all. LOS's DisplayManagerService is final (see
 * dualscreen-attach-flow.md), so the stock in-process subclass pattern isn't reachable; the
 * follow-up DualScreenBridgeService (SystemService-based, in-process composition) is *also* not
 * used here, because injecting into SystemServer.java's boot sequence to start it would require
 * recompiling and dex-patching that 2750-line, boot-critical class — real bootloop risk for
 * negligible benefit over just running standalone.
 *
 * Instead this runs as its own always-on process, started by init exactly like the native HAL
 * itself: `app_process64 ... com.android.server.display.DualScreenBridgeDaemon`. Registers its
 * binder directly via ServiceManager.addService() (a general IPC primitive available to any
 * privileged process, not exclusive to system_server) under "dualscreen_ex", then parks the
 * calling thread in Looper.loop() to keep servicing binder calls indefinitely.
 */
public class DualScreenBridgeDaemon {
    private static final String TAG = "DualScreenBridgeDaemon";
    private static final String SERVICE_NAME = "dualscreen_ex";

    public static void main(String[] args) {
        Slog.i(TAG, "starting");
        Looper.prepareMainLooper();

        // SubLcdController only uses the Context for Slog/HandlerThread bookkeeping in the paths
        // this daemon exercises (attach/detach notification, power, brightness) — a null Context
        // is sufficient here; revisit if a later method needs real Context services.
        SubLcdController controller = new SubLcdController((Context) null);

        ServiceManager.addService(SERVICE_NAME, new BinderServiceEx(controller));
        Slog.i(TAG, "registered '" + SERVICE_NAME + "'");

        // Obtained early (rather than down by ScreenWakeWatcher's original call site) because
        // the attach listener below also needs it, and registration order matters here: this
        // requires Looper.prepareMainLooper() to have already run on this exact thread, which is
        // true from this point on but was not true before it.
        Context systemContext = ActivityThread.systemMain().getSystemContext();

        // Stand in for Stock's CoverDisplayPowerManagerService, which LOS does not ship.
        // Without this nothing ever drives IAccessory.setCoverDisplayButtonStatus(), so the
        // hinge fires in the kernel but the DS2 is never powered. See CoverDisplayPowerBridge.
        CoverDisplayPowerBridge coverPower = new CoverDisplayPowerBridge();
        if (coverPower.start()) {
            Slog.i(TAG, "cover display power bridge started, cable status="
                    + coverPower.getCoverDisplayCableStatus());
        } else {
            Slog.w(TAG, "cover display power bridge unavailable; DS2 will not auto-power "
                    + "(cover window still works)");
        }

        // Keep the cover window populated. Stock's LGSubDisplay stack redraws continuously;
        // without any content the panel illuminates a blank frame, which is indistinguishable
        // from being off.
        final CoverWindowRenderer renderer = new CoverWindowRenderer(
                controller, new android.os.Handler(Looper.getMainLooper()));
        renderer.start();

        // The strip is only visible while the case is shut, so let the hinge gate it.
        coverPower.setCoverStateListener(new CoverDisplayPowerBridge.CoverStateListener() {
            @Override
            public void onCoverClosedChanged(boolean closed) {
                renderer.setCoverClosed(closed);
            }
        });

        // The DS2 digitizer comes up in LPWG (gesture-only) mode on every attach and stays
        // silent until told otherwise, so re-arm it each time the accessory appears.
        coverPower.setCoverDisplayAttachListener(
                new CoverDisplayPowerBridge.CoverDisplayAttachListener() {
            @Override
            public void onCoverDisplayAttached() {
                boolean ok = TouchEnabler.enableTouchReporting();
                Slog.i(TAG, "DS2 attached: enableTouchReporting -> " + ok);
                // The panel comes back at its own default brightness, so re-push ours.
                BrightnessSync.resync();
            }
        });

        // Cover the case where the DS2 was already attached before this daemon started, which
        // is the normal situation at boot -- no attach event will arrive for it.
        TouchEnabler.enableTouchReporting();

        // DisplayRotationFix.watch() covers re-applying itself on every future attach (the
        // DS2's displayId is not stable across attaches, observed as 2, 3, 4) via its own
        // DisplayManager.DisplayListener -- no need to hook it into the accessory attach
        // listener above as well.
        DisplayRotationFix.watch(systemContext);

        // The DS2 lives in its own display group, separate from the built-in panel's, and each
        // group tracks wakefulness independently. Waking the device from sleep only wakes the
        // default group, so the DS2's Display can be left stuck OFF after any lock/unlock cycle
        // even though the accessory never lost power. See ScreenWakeWatcher for how this was
        // diagnosed and why it calls PowerManager.wakeUp() directly rather than touching the HAL.
        ScreenWakeWatcher.start(systemContext);

        // The "switch app to the other screen" feature (AppSwitchServer + app/ds2-appswitch) is
        // shelved for now to prioritize the DS2 rotation fix -- it worked (confirmed on
        // hardware), but is parked rather than started here. See AppSwitchServer's javadoc.

        // Android's brightness slider only drives the built-in panel, so mirror it onto the
        // DS2 to keep the two matched from the normal UI.
        BrightnessSync.start();

        // Power+VolDown only ever captures the built-in panel, so mirror each screenshot onto
        // the DS2 by watching the screenshots folder.
        ScreenshotMirror.start();

        Slog.i(TAG, "entering loop");
        Looper.loop();
    }

    private static final class BinderServiceEx extends IDisplayManagerEx.Stub {
        private final SubLcdController mSubLcdController;

        BinderServiceEx(SubLcdController controller) {
            mSubLcdController = controller;
        }

        // ---- DS2-relevant methods: delegate to SubLcdController ----

        @Override
        public boolean getSubDisplayInfo(SubDisplayInfo outInfo) throws RemoteException {
            return mSubLcdController.getSubDisplayInfo(outInfo);
        }

        @Override
        public boolean drawSubDisplay(int[] pixels) throws RemoteException {
            return mSubLcdController.drawSubDisplay(pixels);
        }

        @Override
        public boolean setSubDisplayPowerState(boolean enable) throws RemoteException {
            return mSubLcdController.setSubDisplayPowerState(enable);
        }

        @Override
        public void setSubDisplayBrightness(int brightness) throws RemoteException {
            mSubLcdController.setSubDisplayBrightness(brightness);
        }

        @Override
        public int getSubDisplayPowerState() throws RemoteException {
            return mSubLcdController.getSubDisplayPowerState();
        }

        @Override
        public void registerSubDisplayCallback(String key, ISubDisplayCallback callback) throws RemoteException {
            mSubLcdController.registerSubDisplayCallback(key, callback);
        }

        @Override
        public void unregisterSubDisplayCallback(String key) throws RemoteException {
            mSubLcdController.unregisterSubDisplayCallback(key);
        }

        // ---- Everything else: same benign defaults as IDisplayManagerEx.Default ----

        @Override
        public void setDisplayProjection(int displayId, int orientation, Rect viewport, Rect frame) throws RemoteException {
        }

        @Override
        public void setColorFadeLevelWithNoPrepare(float level) throws RemoteException {
        }

        @Override
        public void setBlackMode(boolean enable) throws RemoteException {
        }

        @Override
        public int getALCRate() throws RemoteException {
            return 0;
        }

        @Override
        public int getCurrentBrightnessLevel() throws RemoteException {
            return 0;
        }

        @Override
        public void registerHBMCallback(IHBMCallback callback) throws RemoteException {
        }

        @Override
        public void unregisterHBMCallback(IHBMCallback callback) throws RemoteException {
        }

        @Override
        public boolean IsHBMState() throws RemoteException {
            return false;
        }

        @Override
        public void setLayerForced(int zorder) throws RemoteException {
        }

        @Override
        public void setTemporaryBrightness(float brightness, int barState) throws RemoteException {
        }

        @Override
        public boolean isBacklightDimmingMode() throws RemoteException {
            return false;
        }

        @Override
        public void setBacklightDimmingWhenLowBattery(int target) throws RemoteException {
        }

        @Override
        public void setTemporaryBrightnessForCoverDisplay(float brightness) throws RemoteException {
        }

        @Override
        public void setScreenBrightnessForCoverDisplayOverrideFromWindowManager(int screenBrightness) throws RemoteException {
        }

        @Override
        public void coverPowerOffWithWipingDisplayDevice() throws RemoteException {
        }

        @Override
        public int getCoverDisplayState() throws RemoteException {
            return 0;
        }

        @Override
        public void registerCoverDisplayEnabledCallback(ICoverDisplayEnabledCallback callback) throws RemoteException {
        }

        @Override
        public void unregisterCoverDisplayEnabledCallback(ICoverDisplayEnabledCallback callback) throws RemoteException {
        }

        @Override
        public boolean isCoverDisplayConnectedWithNoError() throws RemoteException {
            return false;
        }

        @Override
        public int getDisplayCableStatus(boolean connect) throws RemoteException {
            return 0;
        }

        @Override
        public void setMainMaxBrightness(int id, int max_brightness) throws RemoteException {
        }

        @Override
        public void setCoverMaxBrightness(int id, int max_brightness) throws RemoteException {
        }

        @Override
        public int getMainMaxBrightness() throws RemoteException {
            return 0;
        }

        @Override
        public int getCoverMaxBrightness() throws RemoteException {
            return 0;
        }

        @Override
        public boolean getWideScreenMode() throws RemoteException {
            return false;
        }

        @Override
        public void setWideScreenMode(boolean isWideMode) throws RemoteException {
        }

        @Override
        public float getUnderDisplayFingerprintCueAlpha() throws RemoteException {
            return 0.0f;
        }

        @Override
        public void registerDsAirDisplayStateCallback(IDsAirDisplayStateCallback callback) throws RemoteException {
        }

        @Override
        public void unregisterDsAirDisplayStateCallback(IDsAirDisplayStateCallback callback) throws RemoteException {
        }

        @Override
        public void requestForceMirrorMode(int displayId) throws RemoteException {
        }
    }
}
