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

        // Read-only diagnostic: does the panel think its own digitizer is functional?
        try {
            final int[] st = { -1 };
            final String[] res = { null };
            hal.getSelfTest((s, r) -> { st[0] = s; res[0] = r; });
            System.out.println("getSelfTest -> status=" + st[0] + " result="
                    + (res[0] == null ? "<null>" : "\"" + res[0] + "\""));
        } catch (Throwable t) {
            System.out.println("getSelfTest THREW: " + t);
        }

        try {
            final int[] gp = { -1 };
            final String[] pin = { null };
            hal.getGpiopin((s, r) -> { gp[0] = s; pin[0] = r; });
            System.out.println("getGpiopin -> status=" + gp[0] + " pins="
                    + (pin[0] == null ? "<null>" : "\"" + pin[0].trim() + "\""));
        } catch (Throwable t) {
            System.out.println("getGpiopin THREW: " + t);
        }

        if (!doReset) {
            System.out.println("(pass --reset to also issue DoTouchReset + set_touch_perf(true))");
            return;
        }

        try {
            System.out.println("DoTouchReset -> " + hal.DoTouchReset());
        } catch (Throwable t) {
            System.out.println("DoTouchReset THREW: " + t);
        }

        // LG touch controllers sit in U0 (sleep/LPWG, gesture-only) or U3 (normal reporting).
        // A controller parked in U0 matches every symptom here: firmware answers, self-test
        // passes, INT idle, and no ordinary touch reports. setStatus is how the screen state is
        // handed to it -- screenStatus=ON with LPWG disabled should mean "wake up and report
        // normally".
        try {
            vendor.lge.hardware.dualscreen.V1_0.LpwgStatus st =
                    new vendor.lge.hardware.dualscreen.V1_0.LpwgStatus();
            st.lpwgMode = vendor.lge.hardware.dualscreen.V1_0.LpwgMode.DISABLE;
            st.screenStatus = vendor.lge.hardware.dualscreen.V1_0.ScreenStatus.ON;
            System.out.println("setStatus(lpwg=DISABLE, screen=ON) -> " + hal.setStatus(st));
        } catch (Throwable t) {
            System.out.println("setStatus THREW: " + t);
        }

        // set_touch_perf lives on @1.1 and maps to the HAL's AT%TCPERF= command. It takes an
        // explicit boolean, which makes it the most enable-shaped method on the interface.
        try {
            vendor.lge.hardware.dualscreen.V1_1.IDualScreen hal11 =
                    vendor.lge.hardware.dualscreen.V1_1.IDualScreen.getService(true);
            if (hal11 == null) {
                System.out.println("IDualScreen@1.1 not available");
            } else {
                System.out.println("set_touch_perf(true) -> " + hal11.set_touch_perf(true));
                System.out.println("ds_update_state()    -> " + hal11.ds_update_state());
            }
        } catch (Throwable t) {
            System.out.println("@1.1 calls THREW: " + t);
        }
    }
}
