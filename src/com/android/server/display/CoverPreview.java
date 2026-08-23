package com.android.server.display;

import android.graphics.Bitmap;
import android.os.Looper;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Renders one cover-window frame to a PNG so it can be eyeballed without a reboot. */
public class CoverPreview {
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        CoverWindowRenderer r = new CoverWindowRenderer(null, null);
        r.drawFrame();
        Bitmap b = r.frameBitmap();
        Class fmt = Class.forName("android.graphics.Bitmap$CompressFormat");
        Object png = Enum.valueOf(fmt, "PNG");
        Method comp = Bitmap.class.getMethod("compress", fmt, int.class, java.io.OutputStream.class);
        try (FileOutputStream f = new FileOutputStream("/data/local/tmp/cover.png")) {
            System.out.println("ok=" + comp.invoke(b, png, 100, f));
        }
    }
}
