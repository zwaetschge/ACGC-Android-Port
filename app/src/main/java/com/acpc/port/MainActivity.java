package com.acpc.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Launcher: the user provides every game asset themselves.
 *  1. USA disc (GAFE01) - required, imported from a file or SMB share (NKit is converted)
 *  2. HD texture pack   - optional, downloaded from its original source and converted
 *  3. Translation       - optional, generated from the user's European disc (GAFP01)
 */
public class MainActivity extends Activity {

    private enum Purpose { US_DISC, EU_DISC, HD_ZIP }

    private static final boolean DE = Locale.getDefault().getLanguage().equals("de");

    private TextView romStatus, hdStatus, langStatus;
    private String pendingLang = "de-DE";

    static String tr(String de, String en) {
        return DE ? de : en;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text(getString(R.string.app_name), 30, R.color.ac_text);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView subtitle = text(tr("Nativer Port von ACGC-PC-Port · Spieldaten bringst du selbst mit",
                "Native port of ACGC-PC-Port · bring your own game data"), 13, R.color.ac_text_dim);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        Button start = button(tr("Spiel starten", "Start game"));
        start.setTextSize(19);
        start.setOnClickListener(v -> {
            File rom = RomManager.findRom(this);
            if (rom == null || !RomManager.readInfo(rom).isRequired()) {
                toast(tr("Zuerst die US-Disc (GAFE01) importieren.", "Import the USA disc (GAFE01) first."));
                return;
            }
            startActivity(new Intent(this, GameActivity.class));
        });
        root.addView(start, margins(dp(20), 0));

        // 1. game data
        root.addView(header(tr("1 · Spieldaten (Pflicht)", "1 · Game data (required)")));
        romStatus = text("", 13, R.color.ac_text_dim);
        root.addView(romStatus);
        root.addView(row(
                button(tr("Datei wählen", "Choose file"), () -> pickFile(Purpose.US_DISC)),
                button(tr("Von SMB-Share", "From SMB share"), () -> smbDialog(Purpose.US_DISC))));
        root.addView(note(tr("Animal Crossing USA (GAFE01) als .iso, .gcm, .ciso oder NKit (.nkit.iso).",
                "Animal Crossing USA (GAFE01) as .iso, .gcm, .ciso or NKit (.nkit.iso).")));

        // 2. HD textures
        root.addView(header(tr("2 · HD-Texturen (optional)", "2 · HD textures (optional)")));
        hdStatus = text("", 13, R.color.ac_text_dim);
        root.addView(hdStatus);
        root.addView(row(
                button(tr("Herunterladen & installieren", "Download & install"), this::confirmHdDownload),
                button(tr("ZIP wählen", "Choose ZIP"), () -> pickFile(Purpose.HD_ZIP))));
        root.addView(row(button(tr("HD-Texturen entfernen", "Remove HD textures"), () -> {
            HdPack.remove(this);
            refresh();
        })));
        root.addView(note(tr("„Animal Crossing HD Texture Pack“ (" + HdPack.NAME + ") von TechieAndroid, Brackenhawk "
                + "und der AC-Modding-Community. Wird von der Originalquelle geladen und auf dem Gerät "
                + "umgewandelt (einmalig einige Minuten, ca. 1,5 GB).",
                "\"Animal Crossing HD Texture Pack\" (" + HdPack.NAME + ") by TechieAndroid, Brackenhawk and the "
                + "AC modding community. Downloaded from its original source and converted on the device "
                + "(one-time, a few minutes, about 1.5 GB).")));

        // 3. language
        root.addView(header(tr("3 · Sprache (optional)", "3 · Language (optional)")));
        langStatus = text("", 13, R.color.ac_text_dim);
        root.addView(langStatus);
        root.addView(row(
                button(tr("Sprache wählen", "Choose language"), this::chooseLanguage),
                button(tr("Übersetzung erstellen", "Create translation"), this::createTranslation)));
        root.addView(note(tr("Übersetzungen werden aus deiner europäischen Disc (GAFP01) erzeugt; "
                + "die Texte werden nicht mit der App verteilt. Basierend auf dem l10n-Branch von birabittoh.",
                "Translations are generated from your European disc (GAFP01); no game text ships with "
                + "the app. Based on birabittoh's l10n branch.")));

        root.addView(note(tr("Steuerung: Gamepad, Tastatur oder Touch-Overlay. Select/Back = Pause-Menü.",
                "Controls: gamepad, keyboard or touch overlay. Select/Back = pause menu.")));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        File rom = RomManager.findRom(this);
        if (rom == null) {
            romStatus.setText(tr("✗ Keine Disc importiert", "✗ No disc imported"));
        } else {
            RomManager.Info info = RomManager.readInfo(rom);
            romStatus.setText(info.isRequired()
                    ? String.format(Locale.US, "✓ %s Rev %d · %s", info.id, info.revision, rom.getName())
                    : String.format(Locale.US, tr("✗ Falsche Disc: %s (GAFE01 nötig)", "✗ Wrong disc: %s (GAFE01 needed)"), info.id));
        }
        String hd = HdPack.installed(this);
        hdStatus.setText(hd != null ? "✓ " + hd : tr("– nicht installiert", "– not installed"));
        String lang = Tools.getLanguage(this);
        StringBuilder sb = new StringBuilder();
        for (String[] l : Tools.installedLanguages(this)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(l[1]);
            if (l[0].equals(lang)) sb.append(" ✓");
        }
        langStatus.setText(tr("Verfügbar: ", "Available: ") + sb);
    }

