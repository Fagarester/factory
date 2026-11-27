package com.factorynative;

import android.graphics.Color;
import java.io.File;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import org.libsdl.app.SDLActivity;

import static com.factorynative.GameActivity.*;

final class Tutorial extends FrameLayout {
    private final android.graphics.Paint dim = new android.graphics.Paint(), ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
            finger = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final LinearLayout card;
    private final TextView title, body, count;
    private final android.widget.Button back, next;
    private java.util.List<TutStep> steps = new java.util.ArrayList<>();
    private int at;
    private long t0;
    private final android.graphics.Bitmap[] demoIcons = new android.graphics.Bitmap[20];
    // the player trying the lit key: the demo waits until a second after the last finger is up
    private boolean trying;
    private long resumeAt;

    /** The lit area(s) of the step, or null: touches there go to the real key (the player may try it). */
    private android.graphics.RectF litArea() {
        if (steps.isEmpty()) return null;
        android.graphics.RectF r = target(steps.get(at));
        if (r != null) r.inset(-dp(4), -dp(4));
        return r;
    }

    private boolean onCard(float x, float y) {
        return x >= card.getX() && x < card.getX() + card.getWidth() && y >= card.getY() && y < card.getY() + card.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        // a touch starting on the lit key goes past this layer to the key (the whole gesture: drags, swipes)
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            android.graphics.RectF lit = litArea();
            if (!onCard(e.getX(), e.getY()) && lit != null && lit.contains(e.getX(), e.getY())) return false;
        }
        return super.dispatchTouchEvent(e);
    }

    /** Every touch of the screen (GameActivity.dispatchTouchEvent): pause the demo while the player tries. */
    void observe(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            int[] me = new int[2];
            getLocationOnScreen(me);
            float x = e.getRawX(i) - me[0], y = e.getRawY(i) - me[1];
            android.graphics.RectF lit = litArea();
            if (lit != null && lit.contains(x, y) && !onCard(x, y)) {
                trying = true;
                invalidate();
            }
        } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && trying) {
            trying = false;
            resumeAt = android.os.SystemClock.uptimeMillis() + 1000;
            t0 = resumeAt;  // the demo starts over after the pause
        }
    }
    private final android.graphics.Paint scene = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** The RMB ring as the game shows it (same look as RadialMenu). */
    private void drawRing(android.graphics.Canvas c, float ox, float oy, float shown, int sel) {
        if (shown <= 0f) return;
        float r = dp(78f) * shown, br = dp(24f) * Math.min(1f, shown);
        int alpha = (int) (255 * Math.min(1f, shown));
        scene.setStyle(android.graphics.Paint.Style.FILL);
        scene.setColor(0x40000000);
        c.drawCircle(ox, oy, r + br + dp(6f), scene);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 - 90);
            float bx = ox + r * (float) Math.cos(a), by = oy + r * (float) Math.sin(a), rr = i == sel ? br * 1.15f : br;
            scene.setStyle(android.graphics.Paint.Style.FILL);
            int col = i == sel ? act.style.pTop : act.style.top;
            scene.setColor(col);
            scene.setAlpha(alpha * (col >>> 24) / 255);
            c.drawCircle(bx, by, rr, scene);
            scene.setStyle(android.graphics.Paint.Style.STROKE);
            scene.setStrokeWidth(dp(i == sel ? 2.5f : 1.5f));
            scene.setColor(i == sel ? act.style.pStroke : act.style.stroke);
            scene.setAlpha(alpha);
            c.drawCircle(bx, by, rr, scene);
        }
    }

    /** The R key's wheel beside the key; its ridges move with [offset]. */
    private void drawWheel(android.graphics.Canvas c, android.graphics.RectF key, float alpha, float offset) {
        if (alpha <= 0f) return;
        float w = dp(64f), h = dp(170f), l = key.right + dp(6f), t = key.centerY() - dp(85f);
        scene.setStyle(android.graphics.Paint.Style.FILL);
        scene.setColor(act.style.wheelBg);
        scene.setAlpha((int) (((act.style.wheelBg >>> 24)) * alpha));
        c.drawRoundRect(l, t, l + w, t + h, dp(12f), dp(12f), scene);
        scene.setColor(act.style.wheelFg);
        scene.setAlpha((int) (255 * alpha));
        scene.setTextSize(dp(14f));
        scene.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText("▲", l + w / 2, t + dp(22f), scene);
        c.drawText("▼", l + w / 2, t + h - dp(10f), scene);
        scene.setStrokeWidth(dp(2f));
        float gap = dp(12f), shift = ((offset % gap) + gap) % gap;
        for (float y = t + dp(32f) + shift; y < t + h - dp(28f); y += gap) c.drawLine(l + w * 0.3f, y, l + w * 0.7f, y, scene);
    }

    /** The quick bar panel sliding in by [k] (0 hidden … 1 in place); slot [lit] highlighted. */
    private void drawQuickBar(android.graphics.Canvas c, float k, int lit) {
        if (k <= 0f) return;
        float cell = dp(42f), pad = dp(6f), w = pad * 2 + cell * 10, h = pad * 2 + cell * 2;
        float x = (getWidth() - w) / 2f, y = getHeight() - dp(2f) - h + (1f - k) * (h + dp(8f));
        scene.setStyle(android.graphics.Paint.Style.FILL);
        scene.setColor(0xF0313031);
        c.drawRoundRect(x, y, x + w, y + h, dp(4f), dp(4f), scene);
        for (int i = 0; i < 20; i++) {
            float sx = x + pad + (i % 10) * cell + dp(1f), sy = y + pad + (i / 10) * cell + dp(1f), sw = cell - dp(2f);
            scene.setStyle(android.graphics.Paint.Style.FILL);
            scene.setColor(i == lit ? 0xFFF29A1F : 0xFF303030);
            c.drawRoundRect(sx, sy, sx + sw, sy + sw, dp(2f), dp(2f), scene);
            if (demoIcons[i] != null) {
                android.graphics.RectF dst = new android.graphics.RectF(sx + dp(4f), sy + dp(4f), sx + sw - dp(4f), sy + sw - dp(4f));
                c.drawBitmap(demoIcons[i], null, dst, null);
            }
            scene.setColor(0xB0FFFFFF);
            scene.setTextSize(dp(8f));
            scene.setTextAlign(android.graphics.Paint.Align.LEFT);
            c.drawText(String.valueOf((i % 10 + 1) % 10), sx + dp(2f), sy + dp(9f), scene);
        }
    }

    /** The combo result card ("Z", "F", "Ctrl / Shift") above [over], as GameActivity.showComboCard draws it. */
    private void drawResultCard(android.graphics.Canvas c, android.graphics.RectF over, String text, float k) {
        if (k <= 0f) return;
        String[] lines = Tr.t(text).split("\n");
        float w = Math.max(dp(56f), lines.length > 1 ? dp(64f) : dp(56f)), h = lines.length > 1 ? dp(52f) : dp(42f);
        float pop = k < 1f ? 0.7f + 0.3f * k + (float) Math.sin(k * Math.PI) * 0.08f : 1f;
        float cx = over.centerX(), bottom = over.top - dp(6f);
        c.save();
        c.scale(pop, pop, cx, bottom);
        scene.setStyle(android.graphics.Paint.Style.FILL);
        scene.setColor(act.style.pTop);
        c.drawRoundRect(cx - w / 2, bottom - h, cx + w / 2, bottom, dp((float) act.style.radius), dp((float) act.style.radius), scene);
        scene.setStyle(android.graphics.Paint.Style.STROKE);
        scene.setStrokeWidth(dp(1.5f));
        scene.setColor(act.style.pStroke);
        c.drawRoundRect(cx - w / 2, bottom - h, cx + w / 2, bottom, dp((float) act.style.radius), dp((float) act.style.radius), scene);
        scene.setStyle(android.graphics.Paint.Style.FILL);
        scene.setColor(act.style.pText);
        scene.setTextAlign(android.graphics.Paint.Align.CENTER);
        scene.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        scene.setTextSize(dp(lines.length > 1 ? 12f : 16f));
        float lh = dp(lines.length > 1 ? 15f : 18f), ty = bottom - h / 2 - (lines.length - 1) * lh / 2 + dp(5f);
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], cx, ty + i * lh, scene);
        scene.setTypeface(android.graphics.Typeface.DEFAULT);
        c.restore();
    }

    /** Where slot i of the demo panel is (centre), with the panel in place. */
    private float[] quickBarSlot(int i) {
        float cell = dp(42f), pad = dp(6f), w = pad * 2 + cell * 10, h = pad * 2 + cell * 2, y = getHeight() - dp(2f) - h;
        return new float[] {(getWidth() - w) / 2f + pad + (i % 10) * cell + cell / 2, y + pad + (i / 10) * cell + cell / 2};
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private final GameActivity act;

    Tutorial(GameActivity c) {
        super(c);
        this.act = c;
        setWillNotDraw(false);
        setClickable(true);  // the game and the keys get nothing meanwhile
        dim.setColor(0xB4000000);
        ring.setStyle(android.graphics.Paint.Style.STROKE);
        ring.setColor(0xFFF29A1F);
        ring.setStrokeWidth(dp(2.5f));
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF2262526);
        bg.setCornerRadius(dp(8f));
        bg.setStroke(dp(1), 0xFFF29A1F);
        card.setBackground(bg);
        card.setClickable(true);
        count = new TextView(c);
        count.setTextColor(0xFFB8B0A8);
        count.setTextSize(11);
        card.addView(count);
        title = new TextView(c);
        title.setTextColor(0xFFFFE6C0);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(title);
        body = new TextView(c);
        body.setTextColor(0xFFFFFFFF);
        body.setTextSize(14);
        body.setLineSpacing(0, 1.15f);
        body.setPadding(0, dp(4), 0, dp(8));
        card.addView(body);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button quit = new android.widget.Button(c);
        quit.setText(Tr.t("Выйти"));
        back = new android.widget.Button(c);
        back.setText(Tr.t("Назад"));
        next = new android.widget.Button(c);
        quit.setOnClickListener(v -> finish());
        back.setOnClickListener(v -> show(at - 1));
        next.setOnClickListener(v -> {
            if (at + 1 < steps.size()) show(at + 1); else finish();
        });
        buttons.addView(quit);
        buttons.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        buttons.addView(back);
        buttons.addView(next);
        card.addView(buttons);
        addView(card, new FrameLayout.LayoutParams(dp(330), FrameLayout.LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    void start(java.util.List<TutStep> s) {
        steps = s;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(200).start();
        show(0);
    }

    void finish() {
        animate().alpha(0f).setDuration(180).withEndAction(() -> setVisibility(GONE)).start();
    }

    private void show(int i) {
        if (i < 0 || i >= steps.size()) return;
        at = i;
        TutStep s = steps.get(i);
        count.setText((i + 1) + " / " + steps.size() + (target(s) != null ? Tr.t("  ·  кнопку в рамке можно попробовать самому") : ""));
        trying = false;
        resumeAt = 0;
        title.setText(s.title);
        body.setText(s.text);
        back.setEnabled(i > 0);
        next.setText(i + 1 < steps.size() ? Tr.t("Далее") : Tr.t("Готово"));
        t0 = android.os.SystemClock.uptimeMillis();
        if (s.gesture == G_QUICKBAR) {
            String[] names = null;
            try {
                names = nativeQuickBar();
            } catch (UnsatisfiedLinkError e) {
                // no game memory reader: empty slots
            }
            for (int k = 0; k < 20; k++)
                demoIcons[k] = names != null && k < names.length && !names[k].isEmpty() ? act.itemIcon(names[k]) : null;
        }
        post(this::placeCard);
        invalidate();
    }

    /** Where a view is on this layer. */
    private android.graphics.RectF rectOf(View v) {
        if (v == null || v.getWidth() == 0) return null;
        int[] a = new int[2], me = new int[2];
        v.getLocationOnScreen(a);
        getLocationOnScreen(me);
        float l = a[0] - me[0], t = a[1] - me[1];
        return new android.graphics.RectF(l, t, l + v.getWidth() * v.getScaleX(), t + v.getHeight() * v.getScaleY());
    }

    private android.graphics.RectF target(TutStep s) {
        if (s.stickZone && act.stickView != null) return rectOf(act.stickView);
        return rectOf(s.target);
    }

    /** The card away from what it explains: right half for things on the left, else the left half. */
    private void placeCard() {
        android.graphics.RectF r = target(steps.get(at));
        float cx = r != null ? r.centerX() : 0;
        boolean right = r == null || cx < getWidth() / 2f;
        float x = r == null ? (getWidth() - card.getWidth()) / 2f
                : right ? Math.max(r.right + dp(24), getWidth() * 0.45f) : Math.max(dp(16), r.left - card.getWidth() - dp(24));
        x = Math.min(x, getWidth() - card.getWidth() - dp(16));
        card.setX(x);
        card.setY(steps.get(at).gesture == G_QUICKBAR ? dp(16) : (getHeight() - card.getHeight()) / 2f);
    }

    @Override
    protected void onDraw(android.graphics.Canvas c) {
        if (steps.isEmpty()) return;
        TutStep s = steps.get(at);
        android.graphics.RectF r = target(s);
        if (r != null) {
            android.graphics.RectF hole = new android.graphics.RectF(r);
            hole.inset(-dp(4), -dp(4));
            c.save();
            c.clipOutRect(hole);
            c.drawPaint(dim);
            c.restore();
            c.drawRoundRect(hole, dp(6), dp(6), ring);
            android.graphics.RectF r2 = rectOf(s.target2);
            if (r2 != null) {
                r2.inset(-dp(4), -dp(4));
                c.drawRoundRect(r2, dp(6), dp(6), ring);
            }
            if (!trying && android.os.SystemClock.uptimeMillis() >= resumeAt) drawFinger(c, s, r, rectOf(s.target2));
        } else {
            c.drawPaint(dim);
        }
        postInvalidateOnAnimation();
    }

    /** The see-through finger, a loop of 1.8 s. */
    private void drawFinger(android.graphics.Canvas c, TutStep s, android.graphics.RectF r, android.graphics.RectF r2) {
        long loop = s.gesture == G_RING || s.gesture == G_QUICKBAR ? 3200 : s.gesture == G_WHEEL ? 2600 : 1800;
        float t = ((android.os.SystemClock.uptimeMillis() - t0) % loop) / (float) loop;
        float x = r.centerX(), y = r.centerY(), press = 0f;
        boolean down = false;
        switch (s.gesture) {
            case G_RING: {
                // swipe right, the ring opens at the finger, the finger goes to a button (up-right), release
                down = t > 0.06f && t < 0.86f;
                float sw = clamp01((t - 0.1f) / 0.25f);
                float ox = x + sw * dp(70f), oy = y;
                float open = t < 0.36f ? 0f : t < 0.86f ? clamp01((t - 0.36f) / 0.1f) : 1f - clamp01((t - 0.86f) / 0.1f);
                float go = clamp01((t - 0.5f) / 0.2f);
                double a = Math.toRadians(45 - 90);
                x = ox + go * dp(78f) * (float) Math.cos(a);
                y = oy + go * dp(78f) * (float) Math.sin(a);
                drawRing(c, ox, oy, open, go > 0.6f ? 1 : -1);
                break;
            }
            case G_WHEEL: {
                // held: the wheel comes, the finger goes up and down, the ridges follow
                down = t > 0.06f && t < 0.92f;
                float show = t < 0.92f ? clamp01((t - 0.12f) / 0.08f) : 1f - clamp01((t - 0.92f) / 0.06f);
                float k = clamp01((t - 0.22f) / 0.66f);
                float dy = (float) Math.sin(k * 2 * Math.PI) * dp(40f);
                y += dy;
                drawWheel(c, r, show, dy);
                break;
            }
            case G_QUICKBAR: {
                // swipe right on Ctrl, the panel slides in, the finger taps slot 3, the panel slides out
                float in = t < 0.82f ? clamp01((t - 0.3f) / 0.14f) : 1f - clamp01((t - 0.82f) / 0.12f);
                float[] slot = quickBarSlot(2);
                if (t < 0.32f) {
                    down = t > 0.04f && t < 0.3f;
                    x += clamp01((t - 0.08f) / 0.2f) * dp(70f);
                } else {
                    float go = clamp01((t - 0.46f) / 0.18f);
                    float sx = x + dp(70f);
                    x = sx + go * (slot[0] - sx);
                    y = y + go * (slot[1] - y);
                    down = t > 0.66f && t < 0.74f;
                }
                drawQuickBar(c, in, t > 0.66f && t < 0.82f ? 2 : -1);
                break;
            }
            case G_TAP:
                down = t > 0.25f && t < 0.4f;
                press = down ? 1f : 0f;
                break;
            case G_HOLD:
                down = t > 0.15f && t < 0.85f;
                press = down ? Math.min(1f, (t - 0.15f) / 0.5f) : 0f;
                break;
            case G_SWIPE: {
                down = t > 0.15f && t < 0.8f;
                float k = Math.max(0f, Math.min(1f, (t - 0.25f) / 0.45f));
                x += k * dp(80);
                break;
            }
            case G_RAILDOWN: {
                // the key slides down its rail by its height; at the end the result card pops up over it
                down = t > 0.1f && t < 0.85f;
                float k = clamp01((t - 0.2f) / 0.4f);
                y += k * r.height();
                if (down && s.target instanceof TextView) {
                    float w = r.width(), h = r.height();
                    scene.setStyle(android.graphics.Paint.Style.FILL);
                    scene.setColor(act.style.pTop);
                    scene.setAlpha(200);
                    c.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, dp((float) act.style.radius), dp((float) act.style.radius), scene);
                    scene.setColor(act.style.pText);
                    scene.setTextSize(dp(14f));
                    scene.setTextAlign(android.graphics.Paint.Align.CENTER);
                    CharSequence label = s.target.getContentDescription() != null ? s.target.getContentDescription() : ((TextView) s.target).getText();
                    c.drawText(Tr.t(String.valueOf(label)), x, y + dp(5f), scene);
                }
                if (s.result != null && k >= 1f && down) {
                    android.graphics.RectF moved = new android.graphics.RectF(r);
                    moved.offset(0, r.height());
                    drawResultCard(c, moved, s.result, clamp01((t - 0.6f) / 0.06f));
                }
                break;
            }
            case G_DRAG:
                if (r2 != null) {
                    down = t > 0.1f && t < 0.85f;
                    float k = Math.max(0f, Math.min(1f, (t - 0.2f) / 0.45f));
                    x += k * (r2.centerX() - x);
                    y += k * (r2.centerY() - y);
                    // the dragged key follows the finger, as in the game
                    if (down && s.target instanceof TextView) {
                        float w = r.width(), h = r.height();
                        scene.setStyle(android.graphics.Paint.Style.FILL);
                        scene.setColor(act.style.pTop);
                        scene.setAlpha(200);
                        c.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, dp((float) act.style.radius), dp((float) act.style.radius), scene);
                        scene.setColor(act.style.pText);
                        scene.setTextSize(dp(14f));
                        scene.setTextAlign(android.graphics.Paint.Align.CENTER);
                        CharSequence label = s.target.getContentDescription() != null ? s.target.getContentDescription() : ((TextView) s.target).getText();
                        c.drawText(Tr.t(String.valueOf(label)), x, y + dp(5f), scene);
                    }
                    // over the target: the result card pops up above it
                    if (s.result != null && k >= 1f && down) drawResultCard(c, r2, s.result, clamp01((t - 0.65f) / 0.06f));
                }
                break;
            case G_STICK: {
                down = t > 0.08f && t < 0.92f;
                if (s.stickZone) {
                    x = r.left + r.width() * 0.5f;
                    y = r.top + r.height() * 0.55f;
                }
                double a = t * 2 * Math.PI;
                float k = Math.min(1f, Math.max(0f, (t - 0.08f) / 0.15f));
                x += k * dp(45) * (float) Math.cos(a);
                y += k * dp(45) * (float) Math.sin(a);
                break;
            }
            case G_UPDOWN: {
                down = t > 0.1f && t < 0.9f;
                float k = (t - 0.1f) / 0.8f;
                y += (float) Math.sin(k * 2 * Math.PI) * dp(40) * (down ? 1 : 0);
                break;
            }
            default:
                return;
        }
        finger.setStyle(android.graphics.Paint.Style.FILL);
        finger.setColor(down ? 0xA0FFFFFF : 0x50FFFFFF);
        c.drawCircle(x, y, dp(down ? 15f : 17f), finger);
        finger.setStyle(android.graphics.Paint.Style.STROKE);
        finger.setStrokeWidth(dp(1.5f));
        finger.setColor(0xC0FFFFFF);
        c.drawCircle(x, y, dp(down ? 15f : 17f), finger);
        if (press > 0f) {  // holding: a ring grows
            finger.setColor(((int) (0xC0 * (1f - press * 0.6f)) << 24) | 0xF29A1F);
            c.drawCircle(x, y, dp(17f) + press * dp(14f), finger);
        }
    }
}
