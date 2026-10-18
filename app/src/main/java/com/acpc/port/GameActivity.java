package com.acpc.port;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class GameActivity extends SDLActivity {

    /** Pushes the on-screen pad state into PADRead (android_glue.c). */
    public static native void nativeTouchPad(int buttons, int stickX, int stickY, int cstickX, int cstickY);

    private static final int[] RENDER_HEIGHTS = {480, 540, 720, 0};
    private static final int DEFAULT_RENDER_HEIGHT = 540;

    private TouchControlsView touchControls;

    @Override
    protected String[] getLibraries() {
        // single library: game code + SDL2 static
        return new String[]{"AnimalCrossing"};
    }

    @Override
    protected String[] getArguments() {
        return new String[0]; // "--verbose" enables the diagnostic logs in files/stdout.log
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        extractAssetsOnce();
        super.onCreate(savedInstanceState);
        limitRenderResolution();
        lockRefreshRate();
        if (mLayout != null && touchOverlayEnabled(this)) {
            touchControls = new TouchControlsView(this);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** Internal render height in pixels, 0 = native. */
    static int renderHeight(Context ctx) {
        return ctx.getSharedPreferences("video", MODE_PRIVATE).getInt("render_height", DEFAULT_RENDER_HEIGHT);
    }

    static void setRenderHeight(Context ctx, int height) {
        ctx.getSharedPreferences("video", MODE_PRIVATE).edit().putInt("render_height", height).apply();
    }

    static int nextRenderHeight(int current) {
        for (int i = 0; i < RENDER_HEIGHTS.length; i++) {
            if (RENDER_HEIGHTS[i] == current) return RENDER_HEIGHTS[(i + 1) % RENDER_HEIGHTS.length];
        }
        return DEFAULT_RENDER_HEIGHT;
    }

    /** A fixed-size surface is upscaled by the display compositor, so the GPU only fills the smaller buffer. */
    private void limitRenderResolution() {
        int target = renderHeight(this);
        if (target <= 0 || mSurface == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        if (shortSide <= target) return;
        int width = Math.round(longSide * (float) target / shortSide) & ~1;
        // SDL adds the surface as wrap_content, which would show the small buffer unscaled
        mSurface.setLayoutParams(new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSurface.getHolder().setFixedSize(width, target);
    }

    /** The game is paced at 60 fps; on a 90/120 Hz panel that judders unless the display runs at 60. */
    private void lockRefreshRate() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredRefreshRate = 60f;
        getWindow().setAttributes(lp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mSurface != null) {
            mSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    holder.getSurface().setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                }
            });
        }
    }

    static boolean touchOverlayEnabled(android.content.Context ctx) {
        return ctx.getSharedPreferences("controls", MODE_PRIVATE).getBoolean("touch_overlay", true);
    }

    static void setTouchOverlayEnabled(android.content.Context ctx, boolean on) {
        ctx.getSharedPreferences("controls", MODE_PRIVATE).edit().putBoolean("touch_overlay", on).apply();
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
