package com.spazpeek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * 圆圈读秒倒计时：外环进度弧 + 中心秒数。
 * 用于采集向导每一步的倒计时展示。
 */
public class CountdownRingView extends View {

    public interface Callback {
        void onFinished();
    }

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTime = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pUnit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final Handler h = new Handler(Looper.getMainLooper());

    private float totalSec = 3f;
    private long endAt = 0;
    private boolean running = false;
    private float density = 1f;
    private Callback cb;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float left = (endAt - now) / 1000f;
            if (left <= 0f) {
                running = false;
                invalidate();
                if (cb != null) {
                    Callback c = cb;
                    cb = null;
                    c.onFinished();
                }
                return;
            }
            invalidate();
            h.postDelayed(this, 80);
        }
    };

    public CountdownRingView(Context c) {
        super(c);
        init();
    }

    public CountdownRingView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        int tc = 0xFF212121;
        try {
            TypedValue v = new TypedValue();
            if (getContext().getTheme().resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurface, v, true)) {
                tc = v.data;
            }
        } catch (Throwable ignored) {}

        pTrack.setStyle(Paint.Style.STROKE);
        pTrack.setStrokeWidth(dp(9));
        pTrack.setColor((tc & 0x00FFFFFF) | 0x22000000);

        pArc.setStyle(Paint.Style.STROKE);
        pArc.setStrokeWidth(dp(9));
        pArc.setStrokeCap(Paint.Cap.ROUND);
        pArc.setColor(0xFF2196F3);

        pTime.setColor(tc);
        pTime.setTextAlign(Paint.Align.CENTER);
        pTime.setFakeBoldText(true);

        pUnit.setColor((tc & 0x00FFFFFF) | 0x99000000);
        pUnit.setTextAlign(Paint.Align.CENTER);
    }

    /** 开始倒计时（秒）。每秒刷新 UI，结束时回调 onFinished（主线程）。 */
    public void start(float seconds, Callback callback) {
        stop();
        totalSec = Math.max(0.1f, seconds);
        cb = callback;
        endAt = SystemClock.uptimeMillis() + (long) (totalSec * 1000);
        running = true;
        invalidate();
        h.post(tick);
    }

    public void stop() {
        running = false;
        cb = null;
        h.removeCallbacks(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float hgt = getHeight();
        float cx = w / 2f;
        float cy = hgt / 2f;
        float R = Math.min(w, hgt) / 2f - dp(10);

        // 底环
        cv.drawCircle(cx, cy, R, pTrack);

        // 剩余时间
        float left = 0f;
        if (running) {
            left = Math.max(0f, (endAt - SystemClock.uptimeMillis()) / 1000f);
        }
        float frac = totalSec > 0 ? Math.min(1f, left / totalSec) : 0f;

        oval.set(cx - R, cy - R, cx + R, cy + R);
        cv.drawArc(oval, -90f, 360f * frac, false, pArc);

        // 中心数字
        int secShow = (int) Math.ceil(left - 0.001f);
        if (secShow < 0) secShow = 0;
        pTime.setTextSize(R * 0.85f);
        pUnit.setTextSize(R * 0.24f);
        cv.drawText(String.valueOf(secShow), cx, cy + R * 0.28f, pTime);
        cv.drawText("秒", cx, cy + R * 0.55f, pUnit);
    }

    private float dp(float v) {
        return v * density;
    }
}