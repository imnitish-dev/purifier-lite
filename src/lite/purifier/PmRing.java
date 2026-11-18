package lite.purifier;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** PM2.5 ring: an arc that fills with the reading, plus a soft glow circling while the purifier runs. */
class PmRing extends View {
    static final float FULL_SCALE = 150f; // µg/m³ at which the arc is full

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF box = new RectF();
    final ValueAnimator spin = ValueAnimator.ofFloat(0f, 360f);
    float sweep, angle;
    int color, track;
    boolean running;

    PmRing(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        spin.setDuration(3500);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidate(); });
    }

    void set(int pm, int color, int track, boolean running, float fromPm) {
        this.color = color; this.track = track; this.running = running;
        ValueAnimator fill = ValueAnimator.ofFloat(sweepFor(fromPm), sweepFor(pm)).setDuration(900);
        fill.setInterpolator(new DecelerateInterpolator());
        fill.addUpdateListener(a -> { sweep = (float) a.getAnimatedValue(); invalidate(); });
        fill.start();
        if (running && isAttachedToWindow() && !spin.isStarted()) spin.start();
    }

    static float sweepFor(float pm) { return Math.max(4f, Math.min(pm, FULL_SCALE) / FULL_SCALE * 360f); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (running) spin.start(); }
    @Override protected void onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth() * 0.045f;
        box.set(w * 1.5f, w * 1.5f, getWidth() - w * 1.5f, getHeight() - w * 1.5f);
        paint.setStrokeWidth(w);
        paint.setColor(track);
        c.drawArc(box, 0, 360, false, paint);
        paint.setColor(color);
        c.drawArc(box, -90, sweep, false, paint);
        if (running) {
            // airflow glow: a few fading segments chasing each other around the ring
            paint.setStrokeCap(Paint.Cap.BUTT);
            for (int i = 0; i < 16; i++) {
                paint.setColor(color);
                paint.setAlpha(120 - i * 7);
                c.drawArc(box, angle - 90 - i * 3, 3.5f, false, paint);
            }
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setAlpha(255);
        }
    }
}
