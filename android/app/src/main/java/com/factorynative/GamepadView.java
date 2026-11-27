package com.factorynative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLControllerManager;

/**
 * The on-screen gamepad (gear menu → Управление → Геймпад). It is a real gamepad for the game: a virtual SDL
 * joystick (SDLControllerManager.fnAddVirtualPad) with the standard mapping, so Factorio uses its own controller
 * mode. Each block (stick, d-pad, A B X Y, shoulder button) is its own view: a touch past them goes to the game,
 * and several fingers work at once.
 *
 * SDL takes the first value of an Android axis as its rest, so every touch first sends the rest value
 * (0 for a stick, -1 for a trigger) and only then the real one.
 */
final class GamepadView extends ViewGroup {
    /** The joystick's id among the Android devices: far from the real ones. */
    static final int DEVICE_ID = 0x46550001;
    private static final int AXIS_LX = 0, AXIS_LY = 1, AXIS_RX = 2, AXIS_RY = 3, AXIS_LT = 4, AXIS_RT = 5;

    private final float dp;
    private boolean attached;
    private final Stick leftStick, rightStick;
    private final Cluster dpad, faces;
    private final Button lt, lb, rt, rb, back, start, l3, r3;

    GamepadView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        leftStick = add(new Stick(c, AXIS_LX, AXIS_LY));
        rightStick = add(new Stick(c, AXIS_RX, AXIS_RY));
        dpad = add(new Cluster(c, true, new String[] {"▲", "▶", "▼", "◀"}, new int[] {KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT}, null));
        // Y on top, B right, A below, X left; the Xbox colours
        faces = add(new Cluster(c, false, new String[] {"Y", "B", "A", "X"}, new int[] {KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X},
                new int[] {0xFFE8C030, 0xFFE04848, 0xFF60C040, 0xFF4890E8}));
        lt = add(new Button(c, "LT", 0, AXIS_LT));
        lb = add(new Button(c, "LB", KeyEvent.KEYCODE_BUTTON_L1, -1));
        rt = add(new Button(c, "RT", 0, AXIS_RT));
        rb = add(new Button(c, "RB", KeyEvent.KEYCODE_BUTTON_R1, -1));
        back = add(new Button(c, "Back", KeyEvent.KEYCODE_BUTTON_SELECT, -1));
        start = add(new Button(c, "Start", KeyEvent.KEYCODE_BUTTON_START, -1));
        l3 = add(new Button(c, "L3", KeyEvent.KEYCODE_BUTTON_THUMBL, -1));
        r3 = add(new Button(c, "R3", KeyEvent.KEYCODE_BUTTON_THUMBR, -1));
    }

    private <T extends View> T add(T v) {
        addView(v);
        return v;
    }

    /** Plugs the gamepad into SDL (once SDL runs); the game sees a gamepad connected. */
    void attachPad() {
        if (attached) return;
        attached = true;
        SDLControllerManager.fnAddVirtualPad(DEVICE_ID, "FNative Gamepad");
    }

    /** Lets everything go and unplugs the gamepad. */
    void detachPad() {
        if (!attached) return;
        for (int i = 0; i < getChildCount(); i++) ((Control) getChildAt(i)).releaseAll();
        SDLControllerManager.fnRemoveVirtualPad(DEVICE_ID);
        attached = false;
    }

    boolean isAttached() {
        return attached;
    }

    private void axis(int a, float v) {
        if (attached) SDLControllerManager.fnVirtualPadAxis(DEVICE_ID, a, Math.max(-1f, Math.min(1f, v)));
    }

    private void button(int keycode, boolean down) {
        if (!attached) return;
        if (down) SDLControllerManager.onNativePadDown(DEVICE_ID, keycode, 0);
        else SDLControllerManager.onNativePadUp(DEVICE_ID, keycode, 0);
    }

    // ---- layout: shoulders on top, Back / Start in the middle, the left stick and the A B X Y where the thumbs
    // rest (bottom corners), the d-pad and the right stick above them; the gear (bottom right) stays free

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        layoutRects(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            RectF r = ((Control) v).rect;
            v.measure(MeasureSpec.makeMeasureSpec(Math.round(r.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(r.height()), MeasureSpec.EXACTLY));
        }
    }

    private void layoutRects(int w, int h) {
        float m = 8 * dp, gear = 60 * dp;
        // smaller on a small screen
        float s = Math.max(0.7f, Math.min(1.2f, h / (400 * dp)));
        float sw = 220 * dp * s, sh = 180 * dp * s, pad = 124 * dp * s, face = 140 * dp * s;
        float bw = 64 * dp * s, bh = 38 * dp * s, small = 44 * dp * s, top = m + bh + m;

        lt.rect.set(m, m, m + bw, m + bh);
        lb.rect.set(m + bw + m, m, m + 2 * bw + m, m + bh);
        rt.rect.set(w - m - bw, m, w - m, m + bh);
        rb.rect.set(w - 2 * m - 2 * bw, m, w - 2 * m - bw, m + bh);
        float cw = 60 * dp * s;
        back.rect.set(w / 2f - m - cw, m, w / 2f - m, m + bh * 0.85f);
        start.rect.set(w / 2f + m, m, w / 2f + m + cw, m + bh * 0.85f);

        leftStick.rect.set(m, h - m - sh, m + sw, h - m);
        dpad.rect.set(m + 2 * m, top, m + 2 * m + pad, top + pad);
        l3.rect.set(dpad.rect.right + m, top + (pad - small) / 2, dpad.rect.right + m + small, top + (pad + small) / 2);

        faces.rect.set(w - gear - face, h - m - face, w - gear, h - m);
        float rTop = top, rBottom = faces.rect.top - m;
        rightStick.rect.set(w - m - sw, rTop, w - m, Math.max(rTop + 120 * dp * s, rBottom));
        r3.rect.set(rightStick.rect.left - m - small, rTop + (pad - small) / 2, rightStick.rect.left - m, rTop + (pad + small) / 2);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            RectF rr = ((Control) v).rect;
            v.layout(Math.round(rr.left), Math.round(rr.top), Math.round(rr.right), Math.round(rr.bottom));
        }
    }

    // ---- the blocks

    private abstract class Control extends View {
        final RectF rect = new RectF(), box = new RectF();  // box: onDraw's outline, reused
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Control(Context c) {
            super(c);
            fill.setColor(0x30FFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * dp);
            line.setColor(0x90FFFFFF);
            text.setColor(0xDDFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        abstract void releaseAll();

        void label(Canvas c, String s, float x, float y, float size) {
            text.setTextSize(size);
            c.drawText(s, x, y - (text.descent() + text.ascent()) / 2, text);
        }

        void tick() {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /** One button: a key, or a trigger (an axis from -1 up to 1 while held). Down while any finger is on it. */
    private final class Button extends Control {
        private final String name;
        private final int keycode, axis;
        private int fingers;

        Button(Context c, String name, int keycode, int axis) {
            super(c);
            this.name = name;
            this.keycode = keycode;
            this.axis = axis;
        }

        private void set(boolean down) {
            if (axis >= 0) {
                if (down) axis(axis, -1f);  // the rest first (see the class note)
                axis(axis, down ? 1f : -1f);
            } else button(keycode, down);
            if (down) tick();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (fingers++ == 0) set(true);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (fingers > 0 && --fingers == 0) set(false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    return true;
            }
            return true;
        }

        @Override
        void releaseAll() {
            if (fingers > 0) {
                fingers = 0;
                set(false);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            fill.setColor(fingers > 0 ? 0x90FFFFFF : 0x30FFFFFF);
            RectF b = box;
            b.set(line.getStrokeWidth(), line.getStrokeWidth(), w - line.getStrokeWidth(), h - line.getStrokeWidth());
            c.drawRoundRect(b, r, r, fill);
            c.drawRoundRect(b, r, r, line);
            label(c, name, w / 2, h / 2, Math.min(h * 0.42f, w * 0.3f));
        }
    }

    /**
     * Four buttons round a centre: the d-pad (8 directions: a diagonal holds two) or A B X Y (the one nearest to
     * the finger). A finger may slide from one to another.
     */
    private final class Cluster extends Control {
        private final boolean dpadMode;
        private final String[] names;
        private final int[] keycodes, colours;
        private final android.util.SparseIntArray byFinger = new android.util.SparseIntArray();
        private int held;  // bit i: names[i] is down

        Cluster(Context c, boolean dpadMode, String[] names, int[] keycodes, int[] colours) {
            super(c);
            this.dpadMode = dpadMode;
            this.names = names;
            this.keycodes = keycodes;
            this.colours = colours;
        }

        /** The buttons under a finger at (x, y): bit 0 up, 1 right, 2 down, 3 left. */
        private int at(float x, float y) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, dx = x - cx, dy = y - cy;
            float dead = Math.min(getWidth(), getHeight()) * 0.12f;
            if (dx * dx + dy * dy < dead * dead) return 0;
            double a = Math.toDegrees(Math.atan2(dx, -dy));  // 0 up, 90 right
            if (a < 0) a += 360;
            if (!dpadMode) return 1 << (int) (((a + 45) % 360) / 90);
            int sector = (int) (((a + 22.5) % 360) / 45);  // 0 up, 1 up-right, 2 right…
            int main = 1 << (sector / 2 % 4);
            return sector % 2 == 0 ? main : main | (1 << ((sector + 1) / 2 % 4));
        }

        private void update() {
            int now = 0;
            for (int i = 0; i < byFinger.size(); i++) now |= byFinger.valueAt(i);
            int changed = now ^ held;
            if (changed == 0) return;
            for (int i = 0; i < 4; i++) {
                if ((changed & (1 << i)) == 0) continue;
                boolean down = (now & (1 << i)) != 0;
                button(keycodes[i], down);
                if (down) tick();
            }
            held = now;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int i = e.getActionIndex();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    byFinger.put(e.getPointerId(i), at(e.getX(i), e.getY(i)));
                    break;
                case MotionEvent.ACTION_MOVE:
                    for (int p = 0; p < e.getPointerCount(); p++) byFinger.put(e.getPointerId(p), at(e.getX(p), e.getY(p)));
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    byFinger.delete(e.getPointerId(i));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    byFinger.clear();
                    break;
            }
            update();
            return true;
        }

        @Override
        void releaseAll() {
            byFinger.clear();
            update();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, size = Math.min(w, h);
            float off = size * 0.33f, r = size * 0.16f;
            float[][] pos = {{cx, cy - off}, {cx + off, cy}, {cx, cy + off}, {cx - off, cy}};
            for (int i = 0; i < 4; i++) {
                boolean down = (held & (1 << i)) != 0;
                fill.setColor(down ? 0x90FFFFFF : 0x30FFFFFF);
                c.drawCircle(pos[i][0], pos[i][1], r, fill);
                line.setColor(colours != null ? (colours[i] & 0x00FFFFFF) | 0xC0000000 : 0x90FFFFFF);
                c.drawCircle(pos[i][0], pos[i][1], r, line);
                text.setColor(colours != null ? colours[i] : 0xDDFFFFFF);
                label(c, names[i], pos[i][0], pos[i][1], r * (dpadMode ? 0.8f : 1.05f));
            }
            text.setColor(0xDDFFFFFF);
            line.setColor(0x90FFFFFF);
        }
    }

    /**
     * A stick: the centre is where the finger lands (anywhere in the block), the knob follows the finger up to
     * the ring's edge; up sends the stick home.
     */
    private final class Stick extends Control {
        private final int ax, ay;
        private int finger = -1;
        private float cx, cy, kx, ky;

        Stick(Context c, int ax, int ay) {
            super(c);
            this.ax = ax;
            this.ay = ay;
        }

        private float radius() {
            return Math.min(getWidth(), getHeight()) * 0.3f;
        }

        private void move(float x, float y) {
            float r = radius(), dx = x - cx, dy = y - cy, d = (float) Math.hypot(dx, dy);
            if (d > r) {
                dx *= r / d;
                dy *= r / d;
            }
            kx = cx + dx;
            ky = cy + dy;
            axis(ax, dx / r);
            axis(ay, dy / r);  // SDL: down is positive, as on screen
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int i = e.getActionIndex();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (finger >= 0) return true;  // one finger drives a stick
                    finger = e.getPointerId(i);
                    cx = e.getX(i);
                    cy = e.getY(i);
                    axis(ax, 0f);  // the rest first (see the class note)
                    axis(ay, 0f);
                    move(cx, cy);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int p = e.findPointerIndex(finger);
                    if (p >= 0) move(e.getX(p), e.getY(p));
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerId(i) == finger) releaseAll();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    return true;
            }
            return true;
        }

        @Override
        void releaseAll() {
            if (finger < 0) return;
            finger = -1;
            axis(ax, 0f);
            axis(ay, 0f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = radius();
            float x = finger >= 0 ? cx : getWidth() / 2f, y = finger >= 0 ? cy : getHeight() / 2f;
            fill.setColor(0x24FFFFFF);
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r, line);
            fill.setColor(finger >= 0 ? 0x90FFFFFF : 0x50FFFFFF);
            c.drawCircle(finger >= 0 ? kx : x, finger >= 0 ? ky : y, r * 0.45f, fill);
        }
    }
}
