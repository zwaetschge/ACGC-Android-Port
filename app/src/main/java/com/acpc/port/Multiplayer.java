package com.acpc.port;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Town visits through a self-hosted exchange server (tools/multiplayer-server).
 * Animal Crossing's own multiplayer is visiting a friend's town from memory
 * card B; the server replaces handing the card over:
 *  share  - upload card A, get a code
 *  visit  - download a town by code into card B, then take the train in-game
 *  return - upload the visited card B back to the owner
 *  fetch  - the owner replaces card A with the returned town
 */
final class Multiplayer {
    static final String SAVE_NAME = "DobutsunomoriP_MURA.gci";

    private Multiplayer() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("mp", Context.MODE_PRIVATE);
    }

    static String server(Context ctx) {
        return prefs(ctx).getString("server", "");
    }

    static String key(Context ctx) {
        return prefs(ctx).getString("key", "");
    }

    static void setServer(Context ctx, String server, String key) {
        server = server.trim();
        if (!server.isEmpty() && !server.contains("://")) server = "http://" + server;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        prefs(ctx).edit().putString("server", server).putString("key", key.trim()).apply();
    }

    /** Code of the town this player shared (and has not fetched back yet), or null. */
    static String sharedCode(Context ctx) {
        return prefs(ctx).getString("share_code", null);
    }

    /** Code of the town currently in card B, or null. */
    static String visitCode(Context ctx) {
        return prefs(ctx).getString("visit_code", null);
    }

    static File cardA(Context ctx) {
        return new File(ctx.getFilesDir(), "save/card_a/" + SAVE_NAME);
    }

    static File cardB(Context ctx) {
        return new File(ctx.getFilesDir(), "save/card_b/" + SAVE_NAME);
    }

    /* ---------- actions ---------- */

    static String checkServer(Context ctx) throws IOException {
        JSONObject j = json(request(ctx, "GET", "/v1/health", null));
        if (!j.optBoolean("ok")) throw new IOException("unexpected server reply");
        return j.optBoolean("key_required") ? "key" : "open";
    }

    /** Uploads the own town; returns the code. */
    static String share(Context ctx) throws IOException {
        File a = cardA(ctx);
        if (!a.isFile()) throw new IOException(ctx.getString(R.string.mp_no_save));
        HttpURLConnection c = connect(ctx, "POST", "/v1/towns");
        JSONObject j = json(send(c, Files.readAllBytes(a.toPath())));
        String code = j.optString("code");
        prefs(ctx).edit().putString("share_code", code).putString("share_token", j.optString("token")).apply();
        return code;
    }

    /** Info about a shared town (town, player, returned, visitor). */
    static JSONObject info(Context ctx, String code) throws IOException {
        return json(request(ctx, "GET", "/v1/towns/" + code + "/info", null));
    }

    /** Puts the friend's town into memory card B. */
    static void visit(Context ctx, String code) throws IOException {
        byte[] town = request(ctx, "GET", "/v1/towns/" + code, null);
        File b = cardB(ctx);
        backup(ctx, b.getParentFile(), "card_b");
        b.getParentFile().mkdirs();
        File[] old = b.getParentFile().listFiles();
        if (old != null) for (File f : old) f.delete(); // card B holds exactly one town
        writeAtomic(b, town);
        prefs(ctx).edit().putString("visit_code", code).apply();
    }

    /** Sends the visited town (card B) back to its owner and empties card B. */
    static void sendBack(Context ctx) throws IOException {
        String code = visitCode(ctx);
        File b = cardB(ctx);
        if (code == null || !b.isFile()) throw new IOException(ctx.getString(R.string.mp_no_visit));
        HttpURLConnection c = connect(ctx, "PUT", "/v1/towns/" + code + "/return");
        send(c, Files.readAllBytes(b.toPath()));
        backup(ctx, b.getParentFile(), "card_b-returned");
        b.delete();
        prefs(ctx).edit().remove("visit_code").apply();
    }

    /** Replaces the own town with the returned one (card A is backed up first). */
    static void fetchBack(Context ctx) throws IOException {
        String code = sharedCode(ctx);
        if (code == null) throw new IOException(ctx.getString(R.string.mp_nothing_shared));
        HttpURLConnection c = connect(ctx, "GET", "/v1/towns/" + code + "/return");
        c.setRequestProperty("X-Owner-Token", prefs(ctx).getString("share_token", ""));
        byte[] town = send(c, null);
        File a = cardA(ctx);
        backup(ctx, a.getParentFile(), "card_a");
        writeAtomic(a, town);
        prefs(ctx).edit().remove("share_code").remove("share_token").apply();
    }

    /** Forgets the shared town (deletes it on the server when possible). */
    static void unshare(Context ctx) {
        String code = sharedCode(ctx);
        if (code != null) {
            try {
                HttpURLConnection c = connect(ctx, "DELETE", "/v1/towns/" + code);
                c.setRequestProperty("X-Owner-Token", prefs(ctx).getString("share_token", ""));
                send(c, null);
            } catch (IOException ignored) {
                // expired or server gone: forgetting it locally is enough
            }
        }
        prefs(ctx).edit().remove("share_code").remove("share_token").apply();
    }

    /* ---------- helpers ---------- */

    /** Copies a save directory to files/save-backups/<time>-<label>/. */
    private static void backup(Context ctx, File dir, String label) throws IOException {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dst = new File(ctx.getFilesDir(), "save-backups/" + stamp + "-" + label);
        if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("cannot create " + dst);
        for (File f : files) {
            if (f.isFile()) Files.copy(f.toPath(), new File(dst, f.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAtomic(File dst, byte[] data) throws IOException {
        File tmp = new File(dst.getPath() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static HttpURLConnection connect(Context ctx, String method, String path) throws IOException {
        String server = server(ctx);
        if (server.isEmpty()) throw new IOException(ctx.getString(R.string.mp_no_server));
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (!key(ctx).isEmpty()) c.setRequestProperty("X-Server-Key", key(ctx));
        return c;
    }

    private static byte[] request(Context ctx, String method, String path, byte[] body) throws IOException {
        return send(connect(ctx, method, path), body);
    }

    private static byte[] send(HttpURLConnection c, byte[] body) throws IOException {
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            if (status >= 400) {
                String msg = new String(data, StandardCharsets.UTF_8);
                try {
                    msg = new JSONObject(msg).optString("error", msg);
                } catch (Exception ignored) {
                    // plain text error
                }
                throw new IOException("HTTP " + status + ": " + msg);
            }
            return data;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static JSONObject json(byte[] data) throws IOException {
        try {
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("unexpected server reply");
        }
    }
}
