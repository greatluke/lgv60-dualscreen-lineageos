package com.android.server.display;

import android.os.RemoteException;
import android.util.Slog;

import vendor.lge.hardware.accessory.V1_0.IAccessory;
import vendor.lge.hardware.accessory.uevent.V1_0.IAccessoryCallback;
import vendor.lge.hardware.accessory.uevent.V1_0.IAccessoryUevent;

/**
 * Minimal LOS equivalent of Stock's {@code CoverDisplayPowerManagerService}, which LineageOS
 * does not ship at all. Without it the DS2 is detected and enumerated, but nothing ever powers
 * the accessory: the hinge fires at the kernel level and no {@code cover_button} write follows.
 *
 * Recovered Stock design (services.jar, ~1960 lines):
 *
 *   binds IAccessory + IAccessoryUevent
 *   startObserving(cb, 5, "CoverDisplayCallback")        -> hall-IC cover state changes
 *   startObserving(cb, 6, "CoverPowerOnResultCallback")  -> result of a power-on request
 *   startObserving(cb, 8, "CoverPowerRecoveryCallback")  -> HAL asks for a power restart
 *
 *   on cover state change:
 *     setCoverDisplayButtonStatusViaHIDL(enable, wantCallback)
 *       -> IAccessory.setCoverDisplayButtonStatus(enable, skip_uevent)
 *
 * That single HIDL call is what matters: the accessory HAL writes {@code cover_button "2 1"}
 * and *natively* calls {@code IDualScreen.setPowerStatus}, powering the DS2 microcontroller
 * (its {@code AT%HPD} goes Off -> On); the dualscreen HAL then writes
 * {@code ds2_pd hpd_high:1} about 235 ms later, matching Stock's ~0.23 s signature.
 *
 * Deliberately omitted from this port: Stock's policy layer (wakelocks, CountDownLatch
 * transition sync, DisplayPowerRequest plumbing, toast/UI, firmware-upgrade paths). None of it
 * is needed to drive the accessory correctly, and reproducing it would pull in a large amount
 * of LG framework surface that LOS has no equivalent for.
 *
 * NOTE: this powers the DS2 and asserts HPD. It does not make the DS2's *main panel* display
 * anything -- the DP/AUX link to that panel is a separate, unresolved problem (see the frozen
 * PROJECT STATUS block in dualscreen-attach-flow.md). The cover window works regardless.
 */
public class CoverDisplayPowerBridge {

    private static final String TAG = "CoverDisplayPowerBridge";

    /**
     * Observer type IDs, from vendor.lge.hardware.accessory.uevent.V1_0.AccessoryType.
     *
     * Stock's CoverDisplayPowerManagerService observes 5/6/8 only. We additionally observe
     * SMARTCOVER (0), the fold/unfold hinge: on Stock that arrives via a separate service
     * (SmartCoverService) feeding the display power policy, which LOS has no equivalent for.
     * Observing it here is what makes the hinge actually drive DS2 power on LOS.
     *
     * Note type 6 is DD_LT_STATUS in the enum even though Stock names its callback
     * "CoverPowerOnResultCallback".
     */
    private static final int TYPE_SMARTCOVER         = 0;  // hinge fold/unfold
    private static final int TYPE_COVER_DISPLAY      = 5;  // DS2 attach/detach
    private static final int TYPE_COVER_POWER_RESULT = 6;  // DD_LT_STATUS
    private static final int TYPE_COVER_RECOVERY     = 8;

    private final Object mLock = new Object();

    private IAccessory mAccessoryHal;
    private IAccessoryUevent mAccessoryUeventHal;

    /** Last hall-IC state delivered by the HAL; -1 until the first callback. */
    private int mReceivedCoverHallICState = -1;
    /** Last hinge (SMARTCOVER) state; -1 until the first callback. */
    private int mSmartCoverState = -1;
    /** Tracks what we last requested, so we don't re-issue identical requests. */
    private int mRequestedEnable = -1;

