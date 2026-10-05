package com.acpc.port;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Manages the game disc image inside the app's private storage. */
public final class RomManager {

    public static final String REQUIRED_ID = "GAFE01";
    public static final String[] ROM_EXTS = {".iso", ".gcm", ".ciso"};

    private RomManager() {
    }

    public static File romDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "rom");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** First disc image found in files/rom (the native side scans the same dir). */
    public static File findRom(Context ctx) {
        File[] files = romDir(ctx).listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            for (String ext : ROM_EXTS) {
                if (n.endsWith(ext) && f.isFile() && f.length() > 0x1000) return f;
            }
        }
        return null;
    }

    public static void clearRoms(Context ctx) {
        File[] files = romDir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    public static class Info {
        public String id = "?";
        public int revision = -1;
        public boolean magicOk;
        public boolean isRequired() {
            return magicOk && REQUIRED_ID.equals(id);
        }
    }

    /** Reads the GC disc header: game id (6 bytes), revision (byte 7), magic at 0x1C. */
    public static Info readInfo(File f) {
        Info info = new Info();
        try (InputStream in = new FileInputStream(f)) {
            byte[] hdr = new byte[0x20];
            int got = in.read(hdr);
            if (got < 0x20) return info;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                char c = (char) (hdr[i] & 0xFF);
                if (c < 0x20 || c > 0x7E) return info;
                sb.append(c);
            }
            info.id = sb.toString();
            info.revision = hdr[7] & 0xFF;
            info.magicOk = (hdr[0x1C] & 0xFF) == 0xC2 && (hdr[0x1D] & 0xFF) == 0x33
                    && (hdr[0x1E] & 0xFF) == 0x9F && (hdr[0x1F] & 0xFF) == 0x3D;
        } catch (IOException ignored) {
        }
        return info;
    }

    /** Copies a stream into files/rom/<name>, replacing any previous import. */
    public static File importRom(Context ctx, InputStream in, String name,
                                 long totalSize, ProgressListener p) throws IOException {
        clearRoms(ctx);
        File target = new File(romDir(ctx), sanitize(name));
        File tmp = new File(romDir(ctx), "import.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, totalSize);
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("rename failed");
        }
        return target;
    }

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private static String sanitize(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._ ()\\[\\]-]", "_");
        String lower = n.toLowerCase();
        for (String ext : ROM_EXTS) {
            if (lower.endsWith(ext)) return n;
        }
        return n + ".iso";
    }
}
