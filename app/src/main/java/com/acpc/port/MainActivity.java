package com.acpc.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
 *  - USA disc (GAFE01), required: file picker or SMB share (NKit is converted)
 *  - HD texture pack, optional: downloaded from its original source and converted
 *  - translation, optional: generated from the user's European disc (GAFP01)
 *  - Animal Crossing Deluxe, optional: built from the user's disc, played in Dolphin
 *  - town visits by code through a self-hosted server
 * The UI follows the system language unless another language is chosen; the
 * same choice selects the game language when its translation exists.
 */
public class MainActivity extends Activity {

    private enum Purpose { US_DISC, EU_DISC, HD_ZIP, ACDX_SOURCE }

    // palette
    private static final int BG_TOP = 0xFF0E2A22, BG_BOTTOM = 0xFF1B4334;
    private static final int CARD = 0xFF1C3A2E, CARD_STROKE = 0xFF2F5A47;
    private static final int TEXT = 0xFFF4F0E2, TEXT_DIM = 0xFFA9C2B2, ON_ACCENT = 0xFF16261E;
    private static final int GREEN = 0xFF8CCB6E, YELLOW = 0xFFF5D76E, RED = 0xFFE88080, GREY = 0xFF7C9086;
    private static final int GHOST = 0x22FFFFFF;

    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Tools.localized(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM}));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(26), dp(28), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        getWindow().setStatusBarColor(BG_TOP);
        getWindow().setNavigationBarColor(BG_BOTTOM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    /** (Re)builds the whole screen from the current state. */
    private void build() {
        root.removeAllViews();

        // header: title + language chip
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(getString(R.string.app_name), 34, TEXT, true));
        titles.addView(text(getString(R.string.tagline), 14, TEXT_DIM, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(pill("🌐  " + Tools.displayName(Tools.uiLanguage(this)) + "  ▾", GHOST, TEXT, this::chooseLanguage));
        root.addView(header);

        // play
        File rom = RomManager.findRom(this);
        boolean ready = rom != null && RomManager.readInfo(rom).isRequired();
        Button play = new Button(this);
        play.setText("▶  " + getString(R.string.play));
        play.setTextSize(22);
        play.setAllCaps(false);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        play.setTextColor(ready ? 0xFF2B2A1E : 0xFF6E7C74);
        play.setStateListAnimator(null);
        play.setBackground(rounded(ready ? YELLOW : 0xFF2E4238, dp(18), 0));
        play.setPadding(0, dp(18), 0, dp(18));
        play.setOnClickListener(v -> play());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(22);
        plp.leftMargin = plp.rightMargin = dp(6);
        root.addView(play, plp);
        TextView hint = text(getString(ready ? R.string.play_ready : R.string.play_need_disc), 13,
                ready ? GREEN : TEXT_DIM, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(6));
        root.addView(hint);

        // cards, two columns on wide screens
        View[] cards = {discCard(rom), hdCard(), languageCard(), controlsCard(), multiplayerCard(), deluxeCard()};
        if (getResources().getConfiguration().screenWidthDp >= 700) {
            for (int i = 0; i < cards.length; i += 2) root.addView(pair(cards[i], cards[i + 1]));
        } else {
            for (View c : cards) root.addView(c, cardParams(false));
        }
    }

    /* ---------- cards ---------- */

    private View discCard(File rom) {
        LinearLayout c = card("💿", getString(R.string.disc_title));
        if (rom == null) {
            status(c, GREY, getString(R.string.disc_missing));
        } else {
            RomManager.Info info = RomManager.readInfo(rom);
            if (info.isRequired()) {
                status(c, GREEN, getString(R.string.disc_ok, info.id, info.revision, rom.getName()));
            } else {
                status(c, RED, getString(R.string.disc_wrong, info.id));
            }
        }
        desc(c, getString(R.string.disc_desc));
        actions(c, pill(getString(R.string.choose_file), GREEN, ON_ACCENT, () -> pickFile(Purpose.US_DISC)),
                pill(getString(R.string.from_smb), GHOST, TEXT, () -> smbDialog(Purpose.US_DISC)));
        return c;
    }

    private View hdCard() {
        LinearLayout c = card("✨", getString(R.string.hd_title));
        String hd = HdPack.installed(this);
        status(c, hd != null ? GREEN : GREY,
                hd != null ? getString(R.string.hd_installed, hd) : getString(R.string.hd_missing));
        desc(c, getString(R.string.hd_desc));
        if (hd == null) {
            actions(c, pill(getString(R.string.hd_download), GREEN, ON_ACCENT, this::confirmHdDownload),
                    pill(getString(R.string.hd_zip), GHOST, TEXT, () -> pickFile(Purpose.HD_ZIP)));
        } else {
            actions(c, pill(getString(R.string.hd_remove), GHOST, TEXT, () -> new AlertDialog.Builder(this)
                    .setTitle(hd)
                    .setMessage(R.string.hd_remove_confirm)
                    .setPositiveButton(R.string.hd_remove, (d, w) -> {
                        HdPack.remove(this);
                        build();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show()));
        }
        return c;
    }

    private View languageCard() {
        LinearLayout c = card("🌐", getString(R.string.lang_title));
        String ui = Tools.uiLanguage(this);
        String name = Tools.displayName(ui);
        String profile = Tools.gameProfile(ui);
        if (profile.equals("default")) {
            status(c, GREEN, getString(R.string.lang_builtin));
        } else if (Tools.hasTranslation(this, profile)) {
            status(c, GREEN, getString(R.string.lang_ready, name));
        } else {
            status(c, YELLOW, getString(R.string.lang_missing, name));
        }
        desc(c, getString(R.string.lang_desc));
        Button chooser = pill(getString(R.string.language) + ": " + name, GHOST, TEXT, this::chooseLanguage);
        if (profile.equals("default")) {
            actions(c, chooser);
        } else {
            boolean have = Tools.hasTranslation(this, profile);
            actions(c, pill(getString(R.string.lang_create), have ? GHOST : GREEN, have ? TEXT : ON_ACCENT,
                    this::createTranslation), chooser);
        }
        return c;
    }

    private View controlsCard() {
        LinearLayout c = card("🎮", getString(R.string.controls_title));
        boolean on = GameActivity.touchOverlayEnabled(this);
        status(c, on ? GREEN : GREY, getString(on ? R.string.overlay_on : R.string.overlay_off));
        desc(c, getString(R.string.controls_desc));
        actions(c, pill(getString(on ? R.string.turn_off : R.string.turn_on), GHOST, TEXT, () -> {
            GameActivity.setTouchOverlayEnabled(this, !GameActivity.touchOverlayEnabled(this));
            build();
        }));
        return c;
    }

    private View multiplayerCard() {
        LinearLayout c = card("🚂", getString(R.string.mp_title));
        String server = Multiplayer.server(this);
        String shared = Multiplayer.sharedCode(this);
        String visit = Multiplayer.visitCode(this);
        if (server.isEmpty()) {
            status(c, GREY, getString(R.string.mp_no_server));
        } else if (visit != null) {
            status(c, YELLOW, getString(R.string.mp_visiting, visit));
        } else if (shared != null) {
            status(c, YELLOW, getString(R.string.mp_shared, shared));
        } else {
            status(c, GREEN, getString(R.string.mp_server, Uri.parse(server).getAuthority()));
        }
        desc(c, getString(R.string.mp_desc));
        if (server.isEmpty()) {
            actions(c, pill(getString(R.string.mp_set_server), GREEN, ON_ACCENT, this::serverDialog));
            return c;
        }
        actions(c, pill(getString(R.string.mp_share), shared == null ? GREEN : GHOST, shared == null ? ON_ACCENT : TEXT,
                        this::shareTown),
                pill(getString(R.string.mp_visit), GHOST, TEXT, this::visitDialog));
        if (visit != null) {
            actions(c, pill(getString(R.string.mp_send_back), GREEN, ON_ACCENT, this::sendBack),
                    pill(getString(R.string.mp_server_short), GHOST, TEXT, this::serverDialog));
        } else if (shared != null) {
            actions(c, pill(getString(R.string.mp_fetch_back), GREEN, ON_ACCENT, this::fetchBack),
                    pill(getString(R.string.mp_server_short), GHOST, TEXT, this::serverDialog));
        } else {
            actions(c, pill(getString(R.string.mp_server_short), GHOST, TEXT, this::serverDialog));
        }
        return c;
    }

    private View deluxeCard() {
        LinearLayout c = card("🌟", getString(R.string.acdx_title));
        String built = Acdx.builtVersion(this);
        boolean dolphin = Acdx.dolphinInstalled(this);
        if (built == null) {
            status(c, GREY, getString(R.string.acdx_missing));
        } else if (!dolphin) {
            status(c, YELLOW, getString(R.string.acdx_need_dolphin, built));
        } else {
            status(c, GREEN, getString(R.string.acdx_ready, built, Acdx.builtLocation(this)));
        }
        desc(c, getString(R.string.acdx_desc));
        Button buildBtn = pill(getString(built == null ? R.string.acdx_build : R.string.acdx_rebuild),
                built == null ? GREEN : GHOST, built == null ? ON_ACCENT : TEXT, this::confirmDeluxe);
        if (built == null) {
            actions(c, buildBtn, pill(getString(R.string.acdx_about), GHOST, TEXT,
                    () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(Acdx.PAGE)))));
        } else if (!dolphin) {
            actions(c, pill(getString(R.string.acdx_get_dolphin), GREEN, ON_ACCENT, () -> open(Acdx.storeIntent())),
                    buildBtn);
        } else {
            actions(c, pill(getString(R.string.acdx_play), YELLOW, 0xFF2B2A1E, this::playDeluxe), buildBtn);
        }
        return c;
    }

    /* ---------- actions ---------- */

    private void play() {
        File rom = RomManager.findRom(this);
        if (rom == null || !RomManager.readInfo(rom).isRequired()) {
            toast(getString(R.string.play_need_disc));
            return;
        }
        try {
            Tools.applyGameLanguage(this);
        } catch (Exception e) {
            error(e);
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }

    private void chooseLanguage() {
        String[] choices = new String[Tools.UI_LANGS.length + 1];
        String[] labels = new String[choices.length];
        choices[0] = "system";
        labels[0] = getString(R.string.lang_system, Tools.displayName(Tools.systemLanguage()));
        for (int i = 0; i < Tools.UI_LANGS.length; i++) {
            choices[i + 1] = Tools.UI_LANGS[i];
            labels[i + 1] = Tools.displayName(Tools.UI_LANGS[i]);
        }
        int current = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equals(Tools.languageChoice(this))) current = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(labels, current, (d, i) -> {
                    d.dismiss();
                    Tools.setLanguageChoice(this, choices[i]);
                    recreate(); // reload resources in the new language
                })
                .show();
    }

    private void createTranslation() {
        if (RomManager.findRom(this) == null) {
            toast(getString(R.string.lang_need_disc));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.eu_disc)
                .setItems(new String[]{getString(R.string.choose_file), getString(R.string.from_smb)}, (d, j) -> {
                    if (j == 0) pickFile(Purpose.EU_DISC);
                    else smbDialog(Purpose.EU_DISC);
                })
                .show();
    }

    private void confirmDeluxe() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.acdx_title)
                .setMessage(R.string.acdx_confirm)
                .setPositiveButton(R.string.choose_file, (d, w) -> pickFile(Purpose.ACDX_SOURCE))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildDeluxe(Uri source) {
        runTask(getString(R.string.acdx_title), pd -> {
            Acdx.Progress p = (msg, done, total) -> {
                if (total > 0) progress(pd, msg, done, total);
                else step(pd, msg);
            };
            Acdx.Match m = Acdx.match(this, source, p);
            Acdx.build(this, source, m, p);
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.acdx_built_title, m.version))
                    .setMessage(getString(R.string.acdx_built, Acdx.builtLocation(this)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        });
    }

    private void playDeluxe() {
        android.content.SharedPreferences sp = getSharedPreferences("acdx", MODE_PRIVATE);
        if (sp.getBoolean("folder_added", false)) {
            open(Acdx.playIntent());
            return;
        }
        // Dolphin only starts games from its library: the folder must be added there once
        new AlertDialog.Builder(this)
                .setTitle(R.string.acdx_title)
                .setMessage(R.string.acdx_dolphin_setup)
                .setPositiveButton(R.string.acdx_start, (d, w) -> {
                    sp.edit().putBoolean("folder_added", true).apply();
                    open(Acdx.playIntent());
                })
                .setNeutralButton(R.string.acdx_open_dolphin, (d, w) -> {
                    Intent i = getPackageManager().getLaunchIntentForPackage(Acdx.DOLPHIN);
                    if (i != null) open(i);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            error(e);
        }
    }

    private void serverDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView help = text(getString(R.string.mp_server_help), 13, 0xFF666666, false);
        box.addView(help);
        EditText url = field(box, getString(R.string.mp_server_url), Multiplayer.server(this));
        url.setHint("http://192.168.1.10:8765");
        EditText key = field(box, getString(R.string.mp_server_key), Multiplayer.key(this));
        new AlertDialog.Builder(this)
                .setTitle(R.string.mp_set_server)
                .setView(box)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    Multiplayer.setServer(this, url.getText().toString(), key.getText().toString());
                    if (Multiplayer.server(this).isEmpty()) {
                        build();
                        return;
                    }
                    runTask(getString(R.string.mp_title), pd -> {
                        String mode = Multiplayer.checkServer(this);
                        runOnUiThread(() -> toast(getString(R.string.mp_server_ok)
                                + (mode.equals("key") && Multiplayer.key(this).isEmpty()
                                ? "\n" + getString(R.string.mp_server_needs_key) : "")));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareTown() {
        String old = Multiplayer.sharedCode(this);
        if (old != null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.mp_code_title, old))
                    .setMessage(R.string.mp_reshare)
                    .setPositiveButton(R.string.mp_share_new, (d, w) -> {
                        runTask(getString(R.string.mp_title), pd -> Multiplayer.unshare(this));
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.mp_share)
                .setMessage(R.string.mp_share_confirm)
                .setPositiveButton(R.string.mp_share, (d, w) -> runTask(getString(R.string.mp_title), pd -> {
                    String code = Multiplayer.share(this);
                    runOnUiThread(() -> showCode(code));
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCode(String code) {
        TextView big = text(code, 40, 0xFF2B2A1E, true);
        big.setGravity(Gravity.CENTER);
        big.setLetterSpacing(0.15f);
        big.setPadding(0, dp(16), 0, dp(8));
        big.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.mp_code_ready)
                .setView(big)
                .setMessage(R.string.mp_code_help)
                .setPositiveButton(R.string.mp_copy, (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code));
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void visitDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText code = field(box, getString(R.string.mp_code), "");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.mp_visit)
                .setView(box)
                .setPositiveButton(R.string.mp_visit, (d, w) -> {
                    String c = code.getText().toString().trim().toUpperCase(Locale.US).replace(" ", "");
                    runTask(getString(R.string.mp_title), pd -> {
                        org.json.JSONObject info = Multiplayer.info(this, c);
                        Multiplayer.visit(this, c);
                        String town = info.optString("town");
                        runOnUiThread(() -> new AlertDialog.Builder(this)
                                .setTitle(R.string.mp_visit_ready_title)
                                .setMessage(getString(R.string.mp_visit_ready, town.isEmpty() ? c : town))
                                .setPositiveButton(R.string.play, (d2, w2) -> play())
                                .setNegativeButton(android.R.string.ok, null)
                                .show());
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void sendBack() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.mp_send_back)
                .setMessage(R.string.mp_send_back_confirm)
                .setPositiveButton(R.string.mp_send_back, (d, w) -> runTask(getString(R.string.mp_title), pd -> {
                    Multiplayer.sendBack(this);
                    runOnUiThread(() -> toast(getString(R.string.mp_sent_back)));
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fetchBack() {
        String code = Multiplayer.sharedCode(this);
        runTask(getString(R.string.mp_title), pd -> {
            org.json.JSONObject info = Multiplayer.info(this, code);
            runOnUiThread(() -> {
                if (!info.optBoolean("returned")) {
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.mp_code_title, code))
                            .setMessage(R.string.mp_not_returned)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton(R.string.mp_unshare, (d, w) ->
                                    runTask(getString(R.string.mp_title), p2 -> Multiplayer.unshare(this)))
                            .show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.mp_fetch_back)
                        .setMessage(getString(R.string.mp_fetch_confirm, info.optString("visitor")))
                        .setPositiveButton(R.string.mp_fetch_back, (d, w) -> runTask(getString(R.string.mp_title), p2 -> {
                            Multiplayer.fetchBack(this);
                            runOnUiThread(() -> toast(getString(R.string.mp_fetched)));
                        }))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        });
    }

    private void confirmHdDownload() {
        new AlertDialog.Builder(this)
                .setTitle(HdPack.NAME)
                .setMessage(R.string.hd_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> runTask(getString(R.string.hd_title), pd -> {
                    File zip = HdPack.download(this, (stage, done, total) ->
                            progress(pd, getString(R.string.downloading, (int) (done >> 20)), done, total));
                    installHd(pd, zip);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void installHd(ProgressDialog pd, File zip) throws Exception {
        try {
            int n = HdPack.install(this, zip, (stage, done, total) ->
                    progress(pd, getString(R.string.converting, (int) done, (int) total), done, total));
            runOnUiThread(() -> toast(getString(R.string.textures_installed, n)));
        } finally {
            zip.delete();
        }
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
        if (p == Purpose.ACDX_SOURCE) {
            buildDeluxe(uri); // read in place, a full disc image is 1.4 GB
            return;
        }
        runTask(getString(R.string.import_title), pd -> {
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
                        throw new IllegalArgumentException(getString(R.string.not_us_disc, info));
                    }
                    Tools.importUsDisc(this, tmp, msg -> step(pd, msg));
                    break;
                }
                case EU_DISC: {
                    String profile = Tools.gameProfile(Tools.uiLanguage(this));
                    Tools.generateTranslation(this, RomManager.findRom(this), tmp, profile, msg -> step(pd, msg));
                    break;
                }
                case HD_ZIP:
                    installHd(pd, tmp);
                    break;
                case ACDX_SOURCE:
                    break;
            }
        } finally {
            tmp.delete();
        }
    }

    private void smbDialog(Purpose p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText host = field(box, getString(R.string.smb_server), pref("host"));
        EditText user = field(box, getString(R.string.smb_user), pref("user"));
        EditText pass = field(box, getString(R.string.smb_password), pref("pass"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText share = field(box, getString(R.string.smb_share), pref("share"));
        new AlertDialog.Builder(this)
                .setTitle("SMB")
                .setView(box)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    savePref("host", host.getText().toString().trim());
                    savePref("user", user.getText().toString().trim());
                    savePref("pass", pass.getText().toString());
                    savePref("share", share.getText().toString().trim());
                    smbList(p);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void smbList(Purpose p) {
        runTask("SMB", pd -> {
            SmbClient client = new SmbClient(pref("host"), pref("user"), pref("pass"));
            List<SmbClient.Entry> entries = client.listRomFiles(pref("share"));
            runOnUiThread(() -> {
                if (entries.isEmpty()) {
                    toast(getString(R.string.no_disc_images));
                    return;
                }
                String[] names = new String[entries.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = String.format(Locale.US, "%s  (%.2f GB)", entries.get(i).name,
                            entries.get(i).size / 1073741824.0);
                }
                new AlertDialog.Builder(this)
                        .setItems(names, (d, i) -> runTask(getString(R.string.import_title), pd2 -> {
                            SmbClient.Entry e = entries.get(i);
                            File tmp = tempFor(p);
                            try (InputStream in = client.open(pref("share"), e.name)) {
                                copy(in, tmp, e.size, pd2);
                            }
                            process(p, tmp, pd2);
                        }))
                        .setNegativeButton(R.string.cancel, null)
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
                    build();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    error(e);
                    build();
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
                    progress(pd, getString(R.string.copying, (int) (done >> 20)), done, total);
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

    /** Progress from the Python tools: step keys map to localized strings. */
    private void step(ProgressDialog pd, String key) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        String msg = id != 0 ? getString(id) : key;
        runOnUiThread(() -> {
            pd.setIndeterminate(true);
            pd.setMessage(msg);
        });
    }

    private void error(Throwable e) {
        String msg = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        if (e instanceof jcifs.CIFSException) msg = describeSmb(e) + "\n\n(" + msg + ")";
        new AlertDialog.Builder(this)
                .setTitle(R.string.error)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String describeSmb(Throwable e) {
        String raw = String.valueOf(e);
        if (e instanceof jcifs.smb.SmbAuthException || raw.contains("Access is denied")
                || raw.contains("LOGON_FAILURE") || raw.contains("ACCESS_DENIED")) {
            return "SMB: access denied – check user and password (guest access may be disabled).";
        }
        if (raw.contains("UnknownHost")) return "SMB: server not found – check host/IP.";
        return "SMB: connection failed – check host/IP and port 445.";
    }

    /* ---------- UI helpers ---------- */

    private LinearLayout card(String icon, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), CARD_STROKE);
        c.setBackground(g);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text(icon, 19, TEXT, false);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setColor(0x1FFFFFFF);
        circle.setShape(GradientDrawable.OVAL);
        ic.setBackground(circle);
        head.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = text(title, 19, TEXT, true);
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        c.addView(head);
        return c;
    }

    private void status(LinearLayout card, int color, String s) {
        TextView t = text("●  " + s, 14, color, true);
        t.setPadding(0, dp(12), 0, 0);
        card.addView(t);
    }

    private void desc(LinearLayout card, String s) {
        TextView t = text(s, 13, TEXT_DIM, false);
        t.setPadding(0, dp(8), 0, 0);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f); // pushes actions to the bottom
        card.addView(t, lp);
    }

    private void actions(LinearLayout card, Button... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setBaselineAligned(false);  // auto-sized labels would otherwise shift their pill down
        r.setPadding(0, dp(14), 0, 0);
        for (Button b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(8);
            r.addView(b, lp);
        }
        card.addView(r);
    }

    private LinearLayout pair(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.addView(a, cardParams(true));
        r.addView(b, cardParams(true));
        return r;
    }

    private LinearLayout.LayoutParams cardParams(boolean column) {
        LinearLayout.LayoutParams lp = column
                ? new LinearLayout.LayoutParams(0, -1, 1f)
                : new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(6), dp(8), dp(6), dp(8));
        return lp;
    }

    private Button pill(String label, int fill, int textColor, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMaxLines(1);
        // long translations shrink instead of wrapping inside the pill
        b.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(rounded(fill, dp(22), fill == GHOST ? 0x55FFFFFF : 0));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Drawable rounded(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, mask);
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText field(LinearLayout box, String label, String initial) {
        box.addView(text(label, 12, TEXT_DIM, false));
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