    /** Notified when the case folds/unfolds, so the cover window can follow. */
    public interface CoverStateListener {
        void onCoverClosedChanged(boolean closed);
    }

    private volatile CoverStateListener mCoverStateListener;

    public void setCoverStateListener(CoverStateListener l) {
        mCoverStateListener = l;
    }

    /**
     * Notified when the DS2 itself attaches, so its digitizer can be taken out of LPWG mode.
     * The controller comes up gesture-only on every attach, so this has to run each time and
     * not once at startup.
     */
    public interface CoverDisplayAttachListener {
        void onCoverDisplayAttached();
    }

    private volatile CoverDisplayAttachListener mAttachListener;

    public void setCoverDisplayAttachListener(CoverDisplayAttachListener l) {
        mAttachListener = l;
    }

    private final IAccessoryCallback mCoverDisplayCallback = new IAccessoryCallback.Stub() {
        @Override
        public void notifyAccessoryChange(int state, int type) {
            if (type != TYPE_COVER_DISPLAY) {
                return;
            }
            synchronized (mLock) {
                if (mReceivedCoverHallICState == state) {
                    return;
                }
                mReceivedCoverHallICState = state;
            }
            Slog.i(TAG, "cover state changed: " + state);
            onCoverStateChanged(state);
        }
    };

    private final IAccessoryCallback mSmartCoverCallback = new IAccessoryCallback.Stub() {
        @Override
        public void notifyAccessoryChange(int state, int type) {
            if (type != TYPE_SMARTCOVER) {
                return;
            }
            synchronized (mLock) {
                if (mSmartCoverState == state) {
                    return;
                }
                mSmartCoverState = state;
            }
            // Polarity determined empirically on-device: folding the case closed reports 1,
            // unfolding reports 0. The DS2 is usable when unfolded, so power it on for 0.
            // (This is the opposite sense to the COVERDISPLAY/attach state below.)
            boolean open = (state == 0);
            Slog.i(TAG, "smart cover (hinge) state changed: " + state
                    + " (" + (open ? "open" : "closed") + ")");

            // Deliberately NOT power-cycling the accessory here. Doing so on every fold made
            // the strip take ~5s to appear on close: setCoverDisplayButtonStatus(false) powers
            // the DS2 down, and the renderer then has to bring the sub-display back up. The
            // accessory is powered once while attached (see start()); the hinge only decides
            // what the strip shows.

            // The cover window strip sits on the outside of the DS2 half: visible only while
            // the case is shut. Keep it lit exactly then.
            CoverStateListener l = mCoverStateListener;
            if (l != null) {
                try {
                    l.onCoverClosedChanged(!open);
                } catch (Throwable t) {
                    Slog.e(TAG, "cover state listener threw", t);
                }
            }
        }
    };

    private final IAccessoryCallback mCoverPowerResultCallback = new IAccessoryCallback.Stub() {
        @Override
        public void notifyAccessoryChange(int state, int type) {
            if (type == TYPE_COVER_POWER_RESULT) {
                // Stock treats -1 as failure and -2 as "power dropped, re-request".
                Slog.i(TAG, "cover power-on result: " + state);
            }
        }
    };

    private final IAccessoryCallback mCoverRecoveryCallback = new IAccessoryCallback.Stub() {
        @Override
        public void notifyAccessoryChange(int state, int type) {
            if (type == TYPE_COVER_RECOVERY) {
                Slog.w(TAG, "HAL requested cover power recovery: " + state);
            }
        }
    };

