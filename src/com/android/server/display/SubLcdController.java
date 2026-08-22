package com.android.server.display;

import android.content.Context;
import android.hardware.display.ISubDisplayCallback;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.MutableBoolean;
import android.util.MutableInt;
import android.util.Slog;
import com.lge.display.SubDisplayInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import vendor.lge.hardware.dualscreen.V1_0.IDualScreen;
import vendor.lge.hardware.dualscreen.V1_0.IDualScreenSubDisplayCallback;
import vendor.lge.hardware.dualscreen.V1_0.SubScreenInfo;

/* JADX INFO: loaded from: classes.dex */
class SubLcdController {
    private static final int DEFAULT_SUB_LCD_BRIGHTNESS = 90;
    private static final int IDualScreen_HAL_DEATH_COOKIE = -559058941;
    private static final int MSG_SET_BRIGHTNESS = 502;
    private static final int MSG_STATE_TO_OFF = 501;
    private static final int MSG_STATE_TO_ON = 500;
    private static final int MSG_SUB_LCD_STATE = 503;
    private static final boolean NOTUSER_DEBUG = !Build.TYPE.equals("user");
    private static final String TAG = SubLcdController.class.getSimpleName();
    private final Context mContext;
    private int mCurrentSubLcdBrightness;
    private SubLcdHandler mSubLcdHandler;
    private final SubLcdHandlerThread mSubLcdHandlerThread;
    protected final Object mLock = new Object();
    private IServiceManager mServiceManager = null;
    private final ServiceNotification mServiceNotification = new ServiceNotification();
    private IDualScreen mDualScreenHal = null;
    private HashMap<String, ISubDisplayCallback> mSubDisplayCallbackList = new HashMap<>();
    private int mReceivedSubDisplayState = -1;
    private final RemoteCallbackList<ISubDisplayCallback> mSubDisplayCallback = new RemoteCallbackList<ISubDisplayCallback>() { // from class: com.android.server.display.SubLcdController.1
        @Override // android.os.RemoteCallbackList
        public void onCallbackDied(ISubDisplayCallback l, Object cookie) {
            synchronized (SubLcdController.this.mSubDisplayCallbackList) {
                String pkg = (String) cookie;
                Slog.w(SubLcdController.TAG, "The remote callback died?? " + l + " / package = " + pkg);
                SubLcdController.this.mSubDisplayCallbackList.remove(pkg);
            }
        }
    };
    private IDualScreenSubDisplayCallback mDualScreenSubDisplayCallback = new IDualScreenSubDisplayCallback.Stub() { // from class: com.android.server.display.SubLcdController.3
        @Override // vendor.lge.hardware.dualscreen.V1_0.IDualScreenSubDisplayCallback
        public void notifyStateChanged(int state) {
            SubLcdController.this.mReceivedSubDisplayState = state;
            Slog.i(SubLcdController.TAG, "mReceivedSubDisplayState state : " + SubLcdController.this.mReceivedSubDisplayState);
            SubLcdController.this.mSubLcdHandler.removeMessages(SubLcdController.MSG_SUB_LCD_STATE);
            Message msg = SubLcdController.this.mSubLcdHandler.obtainMessage(SubLcdController.MSG_SUB_LCD_STATE);
            msg.arg1 = SubLcdController.this.mReceivedSubDisplayState;
            SubLcdController.this.mSubLcdHandler.sendMessage(msg);
        }
    };

    final class ServiceNotification extends IServiceNotification.Stub {
        ServiceNotification() {
        }

        @Override // android.hidl.manager.V1_0.IServiceNotification
        public void onRegistration(String fqName, String name, boolean preexisting) throws RemoteException {
            if (IDualScreen.kInterfaceName.equals(fqName)) {
                SubLcdController.this.connectToIDualScreenProxy();
            }
        }
    }

    public SubLcdController(Context context) {
        this.mSubLcdHandler = null;
        this.mContext = context;
        SubLcdHandlerThread subLcdHandlerThread = new SubLcdHandlerThread();
        this.mSubLcdHandlerThread = subLcdHandlerThread;
        subLcdHandlerThread.start();
        this.mSubLcdHandler = new SubLcdHandler(subLcdHandlerThread.getLooper());
        this.mCurrentSubLcdBrightness = 90;
        initIServiceManager();
    }

