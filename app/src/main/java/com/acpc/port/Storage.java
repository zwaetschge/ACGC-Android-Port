package com.acpc.port;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Saves, settings and mods live in Android/media/<package>, which other apps (file managers,
 * Syncthing) can reach, unlike Android/data. The disc image stays in private storage.
 */
final class Storage {

    private static final String TAG = "Storage";
    private static final String[] MOVED = {
            "save", "save-backups", "settings.ini", "keybindings.ini", "texture_pack", "translations",
    };
    private static boolean migrated;

    private Storage() {
    }

    static File dataDir(Context ctx) {
        File[] media = ctx.getExternalMediaDirs();
        File dir = media != null && media.length > 0 ? media[0] : null;
        if (dir == null || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(dir))
                || (!dir.isDirectory() && !dir.mkdirs())) {
            return ctx.getFilesDir();
        }
        return dir;
    }

    /** Moves data from the old private location without ever overwriting a file that already exists. */
    static synchronized void migrate(Context ctx) {
        File dst = dataDir(ctx);
        File src = ctx.getFilesDir();
        if (migrated || dst.equals(src)) return;
        migrated = true;
        try {
            // keeps gallery apps from indexing the texture pack
            new File(dst, ".nomedia").createNewFile();
        } catch (IOException e) {
            Log.w(TAG, "cannot create .nomedia", e);
        }
        for (String name : MOVED) {
            File from = new File(src, name);
            if (!from.exists()) continue;
            try {
                move(from, new File(dst, name));
            } catch (IOException e) {
                Log.w(TAG, "migration of " + name + " failed, keeping the original", e);
            }
        }
    }

    private static void move(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] children = from.listFiles();
            if (children != null) {
                for (File c : children) move(c, new File(to, c.getName()));
            }
            from.delete();
            return;
        }
        if (!to.exists()) {
            Files.copy(from.toPath(), to.toPath());
            if (to.length() != from.length()) {
                to.delete();
                throw new IOException("size mismatch for " + to);
            }
            from.delete();
        }
    }
}
