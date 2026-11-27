package com.cocfarm.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import com.chaquo.python.Python;

/**
 * What the Python bot calls (see cocfarm_android.py): the screen, the
 * fingers, and the game app. Static, so Python reaches it without a context.
 */
public final class Bridge {
    public interface Listener {
        void onStatus(String text);
    }

    public interface ProgressListener {
        void onProgress(long done, long size);
    }

    static volatile Listener listener;
    static volatile Context appContext;  // for an alert with no farming going (the test one)
    static volatile ProgressListener progress;  // an update being downloaded
    static final String READY = "Ready when you are.";
    static volatile String lastStatus = READY;
    // Asked for by the player (the floating button, the notification or the home screen): the bot
    // acts on them at its next safe point.
    static volatile boolean paused, stopping;

    private Bridge() {
    }

    private static TouchService touch() {
        TouchService service = TouchService.instance;
        if (service == null) {
            throw new IllegalStateException("COCFARM's accessibility service is off: turn it on in Settings → Accessibility");
        }
        return service;
    }

    private static BotService bot() {
        BotService service = BotService.instance;
        if (service == null) {
            throw new IllegalStateException("screen capture isn't running");
        }
        return service;
    }

    public static BotService.Frame screenshot() throws InterruptedException {
        return bot().frame();
    }

    public static int[] screenSize() {
        BotService service = bot();
        return new int[]{service.width, service.height};
    }

    public static boolean tap(int x, int y) {
        return touch().tap(x, y);
    }

    public static boolean tapMany(int[] xs, int[] ys, long gapMs) {
        return touch().tapMany(xs, ys, gapMs);
    }

    public static boolean press(int x, int y, long ms) {
        return touch().press(x, y, ms);
    }

    public static boolean drag(int[] xs, int[] ys, long ms) {
        return touch().drag(xs, ys, ms);
    }

    /** The clipboard's text, or null (none, or Android wouldn't let it be read). */
    public static String clipboardText() {
        return touch().clipboardText();
    }

    public static boolean setClipboardText(String text) {
        return touch().setClipboardText(text);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        return touch().swipe(x1, y1, x2, y2, ms);
    }

    /** Zoom out on the game: the screen less the black bars round it (0 each with none). */
    public static boolean pinch(int left, int top, int right, int bottom) {
        BotService service = bot();
        return touch().pinch(left, top, service.width - left - right, service.height - top - bottom);
    }

    public static boolean back() {
        return touch().back();
    }

    /** Android's home button: the game goes to the background, still running. */
    public static boolean home() {
        return touch().home();
    }

