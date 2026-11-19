package com.cocfarm.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every setting, page by page: each page is one of the home screen's tabs.
 * What's on it comes from cocfarm_settings.py, and it's checked and saved
 * there with the same rules as on Windows: a change is saved a moment after
 * it's made, and a mistake shows in red on its setting. A farming bot picks
 * the change up straight away. (Built in the home screen's context.)
 */
final class SettingsForm extends ContextWrapper {
    private final Handler main = new Handler(Looper.getMainLooper());
    // Setting path -> shows (or, given null, clears) its mistake.
    private final Map<String, Consumer<String>> errors = new HashMap<>();
    private final List<View> dependents = new ArrayList<>();
    private final Map<View, JSONObject> dependsOn = new HashMap<>();
    private final Map<String, View> boxOf = new HashMap<>();  // a setting's path -> its line on the page shown
    private final Map<View, View> dividerOf = new HashMap<>();  // a line -> the divider above it
    private JSONArray pages;
    private JSONObject values;
    private LinearLayout content;
    private final TextView statusLine;
    private final String folder;
    private final Runnable save = this::save;
    private boolean pending;  // a change waiting to be saved
    // A switch that fills settings in when turned on ("Suggested amounts"), and is turned off by
    // typing in one of them: its path, the settings' boxes, and which switch each one turns off.
    private final Map<String, MaterialSwitch> switchOf = new HashMap<>();
    private final Map<String, TextView> editOf = new HashMap<>();
    private final Map<String, NumberScroller> scrollerOf = new HashMap<>();  // a number's scroller
    private final Map<String, Runnable> scrollBy = new HashMap<>();  // a choice -> the scroller whose steps follow it
    private final Map<String, String> turnsOff = new HashMap<>();
    private boolean filling;  // putting a switch's values in: not typing

    SettingsForm(Context screen, String folder, TextView statusLine) {
        super(screen);
        this.folder = folder;
        this.statusLine = statusLine;
    }

    /** Read the settings (Python: off the main thread). */
    void load() throws JSONException {
        JSONObject screen = new JSONObject(Bridge.python(this).getModule("cocfarm_settings")
                .callAttr("load", folder).toString());
        pages = screen.getJSONArray("pages");
        values = screen.getJSONObject("values");
        presets = new org.json.JSONArray(Bridge.python(this).getModule("cocfarm_settings").callAttr("presets", folder).toString());
    }

    int pageCount() {
        return pages.length();
    }

    String title(int page) {
        return pages.optJSONObject(page).optString("title");
    }

    int groupCount(int page) {
        JSONArray groups = pages.optJSONObject(page).optJSONArray("groups");
        return groups == null ? 0 : groups.length();
    }

    String groupTitle(int page, int group) {
        return groupOf(page, group).optString("title");
    }

    /** A group that's turned on and off as a whole (its first setting is a switch most of the
     * rest need on): whether it's on. Null for any other group. */
    Boolean groupOn(int page, int group) {
        JSONArray items = groupOf(page, group).optJSONArray("items");
        JSONObject first = items == null || items.length() == 0 ? null : items.optJSONObject(0);
        if (first == null || !first.optString("kind").equals("switch") || first.has("depends")) return null;
        String path = first.optString("path");
        int needing = 0;
        for (int i = 1; i < items.length(); i++) {
            JSONObject depends = items.optJSONObject(i).optJSONObject("depends");
            if (depends != null && depends.optString("path").equals(path)) needing++;
        }
        if (items.length() > 1 && needing * 2 <= items.length() - 1) return null;
        return values.optBoolean(path);
    }

    /** Every tile's label for the values now on screen: {"page/group": [text, tone]}, tone
     * "on", "off" or "info". Empty if Python couldn't make them (the tiles then show On/Off only). */
    JSONObject groupStates() {
        try {
            return new JSONObject(Bridge.python(this).getModule("cocfarm_settings")
                    .callAttr("group_states", values.toString()).toString());
        } catch (Exception e) {
            android.util.Log.w("COCFARM", "No tile labels", e);
            return new JSONObject();
        }
    }

    /** What's in a group: its settings' titles (after the on/off switch, if it has one). */
    String groupSummary(int page, int group) {
        String own = groupOf(page, group).optString("summary");  // a group's own short line, if it has one
        if (!own.isEmpty()) return own;
        JSONArray items = groupOf(page, group).optJSONArray("items");
        List<String> titles = new ArrayList<>();
        for (int i = groupOn(page, group) != null ? 1 : 0; items != null && i < items.length(); i++) {
            String title = items.optJSONObject(i).optString("title");
            if (!titles.contains(title)) titles.add(title);
        }
        if (titles.isEmpty() && items != null && items.length() > 0) {
            String help = items.optJSONObject(0).optString("help");
            return help.isEmpty() ? items.optJSONObject(0).optString("title") : help;
        }
        return TextUtils.join(" · ", titles);
    }

    /** The settings with every word of ``query`` in them (their title, help, choices, group or
     * page): {page, group, item} each, best first (a word in the title before one only in the
     * help), at most ``limit``. */
    List<int[]> search(String query, int limit) {
        List<int[]> found = new ArrayList<>();
        String[] words = query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return found;
        List<int[]> later = new ArrayList<>();
        for (int p = 0; p < pageCount(); p++) {
            for (int g = 0; g < groupCount(p); g++) {
                JSONArray items = groupOf(p, g).optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    String title = item.optString("title").toLowerCase(java.util.Locale.ROOT);
                    String all = (title + " " + item.optString("help") + " " + choices(item) + " "
                            + groupTitle(p, g) + " " + title(p)).toLowerCase(java.util.Locale.ROOT);
                    boolean every = true, inTitle = true;
                    for (String word : words) {
                        every &= all.contains(word);
                        inTitle &= title.contains(word);
                    }
                    if (every) (inTitle ? found : later).add(new int[]{p, g, i});
                }
            }
        }
        found.addAll(later);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    private static String choices(JSONObject item) {
        StringBuilder out = new StringBuilder();
        for (String[] option : options(item)) out.append(option[1]).append(' ');
        return out.toString();
    }

    JSONObject itemOf(int page, int group, int item) {
        return groupOf(page, group).optJSONArray("items").optJSONObject(item);
    }

    /** The setting to show (scrolled to and lit up) when its group opens next: a search's pick. */
    void highlight(String path) {
        highlight = path;
    }

    private String highlight;

    private JSONObject groupOf(int page, int group) {
        return pages.optJSONObject(page).optJSONArray("groups").optJSONObject(group);
    }

    /** Save a change still waiting (the screen is going away). */
    void flush() {
        if (pending) {
            main.removeCallbacks(save);
            save();
        }
    }

    // ------------------------------------------------------------- pages

    /** One group of settings, built afresh (the controls of the one shown before are let go).
     * On a wide screen its settings go in ``columns`` cards side by side, split evenly. A setting
     * that needs another one on is hidden while that one's off (greyed out instead, when the
     * other one is somewhere else). */
    View group(int page, int index, int columns) {
        NestedScrollView scroll = Ui.column(this);
        content = Ui.inside(scroll);
        errors.clear();
        dependents.clear();
        dependsOn.clear();
        boxOf.clear();
        scrollerOf.clear();
        scrollBy.clear();
        dividerOf.clear();
        JSONArray list = groupOf(page, index).optJSONArray("items");
        int count = list == null ? 0 : list.length();
        int cards = count >= 6 ? Math.max(1, columns) : 1;
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.HORIZONTAL);
        side.setBaselineAligned(false);
        LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        top.topMargin = dp(8);
        content.addView(side, top);
        int perCard = Math.max(1, (count + cards - 1) / cards);
        LinearLayout items = null;
        for (int i = 0; i < count; i++) {
            if (i % perCard == 0) {
                MaterialCardView card = new MaterialCardView(this);
                items = new LinearLayout(this);
                items.setOrientation(LinearLayout.VERTICAL);
                items.setPadding(dp(16), dp(4), dp(16), dp(4));
                card.addView(items);
                LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                room.setMarginStart(side.getChildCount() == 0 ? 0 : dp(8));
                side.addView(card, room);
            } else {
                MaterialDivider divider = new MaterialDivider(this);
                divider.setDividerColor(getColor(R.color.cf_outline_variant));
                items.addView(divider);
            }
            View box = item(list.optJSONObject(i));
            if (i % perCard != 0) dividerOf.put(box, items.getChildAt(items.getChildCount() - 1));
            items.addView(box);
        }
        refreshDependents();
        JSONArray state = groupStates().optJSONArray(title(page) + "/" + groupTitle(page, index));
        if (state != null && state.optBoolean(2)) {  // something in it isn't the default: a way back
            content.addView(resetButton(page, index));
        }
        View found = highlight == null ? null : boxOf.get(highlight);
        for (int i = 0; found != null && found.getVisibility() != View.VISIBLE && i < count; i++) {
            // Hidden until another setting's on: that one's lit up instead.
            JSONObject item = list.optJSONObject(i), depends = item.optJSONObject("depends");
            if (item.optString("path").equals(highlight) && depends != null) {
                found = boxOf.get(depends.optString("path"));
            }
        }
        highlight = null;
        View lit = found;
        if (lit != null) {  // a search's pick: scrolled to, and lit up for a moment
            scroll.post(() -> {
                android.graphics.Rect where = new android.graphics.Rect();
                lit.getDrawingRect(where);
                scroll.offsetDescendantRectToMyCoords(lit, where);
                scroll.smoothScrollTo(0, Math.max(0, where.top - dp(24)));
                android.animation.ValueAnimator glow = android.animation.ValueAnimator.ofArgb(
                        getColor(R.color.cf_accent_container), android.graphics.Color.TRANSPARENT);
                glow.setDuration(2400).setStartDelay(900);
                glow.addUpdateListener(a -> lit.setBackgroundColor((int) a.getAnimatedValue()));
                lit.setBackgroundColor(getColor(R.color.cf_accent_container));
                glow.start();
            });
        }
        // From here on a setting appearing or going slides the ones under it.
        for (int c = 0; c < side.getChildCount(); c++) {
            ((ViewGroup) ((ViewGroup) side.getChildAt(c)).getChildAt(0)).setLayoutTransition(new android.animation.LayoutTransition());
        }
        return scroll;
    }

    private View item(JSONObject item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, dp(14));
        String kind = item.optString("kind"), path = item.optString("path");
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(item.optString("title"), com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, R.color.cf_text));
        String help = item.optString("help");
        if (!help.isEmpty()) {
            TextView helpText = text(help, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
            helpText.setPadding(0, dp(2), 0, 0);
            words.addView(helpText);
        }
        TextView error = text("", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_bad);
        error.setPadding(0, dp(6), 0, 0);
        error.setVisibility(View.GONE);
        Consumer<String> showError = message -> {
            error.setText(message == null ? "" : message);
            error.setVisibility(message == null ? View.GONE : View.VISIBLE);
        };
        if (kind.equals("button")) {  // does something: its title is on the button, the answer under it
            words.removeViewAt(0);
            box.addView(words);
            box.addView(actionButton(item, error));
            box.addView(error);
            return box;
        }
        JSONObject scroll = scrollFor(item);
        if (scroll != null) {  // a number: scrolled, not typed (name left, value top right)
            NumberScroller scroller = new NumberScroller(this, getBaseContext(), item.optString("title"), false, help,
                    item.optString("suffix"), scroll, values.opt(path), value -> changed(path, value));
            keep(path, scroller.show(values.opt(path)));
            scrollerOf.put(path, scroller);
            String by = item.optString("scroll_by");
            if (!by.isEmpty()) {  // the goal: its steps follow the resource picked
                JSONObject scrolls = item.optJSONObject("scrolls");
                scrollBy.put(by, () -> {
                    JSONObject now = scrolls == null ? null : scrolls.optJSONObject(values.optString(by));
                    if (now != null) keep(path, scroller.setChoices(now, values.opt(path)));
                });
            }
            errors.put(path, showError);
            box.addView(scroller);
            box.addView(error);
            if (!path.isEmpty()) boxOf.put(path, box);
            JSONObject depends = item.optJSONObject("depends");
            if (depends != null) {
                dependents.add(box);
                dependsOn.put(box, depends);
            }
            return box;
        }
        if (kind.equals("switch")) {  // the words and the switch on one line; the whole line flips it
            MaterialSwitch toggle = new MaterialSwitch(this);
            toggle.setChecked(values.optBoolean(path));
            JSONObject fills = item.optJSONObject("fills");
            JSONArray offWhen = item.optJSONArray("off_when_typed");
            for (int i = 0; offWhen != null && i < offWhen.length(); i++) turnsOff.put(offWhen.optString(i), path);
            switchOf.put(path, toggle);
            toggle.setOnCheckedChangeListener((v, on) -> {
                if (on && fills != null) fill(fills);
                changed(path, on);
            });
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            // A gap between the words and the switch: on a 360 dp phone the words ran into it.
            LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            beside.setMarginStart(dp(12));
            row.addView(toggle, beside);
            row.setOnClickListener(v -> toggle.toggle());
            box.addView(row);
            errors.put(path, showError);
        } else {
            box.addView(words);
            box.addView(control(kind, path, item, showError));
        }
        box.addView(error);
        if (!path.isEmpty()) boxOf.put(path, box);
        JSONObject depends = item.optJSONObject("depends");
        if (depends != null) {
            dependents.add(box);
            dependsOn.put(box, depends);
        }
        return box;
    }

    private View control(String kind, String path, JSONObject item, Consumer<String> showError) {
        switch (kind) {
            case "speed":
                errors.put(path, showError);
                return speed(path);
            case "choice":
                errors.put(path, showError);
                return choice(path, options(item));
            case "multi": {
                errors.put(path, showError);
                List<String[]> options = options(item);
                return options.size() <= 6 ? chips(path, options) : picker(path, options, false, "Pick");
            }
            case "ordered":
                errors.put(path, showError);
                return picker(path, options(item), true,
                        item.optString("pick_title", "Tap them in the order to give them"));
            case "hours":
                errors.put(path, showError);
                return hoursPicker(path);
            case "presets":
                errors.put(path, showError);
                return presets(path, item.optJSONArray("levels"));
            case "range": {
                JSONObject lowScroll = item.optJSONObject("scroll_low"), highScroll = item.optJSONObject("scroll_high");
                if (lowScroll != null && highScroll != null) {  // From and To, each scrolled
                    LinearLayout column = new LinearLayout(this);
                    column.setOrientation(LinearLayout.VERTICAL);
                    column.setPadding(0, dp(6), 0, 0);
                    String suffix = item.optString("suffix");
                    String lowPath = item.optString("low"), highPath = item.optString("high");
                    NumberScroller[] ends = new NumberScroller[2];
                    for (int e = 0; e < 2; e++) {
                        boolean isLow = e == 0;
                        String endPath = isLow ? lowPath : highPath, otherPath = isLow ? highPath : lowPath;
                        int other = 1 - e;
                        NumberScroller scroller = new NumberScroller(this, getBaseContext(), isLow ? "From" : "To", true,
                                "", suffix, isLow ? lowScroll : highScroll, values.opt(endPath), value -> {
                                    changed(endPath, value);
                                    // From never above To: the other end moves along rather than show a mistake.
                                    double mine = NumberScroller.number(value), theirs = NumberScroller.number(values.opt(otherPath));
                                    if (isLow ? mine > theirs : mine < theirs) {
                                        ends[other].show(value);
                                        changed(otherPath, value);
                                    }
                                });
                        ends[e] = scroller;
                        keep(endPath, scroller.show(values.opt(endPath)));
                        scrollerOf.put(endPath, scroller);
                        errors.put(endPath, showError);
                        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        gap.topMargin = isLow ? 0 : dp(10);
                        column.addView(scroller, gap);
                    }
                    return column;
                }
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextInputLayout low = field(item.optString("low"), "number", "From", "");
                TextInputLayout high = field(item.optString("high"), "number", "To", item.optString("suffix"));
                row.addView(low, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                View gap = new View(this);
                row.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
                row.addView(high, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                return row;
            }
            default:  // amount, number, text
                return field(path, kind, "", item.optString("suffix"));
        }
    }

    /** A button that runs one of cocfarm_settings' actions (test_alert, check_update); its answer shows in ``answer``. */
    private View actionButton(JSONObject item, TextView answer) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(item.optString("title"));
        LinearLayout.LayoutParams spot = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spot.topMargin = dp(6);
        button.setLayoutParams(spot);
        String action = item.optString("action");
        if (action.endsWith("_settings") && getBaseContext() instanceof MainActivity) {  // a file to pick: the screen asks
            MainActivity screen = (MainActivity) getBaseContext();
            if (action.equals(restoredFor) && restored != null) {  // the page was drawn again with what came back
                answer.setText(restored);
                answer.setTextColor(getColor(R.color.cf_good));
                answer.setVisibility(View.VISIBLE);
                restored = null;
            }
            button.setOnClickListener(v -> {
                main.removeCallbacks(save);
                if (pending) save();  // a change still waiting goes in first
                java.util.function.BiConsumer<String, Integer> say = (text, tone) -> {
                    answer.setText(text);
                    answer.setTextColor(getColor(tone));
                    answer.setVisibility(View.VISIBLE);
                };
                if (action.equals("backup_settings")) screen.backUpSettings(say);
                else if (action.equals("copy_account_settings")) screen.copyAccountSettings(say);
                else if (action.equals("welcome_tour_settings")) screen.showTour();
                else screen.restoreSettings(say);
            });
            return button;
        }
        button.setOnClickListener(v -> {
            button.setEnabled(false);
            answer.setTextColor(getColor(R.color.cf_muted));
            answer.setText("Working…");
            answer.setVisibility(View.VISIBLE);
            main.removeCallbacks(save);
            String form = values.toString();
            new Thread(() -> {
                String said;
                int colour = R.color.cf_text;
                try {
                    // A change still waiting (say, a webhook just pasted) is saved first.
                    com.chaquo.python.PyObject settings = Bridge.python(this).getModule("cocfarm_settings");
                    settings.callAttr("save", folder, form);
                    pending = false;
                    if (action.equals("check_update")) {
                        JSONObject found = new JSONObject(settings.callAttr("check_update", folder, true).toString());
                        said = found.isNull("version") ? found.optString("note")
                                : "COCFARM " + found.optString("version") + " is out: update it from the home screen.";
                        colour = found.isNull("version") ? R.color.cf_text : R.color.cf_good;
                    } else {
                        said = settings.callAttr(action, folder).toString();
                        colour = said.startsWith("✓") ? R.color.cf_good : said.startsWith("✗") ? R.color.cf_bad : R.color.cf_text;
                    }
                } catch (Exception e) {
                    said = "✗ " + e.getMessage();
                    colour = R.color.cf_bad;
                }
                String text = said;
                int tone = colour;
                main.post(() -> {
                    button.setEnabled(true);
                    answer.setText(text);
                    answer.setTextColor(getColor(tone));
                });
            }, action).start();
        });
        return button;
    }

    private TextInputLayout field(String path, String kind, String label, String suffix) {
        TextInputLayout layout = (TextInputLayout) LayoutInflater.from(this).inflate(R.layout.field_text, null, false);
        TextView edit = layout.getEditText();
        edit.setText(values.optString(path));
        editOf.put(path, edit);
        if (!label.isEmpty()) {
            layout.setHint(label);
        }
        if (!suffix.isEmpty()) {
            layout.setSuffixText(suffix);
        }
        if (kind.equals("number")) {
            edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        } else if (kind.equals("amount")) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            layout.setPlaceholderText("e.g. 500k or 1.5m");
        } else {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                changed(path, s.toString());
            }
        });
        errors.put(path, layout::setError);
        return layout;
    }

    private View speed(String path) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        Slider slider = new Slider(this);
        slider.setValueFrom(1);
        slider.setValueTo(9);
        slider.setStepSize(1);
        slider.setValue(Math.max(1, Math.min(9, values.optInt(path, 5))));
        slider.setLabelFormatter(value -> String.valueOf(Math.round(value)));
        slider.addOnChangeListener((s, value, user) -> {
            if (user) changed(path, String.valueOf(Math.round(value)));
        });
        column.addView(slider);
        LinearLayout ends = new LinearLayout(this);
        ends.setPadding(dp(14), 0, dp(14), 0);
        ends.addView(text("Slow and careful", com.google.android.material.R.style.TextAppearance_Material3_LabelSmall, R.color.cf_muted),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ends.addView(text("Fast", com.google.android.material.R.style.TextAppearance_Material3_LabelSmall, R.color.cf_muted));
        column.addView(ends);
        return column;
    }

    /** A few options as chips (one picked); more, in a drop-down. */
    private View choice(String path, List<String[]> options) {
        List<String> keys = new ArrayList<>(), labels = new ArrayList<>();
        for (String[] option : options) {
            keys.add(option[0]);
            labels.add(option[1]);
        }
        if (options.size() <= 4) {
            ChipGroup group = new ChipGroup(this);
            group.setPadding(0, dp(8), 0, 0);
            group.setSingleSelection(true);
            group.setSelectionRequired(true);
            for (int i = 0; i < options.size(); i++) {
                Chip chip = (Chip) LayoutInflater.from(this).inflate(R.layout.chip_filter, group, false);
                chip.setId(View.generateViewId());
                chip.setText(labels.get(i));
                group.addView(chip);
                String key = keys.get(i);
                if (key.equals(values.optString(path))) {
                    group.check(chip.getId());
                }
                chip.setOnCheckedChangeListener((v, on) -> {
                    if (on && !key.equals(values.optString(path))) changed(path, key);
                });
            }
            return group;
        }
        TextInputLayout layout = (TextInputLayout) LayoutInflater.from(this).inflate(R.layout.field_dropdown, null, false);
        MaterialAutoCompleteTextView menu = (MaterialAutoCompleteTextView) layout.getEditText();
        menu.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        int current = keys.indexOf(values.optString(path));
        menu.setText(labels.get(Math.max(current, 0)), false);
        menu.setOnItemClickListener((p, v, position, id) -> {
            if (!keys.get(position).equals(values.optString(path))) changed(path, keys.get(position));
        });
        return layout;
    }

    private View chips(String path, List<String[]> options) {
        ChipGroup group = new ChipGroup(this);
        group.setPadding(0, dp(8), 0, 0);
        List<String> chosen = list(path);
        for (String[] option : options) {
            String key = option[0];
            Chip chip = (Chip) LayoutInflater.from(this).inflate(R.layout.chip_filter, group, false);
            chip.setText(option[1]);
            chip.setChecked(chosen.contains(key));
            chip.setOnCheckedChangeListener((v, on) -> {
                List<String> now = list(path);
                now.remove(key);
                if (on) now.add(key);
                changed(path, new JSONArray(now));
            });
            group.addView(chip);
        }
        return group;
    }

    /** A list too long to show: what's picked, opening a dialog to change it. */
    private View picker(String path, List<String[]> options, boolean ordered, String title) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_picker, null, false);
        TextView summary = row.findViewById(R.id.summary);
        summary.setText(summary(list(path), options, ordered));
        row.setOnClickListener(v -> {
            List<String> keys = new ArrayList<>(), now = list(path);
            String[] labels = new String[options.size()];
            boolean[] checked = new boolean[options.size()];
            for (int i = 0; i < options.size(); i++) {
                keys.add(options.get(i)[0]);
                labels[i] = options.get(i)[1];
                checked[i] = now.contains(keys.get(i));
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMultiChoiceItems(labels, checked, (d, which, on) -> {
                        now.remove(keys.get(which));  // a new pick goes last: the order they're tapped in
                        if (on) now.add(keys.get(which));
                    })
                    .setPositiveButton("Done", (d, w) -> {
                        changed(path, new JSONArray(now));
                        summary.setText(summary(now, options, ordered));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        return row;
    }

    /** A line of text per level ([key, title, what fits]): the value is {key: text}. */
    private View presets(String path, JSONArray levels) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        JSONObject now = values.optJSONObject(path);
        JSONObject shown = now == null ? new JSONObject() : now;
        for (int i = 0; levels != null && i < levels.length(); i++) {
            JSONArray level = levels.optJSONArray(i);
            String key = level.optString(0);
            TextInputLayout layout = (TextInputLayout) LayoutInflater.from(this).inflate(R.layout.field_text, null, false);
            layout.setHint(level.optString(1));
            layout.setHelperText(level.optString(2));
            TextView edit = layout.getEditText();
            edit.setText(shown.optString(key));
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            edit.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void afterTextChanged(Editable s) {
                    JSONObject current = values.optJSONObject(path);
                    try {
                        JSONObject next = current == null ? new JSONObject() : new JSONObject(current.toString());
                        next.put(key, s.toString());
                        changed(path, next);
                    } catch (JSONException e) {
                        // (a JSON object's own text always reads back)
                    }
                }
            });
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.topMargin = dp(8);
            column.addView(layout, gap);
        }
        return column;
    }

    /** The hours to play in: a grid of the day's 24 hours. */
    private View hoursPicker(String path) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_picker, null, false);
        TextView summary = row.findViewById(R.id.summary);
        summary.setText(hours(list(path)));
        // The day at a glance under it: tap or drag across hours to change them right here.
        HourBar bar = new HourBar(this);
        bar.set(list(path));
        bar.setContentDescription("Hours to play: " + hours(list(path)));
        bar.listen(on -> {
            List<String> picked = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                if (on[h]) picked.add(String.valueOf(h));
            }
            changed(path, new JSONArray(picked));
            summary.setText(hours(picked));
            bar.setContentDescription("Hours to play: " + hours(picked));
        });
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.VERTICAL);
        both.addView(row);
        LinearLayout.LayoutParams barRoom = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barRoom.topMargin = dp(6);
        both.addView(bar, barRoom);
        row.setOnClickListener(v -> {
            List<String> now = list(path);
            // As wide as the screen lets it (sideways, a short screen: more columns, fewer
            // rows), and the hours scroll if they still don't fit, so the buttons always show.
            int screen = getResources().getConfiguration().screenWidthDp;
            int wide = Math.min(screen - 32, 720);
            int across = (wide - 48) / 66;
            int columns = across >= 8 ? 8 : across >= 6 ? 6 : across >= 4 ? 4 : 3;
            LinearLayout inside = new LinearLayout(this);
            inside.setOrientation(LinearLayout.VERTICAL);
            inside.setPadding(dp(24), dp(4), dp(24), dp(8));
            TextView about = new TextView(this);
            about.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            about.setTextColor(getColor(R.color.cf_muted));
            about.setText("Tap the hours it may play in (this device's time).");
            inside.addView(about);
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(columns);
            LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            under.topMargin = dp(8);
            inside.addView(grid, under);
            androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
            scroll.addView(inside);
            List<Chip> chips = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                Chip chip = (Chip) LayoutInflater.from(this).inflate(R.layout.chip_filter, grid, false);
                chip.setText(String.format("%02d:00", h));
                chip.setCheckedIconVisible(false);
                chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                chip.setChipStartPadding(0);
                chip.setChipEndPadding(0);
                chip.setTextStartPadding(dp(6));
                chip.setTextEndPadding(dp(6));
                chip.setChecked(now.contains(String.valueOf(h)));
                chips.add(chip);
                // Each chip its own width, in the middle of its cell (a chip can't centre a
                // stretched label).
                GridLayout.LayoutParams cell = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED, GridLayout.CENTER, 1f));
                grid.addView(chip, cell);
            }
            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle("Hours to play")
                    .setView(scroll)
                    .setPositiveButton("Done", (d, w) -> {
                        List<String> picked = new ArrayList<>();
                        for (int h = 0; h < 24; h++) {
                            if (chips.get(h).isChecked()) picked.add(String.valueOf(h));
                        }
                        changed(path, new JSONArray(picked));
                        summary.setText(hours(picked));
                        bar.set(picked);
                    })
                    .setNeutralButton("All day", (d, w) -> {
                        List<String> all = new ArrayList<>();
                        for (int h = 0; h < 24; h++) all.add(String.valueOf(h));
                        changed(path, new JSONArray(all));
                        summary.setText(hours(all));
                        bar.set(all);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(dp(wide), ViewGroup.LayoutParams.WRAP_CONTENT);
        });
        return both;
    }

    private String summary(List<String> chosen, List<String[]> options, boolean ordered) {
        if (chosen.isEmpty()) {
            return "None: tap to pick";
        }
        List<String> labels = new ArrayList<>();
        for (String key : chosen) {
            String label = key;
            for (String[] option : options) {
                if (option[0].equals(key)) label = option[1];
            }
            labels.add(ordered ? (labels.size() + 1) + ". " + label : label);
        }
        return TextUtils.join(", ", labels);
    }

    // ------------------------------------------------------------- saving

    String restored, restoredFor = "restore_settings";  // what a button said (and which), shown once the page is drawn again
    Runnable onReset;  // a group was reset (or a quick setup put in): the screen draws it again
    org.json.JSONArray presets = new org.json.JSONArray();  // the quick setups: [{name, about, values, changes}]

    /** True when every value a quick setup sets is already on the screen: nothing for it to do. */
    boolean inPlace(JSONObject given) {
        if (given == null || values == null) return false;
        for (Iterator<String> it = given.keys(); it.hasNext(); ) {
            String path = it.next();
            if (!String.valueOf(given.opt(path)).equals(String.valueOf(values.opt(path)))) return false;
        }
        return true;
    }

    /** "Reset this group": its settings back to what a new phone has, once the player says so. */
    private View resetButton(int page, int index) {
        MaterialButton reset = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        reset.setText("Reset this group");
        reset.setIconResource(R.drawable.ic_undo);
        LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        room.topMargin = dp(8);
        reset.setLayoutParams(room);
        String pageTitle = title(page), group = groupTitle(page, index);
        reset.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(getBaseContext())
                .setTitle("Reset " + group + "?")
                .setMessage("Every setting in " + group + " goes back to what COCFARM starts with. The other groups stay as they are.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> new Thread(() -> {
                    String defaults;
                    try {
                        defaults = Bridge.python(this).getModule("cocfarm_settings")
                                .callAttr("group_defaults", pageTitle, group).toString();
                    } catch (Throwable e) {
                        defaults = null;
                    }
                    String got = defaults;
                    main.post(() -> applyValues(got));
                }, "reset-group").start())
                .show());
        return reset;
    }

    /** Put these values ({path: value}, as the screen shows them) in, save, and draw the screen again. */
    void applyValues(String defaults) {
        if (defaults == null) {
            status("Couldn't change them", R.color.cf_bad);
            return;
        }
        try {
            JSONObject given = new JSONObject(defaults);
            for (java.util.Iterator<String> keys = given.keys(); keys.hasNext(); ) {
                String path = keys.next();
                values.put(path, given.get(path));
            }
        } catch (JSONException e) {
            return;
        }
        status("Saving…", R.color.cf_muted);
        main.removeCallbacks(save);
        save();
        if (onReset != null) onReset.run();
    }

    /** A switch's values go in, on the screen too (not counted as typing). */
    private void fill(JSONObject fills) {
        filling = true;
        for (Iterator<String> it = fills.keys(); it.hasNext(); ) {
            String path = it.next();
            String value = fills.optString(path);
            try {
                values.put(path, value);
            } catch (JSONException ignored) {
            }
            TextView edit = editOf.get(path);
            if (edit != null && !edit.getText().toString().equals(value)) edit.setText(value);
            NumberScroller scroller = scrollerOf.get(path);
            if (scroller != null) keep(path, scroller.show(value));
        }
        filling = false;
    }

    private void changed(String path, Object value) {
        boolean typed = !filling && !String.valueOf(values.opt(path)).equals(String.valueOf(value));
        try {
            values.put(path, value);
        } catch (JSONException e) {
            return;
        }
        String off = typed ? turnsOff.get(path) : null;
        if (off != null && values.optBoolean(off)) {
            MaterialSwitch toggle = switchOf.get(off);
            if (toggle != null && toggle.isAttachedToWindow()) {
                toggle.setChecked(false);  // (its listener saves it)
            } else {
                try {
                    values.put(off, false);
                } catch (JSONException ignored) {
                }
            }
        }
        Runnable follow = scrollBy.get(path);
        if (follow != null) follow.run();
        refreshDependents();
        status("Saving…", R.color.cf_muted);
        pending = true;
        main.removeCallbacks(save);
        main.postDelayed(save, 700);
    }

    private void save() {
        pending = false;
        String form = values.toString();
        new Thread(() -> {
            String result;
            try {
                result = Bridge.python(this).getModule("cocfarm_settings").callAttr("save", folder, form).toString();
            } catch (Throwable e) {  // e.g. the file can't be written: say so rather than close the app
                result = "Couldn't save: " + e.getMessage();
            }
            String shown = result;
            main.post(() -> showErrors(shown));
        }, "save-settings").start();
    }

    private void showErrors(String result) {
        try {
            JSONObject problems = new JSONObject(result);
            for (Consumer<String> error : errors.values()) {
                error.accept(null);
            }
            int shown = 0;
            for (Iterator<String> it = problems.keys(); it.hasNext(); ) {
                String path = it.next();
                Consumer<String> error = errors.get(path);
                if (error != null) {
                    error.accept(problems.optString(path));
                    shown++;
                }
            }
            if (problems.length() == 0) {
                status("✓ Saved", R.color.cf_good);
            } else {
                status("Not saved: fix the setting in red" + (shown < problems.length() ? " (on another tab)" : ""),
                        R.color.cf_bad);
            }
        } catch (JSONException e) {
            status(result, R.color.cf_bad);
        }
    }

    private void status(String text, int colour) {
        statusLine.setText(text);
        statusLine.setTextColor(getColor(colour));
        statusLine.setVisibility(View.VISIBLE);
    }

    private void refreshDependents() {
        for (View view : dependents) {
            boolean on = wanted(view, 0);
            if (boxOf.containsKey(dependsOn.get(view).optString("path"))) {  // what it needs is here: hidden while off
                int shown = on ? View.VISIBLE : View.GONE;
                view.setAlpha(1f);
                enable(view, true);
                view.setVisibility(shown);
                View divider = dividerOf.get(view);
                if (divider != null) divider.setVisibility(shown);
            } else {
                view.animate().alpha(on ? 1f : 0.38f).setDuration(150).start();
                enable(view, on);
            }
        }
    }

    /** Whether a line's setting is wanted: what it needs is set (and is itself wanted). */
    private boolean wanted(View view, int depth) {
        JSONObject depends = dependsOn.get(view);
        if (depends == null) return true;
        Object want = depends.opt("is"), have = values.opt(depends.optString("path"));
        boolean on;
        if (want instanceof JSONArray) {
            on = false;
            for (int i = 0; i < ((JSONArray) want).length(); i++) {
                on |= String.valueOf(((JSONArray) want).opt(i)).equals(String.valueOf(have));
            }
        } else {
            on = String.valueOf(want).equals(String.valueOf(have));
        }
        View parent = boxOf.get(depends.optString("path"));
        return on && (parent == null || depth > 8 || wanted(parent, depth + 1));
    }

    private void enable(View view, boolean on) {
        view.setEnabled(on);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                enable(group.getChildAt(i), on);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private List<String> list(String path) {
        List<String> out = new ArrayList<>();
        JSONArray array = values.optJSONArray(path);
        for (int i = 0; array != null && i < array.length(); i++) {
            out.add(array.optString(i));
        }
        return out;
    }

    /** Hours as spans: "All day", or "08:00–16:59, 20:00–23:59". */
    private static String hours(List<String> chosen) {
        if (chosen.isEmpty()) {
            return "None: tap to pick";
        }
        boolean[] on = new boolean[24];
        for (String hour : chosen) {
            on[Integer.parseInt(hour)] = true;
        }
        List<String> spans = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (!on[h]) continue;
            int end = h;
            while (end + 1 < 24 && on[end + 1]) end++;
            spans.add(String.format("%02d:00–%02d:59", h, end));
            h = end;
        }
        return spans.size() == 1 && chosen.size() == 24 ? "All day" : TextUtils.join(", ", spans);
    }

    /** An item's options, in order: [value, label] pairs. */
    private static List<String[]> options(JSONObject item) {
        List<String[]> out = new ArrayList<>();
        JSONArray pairs = item.optJSONArray("options");
        for (int i = 0; pairs != null && i < pairs.length(); i++) {
            JSONArray pair = pairs.optJSONArray(i);
            out.add(new String[]{pair.optString(0), pair.optString(1)});
        }
        return out;
    }

    /** A number's scroller (Python's, for this Town Hall); the goal's for the resource picked. Null: typed. */
    private JSONObject scrollFor(JSONObject item) {
        String kind = item.optString("kind");
        if (!kind.equals("number") && !kind.equals("amount")) return null;
        JSONObject scrolls = item.optJSONObject("scrolls");
        if (scrolls != null) {
            JSONObject now = scrolls.optJSONObject(values.optString(item.optString("scroll_by")));
            return now != null ? now : scrolls.optJSONObject("gold");
        }
        return item.optJSONObject("scroll");
    }

    /** A saved value outside its scroller's range is kept as the nearest end it shows: saved as that
     * with the next change (opening Settings changes nothing on its own). */
    private void keep(String path, Object snapped) {
        if (snapped == null) return;
        try {
            values.put(path, snapped);
        } catch (JSONException ignored) {
        }
    }

    private TextView text(String value, int appearance, int colour) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextAppearance(appearance);
        view.setTextColor(getColor(colour));
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