    /* ---------- HD textures ---------- */

    private void confirmHdDownload() {
        new AlertDialog.Builder(this)
                .setTitle(HdPack.NAME)
                .setMessage(tr("Lädt ca. 146 MB von Google Drive (Link aus dem Dolphin-Forum-Thread des Packs) "
                        + "und wandelt die Texturen für dein Gerät um. Fortfahren?",
                        "Downloads about 146 MB from Google Drive (link from the pack's Dolphin forum thread) "
                        + "and converts the textures for this device. Continue?"))
                .setPositiveButton("OK", (d, w) -> runTask(tr("HD-Texturen", "HD textures"), pd -> {
                    File zip = HdPack.download(this, (stage, done, total) -> progress(pd,
                            tr("Download", "Download") + " " + (done >> 20) + " MB", done, total));
                    installHd(pd, zip);
                }))
                .setNegativeButton(tr("Abbrechen", "Cancel"), null)
                .show();
    }

    private void installHd(ProgressDialog pd, File zip) throws Exception {
        try {
            int n = HdPack.install(this, zip, (stage, done, total) -> progress(pd,
                    tr("Umwandeln ", "Converting ") + done + " / " + total, done, total));
            runOnUiThread(() -> toast(n + tr(" Texturen installiert", " textures installed")));
        } finally {
            zip.delete();
        }
    }

    /* ---------- language ---------- */

    private void chooseLanguage() {
        List<String[]> langs = Tools.installedLanguages(this);
        String[] names = new String[langs.size()];
        for (int i = 0; i < names.length; i++) names[i] = langs.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle(tr("Sprache", "Language"))
                .setItems(names, (d, i) -> {
                    try {
                        Tools.setLanguage(this, langs.get(i)[0]);
                    } catch (Exception e) {
                        error(e);
                    }
                    refresh();
                })
                .show();
    }

    private void createTranslation() {
        if (RomManager.findRom(this) == null) {
            toast(tr("Zuerst die US-Disc importieren.", "Import the USA disc first."));
            return;
        }
        String[][] all = Tools.LANGUAGES;
        String[] names = new String[all.length - 1];
        for (int i = 1; i < all.length; i++) names[i - 1] = all[i][1];
        new AlertDialog.Builder(this)
                .setTitle(tr("Welche Sprache?", "Which language?"))
                .setItems(names, (d, i) -> {
                    pendingLang = all[i + 1][0];
                    new AlertDialog.Builder(this)
                            .setTitle(tr("Europäische Disc (GAFP01)", "European disc (GAFP01)"))
                            .setItems(new String[]{tr("Datei wählen", "Choose file"), tr("Von SMB-Share", "From SMB share")},
                                    (d2, j) -> {
                                        if (j == 0) pickFile(Purpose.EU_DISC);
                                        else smbDialog(Purpose.EU_DISC);
                                    })
                            .show();
                })
                .show();
    }

    /* ---------- file sources ---------- */

    private File tempFor(Purpose p) {
        return new File(getCacheDir(), "import-" + p.name().toLowerCase(Locale.US) + ".bin");
    }