    private void initIServiceManager() {
        try {
            IServiceManager service = IServiceManager.getService();
            this.mServiceManager = service;
            if (!service.registerForNotifications(IDualScreen.kInterfaceName, "", this.mServiceNotification)) {
                Slog.e(TAG, "Failed to register a listener for IDualScreen service");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception getting IServiceManager: " + e);
        }
        connectToIDualScreenProxy();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void connectToIDualScreenProxy() {
        Slog.d(TAG, "connectToIDualScreenProxy");
        synchronized (this.mLock) {
            if (this.mDualScreenHal != null) {
                return;
            }
            try {
                IDualScreen service = IDualScreen.getService();
                this.mDualScreenHal = service;
                service.linkToDeath(new IHwBinder.DeathRecipient() {
                    public final void serviceDied(long j) {
                        SubLcdController.this.onDualScreenHalDied(j);
                    }
                }, -559058941L);
                this.mDualScreenHal.setSubDisplayCallback(this.mDualScreenSubDisplayCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "connectToDSLpwgProxy: lpwg hal service not responding", e);
            } catch (NoSuchElementException e2) {
                Slog.e(TAG, "connectToDSLpwgProxy: lpwg hal is not implemented yet", e2);
            }
        }
    }

    /* was: synthetic lambda$connectToIDualScreenProxy$0 (jadx-mangled name), renamed for readability */
    void onDualScreenHalDied(long cookie) {
        Slog.e(TAG, "LPWG hal service died cookie: " + cookie);
        synchronized (this.mLock) {
            this.mDualScreenHal = null;
        }
    }

    private final class SubLcdHandlerThread extends HandlerThread {
        public SubLcdHandlerThread() {
            super(SubLcdController.TAG + ":SubLcdHandlerThread", -4);
        }
    }

    private final class SubLcdHandler extends Handler {
        public SubLcdHandler(Looper looper) {
            super(looper);   // stock used the hidden 3-arg (looper, callback, async) ctor; async
                              // status is not required for this low-frequency state-change handler
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 500:
                    Slog.d(SubLcdController.TAG, "Sub LCD State ON");
                    SubLcdController.this.setSubDisplayPowerStateImpl(true);
                    return;
                case SubLcdController.MSG_STATE_TO_OFF /* 501 */:
                    Slog.d(SubLcdController.TAG, "Sub LCD State OFF");
                    SubLcdController.this.setSubDisplayPowerStateImpl(false);
                    return;
                case SubLcdController.MSG_SET_BRIGHTNESS /* 502 */:
                    Slog.d(SubLcdController.TAG, "Sub LCD Set Brightness");
                    SubLcdController.this.setSubDisplayBrightnessImpl(msg.arg1);
                    return;
                case SubLcdController.MSG_SUB_LCD_STATE /* 503 */:
                    Slog.d(SubLcdController.TAG, "CallBack Sub LCD State");
                    synchronized (SubLcdController.this.mSubDisplayCallbackList) {
                        int state = msg.arg1;
                        String[] keys = new String[SubLcdController.this.mSubDisplayCallbackList.size()];
                        Set<String> keySet = SubLcdController.this.mSubDisplayCallbackList.keySet();
                        keySet.toArray(keys);
                        for (String key : keys) {
                            try {
                                Slog.d(SubLcdController.TAG, "Send onDualScreenSubDisplayCallback callback to " + key + ", state : " + state);
                                ((ISubDisplayCallback) SubLcdController.this.mSubDisplayCallbackList.get(key)).onSubDisplayCallback(state);
                            } catch (Exception e) {
                                Slog.e(SubLcdController.TAG, "Fail to send onDualScreenSubDisplayCallback callback to " + key + ", state : " + state, e);
                            }
                        }
                        break;
                    }
                default:
                    return;
            }
        }
    }

    public void setSubDisplayBrightness(int brightness) {
        Slog.d(TAG, "send MSG_SET_BRIGHTNESS message");
        this.mSubLcdHandler.removeMessages(MSG_SET_BRIGHTNESS);
        Message msg = this.mSubLcdHandler.obtainMessage(MSG_SET_BRIGHTNESS);
        msg.arg1 = brightness;
        this.mSubLcdHandler.sendMessage(msg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setSubDisplayBrightnessImpl(int brightness) {
        synchronized (this.mLock) {
            if (this.mDualScreenHal == null) {
                return;
            }
            this.mCurrentSubLcdBrightness = brightness;
            Slog.d(TAG, "Set SubLcd brightness = " + brightness);
            try {
                this.mDualScreenHal.setSubDisplayBrightness(this.mCurrentSubLcdBrightness);
            } catch (RemoteException e) {
                Slog.e(TAG, "fail to set status");
            }
        }
    }

    public boolean setSubDisplayPowerState(boolean enable) {
        if (enable) {
            Slog.d(TAG, "sent MSG_STATE_TO_ON message");
            this.mSubLcdHandler.removeMessages(500);
            Message msg = this.mSubLcdHandler.obtainMessage(500);
            this.mSubLcdHandler.sendMessage(msg);
            return true;
        }
        Slog.d(TAG, "sent MSG_STATE_TO_OFF message");
        this.mSubLcdHandler.removeMessages(MSG_STATE_TO_OFF);
        Message msg2 = this.mSubLcdHandler.obtainMessage(MSG_STATE_TO_OFF);
        this.mSubLcdHandler.sendMessage(msg2);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setSubDisplayPowerStateImpl(boolean enable) {
        synchronized (this.mLock) {
            if (this.mDualScreenHal == null) {
                return false;
            }
            Slog.d(TAG, "SET SubLcd Power state = " + enable);
            try {
                this.mDualScreenHal.setSubDisplayPowerState(enable);
                this.mDualScreenHal.setSubDisplayBrightness(this.mCurrentSubLcdBrightness);
                return true;
            } catch (RemoteException e) {
                Slog.e(TAG, "fail to set status");
                return false;
            }
        }
    }

    public int getSubDisplayPowerState() {
        final MutableInt subDisplayStatus = new MutableInt(-1);
        IDualScreen iDualScreen = this.mDualScreenHal;
        if (iDualScreen != null) {
            try {
                iDualScreen.getSubDisplayPowerState(new IDualScreen.getSubDisplayPowerStateCallback() { // from class: com.android.server.display.SubLcdController.2
                    @Override // vendor.lge.hardware.dualscreen.V1_0.IDualScreen.getSubDisplayPowerStateCallback
                    public void onValues(int res, int state) {
                        if (res == 0) {
                            subDisplayStatus.value = state;
                        } else {
                            Slog.e(SubLcdController.TAG, "Error occured in getSubDisplayStateImpl : " + res);
                        }
                    }
                });
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to read getSubDisplayStateImpl from hal", e);
            }
        }
        Slog.i(TAG, "received state of cover display during boot sequence is " + subDisplayStatus.value);
        return subDisplayStatus.value;
    }

    public void registerSubDisplayCallback(String key, ISubDisplayCallback callback) {
        synchronized (this.mSubDisplayCallbackList) {
            String newKey = key + "@" + Binder.getCallingPid();
            Slog.d(TAG, "Register Sub Display State Callback " + callback + " / package = " + key + " / new = " + newKey);
            ISubDisplayCallback cb = this.mSubDisplayCallbackList.remove(newKey);
            if (cb != null) {
                this.mSubDisplayCallback.unregister(cb);
            }
            this.mSubDisplayCallback.register(callback, newKey);
            this.mSubDisplayCallbackList.put(newKey, callback);
        }
    }

    public void unregisterSubDisplayCallback(String key) {
        synchronized (this.mSubDisplayCallbackList) {
            String newKey = key + "@" + Binder.getCallingPid();
            Slog.d(TAG, "Unregister Sub Display State package = " + key + " / new = " + newKey);
            ISubDisplayCallback cb = this.mSubDisplayCallbackList.remove(newKey);
            if (cb != null) {
                this.mSubDisplayCallback.unregister(cb);
            }
        }
    }

    public boolean getSubDisplayInfo(final SubDisplayInfo outInfo) {
        final MutableBoolean bSuccess = new MutableBoolean(false);
        IDualScreen iDualScreen = this.mDualScreenHal;
        if (iDualScreen == null || outInfo == null) {
            return false;
        }
        try {
            iDualScreen.getSubDisplayInfo(new IDualScreen.getSubDisplayInfoCallback() { // from class: com.android.server.display.SubLcdController.4
                @Override // vendor.lge.hardware.dualscreen.V1_0.IDualScreen.getSubDisplayInfoCallback
                public void onValues(int err, SubScreenInfo info) {
                    if (err == 0) {
                        outInfo.mWidth = info.width;
                        outInfo.mHeight = info.height;
                        outInfo.mFormat = info.format;
                        bSuccess.value = true;
                    }
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException: fail to getSubDisplayInfo");
        }
        return bSuccess.value;
    }

    public boolean drawSubDisplay(int[] pixels) {
        if (this.mDualScreenHal == null) {
            return false;
        }
        try {
            ArrayList<Integer> arraylist = (ArrayList) IntStream.of(pixels).boxed().collect(Collectors.toCollection(new Supplier() { // from class: com.android.server.display.SubLcdController$$ExternalSyntheticLambda0
                @Override // java.util.function.Supplier
                public final Object get() {
                    return new ArrayList();
                }
            }));
            return this.mDualScreenHal.drawSubDisplay(arraylist) == 0;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException: fail to drawSubDisplay");
            return false;
        }
    }
}
