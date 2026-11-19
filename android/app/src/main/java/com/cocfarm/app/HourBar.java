package com.cocfarm.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;

/**
 * The day as 24 cells, the hours it may play lit: tap an hour to turn it on or off, or drag
 * across several (they all take the first one's new state). The hour now is marked above.
 */
final class HourBar extends View {
    interface Listener {
        void onChanged(boolean[] on);
    }

    private final boolean[] on = new boolean[24];
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG), dark = new Paint(Paint.ANTI_ALIAS_FLAG),
            words = new Paint(Paint.ANTI_ALIAS_FLAG), now = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final float density;
    private Listener listener;
    private Boolean painting;  // while dragging: the state every hour touched takes

    HourBar(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        lit.setColor(context.getColor(R.color.cf_accent));
        dark.setColor(context.getColor(R.color.cf_surface_highest));
        words.setColor(context.getColor(R.color.cf_muted));
        words.setTextSize(11 * context.getResources().getDisplayMetrics().scaledDensity);
        words.setTextAlign(Paint.Align.CENTER);
        now.setColor(context.getColor(R.color.cf_text));
        setFocusable(true);
    }

    void set(java.util.List<String> hours) {
        java.util.Arrays.fill(on, false);
        for (String h : hours) {
            try {
                int hour = Integer.parseInt(h.trim());
                if (hour >= 0 && hour < 24) on[hour] = true;
            } catch (NumberFormatException ignored) {
                // not an hour
            }
        }
        invalidate();
    }

    void listen(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(54 * density));
    }

    private float top() {
        return 8 * density;
    }

    private float cellWidth() {
        return (getWidth() - getPaddingLeft() - getPaddingRight()) / 24f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft(), w = cellWidth(), top = top(), high = 26 * density, gap = Math.max(1, density);
        for (int h = 0; h < 24; h++) {
            cell.set(left + h * w + gap / 2, top, left + (h + 1) * w - gap / 2, top + high);
            canvas.drawRoundRect(cell, 3 * density, 3 * density, on[h] ? lit : dark);
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        float x = left + (hour + 0.5f) * w;
        canvas.drawCircle(x, top - 4 * density, 2.5f * density, now);
        float base = top + high + 16 * density;
        for (int h = 0; h <= 24; h += 6) {
            String label = String.format("%02d", h % 24 == 0 && h > 0 ? 24 : h);
            canvas.drawText(label, Math.min(Math.max(left + h * w, 8 * density), getWidth() - 8 * density), base, words);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int hour = (int) Math.floor((event.getX() - getPaddingLeft()) / cellWidth());
        hour = Math.max(0, Math.min(23, hour));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);  // a drag paints hours, not scrolls
                painting = !on[hour];
                on[hour] = painting;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (painting != null && on[hour] != painting) {
                    on[hour] = painting;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                painting = null;
                performClick();
                if (listener != null) listener.onChanged(on.clone());
                return true;
            case MotionEvent.ACTION_CANCEL:
                painting = null;
                if (listener != null) listener.onChanged(on.clone());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
