package com.factorynative;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.WeakHashMap;

/**
 * The gear menu in the game's own look (the "Factorio" key theme): the game's GUI sprites and fonts, read from
 * the player's game folder at run time (data/core/graphics/gui-new.png, data/core/fonts/Lilittium-*.ttf; the app
 * carries no game art). Sprite places are from data/core/prototypes/style.lua of 2.1 (frame, inside_shallow_frame,
 * tab, button, checkbox, radiobutton, slider, draggable_space). Null from load() if the files are not there: the
 * menu keeps the theme's colours.
 */
final class FactorioSkin {
    /** gui_color.caption: the titles and headings. */
    static final int CAPTION = 0xFFFFE6C0;

    private final Bitmap sheet, close;
    final Typeface regular, semibold, bold;
    /** Screen pixels per sprite pixel (the sprites are drawn at half size in the game, at UI scale 100 %). */
    private final float u;
    private final WeakHashMap<View, Object[]> saved = new WeakHashMap<>();

    private FactorioSkin(Bitmap sheet, Bitmap close, Typeface regular, Typeface semibold, Typeface bold, float u) {
        this.sheet = sheet;
        this.close = close;
        this.regular = regular;
        this.semibold = semibold;
        this.bold = bold;
        this.u = u;
    }

    static FactorioSkin load(Context c, File game) {
        if (game == null) return null;
        try {
            File core = new File(game, "data/core");
            Bitmap sheet = BitmapFactory.decodeFile(new File(core, "graphics/gui-new.png").getPath());
            Bitmap close = BitmapFactory.decodeFile(new File(core, "graphics/icons/close.png").getPath());
            if (sheet == null || sheet.getWidth() < 500 || sheet.getHeight() < 230) return null;
            File fonts = new File(core, "fonts");
            Typeface regular = font(fonts, "Lilittium-Regular.ttf", Typeface.DEFAULT);
            return new FactorioSkin(sheet, close, regular, font(fonts, "Lilittium-SemiBold.ttf", Typeface.DEFAULT_BOLD),
                    font(fonts, "Lilittium-Bold.ttf", Typeface.DEFAULT_BOLD), c.getResources().getDisplayMetrics().density * 0.6f);
        } catch (Throwable e) {
            android.util.Log.w("FNative", "Factorio skin", e);
            return null;
        }
    }

