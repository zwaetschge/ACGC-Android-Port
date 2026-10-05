package com.acpc.port;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class GameActivity extends SDLActivity {

    /** Pushes the on-screen pad state into PADRead (android_glue.c). */
    public static native void nativeTouchPad(int buttons, int stickX, int stickY, int cstickX, int cstickY);

    private TouchControlsView touchControls;

    @Override
    protected String[] getLibraries() {
        // single library: game code + SDL2 static
        return new String[]{"AnimalCrossing"};
    }

    @Override
    protected String[] getArguments() {
        return new String[]{"--verbose"};
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        extractAssetsOnce();
        super.onCreate(savedInstanceState);
        if (mLayout != null) {
            touchControls = new TouchControlsView(this);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** A physical controller or keyboard hides the overlay; touching the screen shows it again. */
    private void hideTouchControls() {
        if (touchControls != null && touchControls.getAlpha() > 0f) {
            touchControls.setAlpha(0f);
            touchControls.reset();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (touchControls != null && touchControls.getAlpha() == 0f
                && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchControls.setAlpha(1f);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int vol = event.getKeyCode();
        if (vol != KeyEvent.KEYCODE_VOLUME_UP && vol != KeyEvent.KEYCODE_VOLUME_DOWN
                && vol != KeyEvent.KEYCODE_BACK && event.getDeviceId() > 0) {
            hideTouchControls();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            hideTouchControls();
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /** The native layer loads shaders and writes saves relative to filesDir. */
    private void extractAssetsOnce() {
        File dir = getFilesDir();
        File[] dirs = {
                new File(dir, "rom"), new File(dir, "save"),
                new File(dir, "save/card_a"), new File(dir, "save/card_b"),
                new File(dir, "texture_pack"), new File(dir, "shaders"),
        };
        for (File d : dirs) if (!d.exists()) d.mkdirs();

        copyAssetIfNeeded("shaders/default.vert", new File(dir, "shaders/default.vert"));
        copyAssetIfNeeded("shaders/default.frag", new File(dir, "shaders/default.frag"));
    }

    private void copyAssetIfNeeded(String asset, File target) {
        if (target.exists() && target.length() > 0) return;
        try (InputStream in = getAssets().open(asset);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }
}
