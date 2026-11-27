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

/**
 * Line icons in the game's manner. Drawn in a 24 x 24 box: stroked lines
 * plus optional filled parts. Sets: 0 one tone with a dark rim, 1 thick
 * blocky lines with a drop shadow, 2 in a round badge, 3 dark on a filled
 * signal square, 4 thin glowing lines. Sets 2 and 4 use other glyphs for
 * Tab, Q, E, R and the wheel.
 */
final class KeyIcon extends android.graphics.drawable.Drawable {
    private final android.graphics.Path line = new android.graphics.Path(), fill = new android.graphics.Path();
    private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final int color, set;
    private android.graphics.Bitmap pixels;  // set 9

    KeyIcon(String kind, int color, int set) {
        this.color = color;
        this.set = set;
        boolean blocky = set == 1;
        p.setStrokeCap(blocky ? android.graphics.Paint.Cap.SQUARE : android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(blocky ? android.graphics.Paint.Join.MITER : android.graphics.Paint.Join.ROUND);
        if (!((set == 2 || set == 4) && buildAlt(kind))) build(kind);
    }

    /** Other glyphs: tab arrow, crosshair, inventory grid, two arrows, mouse wheel. */
    private boolean buildAlt(String kind) {
        switch (kind) {
            case "Tab":
                line.moveTo(4, 12); line.lineTo(17, 12); arrowHead(18, 12, 1, 0);
                line.moveTo(20, 6); line.lineTo(20, 18);
                return true;
            case "Q":
                line.addCircle(12, 12, 6, android.graphics.Path.Direction.CW);
                line.moveTo(12, 2); line.lineTo(12, 7); line.moveTo(12, 17); line.lineTo(12, 22);
                line.moveTo(2, 12); line.lineTo(7, 12); line.moveTo(17, 12); line.lineTo(22, 12);
                fill.addCircle(12, 12, 1.5f, android.graphics.Path.Direction.CW);
                return true;
            case "E":
                line.addRect(4, 4, 20, 20, android.graphics.Path.Direction.CW);
                for (float v : new float[] {9.33f, 14.67f}) {
                    line.moveTo(v, 4); line.lineTo(v, 20);
                    line.moveTo(4, v); line.lineTo(20, v);
                }
                return true;
            case "R": {
                android.graphics.RectF o = new android.graphics.RectF(5, 5, 19, 19);
                for (int start : new int[] {200, 20}) {
                    line.addArc(o, start, 130);
                    double a = Math.toRadians(start + 130);
                    arrowHead(12 + 7 * (float) Math.cos(a), 12 + 7 * (float) Math.sin(a), (float) -Math.sin(a), (float) Math.cos(a));
                }
                return true;
            }
            case "Колесо":
                line.addRoundRect(6, 3, 18, 21, 6, 6, android.graphics.Path.Direction.CW);
                line.moveTo(12, 6.5f); line.lineTo(12, 10.5f);
                return true;
        }
        return false;
    }

    private void arrowHead(float x, float y, float dx, float dy) {  // tip at x,y pointing along dx,dy
        float n = (float) Math.hypot(dx, dy);
        dx /= n;
        dy /= n;
        line.moveTo(x - 3.5f * dx + 3f * dy, y - 3.5f * dy - 3f * dx);
        line.lineTo(x, y);
        line.lineTo(x - 3.5f * dx - 3f * dy, y - 3.5f * dy + 3f * dx);
    }

    private void build(String kind) {
        switch (kind) {
            case "Tab":  // cycle: two opposite arrows
                line.moveTo(4, 8); line.lineTo(19, 8); arrowHead(20, 8, 1, 0);
                line.moveTo(20, 16); line.lineTo(5, 16); arrowHead(4, 16, -1, 0);
                break;
            case "F4":  // gear
                line.addCircle(12, 12, 5.5f, android.graphics.Path.Direction.CW);
                line.addCircle(12, 12, 1.8f, android.graphics.Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    line.moveTo(12 + 6.5f * (float) Math.cos(a), 12 + 6.5f * (float) Math.sin(a));
                    line.lineTo(12 + 9.5f * (float) Math.cos(a), 12 + 9.5f * (float) Math.sin(a));
                }
                break;
            case "Q":  // pipette
                line.moveTo(5, 19); line.lineTo(13, 11);
                line.moveTo(11, 9); line.lineTo(15, 13);
                fill.moveTo(13.5f, 7.5f); fill.lineTo(17, 4); fill.lineTo(20, 7); fill.lineTo(16.5f, 10.5f); fill.close();
                break;
            case "E":  // chest
                line.addRect(4, 7, 20, 19, android.graphics.Path.Direction.CW);
                line.moveTo(4, 11.5f); line.lineTo(20, 11.5f);
                fill.addRect(10.5f, 10, 13.5f, 14, android.graphics.Path.Direction.CW);
                break;
            case "R": {  // rotate: an arc with an arrow at its end
                line.addArc(new android.graphics.RectF(5, 5, 19, 19), -60, 290);
                double a = Math.toRadians(230);
                arrowHead(12 + 7 * (float) Math.cos(a), 12 + 7 * (float) Math.sin(a), (float) -Math.sin(a), (float) Math.cos(a));
                break;
            }
            case "Колесо":  // wheel: up / down
                line.moveTo(8, 9); line.lineTo(12, 5); line.lineTo(16, 9);
                line.moveTo(8, 15); line.lineTo(12, 19); line.lineTo(16, 15);
                line.moveTo(10, 12); line.lineTo(14, 12);
                break;
            case "ПКМ":  // mouse with the right button filled
                line.addRoundRect(6, 3, 18, 21, 6, 6, android.graphics.Path.Direction.CW);
                line.moveTo(6, 10); line.lineTo(18, 10);
                line.moveTo(12, 3); line.lineTo(12, 10);
                fill.moveTo(12, 3); fill.lineTo(12, 10); fill.lineTo(18, 10); fill.lineTo(18, 9);
                fill.arcTo(new android.graphics.RectF(6, 3, 18, 15), -90, 90); fill.close();
                break;
            case "Shift":  // hollow up arrow
                line.moveTo(12, 4); line.lineTo(20, 12); line.lineTo(16, 12); line.lineTo(16, 20);
                line.lineTo(8, 20); line.lineTo(8, 12); line.lineTo(4, 12); line.close();
                break;
            case "Enter":  // return arrow
                line.moveTo(19, 5); line.lineTo(19, 14); line.lineTo(6, 14); arrowHead(5, 14, -1, 0);
                break;
            case "Пробел":  // space bar
                line.moveTo(4, 11); line.lineTo(4, 17); line.lineTo(20, 17); line.lineTo(20, 11);
                break;
        }
    }

    boolean empty() { return line.isEmpty() && fill.isEmpty(); }

    private void lines(android.graphics.Canvas c, int col, float width) {
        p.setColor(col);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(width);
        c.drawPath(line, p);
        c.drawPath(fill, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        c.drawPath(fill, p);
    }

    @Override
    public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        c.save();
        c.translate(b.left, b.top);
        c.scale(b.width() / 24f, b.height() / 24f);
        int ink = color;
        float width = 2.2f;
        switch (set) {
            case 1:  // thick, shadow down-right
                width = 3.2f;
                c.translate(1f, 1f);
                lines(c, 0x90000000, width);
                c.translate(-1f, -1f);
                break;
            case 2:  // round badge, icon smaller inside
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0x60000000);
                c.drawCircle(12, 12, 11.5f, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.4f);
                p.setColor(color);
                c.drawCircle(12, 12, 11.2f, p);
                c.translate(12, 12);
                c.scale(0.62f, 0.62f);
                c.translate(-12, -12);
                width = 3f;
                break;
            case 3:  // signal: filled rounded square, dark icon on it
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0x80000000);
                c.drawRoundRect(1, 2, 23, 24, 4, 4, p);
                p.setColor(color);
                c.drawRoundRect(1, 0.5f, 23, 22.5f, 4, 4, p);
                c.translate(12, 11.5f);
                c.scale(0.68f, 0.68f);
                c.translate(-12, -12);
                ink = 0xE8101010;
                width = 2.8f;
                break;
            case 5:  // double outline: wide colour line, dark core
                lines(c, 0xA0000000, 5f);
                lines(c, color, 3.6f);
                ink = 0xE0101010;
                width = 1.3f;
                break;
            case 6:  // diamond badge
            case 7: {  // hexagon badge
                android.graphics.Path badge = new android.graphics.Path();
                if (set == 6) {
                    badge.moveTo(12, 0.6f); badge.lineTo(23.4f, 12); badge.lineTo(12, 23.4f); badge.lineTo(0.6f, 12);
                } else {
                    for (int i = 0; i < 6; i++) {
                        double a = Math.toRadians(60 * i);
                        float x = 12 + 11.4f * (float) Math.cos(a), y = 12 + 11.4f * (float) Math.sin(a);
                        if (i == 0) badge.moveTo(x, y); else badge.lineTo(x, y);
                    }
                }
                badge.close();
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0x70000000);
                c.drawPath(badge, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.4f);
                p.setColor(color);
                c.drawPath(badge, p);
                float k = set == 6 ? 0.56f : 0.62f;
                c.translate(12, 12);
                c.scale(k, k);
                c.translate(-12, -12);
                width = 3f;
                break;
            }
            case 8:  // two-colour shift: red left, cyan right, then the line
                c.translate(-0.9f, 0);
                lines(c, 0xA0FF3A5A, 2.2f);
                c.translate(1.8f, 0);
                lines(c, 0xA030E0FF, 2.2f);
                c.translate(-0.9f, 0);
                width = 2f;
                break;
            case 9: {  // pixel: drawn into a 16 x 16 bitmap, shown without smoothing
                if (pixels == null) {
                    pixels = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas bc = new android.graphics.Canvas(pixels);
                    bc.scale(16 / 24f, 16 / 24f);
                    p.setAntiAlias(false);
                    bc.translate(1.5f, 1.5f);
                    lines(bc, 0xC0000000, 3f);
                    bc.translate(-1.5f, -1.5f);
                    lines(bc, color, 3f);
                    p.setAntiAlias(true);
                }
                android.graphics.Paint bp = new android.graphics.Paint();
                bp.setFilterBitmap(false);
                c.drawBitmap(pixels, null, new android.graphics.RectF(0, 0, 24, 24), bp);
                c.restore();
                return;
            }
            case 10:  // stamp: dark icon cut into a filled circle
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0x80000000);
                c.drawCircle(12, 12.8f, 11.5f, p);
                p.setColor(color);
                c.drawCircle(12, 12, 11.5f, p);
                c.translate(12, 12);
                c.scale(0.6f, 0.6f);
                c.translate(-12, -12);
                ink = 0xE8101010;
                width = 3f;
                break;
            case 11:  // sight: corner brackets around a smaller icon
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.6f);
                p.setColor(color);
                for (int k = 0; k < 4; k++) {
                    float x = (k & 1) == 0 ? 1 : 23, y = (k & 2) == 0 ? 1 : 23, sx = (k & 1) == 0 ? 1 : -1, sy = (k & 2) == 0 ? 1 : -1;
                    c.drawLine(x, y, x + 5 * sx, y, p);
                    c.drawLine(x, y, x, y + 5 * sy, p);
                }
                c.translate(12, 12);
                c.scale(0.7f, 0.7f);
                c.translate(-12, -12);
                lines(c, 0xA0000000, 4.5f);
                width = 2.6f;
                break;
            case 12:  // long shadow down-right
                for (int k = 4; k >= 1; k--) {
                    c.translate(0.7f * k, 0.7f * k);
                    lines(c, 0x38000000, 2.4f);
                    c.translate(-0.7f * k, -0.7f * k);
                }
                width = 2.4f;
                break;
            case 13:  // dashed line over a dark rim
                lines(c, 0x90000000, 3.6f);
                p.setPathEffect(new android.graphics.DashPathEffect(new float[] {2.6f, 1.8f}, 0));
                lines(c, color, 2.2f);
                p.setPathEffect(null);
                c.restore();
                return;
            case 14:  // bold glyph with a soft shadow below (mobile game buttons)
                c.translate(0, 1.2f);
                lines(c, 0x60000000, 3.2f);
                c.translate(0, -1.2f);
                width = 3.2f;
                break;
            case 15:  // pressed-in glyph: light edge below
                c.translate(0, 1f);
                lines(c, 0x60FFFFFF, 2.8f);
                c.translate(0, -1f);
                width = 2.8f;
                break;
            case 4:  // thin line with a soft glow around it
                lines(c, (color & 0x00FFFFFF) | 0x40000000, 5f);
                width = 1.5f;
                break;
            default:  // dark rim
                lines(c, 0xA0000000, 4f);
        }
        lines(c, ink, width);
        c.restore();
    }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(android.graphics.ColorFilter f) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