    private void pickFile(Purpose p) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 100 + p.ordinal());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int idx = requestCode - 100;
        if (idx < 0 || idx >= Purpose.values().length || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        Purpose p = Purpose.values()[idx];
        runTask(tr("Import", "Import"), pd -> {
            File tmp = tempFor(p);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("no stream");
                copy(in, tmp, -1, pd);
            }
            process(p, tmp, pd);
        });
    }

    /** Runs after the source was copied into the cache. */
    private void process(Purpose p, File tmp, ProgressDialog pd) throws Exception {
        try {
            switch (p) {
                case US_DISC: {
                    String info = Tools.discInfo(this, tmp);
                    if (!info.startsWith("GAFE01")) {
                        throw new IllegalArgumentException(tr("Das ist nicht die US-Disc (GAFE01): ",
                                "This is not the USA disc (GAFE01): ") + info);
                    }
                    Tools.importUsDisc(this, tmp, msg -> message(pd, msg));
                    break;
                }
                case EU_DISC: {
                    File rom = RomManager.findRom(this);
                    Tools.generateTranslation(this, rom, tmp, pendingLang, msg -> message(pd, msg));
                    Tools.setLanguage(this, pendingLang);
                    break;
                }
                case HD_ZIP:
                    installHd(pd, tmp);
                    break;
            }
        } finally {
            tmp.delete();
        }
    }

    private void smbDialog(Purpose p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);
        EditText host = field(box, tr("Server (Host / IP)", "Server (host / IP)"), pref("host"));
        EditText user = field(box, tr("Benutzer (leer = Gast)", "User (empty = guest)"), pref("user"));
        EditText pass = field(box, tr("Passwort", "Password"), pref("pass"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText share = field(box, tr("Share-Pfad (z. B. /Games/gc)", "Share path (e.g. /Games/gc)"), pref("share"));
        new AlertDialog.Builder(this)
                .setTitle("SMB")
                .setView(box)
                .setPositiveButton(tr("Verbinden", "Connect"), (d, w) -> {
                    savePref("host", host.getText().toString().trim());
                    savePref("user", user.getText().toString().trim());
                    savePref("pass", pass.getText().toString());
                    savePref("share", share.getText().toString().trim());
                    smbList(p);
                })
                .setNegativeButton(tr("Abbrechen", "Cancel"), null)
                .show();
    }

    private void smbList(Purpose p) {
        runTask("SMB", pd -> {
            SmbClient client = new SmbClient(pref("host"), pref("user"), pref("pass"));
            List<SmbClient.Entry> entries = client.listRomFiles(pref("share"));
            runOnUiThread(() -> {
                if (entries.isEmpty()) {
                    toast(tr("Keine Disc-Images gefunden", "No disc images found"));
                    return;
                }
                String[] names = new String[entries.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = String.format(Locale.US, "%s  (%.2f GB)", entries.get(i).name,
                            entries.get(i).size / 1073741824.0);
                }
                new AlertDialog.Builder(this)
                        .setItems(names, (d, i) -> runTask(tr("Import", "Import"), pd2 -> {
                            SmbClient.Entry e = entries.get(i);
                            File tmp = tempFor(p);
                            try (InputStream in = client.open(pref("share"), e.name)) {
                                copy(in, tmp, e.size, pd2);
                            }
                            process(p, tmp, pd2);
                        }))
                        .setNegativeButton(tr("Abbrechen", "Cancel"), null)
                        .show();
            });
        });
    }

    /* ---------- task plumbing ---------- */

    private interface Task {
        void run(ProgressDialog pd) throws Exception;
    }

    private void runTask(String title, Task task) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(title);
        pd.setMessage("…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.setMax(1000);
        pd.show();
        new Thread(() -> {
            try {
                task.run(pd);
                runOnUiThread(() -> {
                    pd.dismiss();
                    refresh();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    error(e);
                    refresh();
                });
            }
        }, "launcher-task").start();
    }

    private void copy(InputStream in, File dst, long total, ProgressDialog pd) throws Exception {
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if ((done & 0xFFFFF) < (1 << 16)) {
                    progress(pd, tr("Kopiere ", "Copying ") + (done >> 20) + " MB", done, total);
                }
            }
        }
    }

    private void progress(ProgressDialog pd, String msg, long done, long total) {
        runOnUiThread(() -> {
            pd.setMessage(msg);
            if (total > 0) {
                pd.setIndeterminate(false);
                pd.setProgress((int) (done * 1000 / total));
            }
        });
    }

    private void message(ProgressDialog pd, String msg) {
        runOnUiThread(() -> {
            pd.setIndeterminate(true);
            pd.setMessage(msg);
        });
    }

    private void error(Throwable e) {
        String msg = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        if (e instanceof jcifs.CIFSException) msg = describeSmb(e) + "\n\n(" + msg + ")";
        new AlertDialog.Builder(this)
                .setTitle(tr("Fehler", "Error"))
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String describeSmb(Throwable e) {
        String raw = String.valueOf(e);
        if (e instanceof jcifs.smb.SmbAuthException || raw.contains("Access is denied")
                || raw.contains("LOGON_FAILURE") || raw.contains("ACCESS_DENIED")) {
            return tr("Zugriff verweigert – Benutzer und Passwort prüfen (Gastzugriff ist auf dem Server evtl. aus).",
                    "Access denied – check user and password (the server may not allow guest access).");
        }
        if (raw.contains("UnknownHost")) return tr("Server nicht gefunden – Host/IP prüfen.", "Server not found – check host/IP.");
        return tr("Verbindung fehlgeschlagen – Host/IP und SMB-Port 445 prüfen.",
                "Connection failed – check host/IP and SMB port 445.");
    }

    /* ---------- UI helpers ---------- */

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(getColor(color));
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 17, R.color.ac_primary);
        t.setLayoutParams(margins(dp(26), dp(4)));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 12, R.color.ac_text_dim);
        t.setLayoutParams(margins(dp(6), 0));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this, null, 0, android.R.style.Widget_Material_Button);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private Button button(String label, Runnable action) {
        Button b = button(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(6);
            r.addView(b, lp);
        }
        r.setLayoutParams(margins(dp(6), 0));
        return r;
    }

    private LinearLayout.LayoutParams margins(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private EditText field(LinearLayout box, String label, String initial) {
        box.addView(text(label, 12, R.color.ac_text_dim));
        EditText et = new EditText(this);
        et.setText(initial);
        et.setSingleLine();
        box.addView(et);
        return et;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void savePref(String k, String v) {
        getSharedPreferences("smb", MODE_PRIVATE).edit().putString(k, v).apply();
    }

    private String pref(String k) {
        return getSharedPreferences("smb", MODE_PRIVATE).getString(k, "");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
