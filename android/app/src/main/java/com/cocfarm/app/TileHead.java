package com.cocfarm.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.widget.TextView;

/**
 * The top of a settings tile: what the group is set to ("On", "4 sides") always in the top right
 * corner, and the group's name to its left. A name too long for that first line goes on under
 * it, across the tile's whole width, while the state stays in the corner. Nothing is cut off
 * ("…"): the name gets as many lines as it needs. A changed group has a small dot before its name.
 *
 * (Two views side by side can't do this: the name would keep to the left column on every line.
 * Here the name is one StaticLayout, its first line indented by the state's width.)
 */
final class TileHead extends View {

    private final String title, state;
    private final TextPaint titlePaint, statePaint;
    private final int titleColor, stateColor;
    private final Paint dotPaint;
    private final float dp;
    private final boolean dot;
    private StaticLayout layout;
    private int laidFor = -1;
    private float stateWidth;
    private boolean belowState;  // the name's first word didn't fit beside the state: it starts under it

    TileHead(Context context, TextView titleStyle, TextView stateStyle, String title, String state, boolean dot,
             int dotColor) {
        super(context);
        this.title = title;
        this.state = state == null ? "" : state;
        this.dot = dot;
        dp = context.getResources().getDisplayMetrics().density;
        titlePaint = new TextPaint(titleStyle.getPaint());
        statePaint = new TextPaint(stateStyle.getPaint());
        titleColor = titleStyle.getCurrentTextColor();
        stateColor = stateStyle.getCurrentTextColor();
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(dotColor);
        StringBuilder said = new StringBuilder(title);
        if (!this.state.isEmpty()) said.append(", ").append(this.state);
        if (dot) said.append(", changed from the defaults");
        setContentDescription(said);
    }

    private int dotRoom() {
        return dot ? Math.round(14 * dp) : 0;  // a 7 dp dot and a 7 dp gap
    }

    private int stateRoom() {
        return state.isEmpty() ? 0 : (int) Math.ceil(stateWidth + 8 * dp);
    }

    private void lay(int width) {
        if (layout != null && laidFor == width) return;
        laidFor = width;
        stateWidth = state.isEmpty() ? 0 : statePaint.measureText(state);
        int first = Math.max(1, width - dotRoom() - stateRoom());
        // A name whose first word doesn't fit beside the state would be broken inside the word:
        // then it starts on the line under the state instead.
        int space = title.indexOf(' ');
        String firstWord = space < 0 ? title : title.substring(0, space);
        belowState = !state.isEmpty() && titlePaint.measureText(firstWord) > first;
        int[] left = {dotRoom(), 0};
        int[] right = {belowState ? 0 : stateRoom(), 0};
        layout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setIndents(left, right)
                .build();
    }

    private float stateLine() {
        Paint.FontMetrics m = statePaint.getFontMetrics();
        return m.descent - m.ascent;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        lay(width);
        float height = layout.getHeight() + (belowState ? stateLine() + 2 * dp : 0);
        height = Math.max(height, stateLine());
        setMeasuredDimension(width, resolveSize((int) Math.ceil(height), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        lay(getWidth());
        float top = belowState ? stateLine() + 2 * dp : 0;
        float firstBaseline = top + layout.getLineBaseline(0);
        if (!state.isEmpty()) {
            statePaint.setColor(stateColor);
            // On the name's first line (its baseline), or alone on the first line.
            float baseline = belowState ? -statePaint.getFontMetrics().ascent : firstBaseline;
            canvas.drawText(state, getWidth() - stateWidth, baseline, statePaint);
        }
        if (dot) {
            float middle = top + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f;
            canvas.drawCircle(3.5f * dp, middle, 3.5f * dp, dotPaint);
        }
        titlePaint.setColor(titleColor);
        canvas.save();
        canvas.translate(0, top);
        layout.draw(canvas);
        canvas.restore();
    }
}
