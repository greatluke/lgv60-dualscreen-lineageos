package com.android.server.display;

import android.os.Looper;

import vendor.lge.hardware.dualscreen.V1_0.IDualScreen;

/**
 * Probes the DS2 touch controller through IDualScreen.
 *
 * Why this exists: with the Dual Screen attached and its panel lit, the digitizer emits nothing.
 * getevent records zero events on the DS2 input nodes while the built-in screen produces
 * thousands in the same capture, and reading /dev/hidraw0 blocks indefinitely rather than
 * returning data. hid-multitouch has bound the device and created the input nodes with correct
 * axis ranges, and the IDC binds them to the external display -- so everything above the panel is
 * in place and the controller itself is simply never switched on.
 *
 * Stock presumably issues something on attach that we do not. IDualScreen@1.0 exposes touch
 * methods the bridge has never called; this walks the cheap ones in increasing order of
 * intrusiveness so we learn where the controller stands before poking it harder:
 *
 *   1. getTouchFirmwareVersion()  read-only. A real version means the controller is powered and
 *                                 talking, and is merely idle -- which makes a reset the likely
 *                                 switch. An error means it is not powered, a different hunt.
 *   2. DoTouchReset()             the most plausible candidate for what stock does on attach.
 *
 * Usage: CLASSPATH=... app_process /system/bin com.android.server.display.TouchProbe [--reset]
 */
public class TouchProbe {

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();

        boolean doReset = false;
        for (String a : args) {
            if ("--reset".equals(a)) doReset = true;
        }

        IDualScreen hal;
        try {
            hal = IDualScreen.getService(true);
        } catch (Throwable t) {
            System.out.println("FAIL: could not get IDualScreen@1.0: " + t);
            return;
        }
        if (hal == null) {
            System.out.println("FAIL: IDualScreen.getService() returned null");
            return;
        }
        System.out.println("IDualScreen@1.0 bound: " + hal);

        // Read-only first.
        try {
            final int[] status = { -1 };
            final String[] version = { null };
            hal.getTouchFirmwareVersion((s, v) -> { status[0] = s; version[0] = v; });
            System.out.println("getTouchFirmwareVersion -> status=" + status[0]
                    + " version=" + (version[0] == null ? "<null>" : "\"" + version[0] + "\""));
        } catch (Throwable t) {
            System.out.println("getTouchFirmwareVersion THREW: " + t);
        }

        try {
            final int[] ps = { -1, -1 };
            hal.getSubDisplayPowerState((s, v) -> { ps[0] = s; ps[1] = v; });
            System.out.println("getSubDisplayPowerState -> status=" + ps[0] + " state=" + ps[1]);
        } catch (Throwable t) {
            System.out.println("getSubDisplayPowerState THREW: " + t);
        }

        if (!doReset) {
            System.out.println("(pass --reset to also issue DoTouchReset)");
            return;
        }

        try {
            System.out.println("DoTouchReset -> " + hal.DoTouchReset());
        } catch (Throwable t) {
            System.out.println("DoTouchReset THREW: " + t);
        }
    }
}
