package com.cocfarm.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The welcome tour a new install starts with, as on Windows (farmbot/tour.py; who gets it:
 * farmbot/onboarding.py): what COCFARM does, getting the phone ready (the game, tapping,
 * notifications), and what to farm (the village, and the loot a base must offer: the Town
 * Hall's suggested amounts or your own). Finishing or skipping it marks it done; leaving
 * halfway brings it back next time. Settings → COCFARM → "Show the welcome tour" opens it again.
 */
public final class TourActivity extends AppCompatActivity {
    private static final String[] TITLES = {"Welcome to COCFARM", "Get your phone ready", "What to farm"};
    private static final String[] INTROS = {
            "COCFARM plays Clash of Clans for you on this phone. A few choices and it's ready.",
            "COCFARM taps in the game for you, and shows how it's going in a notification.",
            "Which village to farm, and how much loot a base must offer before COCFARM attacks it.",
    };
    private static final String[][] FEATURES = {
            {"Finds bases worth it", "Skips bases until one offers the loot you ask for."},
            {"Attacks for you", "Deploys your army and plays the battle out, around the clock if you like."},
            {"Collects the loot", "Empties collectors and mines and brings the loot home."},
            {"Keeps builders busy", "Spends full storages on upgrades and walls while you're away."},
    };
    private static final int[] FEATURE_ICONS = {R.drawable.ic_search, R.drawable.ic_bolt, R.drawable.ic_village,
            R.drawable.ic_build};
    private static final String[][] VILLAGES = {{"home", "Home village"}, {"builder", "Builder base"},
            {"auto", "Auto switch"}};
    private static final String[][] LOOT = {{"search.min_gold", "Gold"}, {"search.min_elixir", "Elixir"},
            {"search.min_dark", "Dark elixir"}};

    private final ExecutorService python = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String folder;
    private int step;
    private TextView progressText, title, intro, lootError;
    private LinearProgressIndicator progress;
    private LinearLayout content, readyRows;
    private NestedScrollView scroll;
    private MaterialButton back, next;
    private ActivityResultLauncher<String> notifications;
    // "What to farm": as the screen shows them, saved at Finish.
    private final Map<String, Object> chosen = new LinkedHashMap<>();
    private JSONObject fills = new JSONObject();  // what "Suggested amounts" puts in
    private String suggestedHelp = "";
    private final Map<String, TextView> lootBoxes = new LinkedHashMap<>();
    private boolean filling;
    private JSONObject kept;  // the choices made before the phone turned (they win over the file's)

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        folder = getExternalFilesDir(null).getAbsolutePath();
        notifications = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> showReady());
        if (saved != null) {
            step = saved.getInt("step", 0);
            try {
                String was = saved.getString("chosen");
                if (was != null) kept = new JSONObject(was);
            } catch (Exception ignored) {
                // nothing kept: the settings as they are
            }
        }
        build();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (step > 0) go(step - 1);
                else finish();  // (not done: it shows again next time)
            }
        });
        python.execute(() -> {
            try {
                JSONObject got = new JSONObject(Bridge.python(this).getModule("cocfarm_settings")
                        .callAttr("tour_values", folder).toString());
                main.post(() -> took(got));
            } catch (Exception e) {
                main.post(() -> took(null));
            }
        });
        go(step);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        if (!chosen.isEmpty()) out.putString("chosen", new JSONObject(chosen).toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == 1) showReady();  // back from Android's settings: what changed
    }

    @Override
    protected void onDestroy() {
        python.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------- the frame

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.cf_background));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(8), dp(8), 0);
        progressText = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, R.color.cf_accent);
        progressText.setLetterSpacing(0.08f);
        top.addView(progressText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialButton skip = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        skip.setText("Skip");
        skip.setTextColor(getColor(R.color.cf_muted));
        skip.setOnClickListener(v -> done(false));
        top.addView(skip);
        root.addView(top);

        progress = new LinearProgressIndicator(this);
        progress.setMax(TITLES.length * 100);
        progress.setTrackCornerRadius(dp(3));
        progress.setTrackThickness(dp(6));
        progress.setIndicatorColor(getColor(R.color.cf_accent));
        progress.setTrackColor(getColor(R.color.cf_surface_high));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bar.setMargins(dp(20), dp(4), dp(20), 0);
        root.addView(progress, bar);

        scroll = new NestedScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(20), dp(20), dp(24));
        title = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, R.color.cf_text);
        column.addView(title);
        intro = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.cf_muted);
        intro.setPadding(0, dp(8), 0, dp(8));
        column.addView(intro);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content);
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(20), dp(10), dp(20), dp(14));
        back = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        back.setText("Back");
        back.setOnClickListener(v -> go(step - 1));
        next = new MaterialButton(this);
        next.setOnClickListener(v -> {
            if (step == TITLES.length - 1) finishTour();
            else go(step + 1);
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams otherHalf = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        otherHalf.setMarginStart(dp(12));
        bottom.addView(back, half);
        bottom.addView(next, otherHalf);
        root.addView(bottom);
        setContentView(root);
    }

    private void go(int index) {
        step = Math.max(0, Math.min(index, TITLES.length - 1));
        progressText.setText("STEP " + (step + 1) + " OF " + TITLES.length);
        progress.setProgressCompat((step + 1) * 100, true);
        title.setText(TITLES[step]);
        intro.setText(INTROS[step]);
        content.removeAllViews();
        lootBoxes.clear();
        if (step == 0) welcome();
        else if (step == 1) ready();
        else farm();
        back.setVisibility(step > 0 ? View.VISIBLE : View.INVISIBLE);
        boolean last = step == TITLES.length - 1;
        next.setText(last ? "Finish" : "Next");
        next.setIconResource(last ? R.drawable.ic_check_circle : R.drawable.ic_chevron_right);
        next.setIconGravity(last ? MaterialButton.ICON_GRAVITY_TEXT_START : MaterialButton.ICON_GRAVITY_TEXT_END);
        scroll.scrollTo(0, 0);
    }

    /** Done: never shown again (``saved``: the choices went in first). Home, where farming starts. */
    private void done(boolean saved) {
        python.execute(() -> {
            try {
                Bridge.python(this).getModule("cocfarm_settings").callAttr("tour_finish", folder);
            } catch (Exception ignored) {  // it shows again next time: no harm
            }
            main.post(() -> {
                setResult(saved ? RESULT_OK : RESULT_CANCELED);
                finish();
            });
        });
    }

    // ------------------------------------------------------------- 1. welcome

    private void welcome() {
        for (int i = 0; i < FEATURES.length; i += 2) {
            LinearLayout pair = new LinearLayout(this);
            for (int j = i; j < i + 2; j++) {
                LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (j > i) room.setMarginStart(dp(10));
                pair.addView(feature(FEATURE_ICONS[j], FEATURES[j][0], FEATURES[j][1]), room);
            }
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.topMargin = dp(10);
            content.addView(pair, gap);
        }
        LinearLayout card = Ui.card(content, "Before you start");
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        for (String line : new String[]{
                "Clash of Clans on this phone, signed in to your account.",
                "COCFARM allowed to tap in the game: the next step turns it on.",
                "A minute for this tour."}) {
            card.addView(checkLine(line));
        }
    }

    private View feature(int icon, String name, String text) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout inside = new LinearLayout(this);
        inside.setOrientation(LinearLayout.VERTICAL);
        inside.setPadding(dp(14), dp(14), dp(14), dp(14));
        ImageView picture = new ImageView(this);
        picture.setImageResource(icon);
        picture.setImageTintList(ColorStateList.valueOf(getColor(R.color.cf_accent)));
        inside.addView(picture, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView heading = Ui.text(this, name, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, R.color.cf_text);
        heading.setPadding(0, dp(10), 0, dp(4));
        inside.addView(heading);
        inside.addView(Ui.text(this, text, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted));
        card.addView(inside);
        return card;
    }

    private View checkLine(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(6), 0, dp(6));
        ImageView tick = new ImageView(this);
        tick.setImageResource(R.drawable.ic_check_circle);
        tick.setImageTintList(ColorStateList.valueOf(getColor(R.color.cf_accent)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(18), dp(18));
        size.setMarginEnd(dp(12));
        size.topMargin = dp(1);
        row.addView(tick, size);
        row.addView(Ui.text(this, text, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.cf_text),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    // ------------------------------------------------------------- 2. get ready

    private void ready() {
        readyRows = Ui.card(content, "On this phone");
        TextView note = Ui.text(this, "When you press Start farming, Android asks to share the screen: COCFARM only "
                        + "looks at it while it farms, and only ever taps Clash of Clans.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(note);
        showReady();
    }

    private void showReady() {
        if (readyRows == null || step != 1) return;
        readyRows.removeAllViews();
        boolean game = gameInstalled(), tapping = accessibilityOn();
        boolean notifying = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        readyRow(game, R.drawable.ic_village, "Clash of Clans",
                game ? "Installed. Open it once and sign in, if you haven't." : "Not on this phone yet.",
                "Get it", v -> openStore());
        readyRow(tapping, R.drawable.ic_touch, "Let COCFARM tap in the game",
                tapping ? "Turned on." : "Turn on COCFARM under Accessibility (in Installed or Downloaded apps).",
                "Turn on", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        readyRow(notifying, R.drawable.ic_notifications, "Allow notifications",
                notifying ? "Allowed." : "So you can pause and stop it from the notification, and get alerts.",
                "Allow", v -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS));
    }

    private void readyRow(boolean done, int iconRes, String name, String text, String action, View.OnClickListener act) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_item, readyRows, false);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(done ? R.drawable.ic_check_circle : iconRes);
        icon.setImageTintList(ColorStateList.valueOf(getColor(done ? R.color.cf_good : R.color.cf_warn)));
        ((TextView) row.findViewById(R.id.title)).setText(name);
        ((TextView) row.findViewById(R.id.detail)).setText(text);
        if (!done) {
            MaterialButton button = row.findViewById(R.id.action);
            button.setText(action);
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(act);
        }
        readyRows.addView(row);
    }

    private boolean gameInstalled() {
        try {
            getPackageManager().getPackageInfo(FloatingButton.GAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + FloatingButton.GAME)));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + FloatingButton.GAME)));
        }
    }

    private boolean accessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String mine = new ComponentName(this, TouchService.class).flattenToString();
        if (enabled == null) return false;
        for (String service : TextUtils.split(enabled, ":")) {
            if (service.equalsIgnoreCase(mine)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- 3. what to farm

    /** The settings as they are now (Python): what the last step starts from. */
    private void took(JSONObject got) {
        if (got == null) return;
        chosen.put("bot.mode", got.optString("mode", "home"));
        chosen.put("search.suggested", got.optBoolean("suggested", true));
        JSONObject amounts = got.optJSONObject("amounts");
        for (String[] kind : LOOT) chosen.put(kind[0], amounts == null ? "" : amounts.optString(kind[0]));
        JSONObject given = got.optJSONObject("fills");
        if (given != null) fills = given;
        suggestedHelp = got.optString("help");
        if (kept != null) {
            for (java.util.Iterator<String> it = kept.keys(); it.hasNext(); ) {
                String path = it.next();
                chosen.put(path, kept.opt(path));
            }
            kept = null;
        }
        if (step == 2) go(2);
    }

    private void farm() {
        LinearLayout village = Ui.card(content, "Village");
        village.setPadding(dp(16), dp(8), dp(16), dp(14));
        ChipGroup chips = new ChipGroup(this);
        chips.setSingleSelection(true);
        chips.setSelectionRequired(true);
        String mode = String.valueOf(chosen.getOrDefault("bot.mode", "home"));
        for (String[] option : VILLAGES) {
            Chip chip = (Chip) LayoutInflater.from(this).inflate(R.layout.chip_filter, chips, false);
            chip.setId(View.generateViewId());
            chip.setText(option[1]);
            chips.addView(chip);
            if (option[0].equals(mode)) chips.check(chip.getId());
            chip.setOnCheckedChangeListener((c, on) -> {
                if (on) chosen.put("bot.mode", option[0]);
            });
        }
        village.addView(chips);
        village.addView(Ui.text(this, "Auto switch farms one village until its storages are full, then the other.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted));

        LinearLayout loot = Ui.card(content, "Loot a base must offer");
        loot.setPadding(dp(16), dp(8), dp(16), dp(14));
        loot.addView(Ui.text(this, "Bases offering less are skipped; 0 ignores it.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(4));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(Ui.text(this, "Suggested amounts", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, R.color.cf_text));
        TextView help = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        words.addView(help);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialSwitch suggested = new MaterialSwitch(this);
        suggested.setChecked(Boolean.TRUE.equals(chosen.getOrDefault("search.suggested", true)));
        LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        beside.setMarginStart(dp(12));  // the words ran into the switch on a 360 dp phone
        row.addView(suggested, beside);
        loot.addView(row);
        for (String[] kind : LOOT) {
            TextInputLayout layout = (TextInputLayout) LayoutInflater.from(this).inflate(R.layout.field_text, loot, false);
            layout.setHint(kind[1]);
            layout.setPlaceholderText("e.g. 500k or 1.5m");
            TextView edit = layout.getEditText();
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            edit.setText(String.valueOf(chosen.getOrDefault(kind[0], "")));
            edit.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void afterTextChanged(Editable s) {
                    if (filling) return;
                    chosen.put(kind[0], s.toString());
                    if (suggested.isChecked()) suggested.setChecked(false);  // typing: your own amounts
                }
            });
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.topMargin = dp(8);
            loot.addView(layout, gap);
            lootBoxes.put(kind[0], edit);
        }
        lootError = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_bad);
        lootError.setPadding(0, dp(8), 0, 0);
        lootError.setVisibility(View.GONE);
        loot.addView(lootError);
        Runnable explain = () -> help.setText(suggested.isChecked() ? suggestedHelp.isEmpty()
                ? "From your Town Hall, and they follow it as it goes up." : suggestedHelp
                : "Your own amounts, below. On: COCFARM suggests them from your Town Hall.");
        explain.run();
        suggested.setOnCheckedChangeListener((v, on) -> {
            chosen.put("search.suggested", on);
            if (on) {
                filling = true;
                for (Iterator<String> it = fills.keys(); it.hasNext(); ) {
                    String path = it.next();
                    chosen.put(path, fills.optString(path));
                    TextView box = lootBoxes.get(path);
                    if (box != null) box.setText(fills.optString(path));
                }
                filling = false;
                lootError.setVisibility(View.GONE);
            }
            explain.run();
        });
        row.setOnClickListener(v -> suggested.toggle());

        TextView tip = Ui.text(this, "Everything else can wait: Settings has the rest, and quick setups at the top "
                        + "for common ways to play.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        tip.setPadding(dp(4), dp(14), dp(4), 0);
        content.addView(tip);
    }

    private void finishTour() {
        if (chosen.isEmpty()) {  // the settings couldn't be read: nothing to save
            done(false);
            return;
        }
        next.setEnabled(false);
        String given = new JSONObject(chosen).toString();
        python.execute(() -> {
            String result;
            try {
                result = Bridge.python(this).getModule("cocfarm_settings").callAttr("tour_save", folder, given).toString();
            } catch (Exception e) {
                result = "{\"\": \"" + e.getMessage() + "\"}";
            }
            String said = result;
            main.post(() -> {
                next.setEnabled(true);
                try {
                    JSONObject problems = new JSONObject(said);
                    if (problems.length() == 0) {
                        done(true);
                        return;
                    }
                    StringBuilder text = new StringBuilder();
                    for (Iterator<String> it = problems.keys(); it.hasNext(); ) {
                        String path = it.next();
                        String name = path;
                        for (String[] kind : LOOT) if (kind[0].equals(path)) name = kind[1];
                        if (text.length() > 0) text.append("\n");
                        text.append(name.isEmpty() ? "" : name + ": ").append(problems.optString(path));
                    }
                    if (lootError != null) {
                        lootError.setText(text);
                        lootError.setVisibility(View.VISIBLE);
                    }
                } catch (JSONException e) {
                    done(false);
                }
            });
        });
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
