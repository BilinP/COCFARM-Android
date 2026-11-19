package com.cocfarm.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A number setting picked by scrolling, in place of typing it: its name on the left (as many
 * lines as it needs) and the value in the top right; under them − and + (hold to keep going)
 * either side of a slider. Tapping the name or the value opens a wheel with every choice.
 *
 * The choices come from Python (cocfarm_ranges): [value as the settings write it, label,
 * number], lowest first, for the user's Town Hall. A value saved between two steps stays as
 * it is (an extra choice in its place); one outside the range is shown at the nearest end.
 */
final class NumberScroller extends LinearLayout {

    interface Picked {
        void picked(Object value);
    }

    private final Context dialogs;
    private final String title, unit;
    private final TileHead head;
    private final Slider slider;
    private final MaterialButton less, more;
    private final Handler repeat = new Handler(Looper.getMainLooper());
    private final List<Object> values = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<Double> numbers = new ArrayList<>();
    private Picked onPick;
    private int index;
    private double low, high;  // the stepped range's ends (words such as "Never" may sit outside)
    private boolean quiet;  // putting a value in: not the user's pick

    /**
     * @param dialogs  a context the wheel's dialog can open on (the activity)
     * @param small    a range's "From"/"To": smaller words than a setting's name
     * @param help     words under the name; empty: none
     */
    NumberScroller(Context context, Context dialogs, String title, boolean small, String help, String unit,
                   JSONObject scroll, Object current, Picked onPick) {
        super(context);
        this.dialogs = dialogs;
        this.title = title;
        this.unit = unit == null ? "" : unit;
        setOrientation(VERTICAL);

        TextView titleStyle = new TextView(context), stateStyle = new TextView(context);
        titleStyle.setTextAppearance(small ? com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                : com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        titleStyle.setTextColor(context.getColor(small ? R.color.cf_muted : R.color.cf_text));
        stateStyle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        stateStyle.setTextColor(context.getColor(R.color.cf_accent));
        stateStyle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head = new TileHead(context, titleStyle, stateStyle, title, "", false, 0);
        head.setOnClickListener(v -> wheel());
        head.setMinimumHeight(dp(24));
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (help != null && !help.isEmpty()) {
            TextView words = new TextView(context);
            words.setText(help);
            words.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            words.setTextColor(context.getColor(R.color.cf_muted));
            words.setPadding(0, dp(2), 0, 0);
            addView(words);
        }

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        less = button(context, "−", "Less");
        more = button(context, "+", "More");
        slider = new Slider(context);
        slider.setStepSize(1);
        slider.setTickVisible(false);
        slider.setLabelFormatter(value -> label(Math.round(value)));
        slider.addOnChangeListener((s, value, user) -> {
            if (user) pick(Math.round(value));
        });
        slider.setContentDescription(title);
        row.addView(less, new LayoutParams(dp(48), dp(48)));
        row.addView(slider, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(more, new LayoutParams(dp(48), dp(48)));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        held(less, -1);
        held(more, 1);

        setChoices(scroll, current);
        this.onPick = onPick;
    }

    /**
     * New choices (the goal's resource changed), the value kept where it fits.
     * @return the value to keep instead, when ``current`` is outside them (else null)
     */
    Object setChoices(JSONObject scroll, Object current) {
        JSONArray choices = scroll == null ? null : scroll.optJSONArray("choices");
        values.clear();
        labels.clear();
        numbers.clear();
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONArray choice = choices.optJSONArray(i);
            if (choice == null) continue;
            values.add(choice.opt(0));
            labels.add(choice.optString(1));
            numbers.add(choice.optDouble(2));
        }
        if (values.size() < 2) {  // (never: Python gives two or more) a lone choice still draws
            values.add(values.isEmpty() ? "" : values.get(0));
            labels.add(labels.isEmpty() ? "" : labels.get(0));
            numbers.add(numbers.isEmpty() ? 0.0 : numbers.get(0));
        }
        low = scroll == null ? numbers.get(0) : scroll.optDouble("low", numbers.get(0));
        high = scroll == null ? numbers.get(numbers.size() - 1) : scroll.optDouble("high", numbers.get(numbers.size() - 1));
        slider.setValueFrom(0);
        slider.setValueTo(values.size() - 1);
        return show(current);
    }

    /**
     * Put a value in (loaded, filled in by a switch, reset), without counting it as a pick.
     * @return the nearest choice's value when ``value`` was outside the range, else null
     */
    Object show(Object value) {
        int at = find(value);
        Object snapped = null;
        if (at < 0) {  // in range, between two steps: kept as it is
            double n = number(value);
            int i = 0;
            while (i < numbers.size() && numbers.get(i) < n) i++;
            values.add(i, value);
            labels.add(i, shown(n));
            numbers.add(i, n);
            slider.setValueTo(values.size() - 1);
            at = i;
        } else if (at >= values.size()) {  // outside: the nearest end
            at = at - values.size();
            snapped = values.get(at);
        }
        quiet = true;
        set(at);
        quiet = false;
        return snapped;
    }

    /** The value's choice; -1: in range but between two steps; size + i: outside, nearest i. */
    private int find(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        for (int i = 0; i < values.size(); i++) {
            if (String.valueOf(values.get(i)).equals(text)) return i;
        }
        double n = number(value);
        int lowAt = -1, highAt = -1;
        for (int i = 0; i < numbers.size(); i++) {
            double at = numbers.get(i);
            if (lowAt < 0 && Math.abs(at - low) < 1e-6) lowAt = i;
            if (Math.abs(at - high) < 1e-6) highAt = i;
        }
        if (lowAt < 0) lowAt = 0;
        if (highAt < 0) highAt = numbers.size() - 1;
        if (n < low - 1e-6) return values.size() + lowAt;
        if (n > high + 1e-6) return values.size() + highAt;
        for (int i = lowAt; i <= highAt; i++) {
            if (Math.abs(numbers.get(i) - n) < 1e-6) return i;
        }
        return -1;
    }

    private void set(int at) {
        index = Math.max(0, Math.min(values.size() - 1, at));
        slider.setValue(index);
        head.setState(label(index));
        less.setEnabled(isEnabled() && index > 0);
        more.setEnabled(isEnabled() && index < values.size() - 1);
    }

    private void pick(int at) {
        int before = index;
        set(at);
        if (!quiet && index != before && onPick != null) onPick.picked(values.get(index));
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        less.setEnabled(on && index > 0);
        more.setEnabled(on && index < values.size() - 1);
    }

    private String label(int at) {
        return at >= 0 && at < labels.size() ? labels.get(at) : "";
    }

    /** The wheel: every choice, the current one in the middle. */
    private void wheel() {
        if (!isEnabled()) return;
        NumberPicker picker = new NumberPicker(dialogs);
        picker.setMinValue(0);
        picker.setMaxValue(labels.size() - 1);
        picker.setDisplayedValues(labels.toArray(new String[0]));
        picker.setWrapSelectorWheel(false);
        picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);  // scroll, don't type
        picker.setValue(index);
        LinearLayout room = new LinearLayout(dialogs);
        room.setGravity(Gravity.CENTER);
        room.setPadding(0, dp(12), 0, 0);
        room.addView(picker);
        new MaterialAlertDialogBuilder(dialogs)
                .setTitle(title)
                .setView(room)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> pick(picker.getValue()))
                .show();
    }

    private MaterialButton button(Context context, String sign, String said) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialIconButtonOutlinedStyle);
        button.setText(sign);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setContentDescription(said + ": " + title);
        return button;
    }

    /** A tap moves one step; held, it keeps moving, faster after a while. */
    @SuppressLint("ClickableViewAccessibility")
    private void held(MaterialButton button, int way) {
        button.setOnClickListener(v -> pick(index + way));
        Runnable[] again = new Runnable[1];
        int[] count = {0};
        again[0] = () -> {
            count[0]++;
            pick(index + way * (count[0] > 12 ? 5 : 1));
            if (index > 0 && index < values.size() - 1) repeat.postDelayed(again[0], 90);
        };
        button.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                count[0] = 0;
                repeat.postDelayed(again[0], 450);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                boolean repeated = count[0] > 0;
                repeat.removeCallbacks(again[0]);
                if (repeated) {
                    v.setPressed(false);
                    return true;  // (no extra step from the click)
                }
            }
            return false;
        });
    }

    /** "300,000", "1.5m", "300k", "10" → the number; "" → 0. */
    static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
        if (text.isEmpty()) return 0;
        double scale = text.endsWith("m") ? 1_000_000 : text.endsWith("k") ? 1_000 : 1;
        if (scale != 1) text = text.substring(0, text.length() - 1);
        try {
            return Double.parseDouble(text) * scale;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** As cocfarm_ranges shows a number: "1.5m", "300k", "2,500", "0.5", with the unit. */
    private String shown(double n) {
        String text;
        if (n >= 1_000_000) {
            text = trim(String.format(Locale.ROOT, "%.2f", n / 1_000_000)) + "m";
        } else if (n >= 10_000) {
            text = trim(String.format(Locale.ROOT, "%.1f", n / 1_000)) + "k";
        } else if (n == Math.rint(n)) {
            text = String.format(Locale.US, "%,d", (long) n);
        } else {
            text = trim(String.format(Locale.ROOT, "%.2f", n));
        }
        if (unit.isEmpty()) return text;
        return unit.equals("%") ? text + "%" : text + " " + unit;
    }

    private static String trim(String number) {
        return number.contains(".") ? number.replaceAll("0+$", "").replaceAll("\\.$", "") : number;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
