package com.cocfarm.app;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * COCFARM's one screen: the logo and tabs along the top. The Dashboard tab
 * is the Windows app's Dashboard: what the bot is doing with
 * Start, Pause and Stop; a problem that stopped it, with a next step; a
 * newer COCFARM; what this run has done (its numbers, the last base scouted,
 * the builder base); what's left to set up. The other tabs are the settings,
 * page by page (SettingsForm), the History (HistoryPage) and the log (LogPage).
 */
public class MainActivity extends AppCompatActivity {
    private static final long POLL_MS = 1000;
    private static final long LAUNCH_MS = 3000;  // the launch screen shows this long at most
    private static final String[] RESOURCES = {"gold", "elixir", "dark"};
    private static final int[] RESOURCE_COLOURS = {R.color.cf_gold, R.color.cf_elixir, R.color.cf_dark};
    private static final String[] RESOURCE_NAMES = {"Gold", "Elixir", "Dark elixir"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService python = Executors.newSingleThreadExecutor();  // one Python call at a time
    private TextView state, status, detail, version, lastAttack, builderLast;
    private MaterialButton start, pause, stop;
    private View controls, setup, session, base, builder, problemCard, updateCard;
    private LinearLayout steps;
    private ImageView basePicture;
    private TextView verdict;
    private View[] counts, gains, loot, builderTiles;
    private String folder;
    private List<String> missingPictures;  // null until checked
    private JSONObject snapshot = new JSONObject();
    private long pictureStamp;
    private boolean polling, asking;
    private volatile boolean ready;  // Python has started: the launch screen can go
    // Python has started in this process (for a screen made again, turned sideways say).
    private static volatile boolean pythonStarted;
    private LaunchView launch;  // COCFARM's own launch screen, after Android's, until Python has started
    private boolean launchLeft;
    // How much of Android's launch-screen icon view the crest fills. drawable/splash_logo is 32% in
    // on each side (0.36 of it), but Android draws the icon larger than its view, and not by a fixed
    // factor: measured on recordings of the hand-off (Android 16, crest matched against the picture),
    // 0.536 at 32% in (0.83 at 18%). Change it with splash_logo's inset, and measure again.
    private static final float SPLASH_CREST = 0.536f;
    private long launchedAt;
    private String updateVersion, updatePage, pendingInstall, stepsShown;
    private NavigationBarView nav;
    private FrameLayout pages;
    private View dashboard;
    private TextView saved;
    private SettingsForm settings;
    private boolean settingsLoaded;
    private String settingsProblem;  // why the settings couldn't be read
    private HistoryPage history;
    private LogPage log;
    private String shown = "home";  // home, settings, history or log
    private int settingsPage = -1;  // the group of settings open (page * 100 + group), or -1: the list of them
    private int listScroll;  // how far down the settings list was, coming back to it
    private ActivityResultLauncher<Intent> capture;
    private ActivityResultLauncher<String> notifications;
    private ActivityResultLauncher<String> backupTo;  // "Back up settings": where the file goes
    private ActivityResultLauncher<String[]> restoreFrom;  // "Restore settings": the file to read
    private ActivityResultLauncher<Intent> tour;  // the welcome tour: its choices are read again after
    private java.util.function.BiConsumer<String, Integer> backupAnswer = (text, tone) -> { };
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            askSnapshot();
            if (polling) {
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        // Android's launch screen (the crest on the dark background) hands over at once to
        // COCFARM's own (LaunchView: the crest in a turning ring), which stays until Python has
        // started, or LAUNCH_MS at most; then the crest flies into the top bar's logo and the
        // Dashboard rises into place. A screen made again before Python has started (opened from
        // an upright home screen into a sideways phone) gets COCFARM's too; with animations turned
        // off in Android's settings, Android's stays until then instead, as before.
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        long launched = SystemClock.uptimeMillis();
        launchedAt = launched;
        boolean own = !pythonStarted && animationsOn();
        splash.setKeepOnScreenCondition(() -> !own && !ready && SystemClock.uptimeMillis() - launched < LAUNCH_MS);
        splash.setOnExitAnimationListener(this::leaveSplash);
        super.onCreate(saved);
        if (saved != null) {  // turned sideways or back: the same page
            shown = saved.getString("shown", "home");
            settingsPage = saved.getInt("settingsPage", -1);
        }
        setContentView(R.layout.activity_main);
        edgeToEdge();
        if (own) {
            launch = new LaunchView(this);
            ((ViewGroup) getWindow().getDecorView()).addView(launch,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            main.postDelayed(this::leaveLaunch, LAUNCH_MS);  // Python slow to start: show the app anyway
        }
        folder = getExternalFilesDir(null).getAbsolutePath();
        if (saved == null) {
            // A new install gets the welcome tour, as on Windows. This is the first Python call, so
            // it looks for the settings file before anything else writes one.
            python.execute(() -> {
                boolean due = false;
                try {
                    due = Bridge.python(this).getModule("cocfarm_settings").callAttr("tour_due", folder).toBoolean();
                } catch (Exception e) {
                    android.util.Log.w("COCFARM", "tour check failed", e);
                }
                if (due) {
                    main.post(this::showTour);
                }
            });
        }
        state = findViewById(R.id.state);
        status = findViewById(R.id.status);
        detail = findViewById(R.id.detail);
        version = findViewById(R.id.version);
        start = findViewById(R.id.start);
        pause = findViewById(R.id.pause);
        stop = findViewById(R.id.stop);
        controls = findViewById(R.id.controls);
        setup = findViewById(R.id.setup);
        steps = findViewById(R.id.steps);
        session = findViewById(R.id.session);
        base = findViewById(R.id.base);
        builder = findViewById(R.id.builder);
        problemCard = findViewById(R.id.problem);
        updateCard = findViewById(R.id.update);
        basePicture = findViewById(R.id.base_picture);
        verdict = findViewById(R.id.verdict);
        lastAttack = findViewById(R.id.last_attack);
        builderLast = findViewById(R.id.builder_last);
        ((TextView) findViewById(R.id.tip)).setCompoundDrawablesRelative(
                tinted(R.drawable.ic_info, R.color.cf_muted, 18), null, null, null);
        ((TextView) findViewById(R.id.problem_title)).setCompoundDrawablesRelative(
                tinted(R.drawable.ic_warning, R.color.cf_bad, 22), null, null, null);

        counts = tiles(findViewById(R.id.counts), "Bases looked at", "Attacks", "Running for");
        gains = tiles(findViewById(R.id.gains), "Gold gained", "Elixir gained", "Dark gained");
        loot = tiles(findViewById(R.id.loot), RESOURCE_NAMES);
        builderTiles = tiles(findViewById(R.id.builder_tiles), "Battles", "Gold", "Elixir");
        for (int i = 0; i < 3; i++) {
            label(gains[i]).setCompoundDrawablesRelative(tinted(R.drawable.dot, RESOURCE_COLOURS[i], 8), null, null, null);
            label(loot[i]).setCompoundDrawablesRelative(tinted(R.drawable.dot, RESOURCE_COLOURS[i], 8), null, null, null);
        }

        capture = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                Ui.snack(start, "COCFARM needs to see the screen to play: press Start and allow it.",
                        Snackbar.LENGTH_LONG).show();
                return;
            }
            Intent service = new Intent(this, BotService.class).setAction(BotService.ACTION_START)
                    .putExtra(BotService.EXTRA_RESULT, result.getResultCode()).putExtra(BotService.EXTRA_DATA, result.getData());
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Bridge.status("Starting…");
        });
        notifications = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> refresh());
        backupTo = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::writeBackup);
        restoreFrom = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::readBackup);
        tour = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            // The tour saved over the settings file: read it again, or the next change made here
            // would save the screen's older values over the tour's.
            python.execute(() -> {
                try {
                    settings.load();
                } catch (Exception e) {
                    android.util.Log.e("COCFARM", "The settings didn't load", e);
                    return;
                }
                main.post(() -> {
                    if (settingsLoaded && shown.equals("settings")) render(false);
                });
            });
        });

        start.setOnClickListener(v -> askToCapture());
        pause.setOnClickListener(v -> Bridge.togglePause("Home"));
        stop.setOnClickListener(v -> Bridge.stop(this));
        findViewById(R.id.base_card).setOnClickListener(v -> startActivity(new Intent(this, SeenActivity.class)));
        findViewById(R.id.problem_more).setOnClickListener(v -> {
            View details = findViewById(R.id.problem_details);
            details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        fitProblemButtons();
        findViewById(R.id.update_get).setOnClickListener(v -> downloadUpdate());
        findViewById(R.id.update_notes).setOnClickListener(v -> {
            if (updatePage != null && !updatePage.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(updatePage)));
            }
        });
        buildLinks();
        nav = findViewById(R.id.nav);  // (its kind says how wide the pages are)
        sizeRailLogo();
        layoutDashboard();
        buildNav();
        startFromTile(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startFromTile(intent);
    }

    /** The quick settings tile was tapped: start, as the Start button does (Android asks first). */
    private void startFromTile(Intent intent) {
        if (intent == null || !FarmTile.ACTION_START.equals(intent.getAction())) {
            return;
        }
        intent.setAction(null);  // once: not again on a rotation
        if (BotService.instance == null) {
            start.post(this::askToCapture);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("shown", shown);
        out.putInt("settingsPage", settingsPage);
    }

    // ------------------------------------------------------------- launch

    private void leaveSplash(SplashScreenViewProvider splash) {
        // Opened again while COCFARM was farming in the background (its process alive, this
        // screen gone), Android 12+ shows a launch screen with no logo, and getIconView()
        // throws: that crash took the farming down with it (live, on the emulator). The
        // screen then just fades.
        View icon;
        try {
            icon = splash.getIconView();
        } catch (RuntimeException noLogo) {
            icon = null;
        }
        View screen = splash.getView();
        if (launch != null && icon != null && icon.getWidth() > 0) {
            // COCFARM's launch screen is under it, its crest put where Android's is: Android's
            // fades off it quickly, and nothing seems to move.
            int[] at = new int[2], mine = new int[2];
            icon.getLocationInWindow(at);
            launch.getLocationInWindow(mine);
            launch.startAt(at[0] - mine[0] + icon.getWidth() / 2f, at[1] - mine[1] + icon.getHeight() / 2f,
                    icon.getWidth() * SPLASH_CREST);
            screen.animate().alpha(0f).setDuration(150).withEndAction(splash::remove).start();
            return;
        }
        if (launch != null) {
            // Android's launch screen has no crest (a task Android put back after it closed
            // COCFARM, say): it fades, and COCFARM's own stays, its crest fading in.
            screen.animate().alpha(0f).setDuration(200).withEndAction(splash::remove).start();
            return;
        }
        AnimatorSet out = new AnimatorSet();
        if (icon != null) {
            out.playTogether(
                    ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.5f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.5f),
                    ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(screen, View.ALPHA, 1f, 0f));
        } else {
            out.play(ObjectAnimator.ofFloat(screen, View.ALPHA, 1f, 0f));
        }
        out.setDuration(420);
        out.setInterpolator(new AccelerateInterpolator(1.5f));
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                splash.remove();
            }
        });
        out.start();
        rise();
    }

    /** Python has started (or LAUNCH_MS went by): COCFARM's launch screen leaves, once. */
    private void leaveLaunch() {
        if (launch == null || launchLeft) return;
        launchLeft = true;
        LaunchView going = launch;
        going.leave(logoView(), () -> {
            if (launch == going) launch = null;
            reportFullyDrawn();
        });
        rise();
    }

    /**
     * The side rail drops its header's own size (rail_header.xml's 40 dp) and shows the crest at
     * its picture's pixel size: 192 dp tall on a 2x phone, which pushed History and Log off a short
     * screen held sideways (360 dp tall), where they couldn't be reached. Set it here.
     */
    private void sizeRailLogo() {
        if (!(nav instanceof NavigationRailView)) return;
        View header = ((NavigationRailView) nav).getHeaderView();
        if (header == null) return;
        ViewGroup.LayoutParams size = header.getLayoutParams();
        if (size == null) return;
        size.width = dp(40);
        size.height = dp(40);
        if (size instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) size).topMargin = dp(4);
            ((ViewGroup.MarginLayoutParams) size).bottomMargin = dp(8);
        }
        header.setLayoutParams(size);
        if (header instanceof ImageView) ((ImageView) header).setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Still too short for the logo and the four places (a 720x1600 phone sideways has 284 dp
        // for the rail): the places matter, the logo goes.
        nav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            View last = nav.findViewById(R.id.nav_log);
            if (last == null || header.getVisibility() != View.VISIBLE) return;
            int[] at = new int[2], rail = new int[2];
            last.getLocationInWindow(at);
            nav.getLocationInWindow(rail);
            if (at[1] + last.getHeight() > rail[1] + nav.getHeight()) {
                header.setVisibility(View.GONE);
                nav.post(nav::requestLayout);
            }
        });
    }

    /** The top bar's logo (upright), or the side rail's (sideways): where the crest lands. */
    private View logoView() {
        View logo = findViewById(R.id.logo);
        if (logo == null && findViewById(R.id.top) instanceof ImageView) logo = findViewById(R.id.top);
        return logo;
    }

    /** False when Android's settings turn animations off (Developer options, or Remove animations). */
    private boolean animationsOn() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    /** The Dashboard's cards float up into place, one after another. */
    private void rise() {
        float lift = getResources().getDisplayMetrics().density * 28;
        int risen = 0;
        for (int column : new int[]{R.id.column, R.id.column2}) {
            ViewGroup cards = findViewById(column);
            for (int i = 0; i < cards.getChildCount(); i++) {
                View child = cards.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                child.setAlpha(0f);
                child.setTranslationY(lift);
                child.animate().alpha(1f).translationY(0f).setStartDelay(120 + 60L * risen++).setDuration(380)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
        }
        View logo = logoView();
        for (View bar : new View[]{nav, findViewById(R.id.top)}) {
            // (Sideways, the top is the rail's logo itself: the launch screen's crest lands on it
            // and shows it, so it isn't faded in under the crest as well.)
            if (bar == null || bar == logo) continue;
            bar.setAlpha(0f);
            bar.animate().alpha(1f).setStartDelay(150).setDuration(400).start();
        }
    }

    // ------------------------------------------------------------- where to go

    /** Home, Settings, History, Log: along the bottom upright, down the side sideways. */
    private void buildNav() {
        pages = findViewById(R.id.pages);
        if (dashboard == null) dashboard = findViewById(R.id.dashboard);
        saved = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_LabelMedium, R.color.cf_muted);
        saved.setVisibility(View.GONE);
        settings = new SettingsForm(this, folder, saved);
        settings.onReset = () -> render(false);
        nav.setOnItemSelectedListener(item -> {
            go(destination(item.getItemId()));
            return true;
        });
        nav.setOnItemReselectedListener(item -> {
            if (shown.equals("settings") && settingsPage >= 0) {
                openSettings(-1);  // Settings again, on a page: back to the list of pages
            } else if (shown.equals("home") && dashboard instanceof NestedScrollView) {
                ((NestedScrollView) dashboard).smoothScrollTo(0, 0);
            }
        });
        // The settings, once Python has read them.
        python.execute(() -> {
            String problem = null;
            try {
                settings.load();
            } catch (Exception e) {
                android.util.Log.e("COCFARM", "The settings didn't load", e);
                problem = e.getMessage();
            }
            String why = problem;
            main.post(() -> {
                settingsLoaded = why == null;
                settingsProblem = why;
                if (shown.equals("settings")) render(false);
            });
        });
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (shown.equals("settings") && settingsPage >= 0) {  // back: a settings page, to the list
                    openSettings(-1);
                } else if (!shown.equals("home")) {  // then Home
                    nav.setSelectedItemId(R.id.nav_home);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        if (!shown.equals("home")) {  // turned sideways or back: the page that was shown
            String again = shown;
            shown = "home";
            nav.setSelectedItemId(itemFor(again));
        }
    }

    private static String destination(int item) {
        if (item == R.id.nav_settings) return "settings";
        if (item == R.id.nav_history) return "history";
        if (item == R.id.nav_log) return "log";
        return "home";
    }

    private static int itemFor(String destination) {
        switch (destination) {
            case "settings":
                return R.id.nav_settings;
            case "history":
                return R.id.nav_history;
            case "log":
                return R.id.nav_log;
            default:
                return R.id.nav_home;
        }
    }

    private void go(String destination) {
        leave(shown);
        shown = destination;
        render(true);
    }

    /** Show the page picked; moved: it was just picked, so it comes in moving (never on a refresh). */
    private void render(boolean moved) {
        for (int i = pages.getChildCount() - 1; i >= 0; i--) {
            if (pages.getChildAt(i) != dashboard) pages.removeViewAt(i);
        }
        dashboard.setVisibility(shown.equals("home") ? View.VISIBLE : View.GONE);
        saved.setVisibility(View.GONE);
        View top = findViewById(R.id.top);  // the logo bar (upright): a settings page has its own bar
        if (top != null && !(nav instanceof NavigationRailView)) {
            top.setVisibility(shown.equals("settings") && settingsPage >= 0 && settingsLoaded ? View.GONE : View.VISIBLE);
        }
        View page = dashboard;
        if (shown.equals("settings")) {
            page = settingsPage >= 0 && settingsLoaded ? settingsPage() : settingsList();
            pages.addView(page);
        } else if (shown.equals("history")) {
            if (history == null) history = new HistoryPage(this, folder, pageWidthDp() >= 680);
            page = history.view;
            pages.addView(page);
            history.refresh();
        } else if (shown.equals("log")) {
            if (log == null) log = new LogPage(this, folder);
            page = log.view;
            pages.addView(page);
            log.start();
        }
        if (moved) enter(page);
    }

    /** A page coming in: it fades in as it rises a little. */
    private void enter(View page) {
        page.setAlpha(0f);
        page.setTranslationY(dp(14));
        page.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void openSettings(int page) {
        settings.flush();
        if (page < 0 && settingsPage < 0) listScroll = 0;  // the list again from its top
        settingsPage = page;
        render(true);
    }

    private void leave(String destination) {
        if (destination.equals("settings")) {
            settings.flush();
            listScroll = 0;
            settingsQuery = "";  // a search lasts while Settings is open
        } else if (destination.equals("log") && log != null) {
            log.stop();
        }
    }

    /** The width the pages have, in dp (the side rail's taken off). */
    private int pageWidthDp() {
        int width = getResources().getConfiguration().screenWidthDp;
        return nav instanceof NavigationRailView ? width - 80 : width;
    }

    // ------------------------------------------------------------- settings

    /** Every group of settings as a small card, under its page's name: one tap from any of
     * them, and each opens on its own (short) page. Two to four across, by width. */
    private org.json.JSONObject tileStates;  // the settings tiles' labels, made as the list is

    private View settingsList() {
        NestedScrollView scroll = Ui.column(this);
        LinearLayout content = Ui.inside(scroll);
        content.addView(heading("Settings", "Changes save by themselves; a running bot gets them straight away."
                + " A gold dot marks a group changed from the defaults."));
        if (!settingsLoaded) {
            TextView note = Ui.text(this, settingsProblem == null ? "Loading…"
                            : "The settings couldn't be read: " + settingsProblem,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                    settingsProblem == null ? R.color.cf_muted : R.color.cf_bad);
            note.setPadding(dp(4), dp(12), dp(4), 0);
            content.addView(note);
            return scroll;
        }
        int columns = Math.max(2, Math.min(4, pageWidthDp() / 210));
        tileStates = settings.groupStates();
        LinearLayout results = new LinearLayout(this), tiles = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        tiles.setOrientation(LinearLayout.VERTICAL);
        content.addView(searchField(results, tiles, scroll));
        content.addView(results);
        content.addView(tiles);
        content = tiles;
        if (settings.presets.length() > 0) content.addView(quickSetups());
        boolean rise = listScroll == 0 && settingsQuery.isEmpty();  // first time in: the cards come in one after another
        int shown = 0;
        java.util.List<View> cards = new java.util.ArrayList<>();
        sameHeight(tiles, cards);
        for (int page = 0; page < settings.pageCount(); page++) {
            content.addView(section(page));
            LinearLayout row = null;
            int groups = settings.groupCount(page);
            for (int g = 0; g < groups; g++) {
                if (g % columns == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setBaselineAligned(false);
                    // Every row is the columns' width; a last row with fewer cards leaves the rest
                    // empty. (It was filled with blank views, and such a row came out 0 tall: the
                    // last card vanished, the COCFARM group's updates, backup and tour with it.)
                    row.setWeightSum(columns);
                    // Every card has 5dp each side, the row reaching 5dp past the column's edges.
                    LinearLayout.LayoutParams across = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    across.setMargins(-dp(5), 0, -dp(5), 0);
                    content.addView(row, across);
                }
                View card = groupCard(page, g, scroll);
                cards.add(card);
                LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                room.setMargins(dp(5), dp(5), dp(5), dp(5));
                row.addView(card, room);
                if (rise) {
                    card.setAlpha(0f);
                    card.setTranslationY(dp(14));
                    card.animate().alpha(1f).translationY(0f).setStartDelay(25L * Math.min(shown, 14)).setDuration(240)
                            .setInterpolator(new DecelerateInterpolator(2f)).start();
                }
                shown++;
            }
            int missing = groups % columns == 0 ? 0 : columns - groups % columns;
            if (row != null && missing > 0) {
                // A short last row: a weight shares out what the cards' own margins leave, so the
                // row gives up the missing cards' margins too, or its cards come out wider.
                LinearLayout.LayoutParams across = (LinearLayout.LayoutParams) row.getLayoutParams();
                across.setMarginStart(-dp(5));  // (both: one alone resolves the other side to 0)
                across.setMarginEnd(-dp(5) + missing * dp(10));
            }
        }
        showResults(results, tiles, scroll);
        if (!rise) {  // back from a group: where the list was
            int y = listScroll;
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        return scroll;
    }

    void backUpSettings(java.util.function.BiConsumer<String, Integer> say) {
        backupAnswer = say;
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
        try {
            backupTo.launch("cocfarm-settings-" + day + ".json");
        } catch (android.content.ActivityNotFoundException e) {
            say.accept("✗ This phone has no app to save files with.", R.color.cf_bad);
        }
    }

    void restoreSettings(java.util.function.BiConsumer<String, Integer> say) {
        backupAnswer = say;
        try {
            restoreFrom.launch(new String[]{"application/json", "application/octet-stream", "text/plain"});
        } catch (android.content.ActivityNotFoundException e) {
            say.accept("✗ This phone has no app to open files with.", R.color.cf_bad);
        }
    }

    /** "Copy settings between accounts": from which, to which (or all the others), then a yes. */
    void copyAccountSettings(java.util.function.BiConsumer<String, Integer> say) {
        python.execute(() -> {
            JSONObject seen;
            try {
                seen = new JSONObject(Bridge.python(this).getModule("cocfarm_settings").callAttr("accounts", folder).toString());
            } catch (Exception e) {
                main.post(() -> say.accept("✗ " + e.getMessage(), R.color.cf_bad));
                return;
            }
            main.post(() -> {
                JSONArray list = seen.optJSONArray("names");
                String active = seen.optString("active");
                int count = list == null ? 0 : list.length();
                if (count < 2) {
                    say.accept("COCFARM has seen " + (count == 0 ? "no accounts" : "one account") + " so far. It learns "
                            + "each one the first time it plays it, by the name at the top left.", R.color.cf_text);
                    return;
                }
                String[] names = new String[count], shown = new String[count];
                for (int i = 0; i < count; i++) {
                    names[i] = list.optString(i);
                    shown[i] = names[i] + (names[i].equals(active) ? " (playing now)" : "");
                }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Copy the settings of")
                        .setItems(shown, (d, from) -> pickTargets(names, from, active, say))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void pickTargets(String[] names, int from, String active, java.util.function.BiConsumer<String, Integer> say) {
        java.util.List<String> others = new java.util.ArrayList<>();
        for (String name : names) if (!name.equals(names[from])) others.add(name);
        String[] choices = new String[others.size() + (others.size() > 1 ? 1 : 0)];
        for (int i = 0; i < others.size(); i++) {
            choices[i] = others.get(i) + (others.get(i).equals(active) ? " (playing now)" : "");
        }
        if (others.size() > 1) choices[others.size()] = "All the others";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(names[from] + "'s settings, to")
                .setItems(choices, (d, to) -> {
                    java.util.List<String> targets = to < others.size()
                            ? java.util.Collections.singletonList(others.get(to)) : others;
                    String who = targets.size() == 1 ? targets.get(0) : "all " + targets.size() + " other accounts";
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Copy to " + who + "?")
                            .setMessage(who + " get" + (targets.size() == 1 ? "s " : " ") + names[from]
                                    + "'s settings in place of " + (targets.size() == 1 ? "its" : "their")
                                    + " own. Storage sizes stay as they are.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Copy", (d2, w) -> copyAccounts(names[from], targets, active, say))
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyAccounts(String source, java.util.List<String> targets, String active,
                              java.util.function.BiConsumer<String, Integer> say) {
        say.accept("Working…", R.color.cf_muted);
        String list = new JSONArray(targets).toString();
        python.execute(() -> {
            String said;
            try {
                said = Bridge.python(this).getModule("cocfarm_settings").callAttr("copy_account", folder, source, list).toString();
                if (said.startsWith("✓") && targets.contains(active)) settings.load();  // the screen shows that account's
            } catch (Exception e) {
                said = "✗ " + e.getMessage();
            }
            String text = said;
            boolean good = said.startsWith("✓");
            main.post(() -> {
                if (good && targets.contains(active)) {
                    settings.restored = text;  // drawn again with the new values: it says it there
                    settings.restoredFor = "copy_account_settings";
                    render(false);
                } else {
                    say.accept(text, good ? R.color.cf_good : R.color.cf_bad);
                }
            });
        });
    }

    private void writeBackup(Uri to) {
        if (to == null) return;  // backed out of the picker
        backupAnswer.accept("Working…", R.color.cf_muted);
        python.execute(() -> {
            String said;
            int tone;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(to, "wt")) {
                String text = Bridge.python(this).getModule("cocfarm_settings").callAttr("backup", folder).toString();
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                said = "✓ Saved. Restore settings puts them back, here or on another phone.";
                tone = R.color.cf_good;
            } catch (Exception e) {
                said = "✗ Couldn't save it: " + e.getMessage();
                tone = R.color.cf_bad;
            }
            String text = said;
            int colour = tone;
            main.post(() -> backupAnswer.accept(text, colour));
        });
    }

    private void readBackup(Uri from) {
        if (from == null) return;
        backupAnswer.accept("Working…", R.color.cf_muted);
        python.execute(() -> {
            String said;
            try (java.io.InputStream in = getContentResolver().openInputStream(from)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0 && bytes.size() < 2_000_000; ) bytes.write(chunk, 0, n);
                said = Bridge.python(this).getModule("cocfarm_settings").callAttr("restore", folder,
                        bytes.toString("UTF-8")).toString();
                if (said.startsWith("✓")) settings.load();  // the screen shows what's saved now
            } catch (Exception e) {
                said = "✗ Couldn't read it: " + e.getMessage();
            }
            String text = said;
            boolean good = said.startsWith("✓");
            main.post(() -> {
                if (good) {
                    settings.restored = text;  // the page is drawn again with the new values: it says this there
                    settings.restoredFor = "restore_settings";
                    render(false);
                } else {
                    backupAnswer.accept(text, R.color.cf_bad);
                }
            });
        });
    }

    /** "Quick setups": one tap sets the few settings a common way of playing needs (after a yes). */
    private View quickSetups() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);
        TextView title = Ui.text(this, "Quick setups", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                R.color.cf_text);
        title.setPadding(dp(4), 0, dp(4), dp(2));
        box.addView(title);
        android.widget.HorizontalScrollView slide = new android.widget.HorizontalScrollView(this);
        slide.setHorizontalScrollBarEnabled(false);
        slide.setClipToPadding(false);
        com.google.android.material.chip.ChipGroup chips = new com.google.android.material.chip.ChipGroup(this);
        chips.setSingleLine(true);
        chips.setChipSpacingHorizontal(dp(8));
        int shownCount = 0;
        for (int i = 0; i < settings.presets.length(); i++) {
            org.json.JSONObject preset = settings.presets.optJSONObject(i);
            if (preset == null || settings.inPlace(preset.optJSONObject("values"))) continue;  // already set up
            shownCount++;
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
            chip.setText(preset.optString("name"));
            chip.setOnClickListener(v -> askPreset(preset));
            chips.addView(chip);
        }
        slide.addView(chips);
        box.addView(slide);
        if (shownCount == 0) box.setVisibility(View.GONE);
        return box;
    }

    /** The welcome tour, again (Settings → COCFARM), or a new install's first look. */
    void showTour() {
        tour.launch(new Intent(this, TourActivity.class));
    }

    private void askPreset(org.json.JSONObject preset) {
        org.json.JSONArray changes = preset.optJSONArray("changes");
        StringBuilder sets = new StringBuilder(preset.optString("about")).append("\n\nIt sets:");
        for (int i = 0; changes != null && i < changes.length(); i++) {
            sets.append("\n• ").append(changes.optString(i));
        }
        sets.append("\n\nEverything else stays as it is.");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(preset.optString("name") + "?")
                .setMessage(sets.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use it", (d, w) -> {
                    org.json.JSONObject values = preset.optJSONObject("values");
                    if (values != null) settings.applyValues(values.toString());
                })
                .show();
    }

    private String settingsQuery = "";  // what's typed in the settings' search box (kept while a pick is open)

    /** "Search settings": typing shows the settings that match in place of the tiles. */
    private View searchField(LinearLayout results, LinearLayout tiles, NestedScrollView scroll) {
        com.google.android.material.textfield.TextInputLayout layout = (com.google.android.material.textfield.TextInputLayout)
                getLayoutInflater().inflate(R.layout.field_text, null, false);
        layout.setHint("Search settings");
        layout.setStartIconDrawable(R.drawable.ic_search);
        layout.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT);
        TextView edit = layout.getEditText();
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setText(settingsQuery);
        edit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable s) {
                settingsQuery = s.toString();
                showResults(results, tiles, scroll);
            }
        });
        return layout;
    }

    private void showResults(LinearLayout results, LinearLayout tiles, NestedScrollView scroll) {
        results.removeAllViews();
        boolean searching = !settingsQuery.trim().isEmpty();
        tiles.setVisibility(searching ? View.GONE : View.VISIBLE);
        if (!searching) return;
        java.util.List<int[]> found = settings.search(settingsQuery, 40);
        if (found.isEmpty()) {
            TextView none = Ui.text(this, "No setting matches “" + settingsQuery.trim() + "”.",
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.cf_muted);
            none.setPadding(dp(4), dp(16), dp(4), 0);
            results.addView(none);
            return;
        }
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list);
        LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        room.topMargin = dp(10);
        results.addView(card, room);
        for (int[] at : found) {
            org.json.JSONObject item = settings.itemOf(at[0], at[1], at[2]);
            String title = item.optString("title");
            if (title.isEmpty()) title = settings.groupTitle(at[0], at[1]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundResource(ripple());
            row.addView(Ui.text(this, title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                    R.color.cf_text));
            TextView where = Ui.text(this, settings.title(at[0]) + " › " + settings.groupTitle(at[0], at[1]),
                    com.google.android.material.R.style.TextAppearance_Material3_LabelSmall, R.color.cf_accent);
            where.setPadding(0, dp(2), 0, 0);
            row.addView(where);
            String help = item.optString("help");
            if (!help.isEmpty()) {
                TextView about = Ui.text(this, help, com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                        R.color.cf_muted);
                about.setPadding(0, dp(2), 0, 0);  // all of it: never cut off with "…"
                row.addView(about);
            }
            String path = item.optString("path");
            int page = at[0], group = at[1];
            row.setOnClickListener(v -> {
                hideKeyboard(v);
                settings.highlight(path);
                listScroll = Math.max(1, scroll.getScrollY());
                openSettings(page * 100 + group);
            });
            if (list.getChildCount() > 0) {
                com.google.android.material.divider.MaterialDivider line = new com.google.android.material.divider.MaterialDivider(this);
                line.setDividerColor(getColor(R.color.cf_outline_variant));
                list.addView(line);
            }
            list.addView(row);
        }
    }

    private int ripple() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private void hideKeyboard(View from) {
        android.view.inputmethod.InputMethodManager keys = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keys != null) keys.hideSoftInputFromWindow(from.getWindowToken(), 0);
    }

    /** A page's name over its groups, with its icon. */
    private View section(int page) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(4), dp(18), dp(4), dp(4));
        ImageView icon = new ImageView(this);
        icon.setImageResource(pageIcon(settings.title(page)));
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.cf_accent)));
        line.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = Ui.text(this, settings.title(page),
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.cf_text);
        title.setPadding(dp(10), 0, 0, 0);
        line.addView(title);
        return line;
    }

    private View groupCard(int page, int group, NestedScrollView list) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout inside = new LinearLayout(this);
        inside.setOrientation(LinearLayout.VERTICAL);
        inside.setPadding(dp(14), dp(12), dp(12), dp(12));
        // A small dot before the name: something in the group isn't the default.
        // Top right: what the group is set to ("On", "3 of 6 on", "Auto switch"...).
        org.json.JSONArray label = tileStates == null ? null
                : tileStates.optJSONArray(settings.title(page) + "/" + settings.groupTitle(page, group));
        boolean changed = label != null && label.optBoolean(2);
        String text = label == null ? "" : label.optString(0);
        String tone = label == null ? "" : label.optString(1);
        if (label == null) {
            Boolean on = settings.groupOn(page, group);
            if (on != null) {
                text = on ? "On" : "Off";
                tone = on ? "on" : "off";
            }
        }
        // The state always in the top right corner, the name to its left and, if it's too long
        // for that line, on under it (never cut off with "…").
        TextView titleStyle = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                R.color.cf_text);
        TextView stateStyle = Ui.text(this, "", com.google.android.material.R.style.TextAppearance_Material3_LabelSmall,
                tone.equals("on") ? R.color.cf_good : tone.equals("off") ? R.color.cf_muted : R.color.cf_accent);
        inside.addView(new TileHead(this, titleStyle, stateStyle, settings.groupTitle(page, group), text, changed,
                getColor(R.color.cf_accent)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView about = Ui.text(this, "",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        about.setMaxLines(2);
        fitWhole(about, settings.groupSummary(page, group), 2);
        about.setPadding(0, dp(4), 0, 0);
        inside.addView(about);
        card.addView(inside);
        card.setOnClickListener(v -> {
            listScroll = Math.max(1, list.getScrollY());
            openSettings(page * 100 + group);
        });
        return card;
    }

    /**
     * Every settings tile as tall as the tallest one, so the tiles make an even grid (a name on two
     * lines made its row taller than the others). Each card's own height is measured again
     * whenever the list is laid out (a summary shortened to fit, turned sideways).
     */
    private void sameHeight(View list, java.util.List<View> cards) {
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int tallest = 0;
            for (View card : cards) {
                View inside = ((ViewGroup) card).getChildAt(0);
                if (inside == null || inside.getWidth() <= 0) continue;
                inside.measure(View.MeasureSpec.makeMeasureSpec(inside.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                tallest = Math.max(tallest, inside.getMeasuredHeight() + card.getPaddingTop() + card.getPaddingBottom());
            }
            if (tallest <= 0) return;
            boolean changed = false;
            for (View card : cards) {
                if (card.getMinimumHeight() != tallest) {
                    card.setMinimumHeight(tallest);
                    changed = true;
                }
            }
            if (changed) v.post(v::requestLayout);
        });
    }

    /**
     * Shows a list of names ("Gold · Elixir · Dark elixir") in at most {@code lines} lines, leaving
     * whole names out at the end, counted ("+3 more"), rather than cutting one off ("Dark eli…").
     */
    private static void fitWhole(TextView view, String joined, int lines) {
        // A no-break space before each "·" (a line never starts with one) and in "+3 more".
        String[] parts = joined.split(" · ");
        String sep = "\u00a0· ";
        String all = TextUtils.join(sep, parts);
        view.setText(all);
        view.post(() -> {
            int width = view.getWidth() - view.getTotalPaddingLeft() - view.getTotalPaddingRight();
            if (width <= 0) return;
            for (int keep = parts.length; keep >= 1; keep--) {
                String text = keep == parts.length ? all
                        : TextUtils.join(sep, java.util.Arrays.copyOf(parts, keep)) + sep + "+" + (parts.length - keep) + "\u00a0more";
                android.text.StaticLayout layout = android.text.StaticLayout.Builder
                        .obtain(text, 0, text.length(), view.getPaint(), width)
                        .setLineSpacing(view.getLineSpacingExtra(), view.getLineSpacingMultiplier())
                        .setIncludePad(view.getIncludeFontPadding())
                        .build();
                if (layout.getLineCount() <= lines) {
                    if (keep != parts.length) view.setText(text);
                    return;
                }
            }
            // Not even the first name fits (a long one, on a narrow tile): how many there are,
            // rather than a sentence cut off half way.
            view.setText(parts.length == 1 ? "1 setting" : parts.length + " settings");
        });
    }

    private static int pageIcon(String title) {
        String t = title.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("farm")) return R.drawable.ic_bolt;
        if (t.contains("builder")) return R.drawable.ic_build;
        if (t.contains("home") || t.contains("village")) return R.drawable.ic_village;
        if (t.contains("when") || t.contains("time") || t.contains("play")) return R.drawable.ic_schedule;
        if (t.contains("person") || t.contains("human")) return R.drawable.ic_person;
        if (t.contains("notif") || t.contains("alert")) return R.drawable.ic_notifications;
        return R.drawable.ic_tune;
    }

    /** One group of settings under a bar with the way back, its name (and its page's) and whether it's saved. */
    private View settingsPage() {
        int page = settingsPage / 100, group = settingsPage % 100;
        if (page >= settings.pageCount() || group >= settings.groupCount(page)) {
            page = 0;
            group = 0;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(16), 0);
        MaterialButton back = new MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle);
        back.setIconResource(R.drawable.ic_arrow_back);
        back.setContentDescription("Back to the settings");
        back.setOnClickListener(v -> openSettings(-1));
        bar.addView(back);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView over = Ui.text(this, settings.title(page),
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium, R.color.cf_muted);
        names.addView(over);
        TextView title = Ui.text(this, settings.groupTitle(page, group),
                com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, R.color.cf_text);
        title.setMaxLines(2);  // a long name goes on a second line rather than being cut off ("…")
        names.addView(title);
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (saved.getParent() != null) ((ViewGroup) saved.getParent()).removeView(saved);
        bar.addView(saved);
        bar.setMinimumHeight(dp(64));  // taller when the name takes two lines
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(settings.group(page, group, pageWidthDp() >= 720 ? 2 : 1),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    /** A page's title, and a line under it. */
    private View heading(String title, String about) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(16), dp(4), dp(8));
        box.addView(Ui.text(this, title, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, R.color.cf_text));
        TextView line = Ui.text(this, about, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.cf_muted);
        line.setPadding(0, dp(4), 0, 0);
        box.addView(line);
        return box;
    }

    // ------------------------------------------------------------- the Home screen's shape

    /** Two columns side by side when the screen is wide enough for two (a phone sideways, a tablet),
     * each scrolling by itself: the status, its controls and this session's numbers on the left (they
     * stay in sight), the rest
     * on the right. One column otherwise, the status first. */
    private void layoutDashboard() {
        if (pageWidthDp() < 680) {
            return;
        }
        NestedScrollView whole = findViewById(R.id.dashboard);
        LinearLayout columns = findViewById(R.id.columns);
        View left = findViewById(R.id.column), right = findViewById(R.id.column2);
        ViewGroup parent = (ViewGroup) whole.getParent();
        int at = parent.indexOfChild(whole);
        parent.removeView(whole);
        columns.removeAllViews();
        // This session's numbers go under the status, which otherwise sits alone over an empty
        // column while the right one runs on; they stay in sight with it.
        View numbers = session;  // (found before the dashboard came off the screen)
        ((ViewGroup) right).removeView(numbers);
        ((ViewGroup) left).addView(numbers);
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.HORIZONTAL);
        side.addView(scrolling(left, dp(16), dp(12), dp(8), 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        side.addView(scrolling(right, dp(8), 0, dp(16), -dp(8)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        parent.addView(side, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dashboard = side;
    }

    private NestedScrollView scrolling(View column, int start, int top, int end, int lift) {
        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPaddingRelative(start, top, end, dp(24));
        FrameLayout.LayoutParams spot = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spot.topMargin = lift;  // the right column's first heading level with the status card
        scroll.addView(column, spot);
        return scroll;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bridge.listener = text -> runOnUiThread(this::refresh);
        polling = true;
        main.post(poll);
        refresh();
        checkPictures();
        checkUpdate();
        if (pendingInstall != null && canInstall()) {
            install(pendingInstall);
        }
        if (shown.equals("log") && log != null) {
            log.start();
        }
    }

    @Override
    protected void onPause() {
        if (nav != null) leave(shown);
        Bridge.listener = null;
        polling = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Left before it went (the welcome tour opened over it, say): gone at once, so coming
        // back shows the app, not the end of an animation.
        if (launch != null) {
            launchLeft = true;
            launch.finishNow();
            launch = null;
        }
    }

    @Override
    protected void onDestroy() {
        python.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------- status

    private void askSnapshot() {
        if (asking) {
            return;
        }
        asking = true;
        python.execute(() -> {
            JSONObject got = null;
            try {
                got = new JSONObject(Bridge.python(this).getModule("cocfarm_android").callAttr("snapshot", folder).toString());
            } catch (Exception ignored) {
                // Python still starting: next time
            }
            JSONObject shown = got;
            main.post(() -> {
                asking = false;
                if (shown != null) {
                    snapshot = shown;
                    refresh();
                }
            });
        });
    }

    private void refresh() {
        boolean running = BotService.instance != null;
        String botState = snapshot.optString("state", "");
        JSONObject problem = snapshot.optJSONObject("problem");
        String label;
        int fore, back;
        if (!running) {
            boolean failed = problem != null && "problem".equals(botState);
            label = failed ? "Problem" : "Not farming";
            fore = failed ? R.color.cf_bad : R.color.cf_muted;
            back = failed ? R.color.cf_bad_container : R.color.cf_surface_highest;
        } else if (Bridge.stopping) {
            label = "Stopping";
            fore = R.color.cf_bad;
            back = R.color.cf_bad_container;
        } else if (Bridge.paused) {
            label = "Paused";
            fore = R.color.cf_warn;
            back = R.color.cf_warn_container;
        } else if ("attacking".equals(botState)) {
            label = "Attacking";
            fore = R.color.cf_attack;
            back = R.color.cf_attack_container;
        } else if ("waiting".equals(botState)) {
            label = "Waiting";
            fore = R.color.cf_info;
            back = R.color.cf_info_container;
        } else {
            label = "Farming";
            fore = R.color.cf_good;
            back = R.color.cf_good_container;
        }
        if (pill(state, label, fore, back)) {
            state.setCompoundDrawablesRelative(tinted(R.drawable.dot, fore, 8), null, null, null);
        }
        boolean live = running && !Bridge.stopping && !Bridge.paused;
        String headline = live && !snapshot.optString("headline").isEmpty() ? snapshot.optString("headline") : Bridge.lastStatus;
        if (!running && headline.equals(Bridge.READY)) {
            // Not ready while a step it can't farm without is left (Start would only say so).
            if (!Ui.gameInstalled(this)) {
                headline = "One step before farming: install Clash of Clans.";
            } else if (!accessibilityOn()) {
                headline = "One step before farming: let COCFARM tap in the game.";
            } else if (missingPictures != null && !missingPictures.isEmpty()) {
                headline = "One step before farming: add the button pictures.";
            }
        }
        show(status, headline);
        String why = live ? snapshot.optString("detail") : "";
        show(detail, why);
        detail.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);

        start.setVisibility(running ? View.GONE : View.VISIBLE);
        controls.setVisibility(running ? View.VISIBLE : View.GONE);
        pause.setText(Bridge.paused ? "Resume" : "Pause");
        pause.setIconResource(Bridge.paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pause.setEnabled(!Bridge.stopping);
        stop.setEnabled(!Bridge.stopping);
        showProblem(running ? null : problem);
        showSession();
        refreshSteps();
    }

    /**
     * The problem card's buttons in one row when they fit; on a narrow phone a long next step
     * ("Where do they go?") goes on a line of its own under Details and Report, instead of
     * squeezing into what's left of the row.
     */
    private void fitProblemButtons() {
        LinearLayout row = findViewById(R.id.problem_buttons);
        LinearLayout card = (LinearLayout) row.getParent();
        MaterialButton fix = findViewById(R.id.problem_fix);
        View[] buttons = {findViewById(R.id.problem_more), findViewById(R.id.problem_report), fix};
        ViewGroup.MarginLayoutParams inRow = (ViewGroup.MarginLayoutParams) fix.getLayoutParams();
        LinearLayout.LayoutParams alone = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alone.gravity = Gravity.END;
        alone.topMargin = dp(4);
        // On the column, which holds the button either way: a new label lays it out again.
        card.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int free = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight();
            if (free <= 0) return;
            int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int need = 0;
            for (View button : buttons) {
                if (button.getVisibility() == View.GONE) continue;
                button.measure(any, any);
                ViewGroup.MarginLayoutParams room = button == fix ? inRow
                        : (ViewGroup.MarginLayoutParams) button.getLayoutParams();
                need += button.getMeasuredWidth() + room.getMarginStart() + room.getMarginEnd();
            }
            boolean fits = need <= free;
            if (fits == (fix.getParent() == row)) return;
            row.post(() -> {  // not in the middle of a layout pass
                if (fits == (fix.getParent() == row)) return;
                ((ViewGroup) fix.getParent()).removeView(fix);
                if (fits) row.addView(fix, inRow);
                else card.addView(fix, card.indexOfChild(row) + 1, alone);
            });
        });
    }

    private void showProblem(JSONObject problem) {
        problemCard.setVisibility(problem == null ? View.GONE : View.VISIBLE);
        if (problem == null) {
            return;
        }
        ((TextView) findViewById(R.id.problem_title)).setText(problem.optString("title"));
        ((TextView) findViewById(R.id.problem_why)).setText(problem.optString("why"));
        ((TextView) findViewById(R.id.problem_details)).setText(problem.optString("details"));
        showSeen(problem.optString("picture"), problem.optString("seen"));
        String picture = problem.optString("picture");
        findViewById(R.id.problem_report).setOnClickListener(v -> report(picture));
        MaterialButton fix = findViewById(R.id.problem_fix);
        switch (problem.optString("action")) {
            case "pictures":
                fix.setText("Where do they go?");
                fix.setOnClickListener(v -> showFolder());
                break;
            case "logs":
                fix.setText("Open the log");
                fix.setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_log));
                break;
            default:  // retry, and the Windows-only ones
                fix.setText("Try again");
                fix.setOnClickListener(v -> askToCapture());
        }
    }

    private static final String[] GOAL_WORDS = {"gold", "elixir", "dark elixir"};

    /** "Farming until 15M gold": a bar of how far this run has got, and about when it'll get there. */
    private void showGoal(JSONObject goal) {
        View box = findViewById(R.id.goal);
        if (goal == null) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        String resource = goal.optString("resource");
        int i = resource.equals("gold") ? 0 : resource.equals("elixir") ? 1 : 2;
        long amount = goal.optLong("amount"), got = Math.min(goal.optLong("got"), amount);
        boolean done = goal.optBoolean("done");
        String what = Fmt.shortNumber(amount) + " " + GOAL_WORDS[i];
        ((TextView) findViewById(R.id.goal_text)).setText(done ? "Goal reached: " + what
                : (BotService.instance == null ? "Goal: " : "Farming until ") + what);
        LinearProgressIndicator bar = findViewById(R.id.goal_bar);
        bar.setIndicatorColor(getColor(done ? R.color.cf_good : RESOURCE_COLOURS[i]));
        bar.setProgressCompat((int) (1000 * got / Math.max(1, amount)), true);
        String eta;
        if (done) {
            eta = Fmt.shortNumber(goal.optLong("got")) + " gained. It stopped by itself.";
        } else if (goal.isNull("eta") || BotService.instance == null) {
            eta = Fmt.shortNumber(got) + " of " + Fmt.shortNumber(amount) + (got == 0 ? ": the first attack sets the pace." : "");
        } else {
            long left = goal.optLong("eta");
            java.util.Calendar at = java.util.Calendar.getInstance();
            at.add(java.util.Calendar.SECOND, (int) Math.min(left, Integer.MAX_VALUE));
            String clock = android.text.format.DateFormat.getTimeFormat(this).format(at.getTime());
            eta = Fmt.shortNumber(got) + " of " + Fmt.shortNumber(amount) + " · about " + Fmt.duration(left)
                    + " left" + (left > 20 * 3600 ? "" : " (around " + clock + ")");  // a day off: the clock would mislead
        }
        ((TextView) findViewById(R.id.goal_eta)).setText(eta);
    }

    /** Upright, the bar of tabs goes all the way down: its own colour runs under the gesture line (or the
     *  buttons), with the tabs just above it. The rest of the screen keeps clear of the status bar and any
     *  cut-out. (Sideways, the layout keeps fitsSystemWindows: the rail is down the side.) */
    private void edgeToEdge() {
        View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        View bar = findViewById(R.id.nav);
        if (root.getFitsSystemWindows() || !(bar instanceof com.google.android.material.bottomnavigation.BottomNavigationView)) {
            return;
        }
        int sides = androidx.core.view.WindowInsetsCompat.Type.systemBars()
                | androidx.core.view.WindowInsetsCompat.Type.displayCutout();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets around = insets.getInsets(sides);
            v.setPadding(around.left, around.top, around.right, 0);
            return insets;  // on to the bar, for the bottom
        });
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> {
            // The gesture line, or the keyboard when it's up (the bar then sits above it, as before).
            int below = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(0, 0, 0, below);
            return insets;
        });
    }

    private String seenShown = "";  // the picture on the problem card now (path and time), so it's read once

    /** The problem card's "what it saw": the last screen before the bot gave up. */
    private void showSeen(String path, String when) {
        ImageView seen = findViewById(R.id.problem_seen);
        TextView caption = findViewById(R.id.problem_seen_caption);
        File file = path.isEmpty() ? null : new File(path);
        if (file == null || !file.isFile()) {
            seen.setVisibility(View.GONE);
            caption.setVisibility(View.GONE);
            seenShown = "";
            return;
        }
        String key = path + file.lastModified();
        if (!key.equals(seenShown)) {
            BitmapFactory.Options small = new BitmapFactory.Options();
            small.inSampleSize = 2;  // a card's width: half the saved picture is plenty
            Bitmap picture = BitmapFactory.decodeFile(path, small);
            if (picture == null) return;
            seen.setImageBitmap(picture);
            seenShown = key;
        }
        seen.setVisibility(View.VISIBLE);
        caption.setText("What it saw" + (when.isEmpty() ? "" : " at " + when) + ". Tap to see it whole.");
        caption.setVisibility(View.VISIBLE);
        seen.setOnClickListener(v -> {
            Uri uri = FileProvider.getUriForFile(this, "com.cocfarm.app.files", file);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (android.content.ActivityNotFoundException e) {
                Ui.share(this, file, "image/jpeg", "What COCFARM saw");
            }
        });
    }

    /** "Report": the log made ready for an issue (personal details out) on the clipboard,
     *  and the share sheet with what it saw, to attach. */
    private void report(String picture) {
        String phone = LogPage.phoneDetails(this);
        python.execute(() -> {
            String text = null, why = null;
            try {
                text = new JSONObject(Bridge.python(this).getModule("cocfarm_settings")
                        .callAttr("bug_report", folder, phone).toString()).getString("text");
            } catch (Exception e) {
                why = e.getMessage();
            }
            String copied = text, problem = why;
            main.post(() -> {
                if (copied == null) {
                    Ui.snack(nav, "Couldn't make the report: " + problem, Snackbar.LENGTH_LONG).show();
                    return;
                }
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("COCFARM bug report", copied));
                File file = picture.isEmpty() ? null : new File(picture);
                if (file == null || !file.isFile()) {
                    Ui.snack(nav, "The report is copied: paste it into the issue.", Snackbar.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "The report is copied: paste it into the issue, with this picture.",
                        Toast.LENGTH_LONG).show();
                Ui.share(this, file, "image/jpeg", "Send what COCFARM saw");
            });
        });
    }

    private void showSession() {
        if (snapshot.optDouble("started", 0) == 0) {
            session.setVisibility(View.GONE);
            return;
        }
        session.setVisibility(View.VISIBLE);
        showGoal(snapshot.optJSONObject("goal"));
        JSONObject today = snapshot.optJSONObject("today");
        JSONArray todayGained = today == null ? null : today.optJSONArray("gained");
        set(counts[0], Fmt.whole(snapshot.optLong("bases")), "");
        set(counts[1], Fmt.whole(snapshot.optLong("attacks")), today == null ? "" : "Today: " + today.optInt("attacks"));
        set(counts[2], Fmt.duration(snapshot.optLong("seconds")), BotService.instance == null ? "Last run" : "");
        JSONArray gained = snapshot.optJSONArray("gained");
        for (int i = 0; i < 3; i++) {
            long value = gained == null ? 0 : gained.optLong(i);
            set(gains[i], Fmt.gain(value), todayGained == null ? "" : "Today " + Fmt.gain(todayGained.optLong(i)));
        }
        JSONObject attack = snapshot.optJSONObject("attack");
        lastAttack.setVisibility(attack == null ? View.GONE : View.VISIBLE);
        if (attack != null) {
            JSONArray target = attack.optJSONArray("target"), got = attack.optJSONArray("gained");
            StringBuilder text = new StringBuilder("Last attack: offered ")
                    .append(Fmt.shortNumber(target.optLong(0))).append(" gold · ")
                    .append(Fmt.shortNumber(target.optLong(1))).append(" elixir · ")
                    .append(Fmt.shortNumber(target.optLong(2))).append(" dark");
            if (!attack.isNull("taken")) {
                text.append(", took ").append(Math.round(attack.optDouble("taken") * 100)).append("%");
            }
            text.append(got == null ? ". Gain unknown: your resources couldn't be read afterwards."
                    : ". Gained " + Fmt.gain(got.optLong(0)) + " · " + Fmt.gain(got.optLong(1)) + " · " + Fmt.gain(got.optLong(2)) + ".");
            show(lastAttack, text);
        }
        showBase(snapshot.optJSONObject("base"));
        showBuilder(snapshot.optJSONObject("builder"));
    }

    private void showBase(JSONObject seen) {
        base.setVisibility(seen == null ? View.GONE : View.VISIBLE);
        if (seen == null) {
            return;
        }
        boolean accepted = seen.optBoolean("accepted");
        pill(verdict, accepted ? "ATTACK" : "SKIP", accepted ? R.color.cf_background : R.color.cf_text,
                accepted ? R.color.cf_good : R.color.cf_surface_highest);
        JSONArray minimums = seen.optJSONArray("minimums");
        for (int i = 0; i < 3; i++) {
            long value = seen.optLong(RESOURCES[i]), minimum = minimums == null ? 0 : minimums.optLong(i);
            boolean met = value >= minimum;
            set(loot[i], Fmt.whole(value), (met ? "✓ " : "✗ ") + "min " + Fmt.shortNumber(minimum));
            note(loot[i]).setTextColor(getColor(met ? R.color.cf_good : R.color.cf_muted));
        }
        long stamp = Math.round(seen.optDouble("time") * 1000);
        if (stamp != pictureStamp) {
            pictureStamp = stamp;
            File picture = new File(folder, "cache/base.jpg");
            python.execute(() -> {  // off the main thread
                Bitmap bitmap = BitmapFactory.decodeFile(picture.getAbsolutePath());
                main.post(() -> {
                    if (bitmap != null) basePicture.setImageBitmap(bitmap);
                });
            });
        }
    }

    private void showBuilder(JSONObject b) {
        int battles = b == null ? 0 : b.optInt("battles");
        builder.setVisibility(battles == 0 ? View.GONE : View.VISIBLE);
        if (battles == 0) {
            return;
        }
        set(builderTiles[0], Fmt.whole(battles), "Second base " + b.optInt("second") + "×");
        set(builderTiles[1], Fmt.gain(b.optLong("gold")), "This session");
        set(builderTiles[2], Fmt.gain(b.optLong("elixir")), "This session");
        JSONObject last = b.optJSONObject("last");
        if (last != null) {
            JSONArray got = last.optJSONArray("gained");
            builderLast.setText("Last battle: " + (last.optInt("stages") >= 2 ? "both bases" : "first base only")
                    + " in " + Fmt.duration(last.optLong("seconds"))
                    + (got == null ? "" : ", gained " + Fmt.gain(got.optLong(0)) + " gold · " + Fmt.gain(got.optLong(1)) + " elixir"));
        }
    }

    // ------------------------------------------------------------- updates

    private void checkUpdate() {
        python.execute(() -> {
            JSONObject found = null;
            try {
                found = new JSONObject(Bridge.python(this).getModule("cocfarm_settings")
                        .callAttr("check_update", folder, false).toString());
            } catch (Exception ignored) {
                // offline, or Python not ready: next time
            }
            JSONObject shown = found;
            main.post(() -> {
                if (shown != null && !shown.isNull("version")) {
                    updateVersion = shown.optString("version");
                    updatePage = shown.optString("page");
                    ((TextView) findViewById(R.id.update_title)).setText("COCFARM " + updateVersion + " is out");
                    updateCard.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void downloadUpdate() {
        if (BotService.instance != null) {
            Ui.snack(start, "Stop farming first: the update restarts COCFARM.", Snackbar.LENGTH_LONG).show();
            return;
        }
        LinearProgressIndicator bar = findViewById(R.id.update_progress);
        MaterialButton get = findViewById(R.id.update_get);
        TextView note = findViewById(R.id.update_note);
        get.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        note.setText("Downloading…");
        Bridge.progress = (done, size) -> main.post(() -> {
            if (size > 0) {
                bar.setIndeterminate(false);
                bar.setProgressCompat((int) (done * 100 / size), true);
                note.setText("Downloading… " + done / 1048576 + " of " + size / 1048576 + " MB");
            }
        });
        python.execute(() -> {
            String path = null, problem = null;
            try {
                path = Bridge.python(this).getModule("cocfarm_settings").callAttr("download_update", folder).toString();
            } catch (Exception e) {
                problem = e.getMessage();
            }
            String apk = path, why = problem;
            main.post(() -> {
                Bridge.progress = null;
                bar.setVisibility(View.GONE);
                get.setEnabled(true);
                if (apk == null) {
                    note.setText("The download didn't work: " + why);
                    return;
                }
                note.setText("Downloaded. Android asks you to confirm the update.");
                get.setText("Install");
                get.setOnClickListener(v -> install(apk));
                install(apk);
            });
        });
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void install(String path) {
        if (!canInstall()) {  // Android asks the player to let COCFARM install apps, once
            pendingInstall = path;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        pendingInstall = null;
        Uri apk = FileProvider.getUriForFile(this, "com.cocfarm.app.files", new File(path));
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ------------------------------------------------------------- setup

    private void refreshSteps() {
        boolean game = Ui.gameInstalled(this);
        String tapProblem = TouchService.problem(this);
        boolean tapping = tapProblem == null;
        boolean notifying = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean pictures = missingPictures != null && missingPictures.isEmpty();
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        boolean unrestricted = power == null || power.isIgnoringBatteryOptimizations(getPackageName());
        String now = game + " " + tapProblem + " " + notifying + " " + unrestricted + " " + missingPictures;
        if (now.equals(stepsShown)) {  // the home screen refreshes every second: rebuild only on a change
            return;
        }
        stepsShown = now;
        steps.removeAllViews();
        if (!game) {  // (once it's there, a row saying so is only clutter)
            addStep(false, R.drawable.ic_village, "Clash of Clans", "Not on this phone yet.",
                    "Get it", v -> Ui.openStore(this));
        }
        addStep(tapping, R.drawable.ic_touch, "Let COCFARM tap in the game",
                tapping ? "Turned on." : tapProblem,
                "Turn on", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addStep(notifying, R.drawable.ic_notifications, "Allow notifications",
                notifying ? "Allowed." : "So you can pause and stop it from the notification.",
                "Allow", v -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS));
        // Not needed to start, but Android may stop a farm left running in the background without it.
        addStep(unrestricted, R.drawable.ic_bolt, "Let it run in the background",
                unrestricted ? "Unrestricted." : "Android may stop COCFARM while it farms. Open COCFARM's app info, "
                        + "then Battery (or App battery usage), and pick Unrestricted.",
                "Open", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));
        String text = missingPictures == null ? "Checking…" : pictures ? "All there."
                : "Missing: " + TextUtils.join(", ", missingPictures) + ".";
        addStep(pictures, R.drawable.ic_image, "Button pictures", text, "Where?", v -> showFolder());
        setup.setVisibility(game && tapping && notifying && unrestricted && pictures ? View.GONE : View.VISIBLE);
    }

    private void addStep(boolean done, int iconRes, String title, String text, String action, View.OnClickListener act) {
        View row = row(steps, done ? R.drawable.ic_check_circle : iconRes, title, text);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageTintList(ColorStateList.valueOf(getColor(done ? R.color.cf_good : R.color.cf_warn)));
        if (!done) {
            MaterialButton button = row.findViewById(R.id.action);
            button.setText(action);
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(act);
        }
    }

    private void checkPictures() {
        python.execute(() -> {
            List<String> missing = new ArrayList<>();
            String shown;
            try {
                Pictures.sync(this, folder);  // the pictures the app comes with (new ones after an update)
                JSONObject info = new JSONObject(Bridge.python(this).getModule("cocfarm_settings")
                        .callAttr("info", folder).toString());
                JSONArray names = info.getJSONArray("missing");
                for (int i = 0; i < names.length(); i++) {
                    missing.add(names.getString(i));
                }
                shown = "v" + info.getString("version");
            } catch (Exception e) {
                shown = "";
            }
            String v = shown;
            ready = true;
            pythonStarted = true;
            android.util.Log.i("COCFARM", "Python ready " + (SystemClock.uptimeMillis() - launchedAt) + " ms after launch");
            main.post(this::leaveLaunch);
            main.post(() -> {
                missingPictures = missing;
                version.setText(v);
                refresh();
            });
        });
    }

    private void showFolder() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Button pictures")
                .setMessage("Put the templates folder (the button pictures) in COCFARM's folder:\n\n" + folder
                        + "\n\nConnect the phone to a PC and copy it there, or use a file manager.")
                .setPositiveButton("Copy the path", (d, w) -> copyFolder())
                .setNegativeButton("Close", null)
                .show();
    }

    private void copyFolder() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("COCFARM folder", folder));
        Ui.snack(start, "Copied the folder's path", Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- links

    private void buildLinks() {
        LinearLayout links = findViewById(R.id.links);
        // Shown as a file manager shows it: from the phone's storage.
        View files = row(links, R.drawable.ic_folder, "COCFARM's folder",
                folder.replace(Environment.getExternalStorageDirectory().getAbsolutePath() + "/", ""));
        MaterialButton copy = files.findViewById(R.id.action);
        copy.setVisibility(View.VISIBLE);
        copy.setText("Copy");
        // A convenience, not a step: a text button, quieter than the setup steps' "Turn on".
        copy.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        copy.setTextColor(getColor(R.color.cf_accent));
        copy.setOnClickListener(v -> copyFolder());
        files.setOnClickListener(v -> showFolder());
        View village = row(links, R.drawable.ic_village, "Your village data",
                "What COCFARM read from the game's Data Export: levels, what's maxed.");
        village.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        village.setOnClickListener(v -> showVillage());
    }

    /** The village data the bot read (Settings, Home village: "Read the village data once a day"). */
    private void showVillage() {
        python.execute(() -> {
            JSONObject read = null;
            try {
                read = new JSONObject(Bridge.python(this).getModule("cocfarm_settings").callAttr("village_summary", folder).toString());
            } catch (Exception e) {
                android.util.Log.e("COCFARM", "The village data didn't read", e);
            }
            JSONObject shownData = read;
            main.post(() -> {
                if (!isFinishing()) villageDialog(shownData);
            });
        });
    }

    private void villageDialog(JSONObject read) {
        NestedScrollView scroll = Ui.column(this);
        LinearLayout content = Ui.inside(scroll);
        // In line with the dialog's title (24dp in, the words' own 4dp included), without a page's room at the bottom.
        content.setPadding(dp(20), 0, dp(20), dp(8));
        String note = read == null ? "It couldn't be read." : read.optString("note");
        if (!note.isEmpty()) {
            TextView words = Ui.text(this, note, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.cf_muted);
            words.setPadding(dp(4), dp(8), dp(4), 0);
            content.addView(words);
        }
        JSONArray sections = read == null ? null : read.optJSONArray("sections");
        for (int s = 0; sections != null && s < sections.length(); s++) {
            JSONObject section = sections.optJSONObject(s);
            LinearLayout card = Ui.card(content, section.optString("title"));
            card.setPadding(0, dp(4), 0, dp(4));
            JSONArray rows = section.optJSONArray("rows");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONArray cells = rows.optJSONArray(i);
                if (i > 0) card.addView(Ui.divider(this));
                LinearLayout line = new LinearLayout(this);
                line.setPadding(dp(16), dp(10), dp(16), dp(10));
                line.addView(Ui.text(this, cells.optString(0), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        R.color.cf_text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView detail = Ui.text(this, cells.optString(1), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                        R.color.cf_muted);
                detail.setGravity(Gravity.END);
                line.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                card.addView(line);
            }
        }
        String title = "Your village data";
        if (read != null && read.optInt("town_hall") > 0) {
            title = "Town Hall " + read.optInt("town_hall") + (read.optString("read_on").isEmpty() ? "" : " · read " + read.optString("read_on"));
        }
        new MaterialAlertDialogBuilder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }

    private View row(ViewGroup parent, int iconRes, String title, String text) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_item, parent, false);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.cf_accent)));
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.detail)).setText(text);
        parent.addView(row);
        return row;
    }

    // ------------------------------------------------------------- starting

    private boolean accessibilityOn() {
        return TouchService.running();
    }

    private void askToCapture() {
        if (!Ui.gameInstalled(this)) {  // (before Android's screen-share question, not after it)
            Ui.snack(start, "Clash of Clans isn't on this phone yet.", Snackbar.LENGTH_LONG)
                    .setAction("Get it", v -> Ui.openStore(this)).show();
            return;
        }
        if (!accessibilityOn()) {
            Ui.snack(start, TouchService.turnedOn(this)
                            ? "Android hasn't started COCFARM's tapping: turn it off and on again under Accessibility."
                            : "Let COCFARM tap in the game first.", Snackbar.LENGTH_LONG)
                    .setAction("Turn on", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
            return;
        }
        if (missingPictures != null && !missingPictures.isEmpty()) {
            Ui.snack(start, "Add the button pictures first.", Snackbar.LENGTH_LONG)
                    .setAction("Where?", v -> showFolder()).show();
            return;
        }
        // Android asks the player each time: COCFARM sees the screen only while it farms.
        // Android 14+ offers "a single app" too, which would show the bot one app or none:
        // the whole screen is asked for (the game, and the black bars round it if any).
        MediaProjectionManager projection = getSystemService(MediaProjectionManager.class);
        capture.launch(Build.VERSION.SDK_INT >= 34
                ? projection.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : projection.createScreenCaptureIntent());
    }

    // ------------------------------------------------------------- helpers

    /** Tiles side by side in ``row``, one per label. */
    private View[] tiles(LinearLayout row, String... labels) {
        View[] out = new View[labels.length];
        for (int i = 0; i < labels.length; i++) {
            View tile = LayoutInflater.from(this).inflate(R.layout.tile_stat, row, false);
            label(tile).setText(labels[i]);
            // As tall as the row: a tile with a line under its number made the others in its row shorter.
            row.addView(tile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            out[i] = tile;
        }
        return out;
    }

    private static TextView label(View tile) {
        return tile.findViewById(R.id.label);
    }

    private static TextView note(View tile) {
        return tile.findViewById(R.id.note);
    }

    private static void set(View tile, String value, String note) {
        show(tile.findViewById(R.id.value), value);
        show(note(tile), note);
        note(tile).setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Text that changed (setting the same text again would lay the screen out again). */
    private static void show(TextView view, CharSequence text) {
        if (!text.toString().contentEquals(view.getText())) {
            view.setText(text);
        }
    }

    /** True if it changed. */
    private boolean pill(TextView view, String text, int fore, int back) {
        if (text.equals(view.getTag())) {
            return false;
        }
        view.setTag(text);
        view.setText(text);
        view.setTextColor(getColor(fore));
        GradientDrawable shape = (GradientDrawable) ContextCompat.getDrawable(this, R.drawable.bg_pill).mutate();
        shape.setColor(getColor(back));
        view.setBackground(shape);
        return true;
    }

    private Drawable tinted(int res, int colour, int sizeDp) {
        Drawable icon = ContextCompat.getDrawable(this, res).mutate();
        int size = Math.round(sizeDp * getResources().getDisplayMetrics().density);
        icon.setBounds(0, 0, size, size);
        icon.setTint(getColor(colour));
        return icon;
    }
}
