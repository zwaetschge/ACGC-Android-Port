package com.acpc.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK_ROM = 41;

    /** Embedded game data: compact GAFE01 disc image, size for validation. */
    private static final long ISO_SIZE = 27573708L;

    private TextView statusTitle;
    private TextView statusDetail;
    private Button startButton;
    private volatile boolean embeddedReady = false;
    private volatile boolean embeddedPreparing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getColor(R.color.ac_text));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Native Port (ACGC-PC-Port)\nDoubutsu no Mori · GameCube");
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextColor(getColor(R.color.ac_text_dim));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(4);
        slp.bottomMargin = dp(28);
        subtitle.setLayoutParams(slp);
        root.addView(subtitle);

        statusTitle = new TextView(this);
        statusTitle.setTextSize(17);
        statusTitle.setPadding(dp(16), dp(14), dp(16), dp(4));
        root.addView(statusTitle, matchWrap());

        statusDetail = new TextView(this);
        statusDetail.setTextSize(13);
        statusDetail.setPadding(dp(16), 0, dp(16), dp(14));
        statusDetail.setTextColor(getColor(R.color.ac_text_dim));
        root.addView(statusDetail, matchWrap());

        Button smb = styledButton("ROM vom SMB-Share laden");
        smb.setOnClickListener(v -> showSmbDialog());
        LinearLayout.LayoutParams blp = matchWrap();
        blp.topMargin = dp(20);
        smb.setLayoutParams(blp);
        root.addView(smb);

        Button pick = styledButton("ROM-Datei wählen (.iso / .gcm / .ciso)");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/x-iso9660-image", "application/octet-stream"});
            startActivityForResult(i, REQ_PICK_ROM);
        });
        LinearLayout.LayoutParams plp = matchWrap();
        plp.topMargin = dp(10);
        pick.setLayoutParams(plp);
        root.addView(pick);

        startButton = styledButton("Spiel starten");
        startButton.setTextSize(19);
        startButton.setOnClickListener(v -> {
            File rom = RomManager.findRom(this);
            if (rom == null && !embeddedReady) {
                toast(embeddedPreparing
                        ? "Spieldaten werden noch vorbereitet…"
                        : "Keine ROM gefunden – erst importieren.");
                return;
            }
            startActivity(new Intent(this, GameActivity.class));
        });
        LinearLayout.LayoutParams stlp = matchWrap();
        stlp.topMargin = dp(34);
        startButton.setLayoutParams(stlp);
        root.addView(startButton);

        TextView hint = new TextView(this);
        hint.setText("Das Disc-Image der US-Version (GAFE01 Rev 0) ist in der App eingebettet " +
                "und wird beim ersten Start entpackt. Optional kann zusätzlich ein vollständiges " +
                "Disc-Image (.iso/.gcm/.ciso) importiert werden.\n\nSteuerung: Gamepad (Handheld) – " +
                "Select/Back = Pause-Menü.");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.ac_text_dim));
        LinearLayout.LayoutParams hlp = matchWrap();
        hlp.topMargin = dp(30);
        hint.setLayoutParams(hlp);
        root.addView(hint);

        refreshStatus();
        ensureEmbeddedData();
    }

    /* ---------- embedded game data ---------- */

    private File embeddedIso() {
        // same dir the native disc scanner searches — with the deterministic
        // GAFE01.iso preference patched into pc_disc.c this always wins
        return new File(getFilesDir(), "rom/GAFE01.iso");
    }

    private boolean embeddedValid() {
        return embeddedIso().length() == ISO_SIZE;
    }

    /** Copies the embedded GAFE01 disc image out of the APK on first launch. */
    private void ensureEmbeddedData() {
        if (embeddedValid()) {
            embeddedReady = true;
            refreshStatus();
            return;
        }
        if (embeddedPreparing) return;
        embeddedPreparing = true;
        new Thread(() -> {
            try {
                deleteRecursive(new File(getFilesDir(), "orig")); // old layout
                copyAsset("rom/GAFE01.iso", embeddedIso(), ISO_SIZE);
                embeddedReady = embeddedValid();
            } catch (Exception e) {
                runOnUiThread(() -> toast("Spieldaten: " + e));
            } finally {
                embeddedPreparing = false;
                runOnUiThread(this::refreshStatus);
            }
        }, "embedded-assets").start();
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void copyAsset(String assetPath, File dst, long expect) throws Exception {
        if (dst.exists() && dst.length() == expect) return;
        dst.getParentFile().mkdirs();
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        long done = 0;
        try (InputStream in = getAssets().open(assetPath);
             java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                final long d = done;
                if (d % (1 << 20) < (1 << 16)) {
                    runOnUiThread(() -> {
                        if (!embeddedReady) refreshStatus();
                    });
                }
            }
        }
        if (done != expect) throw new IllegalStateException(assetPath + ": " + done + " != " + expect);
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rename failed: " + dst);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private Button styledButton(String text) {
        Button b = new Button(this, null, 0, android.R.style.Widget_Material_Button);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshStatus() {
        File rom = RomManager.findRom(this);
        if (rom == null) {
            if (embeddedReady) {
                statusTitle.setTextColor(getColor(R.color.ac_primary));
                statusTitle.setText("Disc-Image eingebettet ✓");
                statusDetail.setText("GAFE01 (USA, Rev 0) – alle Spieldaten vorhanden, bereit zum Start.");
            } else if (embeddedPreparing) {
                statusTitle.setTextColor(getColor(R.color.ac_text_dim));
                statusTitle.setText("Spieldaten werden vorbereitet…");
                statusDetail.setText("Disc-Image wird aus der App entpackt.");
            } else {
                statusTitle.setTextColor(getColor(R.color.ac_error));
                statusTitle.setText("Keine ROM vorhanden");
                statusDetail.setText("Import über SMB-Share oder Dateiauswahl.");
            }
            startButton.setEnabled(true);
            return;
        }
        RomManager.Info info = RomManager.readInfo(rom);
        if (info.isRequired()) {
            statusTitle.setTextColor(getColor(R.color.ac_primary));
            statusTitle.setText(String.format(Locale.US, "ROM bereit: %s Rev %d ✓",
                    info.id, info.revision));
        } else {
            statusTitle.setTextColor(getColor(R.color.ac_error));
            statusTitle.setText(String.format(Locale.US, "Falsche ROM: %s Rev %d",
                    info.id, info.revision));
        }
        statusDetail.setText(String.format(Locale.US, "%s · %.2f GB\n%s",
                rom.getName(), rom.length() / 1073741824.0,
                info.isRequired()
                        ? "US-Version erkannt – bereit zum Start."
                        : "Dieser Port benötigt GAFE01 (USA, Rev 0). Gefundene Version wird " +
                          "voraussichtlich nicht funktionieren."));
        startButton.setEnabled(true);
    }

    /* ---------- SAF import ---------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ROM || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        importFromStream(uri);
    }

    private void importFromStream(Uri uri) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("ROM wird importiert…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.setMax(1000);
        pd.show();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Stream null");
                String name = uri.getLastPathSegment();
                if (name == null) name = "animal-crossing.iso";
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                RomManager.importRom(this, in, name, 0,
                        (done, total) -> runOnUiThread(() -> {
                            if (total > 0) pd.setProgress((int) (done * 1000 / total));
                            else pd.setMessage("ROM wird importiert… " + (done >> 20) + " MB");
                        }));
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("Import abgeschlossen");
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Import fehlgeschlagen")
                            .setMessage(String.valueOf(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "rom-import").start();
    }

    /* ---------- SMB import ---------- */

    private void showSmbDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText host = labeledField(box, "Server (Host / IP)", loadPref("host", ""));
        final EditText user = labeledField(box, "Benutzer (leer = Gast)", loadPref("user", ""));
        final EditText pass = labeledField(box, "Passwort", loadPref("pass", ""));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText share = labeledField(box, "Share-Pfad", loadPref("share", "/Roms/ROMs/gc"));

        new AlertDialog.Builder(this)
                .setTitle("ROM vom SMB-Share laden")
                .setView(box)
                .setPositiveButton("Verbinden", (d, w) -> {
                    savePref("host", host.getText().toString().trim());
                    savePref("user", user.getText().toString().trim());
                    savePref("pass", pass.getText().toString());
                    savePref("share", share.getText().toString().trim());
                    connectAndList(host.getText().toString().trim(),
                            user.getText().toString().trim(),
                            pass.getText().toString(),
                            share.getText().toString().trim());
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private EditText labeledField(LinearLayout box, String label, String initial) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.ac_text_dim));
        box.addView(tv);
        EditText et = new EditText(this);
        et.setText(initial);
        et.setSingleLine();
        box.addView(et);
        return et;
    }

    private void connectAndList(String host, String user, String pass, String share) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Verbinde mit " + host + " …");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                SmbClient client = new SmbClient(host, user, pass);
                List<SmbClient.Entry> entries = client.listRomFiles(share);
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (entries.isEmpty()) {
                        toast("Keine ROM-Dateien gefunden");
                        return;
                    }
                    String[] names = new String[entries.size()];
                    for (int i = 0; i < entries.size(); i++) {
                        SmbClient.Entry e = entries.get(i);
                        names[i] = String.format(Locale.US, "%s  (%.2f GB)",
                                e.name, e.size / 1073741824.0);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("ROM auswählen")
                            .setItems(names, (d, idx) -> downloadSmb(client, share,
                                    entries.get(idx), host, user, pass))
                            .setNegativeButton("Abbrechen", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("SMB-Verbindung fehlgeschlagen")
                            .setMessage(describeSmbError(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "smb-list").start();
    }

    private void downloadSmb(SmbClient client, String share, SmbClient.Entry entry,
                             String host, String user, String pass) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Lade " + entry.name + " …");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.setMax(1000);
        pd.show();
        new Thread(() -> {
            try (InputStream in = client.open(share, entry.name)) {
                RomManager.importRom(this, in, entry.name, entry.size,
                        (done, total) -> runOnUiThread(() -> {
                            if (total > 0) pd.setProgress((int) (done * 1000 / total));
                        }));
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("Import abgeschlossen");
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Download fehlgeschlagen")
                            .setMessage(describeSmbError(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "smb-download").start();
    }

    /* ---------- prefs ---------- */

    /** Maps jcifs-ng exceptions to actionable German text (raw exception appended for debugging). */
    private static String describeSmbError(Exception e) {
        String raw = String.valueOf(e);
        boolean auth = e instanceof jcifs.smb.SmbAuthException
                || raw.contains("Access is denied")
                || raw.contains("Logon failure")
                || raw.contains("NT_STATUS_ACCESS_DENIED")
                || raw.contains("NT_STATUS_LOGON_FAILURE");
        if (auth) {
            return "Zugriff verweigert – Benutzername und Passwort prüfen.\n"
                    + "Gast-Zugriff (leerer Benutzer) ist auf dem Server deaktiviert.\n\n"
                    + "(" + raw + ")";
        }
        if (raw.contains("UnknownHost")) {
            return "Server nicht gefunden – Host/IP prüfen.\n\n(" + raw + ")";
        }
        if (raw.contains("timed out") || raw.contains("Timeout") || raw.contains("refused")
                || raw.contains("Failed to connect") || raw.contains("No route")) {
            return "Verbindung fehlgeschlagen – Host/IP prüfen (SMB-Port 445).\n\n(" + raw + ")";
        }
        return raw;
    }


    private void savePref(String k, String v) {
        getSharedPreferences("smb", MODE_PRIVATE).edit().putString(k, v).apply();
    }

    private String loadPref(String k, String def) {
        return getSharedPreferences("smb", MODE_PRIVATE).getString(k, def);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
