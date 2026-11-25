package com.cocfarm.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.chaquo.python.Python;

/**
 * COCFARM in Android's quick settings (pull the shade down twice, then edit to add it): one tap
 * starts farming (Android asks to see the screen, as from the app) or stops it. It says what it's
 * doing: "Farming · 12 attacks", "Paused", or off.
 */
public final class FarmTile extends TileService {
    static final String ACTION_START = "com.cocfarm.app.START";

    @Override
    public void onStartListening() {
        show();
    }

    @Override
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")  // (the Intent one only below Android 14)
    public void onClick() {
        if (BotService.instance != null) {
            Bridge.stop(this);
            show();
            return;
        }
        // Seeing the screen needs the player's yes each time, and only an activity can ask.
        Intent start = new Intent(this, MainActivity.class).setAction(ACTION_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Runnable open = () -> {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 3, start,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(start);
            }
        };
        if (isLocked()) {
            unlockAndRun(open);
        } else {
            open.run();
        }
    }

    private void show() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean running = BotService.instance != null;
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bolt));
        tile.setLabel("COCFARM");
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        String state = !running ? "Tap to farm" : Bridge.stopping ? "Stopping…" : Bridge.paused ? "Paused"
                : "Farming" + attacks();
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(state);
        }
        tile.setContentDescription("COCFARM: " + state);
        tile.updateTile();
    }

    /** " · 12 attacks" this run, or "" before the first (or with Python not started). */
    private static String attacks() {
        if (!Python.isStarted()) {
            return "";
        }
        try {
            int count = Python.getInstance().getModule("cocfarm_android").callAttr("attack_count").toInt();
            return count > 0 ? " · " + count + (count == 1 ? " attack" : " attacks") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Farming started, stopped or paused, or an attack ended: the tile follows, if it's added. */
    static void refresh(Context context) {
        try {
            requestListeningState(context, new ComponentName(context, FarmTile.class));
        } catch (RuntimeException ignored) {
            // not added, or not now: it's drawn fresh when the shade opens
        }
    }
}