    /**
     * The lines of text on a picture (PNG bytes), read on the phone by ML Kit's bundled Latin text
     * recognition (no download, no network): one "x\ty\tw\th\ttext" line each, as Windows' OCR gives
     * them on the PC. Called from the bot's thread, never the main one: it waits for the answer.
     */
    public static String readText(byte[] png) throws Exception {
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) {
            throw new IllegalArgumentException("not a picture");
        }
        com.google.mlkit.vision.text.Text text = com.google.android.gms.tasks.Tasks.await(
                textRecognizer().process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)),
                15, java.util.concurrent.TimeUnit.SECONDS);
        StringBuilder out = new StringBuilder();
        for (com.google.mlkit.vision.text.Text.TextBlock block : text.getTextBlocks()) {
            for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                android.graphics.Rect box = line.getBoundingBox();
                if (box == null) {
                    continue;
                }
                out.append(box.left).append('\t').append(box.top).append('\t').append(box.width()).append('\t')
                        .append(box.height()).append('\t').append(line.getText().replace('\t', ' ').replace('\n', ' '))
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static com.google.mlkit.vision.text.TextRecognizer recognizer;

    private static synchronized com.google.mlkit.vision.text.TextRecognizer textRecognizer() {
        if (recognizer == null) {
            recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        return recognizer;
    }

    public static String foregroundPackage() {
        return touch().foregroundPackage();
    }

    /** Screen pixels per sp: how big the phone draws its own text (its density and font size),
     *  which sizes the dialogs it shows over the game. */
    public static float textPixels() {
        android.content.res.Resources resources = bot().getResources();
        return resources.getDisplayMetrics().densityDpi / 160f * resources.getConfiguration().fontScale;
    }

    /** The phone's home screen app (its launcher), or "" if there's no telling. */
    public static String homePackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo found = bot().getPackageManager()
                    .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return found == null || found.activityInfo == null ? "" : found.activityInfo.packageName;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The screen is off or locked: taps can't reach the game until it's unlocked. */
    public static boolean screenLocked() {
        Context context = BotService.instance != null ? BotService.instance : appContext;
        if (context == null) {
            return false;
        }
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
        return (power != null && !power.isInteractive()) || (keyguard != null && keyguard.isKeyguardLocked());
    }

    /**
     * What the bot needs from the phone, checked once at the start, as "name=value" lines: the
     * Android version, the screen, the accessibility service, screen capture, battery
     * optimisation, notifications, the lock and the game. Read and logged by cocfarm_android.
     */
    public static String selfCheck(String pkg) {
        Context context = BotService.instance != null ? BotService.instance : appContext;
        StringBuilder out = new StringBuilder();
        out.append("android=").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        out.append("phone=").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
        out.append("accessibility=").append(TouchService.instance != null ? "on" : "off").append('\n');
        out.append("capture=").append(BotService.instance != null && BotService.instance.width > 0 ? "on" : "off").append('\n');
        if (context == null) {
            return out.toString();
        }
        android.content.res.Configuration config = context.getResources().getConfiguration();
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        out.append("density=").append(metrics.densityDpi).append('\n');
        out.append("font_scale=").append(config.fontScale).append('\n');
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        out.append("battery_optimised=").append(power != null
                && !power.isIgnoringBatteryOptimizations(context.getPackageName()) ? "yes" : "no").append('\n');
        android.app.NotificationManager notes = context.getSystemService(android.app.NotificationManager.class);
        out.append("notifications=").append(notes != null && notes.areNotificationsEnabled() ? "on" : "off").append('\n');
        out.append("locked=").append(screenLocked() ? "yes" : "no").append('\n');
        String game = "missing";
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            game = info.versionName == null ? "installed" : info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // stays "missing"
        }
        out.append("game=").append(game).append('\n');
        return out.toString();
    }

    public static void startApp(String pkg) {
        Context context = bot();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            throw new IllegalStateException(pkg + " isn't installed");
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }

    /**
     * The bot's screenshots show the game: it's in front, whatever the last "in front" event said
     * (one doesn't always come when the game is brought back).
     */
    public static void gameInFront(String pkg) {
        touch().noteForeground(pkg);
    }

    /** Close the game: to the home screen, then end it in the background (no root can force-stop it). */
    public static void stopApp(String pkg) {
        touch().home();
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ((ActivityManager) bot().getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
    }

    /** Python, started on first use (from any thread). */
    static synchronized Python python(Context context) {
        appContext = context.getApplicationContext();
        if (!Python.isStarted()) {
            Python.start(new com.chaquo.python.android.AndroidPlatform(context.getApplicationContext()));
        }
        return Python.getInstance();
    }

    /** Pause at the next safe point, or carry on. {@code from}: what was pressed, for the log. */
    static void togglePause(String from) {
        BotService service = BotService.instance;
        if (service == null || stopping || !Python.isStarted()) {
            return;
        }
        paused = Python.getInstance().getModule("cocfarm_android").callAttr("toggle_pause", from).toBoolean();
        if (!paused) {
            status("Carrying on");
        }
        changed();
    }

    static void stop(Context context) {
        if (BotService.instance == null) {
            return;
        }
        stopping = true;
        context.startService(new Intent(context, BotService.class).setAction(BotService.ACTION_STOP));
        changed();
    }

    /** An attack ended: the quick settings tile's count follows. */
    public static void tileChanged() {
        Context context = appContext;
        if (context != null) {
            FarmTile.refresh(context);
        }
    }

    /** COCFARM's floating button off the game (true) while the bot looks at what it covers, or back. */
    public static void buttonAside(boolean away) {
        TouchService touch = TouchService.instance;
        if (touch != null && touch.button != null) {
            touch.button.stepAside(away);
        }
    }

    /** Farming started or ended, or was paused: everything showing it follows. */
    static void changed() {
        tileChanged();
        BotService service = BotService.instance;
        if (service != null) {
            service.refreshNotification();
        }
        TouchService touch = TouchService.instance;
        if (touch != null && touch.button != null) {
            touch.button.refresh();
        }
        status(lastStatus);
    }

    private static final String ALERTS = "alerts";
    private static final java.util.concurrent.atomic.AtomicInteger alertId = new java.util.concurrent.atomic.AtomicInteger(100);

    /**
     * An alert as the phone's own notification (storage full, stopped, the daily summary...): what
     * Discord gets on Windows, with no webhook to set up. Each its own notification; tapping one
     * opens COCFARM. {@code kind}: good, info, warn, error or summary (the colour).
     */
    public static void alert(String title, String text, String kind) {
        Context context = BotService.instance != null ? BotService.instance : appContext;
        if (context == null) {
            throw new IllegalStateException("COCFARM isn't running");
        }
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (!manager.areNotificationsEnabled()) {
            throw new IllegalStateException("notifications are off for COCFARM (Android's Settings → Apps → COCFARM)");
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(ALERTS) == null) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(ALERTS, "Alerts",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Storage full, stopped, problems and the daily summary");
            manager.createNotificationChannel(channel);
        }
        int colour = "error".equals(kind) ? R.color.cf_bad : "warn".equals(kind) ? R.color.cf_warn
                : "good".equals(kind) ? R.color.cf_good : R.color.cf_info;
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(context, 2,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, ALERTS) : new android.app.Notification.Builder(context);
        builder.setContentTitle(title)
                .setContentText(text.split("\n", 2)[0])
                .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_bolt)
                .setColor(context.getColor(colour))
                .setContentIntent(open)
                .setAutoCancel(true);
        manager.notify(alertId.incrementAndGet(), builder.build());
    }

    /** "Sound on match": the phone's notification sound. */
    public static void ding() {
        BotService service = BotService.instance;
        if (service == null) {
            return;
        }
        android.net.Uri sound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
        android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(service, sound);
        if (ringtone != null) {
            ringtone.play();
        }
    }

    /** The battery: {percent, 1 if plugged in, temperature in tenths of a °C}. Android's last broadcast of it. */
    public static int[] battery() {
        Context context = BotService.instance != null ? BotService.instance : appContext;
        if (context == null) {
            throw new IllegalStateException("COCFARM isn't running");
        }
        Intent state = context.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (state == null) {
            throw new IllegalStateException("no battery reading");
        }
        int level = state.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = state.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        int plugged = state.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        int temperature = state.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
        return new int[]{level < 0 || scale <= 0 ? 100 : level * 100 / scale, plugged != 0 ? 1 : 0, temperature};
    }

    /** The battery guard paused farming, or let it carry on: the button and notification follow. */
    public static void heldBack(boolean held) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            paused = held;
            changed();
        });
    }

    public static void updateProgress(long done, long size) {
        ProgressListener l = progress;
        if (l != null) {
            l.onProgress(done, size);
        }
    }

    public static void status(String text) {
        lastStatus = text;
        Listener l = listener;
        if (l != null) {
            l.onStatus(text);
        }
    }
}
