package com.acpc.port;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * GameCube-style on-screen controls drawn over the SDL surface.
 * Left: floating analog stick (main stick) and D-pad. Right: A/B/X/Y, a small
 * C-stick pad for the camera, Z. Top corners: L/R. Bottom center: START.
 * State is pushed to native code (PADRead) on every change.
 */
public class TouchControlsView extends View {
    static final int BTN_LEFT = 1, BTN_RIGHT = 1 << 1, BTN_DOWN = 1 << 2, BTN_UP = 1 << 3;
    static final int TRIG_Z = 1 << 4, TRIG_R = 1 << 5, TRIG_L = 1 << 6;
    static final int BTN_A = 1 << 8, BTN_B = 1 << 9, BTN_X = 1 << 10, BTN_Y = 1 << 11, BTN_START = 1 << 12;
    static final int STICK_MAX = 100;

    private static class Button {
        final int mask;
        final String label;
        final int color;
        float cx, cy, r;      // circle buttons
        RectF rect;           // pill buttons (L/R/Z/START)

        Button(int mask, String label, int color) {
            this.mask = mask;
            this.label = label;
            this.color = color;
        }

        boolean hit(float x, float y) {
            if (rect != null) {
                RectF grown = new RectF(rect);
                grown.inset(-rect.height() * 0.3f, -rect.height() * 0.3f);
                return grown.contains(x, y);
            }
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= (r * 1.25f) * (r * 1.25f);
        }
    }

