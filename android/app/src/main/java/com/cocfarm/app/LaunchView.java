package com.cocfarm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.core.content.ContextCompat;

/**
 * COCFARM's launch screen while Python starts (a second or two on a phone): the crest in a
 * soft gold glow, a ring turning round it, a shine passing over it, and the name settling in
 * below. It takes over from Android's own launch screen at the same place and size, so the
 * change can't be seen. Then {@link #leave} flies the crest into the top bar's logo while the
 * rest fades and the Dashboard rises under it.
 *
 * Everything is drawn here from the time since it started, so nothing keeps running once it's
 * gone: drawing stops when it's off the window.
 */
final class LaunchView extends View {

    private static final long LEAVE_MS = 460;
    private static final long SHINE_EVERY_MS = 2400, SHINE_MS = 750;
    private static final Interpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private final float dp;
    private final int background, accent, track, muted;
    private final Bitmap crest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF crestAt = new RectF();
    private final RectF drawn = new RectF();
    private final long started = SystemClock.uptimeMillis();

    // Where the crest is: the middle, and its size (Android's launch screen's, once known).
    private float middleX = -1, middleY = -1, size;
    // Android's launch screen keeps the crest small (all of it has to fit in its circle): taking
    // over at that size, it grows to fullSize over GROW_MS, so nothing jumps.
    private static final long GROW_MS = 700;
    private final float fullSize;
    private float fromSize;
    private long handedAt = -1;
    private boolean handedOver;  // Android's launch screen showed the crest: it's there from the start
    // Leaving: from when, to where (the top bar's logo), and what to do once gone.
    private long leaving = -1;
    private final RectF target = new RectF();
    private final int[] at = new int[2], mine = new int[2];
    private View logo;
    private Runnable gone;

