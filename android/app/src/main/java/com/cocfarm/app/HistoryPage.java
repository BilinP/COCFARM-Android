package com.cocfarm.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Every attack in the history file (attacks.csv, kept across runs), as the
 * Windows app's History page: totals for today, the last 7 days and all
 * time, gains per hour of play, the last 14 days, and the recent attacks.
 */
final class HistoryPage {
    private static final int M = com.google.android.material.R.style.TextAppearance_Material3_BodySmall;
    private final Context context;
    private final String folder;
    private final Handler main = new Handler(Looper.getMainLooper());
    final NestedScrollView view;
    private final LinearLayout content;
    private final boolean wide;  // room for two columns: the totals and the chart, and the attacks beside them
    private LinearLayout target;  // where the next card goes
    private boolean loading;

    HistoryPage(Context context, String folder, boolean wide) {
        this.context = context;
        this.folder = folder;
        this.wide = wide;
        view = Ui.column(context);
        content = Ui.inside(view);
        intro("Loading…");
    }

    /** Read the history file again (it grows with every attack). */
    void refresh() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            JSONObject summary;
            try {
                summary = new JSONObject(Bridge.python(context).getModule("cocfarm_settings")
                        .callAttr("history_summary", folder).toString());
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    content.removeAllViews();
                    intro("Couldn't read the history: " + e.getMessage());
                });
                return;
            }
            JSONObject shown = summary;
            main.post(() -> {
                loading = false;
                show(shown);
            });
        }, "history").start();
    }

    /** The history file, to a spreadsheet app, a drive or a message. */
    private void share() {
        java.io.File file = new java.io.File(folder, "attacks.csv");
        if (!file.exists()) {
            com.google.android.material.snackbar.Snackbar.make(content, "No attacks yet: the file starts with the first one.",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            return;
        }
        Ui.share(context, file, "text/csv", "Share the attacks");
    }

    private void intro(String words) {
        TextView note = text(words, M, R.color.cf_muted);
        note.setPadding(dp(4), dp(16), dp(4), 0);
        content.addView(note);
    }

    private void show(JSONObject summary) {
        content.removeAllViews();
        // Share only once there's something in the file (it starts with the first attack).
        if (new java.io.File(folder, "attacks.csv").exists()) {
            intro("Every attack, kept in attacks.csv in COCFARM's folder: a spreadsheet opens it.");
            content.addView(Ui.buttons(context, Ui.button(context, "Share attacks.csv", R.drawable.ic_table, v -> share())));
        } else {
            intro("Every attack is kept in attacks.csv in COCFARM's folder, from the first one: a "
                    + "spreadsheet opens it, and it can be shared from here.");
        }
        LinearLayout left = content, right = content;
        if (wide) {
            LinearLayout side = new LinearLayout(context);
            left = new LinearLayout(context);
            right = new LinearLayout(context);
            for (LinearLayout column : new LinearLayout[]{left, right}) {
                column.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (column == left) room.setMarginEnd(dp(8)); else room.setMarginStart(dp(8));
                side.addView(column, room);
            }
            content.addView(side);
        }
        target = left;
        LinearLayout totals = card("Totals");
        total(totals, "Today", summary.optJSONObject("today"));
        totals.addView(divider());
        total(totals, "Last 7 days", summary.optJSONObject("week"));
        totals.addView(divider());
        total(totals, "All time", summary.optJSONObject("all"));
        totals.addView(divider());
        JSONArray hour = summary.optJSONArray("hour");
        row(totals, "Per hour of play", hour == null ? "—" : Fmt.shortNumber(hour.optLong(0) + hour.optLong(1)),
                hour == null ? "Needs a few attacks in a row." : loot(hour) + " (last 7 days)");

        LinearLayout chartCard = card("Gold and elixir gained, last 14 days");
        BarChart chart = new BarChart(context);
        chart.setPadding(0, dp(12), 0, dp(4));
        JSONArray days = summary.optJSONArray("days");
        long[] values = new long[days.length()];
        String[] labels = new String[days.length()];
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            JSONArray gained = day.optJSONArray("gained");
            values[i] = gained.optLong(0) + gained.optLong(1);
            labels[i] = format("yyyy-MM-dd", day.optString("day"), "EEEEE");  // the day's first letter
        }
        long gainedAtAll = 0;
        for (long value : values) gainedAtAll += value;
        if (gainedAtAll > 0) {
            chart.show(values, labels, days.length() - 1);
            chartCard.addView(chart);
        } else {  // an empty chart says less than a line saying what will be there
            JSONObject all = summary.optJSONObject("all");
            boolean attacked = all != null && all.optInt("attacks") > 0;
            row(chartCard, attacked ? "Nothing gained in these 14 days" : "Nothing gained yet", "",
                    "Each day's gold and elixir shows here as a bar.");
        }

        target = right;
        LinearLayout recent = card("Recent attacks");
        JSONArray attacks = summary.optJSONArray("recent");
        if (attacks.length() == 0) {
            row(recent, "No attacks yet", "", "Each attack is added here once it's done.");
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        for (int i = 0; i < attacks.length(); i++) {
            JSONObject attack = attacks.optJSONObject(i);
            String time = attack.optString("time");  // 2026-10-26T14:05
            String day = time.startsWith(today) ? "Today, " + format("yyyy-MM-dd'T'HH:mm", time, "HH:mm")
                    : format("yyyy-MM-dd'T'HH:mm", time, "EEE d MMM, HH:mm");
            JSONArray gained = attack.optJSONArray("gained");
            if (i > 0) recent.addView(divider());
            row(recent, day, gained == null ? "?" : Fmt.gain(gained.optLong(0) + gained.optLong(1)),
                    "Offered " + loot(attack.optJSONArray("target")) + "\nGained " + (gained == null ? "unknown" : loot(gained)));
        }
    }

    private void total(LinearLayout card, String title, JSONObject period) {
        int attacks = period == null ? 0 : period.optInt("attacks");
        row(card, title, attacks + (attacks == 1 ? " attack" : " attacks"),
                attacks == 0 ? "Nothing yet" : "Gained " + loot(period.optJSONArray("gained")));
    }

    /** A title with a figure on the right, and a line under it. */
    private void row(LinearLayout card, String title, String figure, String below) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, R.color.cf_text),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text(figure, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.cf_text);
        top.addView(value);
        row.addView(top);
        if (!below.isEmpty()) {
            TextView note = text(below, M, R.color.cf_muted);
            note.setPadding(0, dp(2), 0, 0);
            row.addView(note);
        }
        card.addView(row);
    }

    private LinearLayout card(String heading) {
        return Ui.card(target == null ? content : target, heading);
    }

    private TextView text(String value, int appearance, int colour) {
        return Ui.text(context, value, appearance, colour);
    }

    private android.view.View divider() {
        return Ui.divider(context);
    }

    private int dp(float value) {
        return Ui.dp(context, value);
    }

    /** ``value`` (in the ``from`` pattern) in the ``to`` pattern; as it was if it doesn't parse. */
    private static String format(String from, String value, String to) {
        try {
            return new SimpleDateFormat(to, Locale.getDefault()).format(new SimpleDateFormat(from, Locale.US).parse(value));
        } catch (ParseException e) {
            return value;
        }
    }

    private static String loot(JSONArray values) {
        if (values == null) return "—";
        return Fmt.shortNumber(values.optLong(0)) + " gold · " + Fmt.shortNumber(values.optLong(1)) + " elixir · "
                + Fmt.shortNumber(values.optLong(2)) + " dark";
    }
}
