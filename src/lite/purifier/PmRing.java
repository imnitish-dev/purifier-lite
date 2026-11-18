package lite.purifier;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.Random;

/**
 * PM2.5 ring: a full ring in the air-quality colour (draws itself in on first show). While the purifier runs, dust particles
 * drift in from the edges and spiral into the ring, as if being pulled in. Dirtier air = more particles.
 */
class PmRing extends View {
    static final int MAX_PARTICLES = 40;

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF box = new RectF();
    final Random rnd = new Random();
    // per particle: angle (rad), radius (px), speed (px/s), size (px)
    final float[] pa = new float[MAX_PARTICLES], pr = new float[MAX_PARTICLES], ps = new float[MAX_PARTICLES], pz = new float[MAX_PARTICLES];
    final float ringRadius;
    float sweep;
    int color, track, count;
    boolean running;
    long lastFrame;
    // One pending frame at a time: other redraws (e.g. the ring drawing in) must not start extra frame loops.
    boolean frameQueued;
    final Runnable nextFrame = () -> { frameQueued = false; invalidate(); };

    PmRing(Context c, float ringRadiusPx) {
        super(c);
        ringRadius = ringRadiusPx;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int pm, int color, int track, boolean running, float fromPm) {
        this.color = color; this.track = track; this.running = running;
        count = Math.min(MAX_PARTICLES, 12 + pm / 3);
        if (sweep >= 360f) { invalidate(); return; }
        ValueAnimator fill = ValueAnimator.ofFloat(0f, 360f).setDuration(900);
        fill.setInterpolator(new DecelerateInterpolator());
        fill.addUpdateListener(a -> { sweep = (float) a.getAnimatedValue(); invalidate(); });
        fill.start();
        lastFrame = 0;
    }

    float outerRadius() { return (float) Math.hypot(getWidth(), getHeight()) / 2f; }

    void spawn(int i, boolean anywhere) {
        float outer = outerRadius();
        pa[i] = rnd.nextFloat() * 6.2832f;
        pr[i] = anywhere ? ringRadius + rnd.nextFloat() * (outer - ringRadius) : outer * (0.85f + rnd.nextFloat() * 0.15f);
        ps[i] = ringRadius * (0.35f + rnd.nextFloat() * 0.45f);
        pz[i] = ringRadius * (0.012f + rnd.nextFloat() * 0.022f);
    }

    @Override protected void onDetachedFromWindow() { removeCallbacks(nextFrame); frameQueued = false; super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, w = ringRadius * 0.085f;
        float outer = outerRadius();

        if (running && count > 0) {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1e9f);
            if (lastFrame == 0) for (int i = 0; i < count; i++) spawn(i, true);
            lastFrame = now;
            dot.setColor(color);
            for (int i = 0; i < count; i++) {
                // closer to the ring = faster and more swirl, like air being pulled in
                float closeness = 1f - (pr[i] - ringRadius) / (outer - ringRadius);
                pr[i] -= ps[i] * dt * (0.6f + closeness * 1.6f);
                pa[i] += dt * (0.25f + closeness * 1.2f);
                if (pr[i] <= ringRadius + w) { spawn(i, false); continue; }
                float fadeIn = Math.min(1f, (outer - pr[i]) / (outer * 0.25f));
                float fadeOut = Math.min(1f, (pr[i] - ringRadius - w) / (ringRadius * 0.25f));
                dot.setAlpha((int) (200 * Math.max(0f, Math.min(fadeIn, fadeOut))));
                c.drawCircle(cx + (float) Math.cos(pa[i]) * pr[i], cy + (float) Math.sin(pa[i]) * pr[i], pz[i] * (0.6f + fadeOut * 0.4f), dot);
            }
            if (!frameQueued) { frameQueued = true; postDelayed(nextFrame, 42); } // ~24 fps, exactly one pending frame
        }

        box.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        paint.setStrokeWidth(w);
        paint.setColor(track);
        c.drawArc(box, 0, 360, false, paint);
        paint.setColor(color);
        c.drawArc(box, -90, sweep, false, paint);
    }
}