    LaunchView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        background = ContextCompat.getColor(context, R.color.cf_background);
        accent = ContextCompat.getColor(context, R.color.cf_accent);
        track = ContextCompat.getColor(context, R.color.cf_outline);
        muted = ContextCompat.getColor(context, R.color.cf_muted);
        crest = BitmapFactory.decodeResource(getResources(), R.drawable.cocfarm_logo);
        // Big, but with room for the ring round it and the name under it on a short screen
        // (sideways) or a narrow one.
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        fullSize = Math.min(190 * dp, Math.min(screen.widthPixels * 0.5f, screen.heightPixels * 0.36f));
        size = fullSize;
        words.setTextAlign(Paint.Align.CENTER);
        setClickable(true);  // nothing under it can be pressed while it's up
        setContentDescription("COCFARM is starting");
    }

    /** Takes over from Android's launch screen: the crest at this middle and size (in this view). */
    void startAt(float x, float y, float crestSize) {
        handedOver = true;
        middleX = x;
        middleY = y;
        if (crestSize > 0) {
            size = crestSize;
            fromSize = crestSize;
            handedAt = SystemClock.uptimeMillis();
        }
        invalidate();
    }

    /**
     * Fly the crest into {@code logo} (hidden till then) while the rest fades; then take this
     * view off the window and run {@code done}. A second call does nothing.
     */
    void leave(View logo, Runnable done) {
        if (leaving >= 0) return;
        this.logo = logo;
        gone = done;
        if (!aim()) this.logo = null;  // nowhere to fly: it shrinks where it is
        if (this.logo != null) this.logo.setAlpha(0f);  // the crest lands in its place, then it's shown
        leaving = SystemClock.uptimeMillis();
        invalidate();
    }

    /**
     * Where the logo is now, in this view. Asked again every frame: the side rail's logo moves
     * down a little once the window's insets come in, after the flight has started (seen on a
     * recording: the crest landed too high and too big, then jumped).
     */
    private boolean aim() {
        if (logo == null || !logo.isLaidOut() || logo.getWidth() <= 0) {
            target.setEmpty();
            return false;
        }
        logo.getLocationInWindow(at);
        getLocationInWindow(mine);
        float left = at[0] - mine[0], top = at[1] - mine[1];
        target.set(0, 0, logo.getWidth(), logo.getHeight());
        // Where the picture itself is drawn in it (the side rail sizes its logo its own way).
        if (logo instanceof android.widget.ImageView && ((android.widget.ImageView) logo).getDrawable() != null) {
            android.widget.ImageView image = (android.widget.ImageView) logo;
            target.set(image.getDrawable().getBounds());
            image.getImageMatrix().mapRect(target);
            target.offset(image.getPaddingLeft(), image.getPaddingTop());
        }
        // (and its own scale, if any: the crest is square, so the middle and the width do)
        float w = target.width() * logo.getScaleX(), h = target.height() * logo.getScaleY();
        float cx = left + target.centerX(), cy = top + target.centerY();
        target.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        return !target.isEmpty();
    }

    /** At once, with no animation (the screen was left, say). */
    void finishNow() {
        if (logo != null) logo.setAlpha(1f);
        if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
        Runnable done = gone;
        gone = null;
        leaving = Long.MAX_VALUE;
        if (done != null) done.run();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float t = (now - started) / 1000f;
        float x = middleX >= 0 ? middleX : getWidth() / 2f;
        float y = middleY >= 0 ? middleY : getHeight() / 2f;
        if (handedAt >= 0 && size != fullSize) {
            size = lerp(fromSize, fullSize, EASE.getInterpolation(clamp((now - handedAt) / (float) GROW_MS)));
        }

        // Leaving: 0 to 1.
        float out = leaving < 0 ? 0f : Math.min(1f, (now - leaving) / (float) LEAVE_MS);
        float eased = EASE.getInterpolation(out);
        float rest = 1f - clamp(out / 0.35f);  // the ring, glow and words go first
        float backdrop = 1f - clamp((out - 0.15f) / 0.6f);

        paint.setShader(null);
        paint.setXfermode(null);
        paint.setColor(background);
        paint.setAlpha(Math.round(255 * backdrop));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        // Coming in: the extras over the first 0.6 s (the crest is already there, as Android left it).
        float come = EASE.getInterpolation(clamp(t / 0.6f));

        // The glow behind the crest, slowly breathing.
        float breathe = (float) (0.5 + 0.5 * Math.sin(t * Math.PI));  // a 2 s cycle
        float glowRadius = size * (0.95f + 0.06f * breathe);
        int glowAlpha = Math.round(255 * rest * come * (0.20f + 0.10f * breathe));
        paint.setShader(new RadialGradient(x, y, glowRadius,
                withAlpha(accent, glowAlpha), withAlpha(accent, 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, glowRadius, paint);
        paint.setShader(null);

        // The ring: a faint track, and a bright arc turning on it (once every 1.4 s).
        float ring = size * 0.66f * (0.92f + 0.08f * come);
        float stroke = 2.5f * dp;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(track);
        paint.setAlpha(Math.round(120 * rest * come));
        canvas.drawCircle(x, y, ring, paint);
        float turn = (t / 1.4f % 1f) * 360f;
        paint.setShader(new SweepGradient(x, y, new int[]{withAlpha(accent, 0), accent},
                new float[]{0f, 0.32f}));
        paint.setAlpha(Math.round(255 * rest * come));
        canvas.save();
        canvas.rotate(turn - 115f, x, y);
        drawn.set(x - ring, y - ring, x + ring, y + ring);
        canvas.drawArc(drawn, 0f, 115f, false, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);

        // The crest: breathing a little while it waits; leaving, it flies to the top bar's logo.
        float scale = 1f + 0.02f * breathe * (1f - eased);
        float half = size * scale / 2f;
        crestAt.set(x - half, y - half, x + half, y + half);
        if (leaving >= 0) {
            if (logo != null) aim();
            if (target.isEmpty()) {
                float shrink = 1f - 0.5f * eased;
                crestAt.set(x - half * shrink, y - half * shrink, x + half * shrink, y + half * shrink);
            } else {
                crestAt.set(lerp(crestAt.left, target.left, eased), lerp(crestAt.top, target.top, eased),
                        lerp(crestAt.right, target.right, eased), lerp(crestAt.bottom, target.bottom, eased));
            }
        }
        if (crest != null) {
            paint.setAlpha(handedOver ? 255 : Math.round(255 * come));
            int layer = canvas.saveLayer(crestAt, null);
            canvas.drawBitmap(crest, null, crestAt, paint);
            // A band of light passing over it (only over the crest's own pixels).
            long since = (now - started) % SHINE_EVERY_MS;
            if (leaving < 0 && now - started > 500 && since < SHINE_MS) {
                float p = EASE.getInterpolation(since / (float) SHINE_MS);
                float w = crestAt.width();
                float from = crestAt.left - w * 0.6f + p * w * 2.2f;
                shine.setShader(new LinearGradient(from, crestAt.top, from + w * 0.45f, crestAt.bottom,
                        new int[]{Color.TRANSPARENT, Color.argb(110, 255, 246, 220), Color.TRANSPARENT},
                        null, Shader.TileMode.CLAMP));
                shine.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
                canvas.drawRect(crestAt, shine);
            }
            canvas.restoreToCount(layer);
            paint.setAlpha(255);
        }

        // The name, its letters closing up as it fades in; then a line saying what's happening.
        float named = EASE.getInterpolation(clamp((t - 0.15f) / 0.7f));
        float below = y + size * 0.66f + 34 * dp;
        words.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        words.setTextSize(sp(24));
        words.setLetterSpacing(0.16f + 0.30f * (1f - named));
        words.setColor(accent);
        words.setAlpha(Math.round(255 * named * rest));
        canvas.drawText("COCFARM", x, below + 10 * dp * (1f - named), words);
        float said = clamp((t - 0.9f) / 0.4f);
        words.setTypeface(Typeface.DEFAULT);
        words.setTextSize(sp(13));
        words.setLetterSpacing(0.02f);
        words.setColor(muted);
        words.setAlpha(Math.round(255 * said * rest));
        canvas.drawText("Getting the bot ready…", x, below + 26 * dp, words);

        if (out >= 1f) {
            post(this::finishNow);
            return;
        }
        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }

    /** Text follows the phone's font size, as the rest of the app does. */
    private float sp(float size) {
        return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size,
                getResources().getDisplayMetrics());
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
