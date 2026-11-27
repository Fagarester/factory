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

/** Rivets in the corners, hazard stripes around the edge, a grid, or a glow from below. */
final class Effect extends android.graphics.drawable.Drawable {
    private final KeyStyle s;
    private final float density;
    private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    Effect(KeyStyle s, float density) {
        this.s = s;
        this.density = density;
    }

    private float dp(float v) { return v * density; }
    private int dp(int v) { return (int) (v * density); }

    @Override
    public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float l = b.left, t = b.top, r = b.right, bt = b.bottom, w = b.width(), h = b.height(), rr = dp((float) s.radius);
        android.graphics.Path round = new android.graphics.Path();
        round.addRoundRect(l, t, r, bt, rr, rr, android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(round);
        switch (s.effect) {
            case FX_RIVETS: {
                float in = dp(4.5f), rad = dp(1.7f);
                for (float x : new float[] {l + in, r - in})
                    for (float y : new float[] {t + in, bt - in}) {
                        p.setColor(0xAA000000);
                        c.drawCircle(x, y + dp(0.7f), rad, p);
                        p.setColor(s.effectColor);
                        c.drawCircle(x, y, rad * 0.85f, p);
                        p.setColor(0x80FFFFFF);
                        c.drawCircle(x - rad * 0.3f, y - rad * 0.3f, rad * 0.3f, p);
                    }
                break;
            }
            case FX_STRIPES: {
                float band = dp(4f);
                android.graphics.Path ring = new android.graphics.Path();
                ring.addRect(l, t, r, bt, android.graphics.Path.Direction.CW);
                ring.addRect(l + band, t + band, r - band, bt - band, android.graphics.Path.Direction.CW);
                ring.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                c.clipPath(ring);
                c.drawColor(s.effectColor);
                p.setColor(0xFF141414);
                p.setStrokeWidth(dp(3f));
                for (float x = l - h; x < r + h; x += dp(8f)) c.drawLine(x, bt, x + h, t, p);
                break;
            }
            case FX_GRID: {
                p.setColor(s.effectColor);
                p.setStrokeWidth(Math.max(1f, dp(0.7f)));
                float step = dp(7f);
                for (float x = l + step; x < r; x += step) c.drawLine(x, t, x, bt, p);
                for (float y = t + step; y < bt; y += step) c.drawLine(l, y, r, y, p);
                break;
            }
            case FX_SLATS: {  // vertical bars (rail sleepers)
                p.setColor(s.effectColor);
                for (float x = l + dp(3f); x < r; x += dp(7f)) c.drawRect(x, t, x + dp(2.5f), bt, p);
                break;
            }
            case FX_SCAN: {  // horizontal scan lines (old monitor)
                p.setColor(s.effectColor);
                for (float y = t + dp(1f); y < bt; y += dp(3f)) c.drawRect(l, y, r, y + Math.max(1f, dp(1f)), p);
                break;
            }
            case FX_DOTS: {
                p.setColor(s.effectColor);
                float step = dp(6f);
                for (float y = t + step / 2; y < bt; y += step)
                    for (float x = l + step / 2; x < r; x += step) c.drawCircle(x, y, dp(0.9f), p);
                break;
            }
            case FX_HATCH: {  // thin diagonal lines
                p.setColor(s.effectColor);
                p.setStrokeWidth(Math.max(1f, dp(1f)));
                for (float x = l - h; x < r; x += dp(5f)) c.drawLine(x, bt, x + h, t, p);
                break;
            }
            case FX_TILES: {  // brick rows
                p.setColor(s.effectColor);
                float row = dp(10f), col = dp(14f), lw = Math.max(1f, dp(0.8f));
                int k = 0;
                for (float y = t; y < bt; y += row, k++) {
                    c.drawRect(l, y, r, y + lw, p);
                    for (float x = l + (k % 2 == 0 ? col / 2 : 0); x < r; x += col) c.drawRect(x, y, x + lw, y + row, p);
                }
                break;
            }
            case FX_CORNERS: {  // L brackets in the corners
                p.setColor(s.effectColor);
                p.setStrokeWidth(dp(1.6f));
                float in = dp(3f), len = dp(6f);
                for (int k = 0; k < 4; k++) {
                    float x = (k & 1) == 0 ? l + in : r - in, y = (k & 2) == 0 ? t + in : bt - in;
                    float sx = (k & 1) == 0 ? 1 : -1, sy = (k & 2) == 0 ? 1 : -1;
                    c.drawLine(x, y, x + sx * len, y, p);
                    c.drawLine(x, y, x, y + sy * len, p);
                }
                break;
            }
            case FX_WAVES: {
                p.setColor(s.effectColor);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dp(1f)));
                float amp = dp(1.5f), wl = dp(12f);
                for (float y = t + dp(5f); y < bt; y += dp(6f)) {
                    android.graphics.Path wave = new android.graphics.Path();
                    wave.moveTo(l, y);
                    for (float x = l; x <= r; x += dp(2f)) wave.lineTo(x, y + amp * (float) Math.sin((x - l) / wl * 2 * Math.PI));
                    c.drawPath(wave, p);
                }
                p.setStyle(android.graphics.Paint.Style.FILL);
                break;
            }
            case FX_CIRCUIT: {  // two traces with pads
                p.setColor(s.effectColor);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dp(1f)));
                android.graphics.Path tr = new android.graphics.Path();
                tr.moveTo(l + w * 0.08f, t + h * 0.3f); tr.lineTo(l + w * 0.32f, t + h * 0.3f); tr.lineTo(l + w * 0.44f, t + h * 0.12f);
                tr.moveTo(l + w * 0.92f, t + h * 0.72f); tr.lineTo(l + w * 0.66f, t + h * 0.72f); tr.lineTo(l + w * 0.54f, t + h * 0.9f);
                c.drawPath(tr, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
                for (float[] d : new float[][] {{0.08f, 0.3f}, {0.44f, 0.12f}, {0.92f, 0.72f}, {0.54f, 0.9f}})
                    c.drawCircle(l + w * d[0], t + h * d[1], dp(1.4f), p);
                break;
            }
            case FX_SHINE:  // gloss on the upper half
                p.setShader(new android.graphics.LinearGradient(0, t, 0, t + h * 0.55f,
                        s.effectColor, s.effectColor & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(l, t, r, t + h * 0.55f, p);
                p.setShader(null);
                break;
            case FX_NOISE: {  // fixed speckles (same for every key of the style)
                p.setColor(s.effectColor);
                java.util.Random rnd = new java.util.Random(s.name.hashCode());
                float d = dp(1.3f);
                for (int k = 0; k < 48; k++) {
                    float x = l + rnd.nextFloat() * w, y = t + rnd.nextFloat() * h;
                    c.drawRect(x, y, x + d, y + d, p);
                }
                break;
            }
            case FX_DOUBLE: {  // second frame inside the edge
                p.setColor(s.effectColor);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dp(1f)));
                float in = dp(3.5f), ir = Math.max(0, rr - in);
                c.drawRoundRect(l + in, t + in, r - in, bt - in, ir, ir, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
                break;
            }
            case FX_TOPBAR:
                p.setColor(s.effectColor);
                c.drawRect(l, t, r, t + dp(3f), p);
                break;
            case FX_CHEVRON: {  // belt arrows
                p.setColor(s.effectColor);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.8f));
                float cy = t + h / 2, k = dp(5f);
                for (float x = l + dp(4f); x < r; x += dp(9f)) {
                    android.graphics.Path v = new android.graphics.Path();
                    v.moveTo(x, cy - k); v.lineTo(x + k * 0.8f, cy); v.lineTo(x, cy + k);
                    c.drawPath(v, p);
                }
                p.setStyle(android.graphics.Paint.Style.FILL);
                break;
            }
            case FX_LEFTBAR:  // status strip on the left
                p.setColor(s.effectColor);
                c.drawRect(l, t, l + dp(3.5f), bt, p);
                break;
            case FX_GLOW:
                p.setShader(new android.graphics.RadialGradient(l + w / 2, bt, Math.max(w, h) * 0.7f,
                        s.effectColor, s.effectColor & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(l, t, r, bt, p);
                p.setShader(null);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(android.graphics.ColorFilter f) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
