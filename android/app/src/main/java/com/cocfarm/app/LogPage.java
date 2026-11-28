package com.cocfarm.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;

import java.io.File;

/**
 * The step-by-step log (the Windows app's "Details"): what the bot has been
 * doing, line by line, as it happens. Warnings in amber, errors in red,
 * attacks in green. The day's log file can be shared, for a bug report.
 */
final class LogPage {
    private static final int WARNING = 30, ERROR = 40;  // Python's logging levels
    private static final long POLL_MS = 1000;
    private static final int SCREENS_SHARED = 6;  // the newest error screens sent with the log
    private final Context context;
    private final String folder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpannableStringBuilder shown = new SpannableStringBuilder();
    final NestedScrollView view;
    private final TextView lines;
    private int count;  // lines shown so far
    private boolean polling, asking;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            fetch();
            if (polling) main.postDelayed(this, POLL_MS);
        }
    };

    LogPage(Context context, String folder) {
        this.context = context;
        this.folder = folder;
        view = Ui.column(context);
        LinearLayout content = Ui.inside(view);
        TextView about = Ui.text(context, "This run, as it happens. For a bug report, Copy report puts the log's last part"
                        + " on the clipboard with names, tags and links taken out, ready to paste into a GitHub issue."
                        + " Share log sends the log with the screens saved when something went wrong (the newest "
                        + SCREENS_SHARED + "): they show the game as the bot saw it.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        about.setPadding(Ui.dp(context, 4), Ui.dp(context, 12), Ui.dp(context, 4), 0);
        content.addView(about);
        content.addView(Ui.buttons(context,
                Ui.button(context, "Copy report", R.drawable.ic_copy, v -> copyReport()),
                Ui.button(context, "Share log", R.drawable.ic_share, v -> share())));
        lines = Ui.text(context, "Nothing yet. Start farming and each step appears here.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        lines.setTypeface(Typeface.MONOSPACE);
        lines.setTextIsSelectable(true);
        lines.setLineSpacing(0, 1.15f);
        lines.setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12));
        Ui.card(content, "Log").addView(lines);
    }

    /** Follow the log while the page shows. */
    void start() {
        polling = true;
        main.removeCallbacks(poll);
        main.post(poll);
    }

    void stop() {
        polling = false;
        main.removeCallbacks(poll);
    }

    private void fetch() {
        if (asking) return;
        asking = true;
        new Thread(() -> {
            JSONArray answer = null;
            try {
                answer = new JSONArray(Bridge.python(context).getModule("cocfarm_android").callAttr("log_lines", count).toString());
            } catch (Exception ignored) {
                // Python still starting: next time
            }
            JSONArray got = answer;
            main.post(() -> {
                asking = false;
                if (got != null) add(got);
            });
        }, "log").start();
    }

    private void add(JSONArray answer) {
        JSONArray fresh = answer.optJSONArray(1);
        count = answer.optInt(0);
        if (fresh == null || fresh.length() == 0) return;
        boolean atEnd = !view.canScrollVertically(1);
        for (int i = 0; i < fresh.length(); i++) {
            JSONArray line = fresh.optJSONArray(i);
            int level = line.optInt(0);
            String text = line.optString(1);
            int colour = level >= ERROR ? R.color.cf_bad : level >= WARNING ? R.color.cf_warn
                    : text.contains("→ attack") || text.contains("done") ? R.color.cf_good : R.color.cf_text;
            int from = shown.length();
            shown.append(shown.length() == 0 ? "" : "\n").append(text);
            shown.setSpan(new ForegroundColorSpan(context.getColor(colour)), from, shown.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (shown.length() > 200_000) {  // keep the screen light: the file has everything
            shown.delete(0, shown.length() - 150_000);
        }
        lines.setText(shown);
        if (atEnd) view.post(() -> view.fullScroll(NestedScrollView.FOCUS_DOWN));
    }

    /** The log made ready for a GitHub issue (personal details out), on the clipboard. */
    private void copyReport() {
        new Thread(() -> {
            String text = null, problem = null;
            int count = 0;
            try {
                org.json.JSONObject report = new org.json.JSONObject(Bridge.python(context).getModule("cocfarm_settings")
                        .callAttr("bug_report", folder, phoneDetails(context)).toString());
                text = report.getString("text");
                count = report.optInt("lines");
            } catch (Exception e) {
                problem = e.getMessage();
            }
            String copied = text, why = problem;
            int lineCount = count;
            main.post(() -> {
                if (copied == null) {
                    Ui.snack(lines, "Couldn't make the report: " + why, Snackbar.LENGTH_LONG).show();
                    return;
                }
                context.getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("COCFARM bug report", copied));
                Ui.snack(lines, lineCount > 0 ? "Copied, with " + lineCount + " lines of the log. Paste it into the issue."
                        : "Copied. There's no log yet: it starts with the first run.", Snackbar.LENGTH_LONG).show();
            });
        }, "report").start();
    }

    /** The phone and its screen, for the report: which phones and sizes a bug shows on. */
    static String phoneDetails(Context context) {
        String make = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL;
        if (!make.isEmpty()) make = Character.toUpperCase(make.charAt(0)) + make.substring(1);
        String name = model.toLowerCase(java.util.Locale.ROOT).startsWith(make.toLowerCase(java.util.Locale.ROOT))
                ? model : (make + " " + model).trim();
        StringBuilder text = new StringBuilder("Phone: ").append(name.isEmpty() ? "unknown" : name)
                .append(", Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")");
        try {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getRealMetrics(metrics);
            // Sideways, as the game is played (and as the bot sees it).
            int wide = Math.max(metrics.widthPixels, metrics.heightPixels);
            int tall = Math.min(metrics.widthPixels, metrics.heightPixels);
            text.append("\nScreen: ").append(wide).append("x").append(tall).append(", ")
                    .append(metrics.densityDpi).append(" dpi");
        } catch (RuntimeException e) {
            text.append("\nScreen: unknown");
        }
        return text.toString();
    }

    private void share() {
        File log = new File(folder, "logs/farmbot.log");
        if (!log.exists()) {
            Ui.snack(lines, "No log file yet: it starts with the first run.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        java.util.List<File> screens = errorScreens();
        if (screens.isEmpty()) {
            Ui.share(context, log, "text/plain", "Share the log");
            return;
        }
        java.util.List<File> files = new java.util.ArrayList<>();
        files.add(log);
        files.addAll(screens);
        Ui.shareAll(context, files, "Share the log and " + screens.size() + " error screen" + (screens.size() == 1 ? "" : "s"));
    }

    /** The newest screens saved when a round failed (screens/errors): Android's own file
     * manager can't open COCFARM's folder, so this is the way to get them off the phone. */
    private java.util.List<File> errorScreens() {
        File[] found = new File(folder, "screens/errors").listFiles(
                f -> f.isFile() && (f.getName().endsWith(".png") || f.getName().endsWith(".jpg")));
        java.util.List<File> screens = new java.util.ArrayList<>();
        if (found == null) return screens;
        java.util.Arrays.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < Math.min(SCREENS_SHARED, found.length); i++) screens.add(found[i]);
        return screens;
    }
}
