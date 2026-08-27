package com.lge.ds2.appswitch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * A plain circle with two chevrons pointing away from each other ("send this / bring that"),
 * entirely canvas-drawn. Deliberately self-drawn rather than inflated from resources, matching
 * ds2navbar's NavBarView -- this module ships a single unbundled APK with no theme to inherit,
 * and the shape is simple enough that drawing it avoids carrying drawables around.
 */
public class SwitchButtonView extends View {

    public interface OnSwitchTap {
        void onSwitchTap();
    }

    private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBgPressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OnSwitchTap mListener;

    private boolean mPressed;

    public SwitchButtonView(Context context, OnSwitchTap listener) {
        super(context);
        mListener = listener;
        mBg.setColor(Color.argb(160, 32, 32, 32));
        mBgPressed.setColor(Color.argb(220, 32, 32, 32));
        mArrow.setColor(Color.WHITE);
        mArrow.setStrokeWidth(getResources().getDisplayMetrics().density * 2.5f);
        mArrow.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f;
        float cx = w / 2f, cy = h / 2f;
        c.drawCircle(cx, cy, r, mPressed ? mBgPressed : mBg);

        float inset = r * 0.55f;
        float armLen = r * 0.45f;
        // Left chevron, pointing left; right chevron, pointing right.
        c.drawLine(cx - inset, cy - armLen * 0.7f, cx - inset + armLen, cy, mArrow);
        c.drawLine(cx - inset, cy + armLen * 0.7f, cx - inset + armLen, cy, mArrow);
        c.drawLine(cx + inset, cy - armLen * 0.7f, cx + inset - armLen, cy, mArrow);
        c.drawLine(cx + inset, cy + armLen * 0.7f, cx + inset - armLen, cy, mArrow);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                mPressed = false;
                invalidate();
                if (mListener != null) {
                    mListener.onSwitchTap();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                mPressed = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