    private static Typeface font(File dir, String name, Typeface fallback) {
        File f = new File(dir, name);
        try {
            return f.isFile() ? Typeface.createFromFile(f) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    int px(float spritePx) {
        return Math.round(spritePx * u);
    }

    private final java.util.HashMap<Long, Bitmap> cuts = new java.util.HashMap<>();

    /** A part of the sheet as a bitmap of its own. Drawn straight from the sheet and scaled, the filter took in the
     * next sprite's row: a 1-pixel green or red line under the tabs, orange beside the chosen one (0.168). */
    private Bitmap cut(int x, int y, int w, int h) {
        long k = (long) x << 48 | (long) y << 32 | (long) w << 16 | h;
        Bitmap b = cuts.get(k);
        if (b == null) {
            b = Bitmap.createBitmap(sheet, x, y, w, h);
            cuts.put(k, b);
        }
        return b;
    }

    // ---- drawables cut from the sheet

    /** A nine-slice: corners [corner] square at (x, y), 1-pixel edges and centre (or the given centre pixel). */
    private final class Slice extends Drawable {
        private final int x, y, c, cx, cy;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Slice(int x, int y, int corner, int cx, int cy) {
            this.x = x;
            this.y = y;
            c = corner;
            this.cx = cx;
            this.cy = cy;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float d = Math.min(c * u, Math.min(b.width(), b.height()) / 2f);
            float l = b.left, t = b.top, r = b.right, bo = b.bottom;
            // corners
            piece(canvas, x, y, c, c, l, t, l + d, t + d);
            piece(canvas, x + c + 1, y, c, c, r - d, t, r, t + d);
            piece(canvas, x, y + c + 1, c, c, l, bo - d, l + d, bo);
            piece(canvas, x + c + 1, y + c + 1, c, c, r - d, bo - d, r, bo);
            // edges
            piece(canvas, x + c, y, 1, c, l + d, t, r - d, t + d);
            piece(canvas, x + c, y + c + 1, 1, c, l + d, bo - d, r - d, bo);
            piece(canvas, x, y + c, c, 1, l, t + d, l + d, bo - d);
            piece(canvas, x + c + 1, y + c, c, 1, r - d, t + d, r, bo - d);
            // centre
            piece(canvas, cx, cy, 1, 1, l + d, t + d, r - d, bo - d);
        }

        private void piece(Canvas canvas, int sx, int sy, int sw, int sh, float l, float t, float r, float b) {
            if (r <= l || b <= t) return;
            canvas.drawBitmap(cut(sx, sy, sw, sh), null, new RectF(l, t, r, b), paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A sprite of a fixed size (checkbox, radio, slider knob), [w] x [h] sprite pixels at (x, y). */
    private final class Sprite extends Drawable {
        private final Rect src;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Bitmap from;

        Sprite(Bitmap from, int x, int y, int w, int h) {
            this.from = from == sheet ? cut(x, y, w, h) : from;
            src = new Rect(x, y, x + w, y + h);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(from, from.getWidth() == src.width() && from.getHeight() == src.height() ? null : src,
                    new RectF(getBounds()), paint);
        }

        @Override public int getIntrinsicWidth() { return px(src.width()); }
        @Override public int getIntrinsicHeight() { return px(src.height()); }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The slider's empty bar: left and right ends, a stretched middle (8 sprite pixels high). */
    private final class Bar extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float h = px(8), t = b.centerY() - h / 2f, e = Math.min(px(8), b.width() / 2f);
            canvas.drawBitmap(cut(56, 72, 8, 8), null, new RectF(b.left, t, b.left + e, t + h), paint);
            canvas.drawBitmap(cut(64, 72, 1, 8), null, new RectF(b.left + e, t, b.right - e, t + h), paint);
            canvas.drawBitmap(cut(65, 72, 8, 8), null, new RectF(b.right - e, t, b.right, t + h), paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The slider's full part: the orange bar, as high as the empty one. */
    private final class FullBar extends Drawable {
        private final Slice bar = new Slice(73, 72, 8, 81, 80);

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int h = px(8), t = b.centerY() - h / 2;
            bar.setBounds(b.left, t, b.right, t + h);
            bar.draw(canvas);
        }

        @Override public void setAlpha(int a) { bar.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { bar.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The title bar's grip: draggable_space, its three rows tiled across. */
    private final class Grip extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = px(8);
            if (s <= 0) return;
            for (float x = b.left; x < b.right; x += s) {
                float w = Math.min(s, b.right - x);
                int sw = Math.max(1, Math.round(8 * w / s));
                canvas.drawBitmap(cut(192, 8, sw, 7), null, new RectF(x, b.top, x + w, b.top + s * 7 / 8), paint);
                canvas.drawBitmap(cut(200, 8, sw, 8), null, new RectF(x, b.top + s * 7 / 8, x + w, b.bottom - s), paint);
                canvas.drawBitmap(cut(208, 8, sw, 8), null, new RectF(x, b.bottom - s, x + w, b.bottom), paint);
            }
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** tabbed_pane_frame: a light top edge (the tabs stand on it, the chosen tab's bottom corners run into it), a
     * dark bottom edge (style.lua tabbed_pane_graphical_set). The game draws no sides (its pane touches the sides
     * of a frame around it); here the left side is the top edge turned, the right one the bottom edge, the corners
     * mitred: a closed bevel (0.182, the user: "no border on the left and right"). */
    private final class Tabbed extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap left, right;
        private final android.graphics.Path clip = new android.graphics.Path();

        /** An 8x1 bitmap from the 1x8 edge at (76, y): its rows as columns. */
        private Bitmap turned(int y) {
            int[] c = new int[8];
            for (int i = 0; i < 8; i++) c[i] = sheet.getPixel(76, y + i);
            return Bitmap.createBitmap(c, 8, 1, Bitmap.Config.ARGB_8888);
        }

        private void edge(Canvas canvas, Bitmap b, RectF to, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
            clip.rewind();
            clip.moveTo(x1, y1);
            clip.lineTo(x2, y2);
            clip.lineTo(x3, y3);
            clip.lineTo(x4, y4);
            clip.close();
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(b, null, to, paint);
            canvas.restore();
        }

        @Override
        public void draw(Canvas canvas) {
            if (left == null) {
                left = turned(0);
                right = turned(9);
            }
            Rect b = getBounds();
            float e = Math.min(px(8), Math.min(b.width(), b.height()) / 2f);
            float l = b.left, t = b.top, r = b.right, bo = b.bottom;
            canvas.drawBitmap(cut(76, 8, 1, 1), null, new RectF(l + e, t + e, r - e, bo - e), paint);
            edge(canvas, cut(76, 0, 1, 8), new RectF(l, t, r, t + e), l, t, r, t, r - e, t + e, l + e, t + e);
            edge(canvas, cut(76, 9, 1, 8), new RectF(l, bo - e, r, bo), l + e, bo - e, r - e, bo - e, r, bo, l, bo);
            edge(canvas, left, new RectF(l, t, l + e, bo), l, t, l + e, t + e, l + e, bo - e, l, bo);
            edge(canvas, right, new RectF(r - e, t, r, bo), r - e, t + e, r, t, r, bo, r - e, bo - e);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private Slice nine(int x, int y) {
        return new Slice(x, y, 8, x + 8, y + 8);
    }

    private static final int[] PRESSED = {android.R.attr.state_pressed}, SELECTED = {android.R.attr.state_selected},
            CHECKED = {android.R.attr.state_checked}, DISABLED = {-android.R.attr.state_enabled},
            CHECKED_DISABLED = {android.R.attr.state_checked, -android.R.attr.state_enabled}, ANY = {};

    /** The window: frame. */
    Drawable frame() {
        return nine(0, 0);
    }

    /** The content area: inside_shallow_frame (its centre is the lighter grey). */
    Drawable inner() {
        return new Slice(17, 0, 8, 76, 8);
    }

    /** The pages under a tab row: tabbed_pane_frame (inner() has a dark top edge: a black band beside the chosen tab). */
    Drawable tabbed() {
        return new Tabbed();
    }

    Drawable grip() {
        return new Grip();
    }

    /** button: grey, orange while pressed or selected (a highlighted action: "restart to apply"). */
    Drawable button() {
        StateListDrawable s = new StateListDrawable();
        s.addState(DISABLED, nine(17, 17));
        s.addState(PRESSED, nine(51, 17));
        s.addState(SELECTED, nine(51, 17));
        s.addState(ANY, nine(0, 17));
        return s;
    }

    /** frame_action_button (the close mark): dark, orange while pressed. */
    Drawable frameButton() {
        StateListDrawable s = new StateListDrawable();
        s.addState(PRESSED, nine(51, 17));
        s.addState(ANY, nine(0, 0));
        return s;
    }

    /** tab: light grey, the chosen one dark (its text in the caption colour). */
    Drawable tab(boolean selected) {
        StateListDrawable s = new StateListDrawable();
        s.addState(PRESSED, nine(170, 0));
        s.addState(ANY, selected ? nine(136, 0) : nine(102, 0));
        return s;
    }

    /** The close icon (white), or null. */
    Drawable closeIcon() {
        if (close == null) return null;
        Sprite s = new Sprite(close, 0, 0, close.getWidth(), close.getHeight());
        s.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        return s;
    }

    private Drawable checkbox() {
        StateListDrawable s = new StateListDrawable();
        s.addState(CHECKED_DISABLED, new LayerDrawable(new Drawable[] {new Sprite(sheet, 28, 132, 28, 28), new Sprite(sheet, 456, 188, 28, 28)}));
        s.addState(DISABLED, new Sprite(sheet, 28, 132, 28, 28));
        s.addState(CHECKED, new LayerDrawable(new Drawable[] {new Sprite(sheet, 56, 132, 28, 28), new Sprite(sheet, 112, 132, 28, 28)}));
        s.addState(ANY, new Sprite(sheet, 0, 132, 28, 28));
        return s;
    }

    private Drawable radio() {
        StateListDrawable s = new StateListDrawable();
        s.addState(DISABLED, new Sprite(sheet, 96, 160, 24, 24));
        s.addState(CHECKED, new Sprite(sheet, 72, 160, 24, 24));
        s.addState(ANY, new Sprite(sheet, 0, 160, 24, 24));
        return s;
    }

    private Drawable sliderBar() {
        LayerDrawable l = new LayerDrawable(new Drawable[] {new Bar(), new ClipDrawable(new FullBar(), Gravity.START, ClipDrawable.HORIZONTAL)});
        l.setId(0, android.R.id.background);
        l.setId(1, android.R.id.progress);
        return l;
    }

    /** The game's bar (as the slider's track), for a progress bar. */
    Drawable progressBar() {
        return sliderBar();
    }

    private Drawable sliderKnob() {
        StateListDrawable s = new StateListDrawable();
        s.addState(PRESSED, new Sprite(sheet, 184, 48, 40, 24));
        s.addState(ANY, new Sprite(sheet, 64, 48, 40, 24));
        return s;
    }

    // ---- the whole menu

    /**
     * Gives [root]'s widgets the game's look: check boxes, radio buttons, sliders, buttons, texts (white; the
     * larger ones are headings in the caption colour). Views tagged "preview" or "tab" are left to their owner.
     */
    void skin(View root) {
        if ("preview".equals(root.getTag()) || "tab".equals(root.getTag()) || "noskin".equals(root.getTag())) return;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) skin(g.getChildAt(i));
            return;
        }
        if (!(root instanceof TextView) && !(root instanceof SeekBar)) return;
        save(root);
        if (root instanceof SeekBar) {
            SeekBar b = (SeekBar) root;
            int p = b.getProgress();
            b.setProgressDrawable(sliderBar());
            b.setThumb(sliderKnob());
            b.setThumbOffset(0);
            b.setMinHeight(px(24));
            b.setMaxHeight(px(24));
            b.setProgressTintList(null);
            b.setThumbTintList(null);
            b.setProgress(0);  // the new drawables take the level
            b.setProgress(p);
            return;
        }
        TextView t = (TextView) root;
        if (root instanceof CompoundButton) {
            CompoundButton c = (CompoundButton) root;
            c.setButtonTintList(null);
            c.setButtonDrawable(root instanceof android.widget.RadioButton ? radio() : checkbox());
            c.setTypeface(regular);
            c.setTextColor(new ColorStateList(new int[][] {DISABLED, ANY}, new int[] {0x80FFFFFF, 0xFFFFFFFF}));
            c.setCompoundDrawablePadding(px(8));
            c.setPadding(px(6), c.getPaddingTop(), c.getPaddingRight(), c.getPaddingBottom());
            return;
        }
        if (root instanceof android.widget.Button) {
            t.setBackground(button());
            t.setStateListAnimator(null);
            t.setAllCaps(false);
            t.setTypeface(semibold);
            t.setTextColor(new ColorStateList(new int[][] {DISABLED, ANY}, new int[] {0xFFB3B3B3, 0xFF000000}));
            t.setPadding(px(16), px(6), px(16), px(6));
            return;
        }
        boolean heading = t.getTextSize() >= 14.5f * t.getResources().getDisplayMetrics().scaledDensity;
        t.setTypeface(heading ? bold : regular);
        t.setTextColor(heading ? CAPTION : 0xFFFFFFFF);
    }

    private void save(View v) {
        if (saved.containsKey(v)) return;
        Object[] s = new Object[8];
        s[0] = v.getBackground();
        s[1] = v.getStateListAnimator();
        s[7] = new int[] {v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()};
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            s[2] = t.getTypeface();
            s[3] = t.getTextColors();
            if (v instanceof CompoundButton) s[4] = ((CompoundButton) v).getButtonDrawable();
            s[6] = t.isAllCaps();
        }
        if (v instanceof SeekBar) {
            SeekBar b = (SeekBar) v;
            s[4] = b.getProgressDrawable();
            s[5] = new Object[] {b.getThumb(), b.getMinHeight(), b.getMaxHeight(), b.getThumbOffset()};
        }
        saved.put(v, s);
    }

    /** Puts back what skin() changed (another key theme chosen). */
    void restore() {
        for (java.util.Map.Entry<View, Object[]> e : saved.entrySet()) {
            View v = e.getKey();
            Object[] s = e.getValue();
            v.setBackground((Drawable) s[0]);
            v.setStateListAnimator((android.animation.StateListAnimator) s[1]);
            int[] p = (int[]) s[7];
            v.setPadding(p[0], p[1], p[2], p[3]);
            if (v instanceof SeekBar) {
                SeekBar b = (SeekBar) v;
                Object[] k = (Object[]) s[5];
                int at = b.getProgress();
                b.setProgressDrawable((Drawable) s[4]);
                b.setThumb((Drawable) k[0]);
                b.setMinHeight((Integer) k[1]);
                b.setMaxHeight((Integer) k[2]);
                b.setThumbOffset((Integer) k[3]);
                b.setProgress(0);
                b.setProgress(at);
            } else if (v instanceof TextView) {
                TextView t = (TextView) v;
                t.setTypeface((Typeface) s[2]);
                t.setTextColor((ColorStateList) s[3]);
                t.setAllCaps((Boolean) s[6]);
                if (v instanceof CompoundButton) ((CompoundButton) v).setButtonDrawable((Drawable) s[4]);
            }
        }
        saved.clear();
    }
}