    /** Connects to both accessory HALs and starts observing. Safe to call more than once. */
    public boolean start() {
        synchronized (mLock) {
            try {
                mAccessoryHal = IAccessory.getService(true);
            } catch (RemoteException | RuntimeException e) {
                Slog.e(TAG, "IAccessory unavailable", e);
                return false;
            }
            try {
                mAccessoryUeventHal = IAccessoryUevent.getService(true);
            } catch (RemoteException | RuntimeException e) {
                Slog.e(TAG, "IAccessoryUevent unavailable", e);
                return false;
            }
        }

        // Stock stops any previous observer under the same name before registering, so the
        // registration is idempotent across restarts of this process.
        observe(mSmartCoverCallback, TYPE_SMARTCOVER, "SmartCoverCallback");
        observe(mCoverDisplayCallback, TYPE_COVER_DISPLAY, "CoverDisplayCallback");
        observe(mCoverPowerResultCallback, TYPE_COVER_POWER_RESULT, "CoverPowerOnResultCallback");
        observe(mCoverRecoveryCallback, TYPE_COVER_RECOVERY, "CoverPowerRecoveryCallback");

        Slog.i(TAG, "observing cover display events");

        // Power the accessory up front and leave it up while attached. This is what asserts
        // the DS2's HPD; gating it on the hinge only added latency (see the note above).
        setCoverDisplayButtonStatus(true, true);
        return true;
    }

    private void observe(IAccessoryCallback cb, int type, String name) {
        try {
            mAccessoryUeventHal.stopObserving(name);
        } catch (Throwable ignored) {
            // Expected when nothing was registered under this name yet.
        }
        try {
            int rc = mAccessoryUeventHal.startObserving(cb, type, name);
            Slog.i(TAG, "startObserving(" + type + ", " + name + ") = " + rc);
        } catch (Throwable t) {
            Slog.e(TAG, "startObserving(" + type + ", " + name + ") failed", t);
        }
    }

    /**
     * Maps a hall-IC cover state to a power request. Stock gates this behind a full display
     * power policy; here any non-zero state (cover open / DS2 usable) powers the accessory,
     * and state 0 (closed) powers it down.
     */
    private void onCoverStateChanged(int state) {
        setCoverDisplayButtonStatus(state != 0, true);

        if (state != 0) {
            CoverDisplayAttachListener l = mAttachListener;
            if (l != null) {
                // The panel has to be powered before the digitizer will accept the mode change,
                // so this deliberately runs after setCoverDisplayButtonStatus above.
                l.onCoverDisplayAttached();
            }
        }
    }

    /**
     * The call that actually matters. {@code enable} maps to the kernel's {@code cover_button}
     * on/off value; {@code skipUevent} is Stock's second argument (named {@code wantCallback}
     * in the service, {@code skip_uevent} in the HIDL). Stock passes {@code (true, true)} on
     * the device-added path, which produces {@code cover_button_set : 2 1}.
     */
    public boolean setCoverDisplayButtonStatus(boolean enable, boolean skipUevent) {
        IAccessory hal;
        synchronized (mLock) {
            hal = mAccessoryHal;
            if (hal == null) {
                Slog.e(TAG, "no IAccessory; ignoring request enable=" + enable);
                return false;
            }
            int want = enable ? 1 : 0;
            if (mRequestedEnable == want) {
                Slog.d(TAG, "already requested enable=" + enable + "; skipping");
                return true;
            }
            mRequestedEnable = want;
        }
        try {
            Slog.i(TAG, "setCoverDisplayButtonStatus(" + enable + ", " + skipUevent + ")");
            int rc = hal.setCoverDisplayButtonStatus(enable, skipUevent);
            if (rc != 0) {
                Slog.e(TAG, "setCoverDisplayButtonStatus failed, rc=" + rc);
                return false;
            }
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "setCoverDisplayButtonStatus threw", t);
            return false;
        }
    }

    /** Current cover cable status as reported by the HAL, or -1 if unavailable. */
    public int getCoverDisplayCableStatus() {
        IAccessory hal;
        synchronized (mLock) {
            hal = mAccessoryHal;
        }
        if (hal == null) {
            return -1;
        }
        final int[] out = { -1 };
        try {
            hal.getCoverDisplayCableStatus((err, st) -> out[0] = st);
        } catch (Throwable t) {
            Slog.e(TAG, "getCoverDisplayCableStatus threw", t);
        }
        return out[0];
    }
}
