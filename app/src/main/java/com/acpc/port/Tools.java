package com.acpc.port;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Python disc/translation tools (src/main/python/acport) and settings.ini access. */
final class Tools {
    /** Called from Python with human-readable progress lines. */
    public interface Progress {
        void onProgress(String message);
    }

    /** Launcher/game languages: UI locale code -> translation profile (translations/<profile>). */
    static final String[] UI_LANGS = {"en", "de", "fr", "it", "es"};

    static String gameProfile(String uiLang) {
        switch (uiLang) {
            case "de": return "de-DE";
            case "fr": return "fr-FR";
            case "it": return "it-IT";
            case "es": return "es-ES";
            default: return "default"; // US English, built into the disc
        }
    }

    /** Native name of a UI language ("Deutsch", "Français", ...). */
    static String displayName(String uiLang) {
        java.util.Locale l = new java.util.Locale(uiLang);
        String n = l.getDisplayLanguage(l);
        return n.isEmpty() ? uiLang : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** "system" or one of UI_LANGS. */
    static String languageChoice(Context ctx) {
        return ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lang", "system");
    }

    static void setLanguageChoice(Context ctx, String choice) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("lang", choice).apply();
    }

    static String systemLanguage() {
        String sys = java.util.Locale.getDefault().getLanguage();
        for (String l : UI_LANGS) if (l.equals(sys)) return l;
        return "en";
    }

    /** Effective UI language (choice or system). */
    static String uiLanguage(Context ctx) {
        String c = languageChoice(ctx);
        return c.equals("system") ? systemLanguage() : c;
    }

    /** Context whose resources use the chosen UI language. */
    static Context localized(Context base) {
        String c = base.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lang", "system");
        if (c.equals("system")) return base;
        android.content.res.Configuration cfg = new android.content.res.Configuration(base.getResources().getConfiguration());
        cfg.setLocale(new java.util.Locale(c));
        return base.createConfigurationContext(cfg);
    }

    private Tools() {
    }

    private static PyObject tasks(Context ctx) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(ctx.getApplicationContext()));
        return Python.getInstance().getModule("acport.tasks");
    }

    /** "GAFE01|nkit" etc.; throws for files that are not GameCube images. */
    static String discInfo(Context ctx, File f) {
        return tasks(ctx).callAttr("disc_info", f.getAbsolutePath()).toString();
    }

    /** Moves the temp copy into rom/ (NKit is rebuilt into a plain ISO). */
    static File importUsDisc(Context ctx, File tmp, Progress p) {
        PyObject out = tasks(ctx).callAttr("import_us_disc", tmp.getAbsolutePath(),
                RomManager.romDir(ctx).getAbsolutePath(), p);
        return new File(out.toString());
    }

    static void generateTranslation(Context ctx, File usDisc, File eurDisc, String lang, Progress p) {
        tasks(ctx).callAttr("generate_translation", usDisc.getAbsolutePath(), eurDisc.getAbsolutePath(),
                lang, ctx.getFilesDir().getAbsolutePath(),
                new File(ctx.getCacheDir(), "l10n-work").getAbsolutePath(), p);
    }

    static boolean hasTranslation(Context ctx, String lang) {
        return new File(ctx.getFilesDir(), "translations/" + lang + "/msg.bin").isFile();
    }

    /** Writes the game language for the chosen UI language (English when no translation exists). */
    static void applyGameLanguage(Context ctx) throws IOException {
        String profile = gameProfile(uiLanguage(ctx));
        setLanguage(ctx, hasTranslation(ctx, profile) ? profile : "default");
    }

    /* settings.ini is owned by the game; only the [Localization] language key is edited here. */
    private static File settingsFile(Context ctx) {
        return new File(ctx.getFilesDir(), "settings.ini");
    }

    static String getLanguage(Context ctx) {
        try {
            for (String line : Files.readAllLines(settingsFile(ctx).toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("language") && t.contains("=")) return t.substring(t.indexOf('=') + 1).trim();
            }
        } catch (IOException ignored) {
        }
        return "default";
    }

    static void setLanguage(Context ctx, String lang) throws IOException {
        File f = settingsFile(ctx);
        List<String> lines = f.exists()
                ? new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<>();
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("language") && t.contains("=")) {
                lines.set(i, "language = " + lang);
                done = true;
            }
        }
        if (!done) {
            lines.add("");
            lines.add("[Localization]");
            lines.add("language = " + lang);
        }
        Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
    }
}
