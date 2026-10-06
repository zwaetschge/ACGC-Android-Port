package com.acpc.port;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;

/**
 * "Animal Crossing Deluxe" (ACDX) by Cuyler36: a mod that ships as xdelta
 * patches for the original disc. Deluxe replaces the game's PowerPC code, so it
 * cannot run on this native port; the launcher builds the Deluxe image from the
 * user's own disc (same release list, hashes and patches as the official patch
 * builder) and hands it to Dolphin.
 */
final class Acdx {
    static final String SITE = "https://cuyler36.github.io";
    static final String RELEASES = SITE + "/assets/data/acdx/releases.json";
    static final String PAGE = SITE + "/acdx/";
    static final String DOLPHIN = "org.dolphinemu.dolphinemu";
    static final String GAME_ID = "GADEXX";
    static final String FOLDER = "ACDX";

    interface Progress {
        void onProgress(String message, long done, long total);
    }

    /** Release + patch matching the user's source image. */
    static final class Match {
        final String version, sourceKey, sourceLabel, patchUrl, outputName, outputSha1;

        Match(String version, String sourceKey, String sourceLabel, String patchUrl, String outputName,
              String outputSha1) {
            this.version = version;
            this.sourceKey = sourceKey;
            this.sourceLabel = sourceLabel;
            this.patchUrl = patchUrl;
            this.outputName = outputName;
            this.outputSha1 = outputSha1;
        }
    }

    private Acdx() {
    }

    /* ---------- state ---------- */

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("acdx", Context.MODE_PRIVATE);
    }

    /** Built version ("0.43.3") or null. */
    static String builtVersion(Context ctx) {
        return prefs(ctx).getString("version", null);
    }

    /** Where the image was saved, for display. */
    static String builtLocation(Context ctx) {
        return prefs(ctx).getString("location", "");
    }

    static boolean dolphinInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(DOLPHIN, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Starts the Deluxe image in Dolphin (it must be in one of Dolphin's game folders). */
    static Intent playIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("dolphinemu://app/play/0/" + GAME_ID))
                .setPackage(DOLPHIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent storeIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + DOLPHIN))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /* ---------- build ---------- */

    /** Checks the picked image against the latest release; throws if unsupported. */
    static Match match(Context ctx, Uri source, Progress p) throws Exception {
        p.onProgress(ctx.getString(R.string.acdx_checking), 0, 0);
        JSONObject meta = new JSONObject(fetchText(RELEASES));
        long size = sizeOf(ctx, source);
        JSONObject sources = meta.getJSONObject("sources");
        String sha1 = null;
        String key = null;
        for (Iterator<String> it = sources.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = sources.getJSONObject(k);
            if (s.optLong("size", -1) != size || s.optString("sha1").isEmpty()) continue;
            if (sha1 == null) sha1 = sha1(ctx, source, size, p);
            if (s.getString("sha1").equalsIgnoreCase(sha1)) {
                key = k;
                break;
            }
        }
        if (key == null) {
            throw new IllegalArgumentException(ctx.getString(R.string.acdx_unsupported_source));
        }
        String latest = meta.getString("latest");
        JSONObject release = null;
        for (int i = 0; i < meta.getJSONArray("releases").length(); i++) {
            JSONObject r = meta.getJSONArray("releases").getJSONObject(i);
            if (r.getString("version").equals(latest)) release = r;
        }
        if (release == null) throw new IOException("release " + latest + " missing");
        JSONObject patch = release.getJSONObject("patches").getJSONObject(key);
        String url = patch.getString("url");
        return new Match(latest, key, sources.getJSONObject(key).optString("label", key),
                url.startsWith("http") ? url : SITE + url,
                patch.optString("outputName", "ACDX-" + latest + ".iso"), patch.optString("outputSha1"));
    }

    /** Downloads the patch, applies it and saves the image to Download/ACDX. */
    static void build(Context ctx, Uri source, Match m, Progress p) throws Exception {
        File patch = new File(ctx.getCacheDir(), "acdx.xdelta");
        File out = new File(ctx.getCacheDir(), m.outputName);
        try {
            download(m.patchUrl, patch, (done, total) ->
                    p.onProgress(ctx.getString(R.string.downloading, (int) (done >> 20)), done, total));
            p.onProgress(ctx.getString(R.string.step_acdx_patch), 0, 0);
            try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(source, "r")) {
                if (pfd == null) throw new IOException("cannot open source");
                // Python reads the picked document through the descriptor (no copy of a 1.4 GB image)
                if (!Python.isStarted()) Python.start(new AndroidPlatform(ctx.getApplicationContext()));
                Python.getInstance().getModule("acport.tasks").callAttr("build_deluxe",
                        pfd.getFd(), patch.getAbsolutePath(), out.getAbsolutePath(),
                        m.outputSha1, (Tools.Progress) msg -> p.onProgress(stepText(ctx, msg), 0, 0));
            }
            p.onProgress(ctx.getString(R.string.acdx_saving), 0, 0);
            String location = publish(ctx, out, m.outputName);
            prefs(ctx).edit().putString("version", m.version).putString("location", location).apply();
        } finally {
            patch.delete();
            out.delete();
        }
    }

    private static String stepText(Context ctx, String key) {
        int id = ctx.getResources().getIdentifier(key, "string", ctx.getPackageName());
        return id != 0 ? ctx.getString(id) : key;
    }

    /** Copies the image to the shared Download/ACDX folder (Dolphin can add it as a game folder). */
    private static String publish(Context ctx, File image, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = ctx.getContentResolver();
            String rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/";
            // replace an image of the same name written earlier by this app
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel, name});
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("cannot create " + rel + name);
            try (InputStream in = new FileInputStream(image); OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("cannot write " + rel + name);
                copy(in, os);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return rel + name;
        }
        File dir = new File(ctx.getExternalFilesDir(null), FOLDER);
        dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(image); OutputStream os = new FileOutputStream(dst)) {
            copy(in, os);
        }
        return dst.getAbsolutePath();
    }

    /* ---------- helpers ---------- */

    private static long sizeOf(Context ctx, Uri uri) throws IOException {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("cannot open source");
            return pfd.getStatSize();
        }
    }

    private static String sha1(Context ctx, Uri uri, long size, Progress p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open source");
            byte[] buf = new byte[1 << 20];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                done += n;
                p.onProgress(ctx.getString(R.string.acdx_hashing, (int) (done >> 20)), done, size);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }

    private interface Bytes {
        void onProgress(long done, long total);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "ACGC-Android-Port");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        return c;
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            copy(in, bo);
            return bo.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dst, Bytes p) throws IOException {
        HttpURLConnection c = open(url);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if ((done & 0xFFFFF) < (1 << 16)) p.onProgress(done, total);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