    private final Button[] buttons = {
        new Button(BTN_A, "A", 0xFF2EBD6B), new Button(BTN_B, "B", 0xFFE04848),
        new Button(BTN_X, "X", 0xFFE8E8E8), new Button(BTN_Y, "Y", 0xFFE8E8E8),
        new Button(TRIG_Z, "Z", 0xFF7A5CE0), new Button(TRIG_L, "L", 0xFFBDBDBD),
        new Button(TRIG_R, "R", 0xFFBDBDBD), new Button(BTN_START, "START", 0xFFBDBDBD),
        new Button(BTN_UP, "", 0xFFBDBDBD), new Button(BTN_DOWN, "", 0xFFBDBDBD),
        new Button(BTN_LEFT, "", 0xFFBDBDBD), new Button(BTN_RIGHT, "", 0xFFBDBDBD),
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    // main stick: floats to where the thumb lands inside the left zone
    private float stickBaseX, stickBaseY, stickHomeX, stickHomeY, stickRadius;
    private float stickKnobX, stickKnobY;
    private int stickPointer = -1;
    // C-stick: fixed small pad
    private float cBaseX, cBaseY, cRadius, cKnobX, cKnobY;
    private int cPointer = -1;

    private int lastButtons = -1, lastSx, lastSy, lastCx, lastCy;
    private int pressed;

    public TouchControlsView(Context ctx) {
        super(ctx);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float u = Math.min(w, h) / 100f; // 1% of the short side
        stickRadius = 13 * u;
        stickHomeX = 18 * u;
        stickHomeY = h - 20 * u;
        stickBaseX = stickKnobX = stickHomeX;
        stickBaseY = stickKnobY = stickHomeY;

        float ax = w - 19 * u, ay = h - 50 * u, br = 7.5f * u; // above the clock HUD
        place(BTN_A, ax, ay, br);
        place(BTN_B, ax - 13 * u, ay + 7 * u, br * 0.7f);
        place(BTN_X, ax + 12 * u, ay - 7 * u, br * 0.65f); // clear of A
        place(BTN_Y, ax - 5 * u, ay - 13 * u, br * 0.65f);

        cRadius = 7 * u;
        cBaseX = cKnobX = ax - 17 * u;
        cBaseY = cKnobY = ay - 24 * u;

        float dx = stickHomeX, dy = stickHomeY - 34 * u, dr = 4.2f * u;
        place(BTN_UP, dx, dy - 7 * u, dr);
        place(BTN_DOWN, dx, dy + 7 * u, dr);
        place(BTN_LEFT, dx - 7 * u, dy, dr);
        place(BTN_RIGHT, dx + 7 * u, dy, dr);

        float pw = 16 * u, ph = 6 * u;
        rect(TRIG_L, new RectF(3 * u, 3 * u, 3 * u + pw, 3 * u + ph));
        rect(TRIG_R, new RectF(w - 3 * u - pw, 3 * u, w - 3 * u, 3 * u + ph));
        rect(TRIG_Z, new RectF(w - 3 * u - pw, 12 * u, w - 3 * u, 12 * u + ph));
        rect(BTN_START, new RectF(w / 2f - pw * 0.6f, h - 9 * u, w / 2f + pw * 0.6f, h - 9 * u + ph));
        text.setTextSize(3.6f * u);
        stroke.setStrokeWidth(0.5f * u);
    }

    private Button find(int mask) {
        for (Button b : buttons) if (b.mask == mask) return b;
        return null;
    }

    private void place(int mask, float x, float y, float r) {
        Button b = find(mask);
        b.cx = x; b.cy = y; b.r = r;
    }

    private void rect(int mask, RectF r) {
        find(mask).rect = r;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (stickPointer < 0 && x < getWidth() * 0.4f && y > getHeight() * 0.45f) {
                stickPointer = id;
                stickBaseX = stickKnobX = x;
                stickBaseY = stickKnobY = y;
            } else if (cPointer < 0 && dist(x, y, cBaseX, cBaseY) < cRadius * 1.8f) {
                cPointer = id;
                cKnobX = x;
                cKnobY = y;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            boolean all = action != MotionEvent.ACTION_POINTER_UP; // last finger lifted
            if (all || id == stickPointer) {
                stickPointer = -1;
                stickBaseX = stickKnobX = stickHomeX;
                stickBaseY = stickKnobY = stickHomeY;
            }
            if (all || id == cPointer) {
                cPointer = -1;
                cKnobX = cBaseX;
                cKnobY = cBaseY;
            }
        }

        // Buttons: every active pointer that is not a stick contributes.
        int mask = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            int pid = e.getPointerId(i);
            boolean lifting = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_POINTER_UP && i == idx));
            if (lifting) continue;
            float x = e.getX(i), y = e.getY(i);
            if (pid == stickPointer) {
                stickKnobX = x;
                stickKnobY = y;
                continue;
            }
            if (pid == cPointer) {
                cKnobX = x;
                cKnobY = y;
                continue;
            }
            for (Button b : buttons) if (b.hit(x, y)) mask |= b.mask;
        }
        pressed = mask;
        push();
        invalidate();
        return true;
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x0 - x1, dy = y0 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static int axis(float delta, float radius) {
        float v = delta / radius;
        if (Math.abs(v) < 0.15f) return 0;
        if (v > 1) v = 1;
        if (v < -1) v = -1;
        return Math.round(v * STICK_MAX);
    }

    private void push() {
        int sx = 0, sy = 0, cx = 0, cy = 0;
        if (stickPointer >= 0) {
            float dx = stickKnobX - stickBaseX, dy = stickKnobY - stickBaseY;
            float d = dist(stickKnobX, stickKnobY, stickBaseX, stickBaseY);
            if (d > stickRadius) {
                dx *= stickRadius / d;
                dy *= stickRadius / d;
                stickKnobX = stickBaseX + dx;
                stickKnobY = stickBaseY + dy;
            }
            sx = axis(dx, stickRadius);
            sy = -axis(dy, stickRadius);
        }
        if (cPointer >= 0) {
            cx = axis(cKnobX - cBaseX, cRadius);
            cy = -axis(cKnobY - cBaseY, cRadius);
        }
        if (pressed != lastButtons || sx != lastSx || sy != lastSy || cx != lastCx || cy != lastCy) {
            lastButtons = pressed; lastSx = sx; lastSy = sy; lastCx = cx; lastCy = cy;
            GameActivity.nativeTouchPad(pressed, sx, sy, cx, cy);
        }
    }

    /** Release everything (overlay hidden while a physical controller is used). */
    public void reset() {
        stickPointer = cPointer = -1;
        stickBaseX = stickKnobX = stickHomeX;
        stickBaseY = stickKnobY = stickHomeY;
        cKnobX = cBaseX;
        cKnobY = cBaseY;
        pressed = 0;
        push();
    }

    @Override
    protected void onDraw(Canvas c) {
        // main stick
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x33FFFFFF);
        c.drawCircle(stickBaseX, stickBaseY, stickRadius, fill);
        stroke.setColor(0x88FFFFFF);
        c.drawCircle(stickBaseX, stickBaseY, stickRadius, stroke);
        fill.setColor(0x99D0D0D0);
        c.drawCircle(stickKnobX, stickKnobY, stickRadius * 0.45f, fill);

        // C-stick
        fill.setColor(0x26F5C518);
        c.drawCircle(cBaseX, cBaseY, cRadius, fill);
        stroke.setColor(0x77F5C518);
        c.drawCircle(cBaseX, cBaseY, cRadius, stroke);
        fill.setColor(0xAAF5C518);
        c.drawCircle(cKnobX, cKnobY, cRadius * 0.5f, fill);
        text.setColor(0xCC3A2E00);
        c.drawText("C", cKnobX, cKnobY + text.getTextSize() * 0.35f, text);

        for (Button b : buttons) {
            boolean on = (pressed & b.mask) != 0;
            int base = b.color & 0x00FFFFFF;
            fill.setColor((on ? 0xCC000000 : 0x38000000) | base);
            stroke.setColor(on ? 0xCCFFFFFF : 0x66FFFFFF);
            if (b.rect != null) {
                float rr = b.rect.height() / 2;
                c.drawRoundRect(b.rect, rr, rr, fill);
                c.drawRoundRect(b.rect, rr, rr, stroke);
                text.setColor(0xAA202020);
                c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + text.getTextSize() * 0.35f, text);
            } else {
                c.drawCircle(b.cx, b.cy, b.r, fill);
                c.drawCircle(b.cx, b.cy, b.r, stroke);
                if (!b.label.isEmpty()) {
                    text.setColor(b.mask == BTN_A || b.mask == BTN_B ? 0xCCFFFFFF : 0xAA202020);
                    c.drawText(b.label, b.cx, b.cy + text.getTextSize() * 0.35f, text);
                }
            }
        }
    }
}
