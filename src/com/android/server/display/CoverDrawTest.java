package com.android.server.display;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Renders one cover-window frame standalone, to verify android.graphics works under
 * app_process and that the pixel format the HAL expects matches what we produce, before
 * relying on the renderer inside the long-running daemon.
 *
 * Lives in com.android.server.display because SubLcdController is package-private (as in Stock).
 *
 * Usage: app_process ... com.android.server.display.CoverDrawTest
 */
public class CoverDrawTest {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();

        SubLcdController controller = new SubLcdController((Context) null);
        // The HAL connection is established asynchronously in the constructor.
        Thread.sleep(1500);

        // Power the panel first, otherwise the frame lands on a backlight-off panel and
        // looks like a failure.
        controller.setSubDisplayPowerState(true);
        Thread.sleep(1000);

        CoverWindowRenderer renderer =
                new CoverWindowRenderer(controller, new Handler(Looper.getMainLooper()));
        System.out.println("rendering one frame...");
        renderer.render();
        System.out.println("done");

        Thread.sleep(2000);
    }
}
