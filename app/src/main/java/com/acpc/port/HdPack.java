package com.acpc.port;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Community "Animal Crossing HD Texture Pack" (ACHD, Dolphin format).
 * The pack is downloaded by the user from its original source and converted
 * on the device (BC7 -> ASTC, see TexConv); nothing of it ships with the app.
 */
final class HdPack {
    static final String NAME = "ACHD V24";
    /** Google Drive file id published in the pack's Dolphin forum thread. */
    static final String DRIVE_ID = "1Z8-pMx20xO8q3caiqy4NOrSNVT1veDSk";
    static final String THREAD_URL =
            "https://forums.dolphin-emu.org/Thread-animal-crossing-hd-texture-pack-version-23-feb-22nd-2026";

    interface Progress {
        void onProgress(String stage, long done, long total);
    }

    private HdPack() {
    }

    static File packDir(Context ctx) {
        return new File(ctx.getFilesDir(), "texture_pack");
    }

    private static File marker(Context ctx) {
        return new File(packDir(ctx), ".installed");
    }

    /** Installed pack name, or null. */
    static String installed(Context ctx) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(marker(ctx).toPath());
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static void remove(Context ctx) {
        deleteRecursive(packDir(ctx));
        packDir(ctx).mkdirs();
    }

    /* ---------- download ---------- */

    static File download(Context ctx, Progress p) throws IOException {
        File out = new File(ctx.getCacheDir(), "achd.zip");
        String base = "https://drive.usercontent.google.com/download?id=" + DRIVE_ID + "&export=download";
        HttpURLConnection c = open(base);
        String type = c.getContentType();
        if (type != null && type.startsWith("text/html")) {
            // large files: Drive answers with a "can't scan for viruses" form first
            String html = readAll(c.getInputStream());
            c.disconnect();
            Matcher m = Pattern.compile("name=\"uuid\" value=\"([^\"]+)\"").matcher(html);
            String url = base + "&confirm=t" + (m.find() ? "&uuid=" + m.group(1) : "");
            c = open(url);
            type = c.getContentType();
            if (type != null && type.startsWith("text/html")) {
                c.disconnect();
                throw new IOException("Google Drive did not return the file (quota or link changed). "
                        + "Download the ZIP manually: " + THREAD_URL);
            }
        }
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                p.onProgress("download", done, total);
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ACPC");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " for " + url);
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    /* ---------- install ---------- */

    /**
     * Converts every texture of the ZIP into texture_pack/. German UI textures
     * (GAF/Localisation/GER) are renamed to the USA texture names using the
     * hash table in assets/l10n/de-DE_textures.json and stored under
     * Localisation_de-DE/, which the game only uses when German is active.
     */
    static int install(Context ctx, File zip, Progress p) throws Exception {
        Map<String, String> german = loadMap(ctx, "l10n/de-DE_textures.json");
        File dir = packDir(ctx);
        remove(ctx);

        List<ZipEntry> jobs = new ArrayList<>();
        Map<ZipEntry, File> targets = new HashMap<>();
        try (ZipFile zf = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                File target = target(dir, ze, german);
                if (target != null) {
                    jobs.add(ze);
                    targets.put(ze, target);
                }
            }
            if (jobs.isEmpty()) throw new IOException("No Animal Crossing textures in this ZIP");

            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            Semaphore inFlight = new Semaphore(threads * 2);
            AtomicInteger done = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            final Exception[] firstError = {null};
            for (ZipEntry ze : jobs) {
                byte[] data;
                try (InputStream in = zf.getInputStream(ze)) {
                    data = readAllBytes(in, (int) ze.getSize());
                }
                inFlight.acquire();
                final byte[] bytes = data;
                pool.execute(() -> {
                    try {
                        File out = targets.get(ze);
                        out.getParentFile().mkdirs();
                        if (TexConv.convert(bytes, bytes.length, out.getPath(), TexConv.QUALITY) < 0) {
                            failed.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        synchronized (firstError) {
                            if (firstError[0] == null) firstError[0] = ex;
                        }
                    } finally {
                        inFlight.release();
                        p.onProgress("convert", done.incrementAndGet(), jobs.size());
                    }
                });
                if (firstError[0] != null) break;
            }
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.DAYS);
            if (firstError[0] != null) throw firstError[0];

            try (OutputStream o = new FileOutputStream(marker(ctx))) {
                o.write(NAME.getBytes(StandardCharsets.UTF_8));
            }
            return jobs.size() - failed.get();
        }
    }

    /** Output file for a ZIP entry, or null to skip it. */
    private static File target(File dir, ZipEntry ze, Map<String, String> german) {
        String name = ze.getName().replace('\\', '/');
        if (ze.isDirectory() || !name.toLowerCase().endsWith(".dds")) return null;
        String file = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("/Localisation/")) {
            // PAL texture hashes; only the German set has a USA mapping
            if (!name.contains("/Localisation/GER/") || !german.containsKey(file)) return null;
            return new File(dir, "GAF/Localisation_de-DE/" + german.get(file));
        }
        if (name.contains("/Font/") && !name.contains("/Font/GAFE/")) return null; // other regions
        int root = name.indexOf("GAF/");
        return new File(dir, root >= 0 ? name.substring(root) : name);
    }

    private static Map<String, String> loadMap(Context ctx, String asset) throws Exception {
        String json;
        try (InputStream in = ctx.getAssets().open(asset)) {
            json = readAll(in);
        }
        JSONObject map = new JSONObject(json).getJSONObject("map");
        Map<String, String> out = new HashMap<>();
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, map.getString(k));
        }
        return out;
    }

    private static byte[] readAllBytes(InputStream in, int sizeHint) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(sizeHint > 0 ? sizeHint : 1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
