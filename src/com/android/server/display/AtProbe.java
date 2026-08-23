package com.android.server.display;

import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import vendor.lge.hardware.dualscreen.V1_0.IDualScreen;
import vendor.lge.hardware.dualscreen.V1_0.IDualScreenAtCallback;

/**
 * Sends raw AT commands to the DS2's microcontroller via IDualScreen.sendAtCommand().
 *
 * Every enable-shaped HIDL method (DoTouchReset, set_touch_perf, setStatus, ds_update_state)
 * returns success and changes nothing: the digitizer still emits no HID reports even though its
 * firmware answers, its self-test passes and it is out of reset. So this drops underneath the
 * HIDL wrappers and talks to the MCU directly, using the AT strings extracted from
 * vendor.lge.hardware.dualscreen@1.1-service.
 *
 * Replies arrive asynchronously on IDualScreenAtCallback.responseAtCommand(), not through the
 * synchronous return, so each command waits briefly for its response before moving on.
 *
 * Usage: CLASSPATH=... app_process /system/bin com.android.server.display.AtProbe [cmd ...]
 */
public class AtProbe {

    private static final String[] DEFAULT_CMDS = {
        // Known-good control: this is what getTouchFirmwareVersion() uses internally, so a reply
        // here proves the channel works and anything silent afterwards is the command, not us.
        "AT%TCHDEBUG=FWVER",
        "AT%TCHDEBUG=GPIO_PIN",
        "AT%TCHUPDATE=UPDATE_STATE",
        "AT%TCHNOTIFY=LPWG_NOTIFY",
        "AT%TCPERF=1",
    };

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();

        IDualScreen hal = IDualScreen.getService(true);
        if (hal == null) {
            System.out.println("FAIL: IDualScreen@1.0 unavailable");
            return;
        }

        String[] cmds = (args != null && args.length > 0) ? args : DEFAULT_CMDS;

        for (String cmd : cmds) {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] reply = { null };

            IDualScreenAtCallback cb = new IDualScreenAtCallback.Stub() {
                @Override
                public void responseAtCommand(String response) {
                    reply[0] = response;
                    latch.countDown();
                }
            };

            final int[] sync = { -1 };
            final String[] syncStr = { null };
            try {
                hal.sendAtCommand(cmd, cb, (s, v) -> { sync[0] = s; syncStr[0] = v; });
            } catch (Throwable t) {
                System.out.println(cmd + " -> THREW " + t);
                continue;
            }

            boolean got = latch.await(3, TimeUnit.SECONDS);
            System.out.println(cmd
                    + "\n    sync: status=" + sync[0]
                    + " value=" + (syncStr[0] == null ? "<null>" : "\"" + syncStr[0].trim() + "\"")
                    + "\n    async: " + (got ? "\"" + String.valueOf(reply[0]).trim() + "\""
                                             : "<no response within 3s>"));
        }
    }
}
