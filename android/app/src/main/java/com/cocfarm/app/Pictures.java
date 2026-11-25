package com.cocfarm.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * The button pictures (templates/) the app comes with, put in COCFARM's folder, as the Windows
 * app's zip has them beside COCFARM.exe. A picture the player changed or added is never touched:
 * one is only replaced while it is still exactly as the app last wrote it (its digest is kept in
 * templates/.bundled), so an update brings the new pictures and keeps the player's own.
 */
final class Pictures {
    private static final String TAG = "COCFARM";
    private static final String INDEX = "templates/index.txt";  // written by copyPictures (build.gradle.kts)
    private static final String KEPT = ".bundled";

    private Pictures() {
    }

    /** Brings templates/ up to date. Returns how many pictures were written. Safe from any thread. */
    static synchronized int sync(Context context, String folder) {
        AssetManager assets = context.getAssets();
        File root = new File(folder, "templates");
        File kept = new File(root, KEPT);
        Map<String, String> written = read(kept);
        int count = 0;
        try (BufferedReader index = new BufferedReader(new InputStreamReader(assets.open(INDEX), StandardCharsets.UTF_8))) {
            for (String name; (name = index.readLine()) != null; ) {
                name = name.trim();
                if (name.isEmpty()) {
                    continue;
                }
                byte[] bundled = bytes(assets.open("templates/" + name));
                String digest = digest(bundled);
                File target = new File(root, name);
                if (target.exists()) {
                    String now = digest(bytes(new java.io.FileInputStream(target)));  // (Files is Android 8+)
                    if (now.equals(digest)) {
                        written.put(name, digest);
                        continue;
                    }
                    if (!now.equals(written.get(name))) {
                        continue;  // the player's own picture
                    }
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("can't make " + parent);
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    out.write(bundled);
                }
                written.put(name, digest);
                count++;
            }
        } catch (IOException e) {
            Log.w(TAG, "Couldn't put the button pictures in place", e);
        }
        write(kept, written);
        if (count > 0) {
            Log.i(TAG, "Button pictures: " + count + " written to " + root);
        }
        return count;
    }

    private static Map<String, String> read(File file) {
        Map<String, String> out = new HashMap<>();
        if (!file.exists()) {
            return out;
        }
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            for (String line; (line = in.readLine()) != null; ) {
                int tab = line.lastIndexOf('\t');
                if (tab > 0) {
                    out.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Couldn't read " + file, e);
        }
        return out;
    }

    private static void write(File file, Map<String, String> written) {
        if (written.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(written).entrySet()) {
            text.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Couldn't write " + file, e);
        }
    }

    private static byte[] bytes(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = stream.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String digest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
