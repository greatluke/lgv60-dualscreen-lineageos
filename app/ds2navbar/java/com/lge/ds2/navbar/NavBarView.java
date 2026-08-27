package com.lge.ds2.navbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Three-button navigation bar drawn for the Dual Screen.
 *
 * Deliberately self-drawn rather than inflated from resources: the module ships a single
 * unbundled APK with no theme to inherit, and the shapes are simple enough that drawing them
 * avoids carrying drawables and a resource table around.
 *
 * Geometry follows AOSP's proportions -- three equal touch columns, icons sized to a fraction
 * of the bar height so the bar scales if the DS2's density is ever overridden (see README TO-DO
 * ~4: `wm density -d <id>` changes everything on that panel).
 */
public class NavBarView extends View {

    public interface OnNavAction {
        int BACK = 0, HOME = 1, RECENTS = 2;
        void onNav(int action);
    }

    private static final float ICON_FRACTION = 0.34f;   // icon size relative to bar height
    private static final int   PRESS_ALPHA   = 0x33;

    private final Paint mIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mTriangle = new Path();
    private final OnNavAction mListener;

    private int mPressedIndex = -1;

    public NavBarView(Context context, OnNavAction listener) {
        super(context);
        mListener = listener;
        setBackgroundColor(0xE6101314);          // near-opaque, matches the system bar tone
        mIcon.setColor(Color.WHITE);
        mIcon.setStyle(Paint.Style.FILL);
        mPress.setColor(Color.WHITE);
        mPress.setAlpha(PRESS_ALPHA);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        final float w = getWidth(), h = getHeight();
        final float col = w / 3f;
        final float cy = h / 2f;
        final float s = h * ICON_FRACTION;

        for (int i = 0; i < 3; i++) {
            float cx = col * i + col / 2f;
            if (i == mPressedIndex) {
                c.drawCircle(cx, cy, h * 0.36f, mPress);
            }
            switch (i) {
                case 0: drawBack(c, cx, cy, s); break;
                case 1: c.drawCircle(cx, cy, s * 0.46f, mIcon); break;
                case 2: drawRecents(c, cx, cy, s); break;
            }
        }
    }

    /** Left-pointing triangle, same silhouette as the AOSP back chevron at small sizes. */
    private void drawBack(Canvas c, float cx, float cy, float s) {
        mTriangle.reset();
        mTriangle.moveTo(cx - s * 0.42f, cy);
        mTriangle.lineTo(cx + s * 0.30f, cy - s * 0.46f);
        mTriangle.lineTo(cx + s * 0.30f, cy + s * 0.46f);
        mTriangle.close();
        c.drawPath(mTriangle, mIcon);
    }

    private void drawRecents(Canvas c, float cx, float cy, float s) {
        float half = s * 0.40f;
        RectF r = new RectF(cx - half, cy - half, cx + half, cy + half);
        c.drawRoundRect(r, s * 0.10f, s * 0.10f, mIcon);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int idx = (int) (e.getX() / (getWidth() / 3f));
        if (idx < 0) idx = 0;
        if (idx > 2) idx = 2;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressedIndex = idx;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (idx != mPressedIndex) { mPressedIndex = idx; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (mPressedIndex == idx && mListener != null) mListener.onNav(idx);
                mPressedIndex = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mPressedIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
