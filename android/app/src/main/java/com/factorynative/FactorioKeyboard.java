package com.factorynative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLInputConnection;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The screen keyboard in the game's look, instead of the system one while the game types (gear menu "Управление",
 * "Клавиатура в стиле игры"): a window frame of the game without a title (the user: it only takes room), its buttons — grey
 * letters, dark special keys, the green Enter (style.lua button, dark_button, green_button) — and its font
 * (FactorioSkin). Text goes to SDL as the system keyboard's does (SDLInputConnection.updateText): ASCII also as key
 * presses, then the text; Backspace as '\b'. Russian or Ukrainian and English letters, a symbols page; long press:
 * е → ё, ь → ъ, г → ґ.
 */
final class FactorioKeyboard extends View {
    /** The user put the keyboard away (close mark): the game's input stays on. */
    interface Owner {
        void keyboardClosed();
    }

    private static final int TEXT = 0, SHIFT = 1, BACKSPACE = 2, ENTER = 3, SYMBOLS = 4, LANG = 5, LEFT = 6, RIGHT = 7, CLOSE = 8;

    // languages: letters of rows 2–4 (row 4 between Shift and Backspace); the names are not translated
    private static final String[] LANGS = {"ru", "uk", "en"};
    private static final String[][] LETTERS = {
            {"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"},
            {"йцукенгшщзхї", "фівапролджє", "ячсмитьбю"},
            {"qwertyuiop", "asdfghjkl", "zxcvbnm"}};
    private static final String[] NAMES = {"Русский", "Українська", "English"};
    private static final String[] SHORT = {"РУС", "УКР", "ENG"};
    private static final String[] ABC = {"АБВ", "АБВ", "ABC"};
    private static final String[] SYMBOL_ROWS = {"@#$%&*-+()", "/\\=_\"':;!?", "[]{}<>|~^`"};
    private static final String ALT_FROM = "еьг", ALT_TO = "ёъґ";

    private static final class Key {
        final int kind;
        final String text;
        final float weight;
        String label;
        Drawable bg;
        final RectF r = new RectF();
        boolean down;

        Key(int kind, String text, float weight) {
            this.kind = kind;
            this.text = text;
            this.weight = weight;
        }
    }

    /** A finger on a key; done: its key already acted (long press), the release types nothing. */
    private static final class Touch {
        Key key;
        boolean done;
    }

    private final Owner owner;
    private FactorioSkin skin;
    private Drawable frame, inner;
    private final ArrayList<Key[]> rows = new ArrayList<>();
    private final boolean[] stretch = new boolean[6];
    private final SparseArray<Touch> touches = new SparseArray<>();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] langs;
    private int lang;
    private boolean symbols, numberRow;
    private int shift;  // 0 off, 1 the next letter, 2 caps lock
    private long lastShiftTap;
    private float pad, innerPad, gap, rowH;
    private Key repeating;
    private Touch pressing;  // the finger whose long press is waited for

    FactorioKeyboard(Context c, Owner owner) {
        super(c);
        this.owner = owner;
        String phone = Locale.getDefault().getLanguage();
        langs = "uk".equals(phone) ? new int[] {1, 2} : "ru".equals(phone) || "be".equals(phone) ? new int[] {0, 2} : new int[] {2, 0};
        String saved = c.getSharedPreferences("game", Context.MODE_PRIVATE).getString("keyboardLang", null);
        lang = langs[0];
        for (int l : langs) if (LANGS[l].equals(saved)) lang = l;
        text.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
    }

    /** The game's sprites and fonts (they are read with the gear menu). */
    void setSkin(FactorioSkin s) {
        if (s == skin) return;
        skin = s;
        frame = s.frame();
        inner = s.inner();
        build();
    }

    /** Shown for the game's text input; a number field starts on the symbols page (the digits). */
    void show(int inputType) {
        symbols = (inputType & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER;
        shift = 0;
        build();
        setVisibility(VISIBLE);
    }

    void hide() {
        release();
        setVisibility(GONE);
    }

    boolean shown() {
        return getVisibility() == VISIBLE;
    }

    // ---- the keys

    private Key key(int kind, String s, float weight) {
        Key k = new Key(kind, s, weight);
        k.label = s;
        if (skin != null) k.bg = kind == TEXT ? skin.button() : kind == ENTER ? skin.greenButton() : skin.darkButton();
        return k;
    }

    private Key[] chars(String s) {
        Key[] r = new Key[s.length()];
        for (int i = 0; i < r.length; i++) r[i] = key(TEXT, s.substring(i, i + 1), 1);
        return r;
    }

    private static Key[] join(Key a, Key[] m, Key b) {
        Key[] r = new Key[m.length + (a != null ? 1 : 0) + 1];
        int i = 0;
        if (a != null) r[i++] = a;
        for (Key k : m) r[i++] = k;
        r[i] = b;
        return r;
    }

    private void build() {
        release();
        rows.clear();
        int n = 0;
        if (numberRow || symbols) {
            stretch[n++] = true;
            rows.add(chars("1234567890"));
        }
        if (symbols) {
            rows.add(chars(SYMBOL_ROWS[0]));
            rows.add(chars(SYMBOL_ROWS[1]));
            rows.add(join(null, chars(SYMBOL_ROWS[2]), key(BACKSPACE, "⌫", 1.5f)));
        } else {
            rows.add(chars(LETTERS[lang][0]));
            rows.add(chars(LETTERS[lang][1]));
            rows.add(join(key(SHIFT, "⇧", 1.5f), chars(LETTERS[lang][2]), key(BACKSPACE, "⌫", 1.5f)));
        }
        stretch[n++] = symbols;
        stretch[n++] = symbols;
        stretch[n++] = symbols;
        rows.add(new Key[] {key(SYMBOLS, symbols ? ABC[lang] : "?123", 1.4f), key(LANG, SHORT[lang], 1.2f), key(TEXT, ",", 1),
                key(LEFT, "←", 1), key(TEXT, " ", 3.6f), key(RIGHT, "→", 1), key(TEXT, ".", 1), key(CLOSE, "▼", 1), key(ENTER, "↵", 1.6f)});
        rows.get(rows.size() - 1)[4].label = NAMES[lang];
        stretch[n] = true;
        relabel();
        place();
        requestLayout();  // the symbols page has one row more (the digits): its bottom row was cut off (0.197)
        invalidate();
    }

    /** Letters in the case Shift gives; Shift's own mark (caps lock: ⇪). */
    private void relabel() {
        for (Key[] row : rows)
            for (Key k : row) {
                if (k.kind == TEXT && k.text.length() == 1 && Character.isLetter(k.text.charAt(0)))
                    k.label = shift > 0 ? k.text.toUpperCase(Locale.ROOT) : k.text;
                if (k.kind == SHIFT) k.label = shift == 2 ? "⇪" : "⇧";
            }
        invalidate();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // the screen from the configuration, not from the views: right after a turn the root view still had the old
        // orientation's height — the rows came out large in one orientation (0.199, the user's screenshots)
        android.content.res.Configuration cf = getResources().getConfiguration();
        int w = MeasureSpec.getSize(wSpec);
        boolean portrait = cf.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        if (portrait != numberRow) {
            numberRow = portrait;  // the digits row only when the keyboard has height to spare
            build();
        }
        FactorioSkin s = skin;
        // compact (the user, 0.197: "fat"): thin frame, small gaps, low rows
        pad = s != null ? s.px(5) : dp(3);
        innerPad = s != null ? s.px(4) : dp(2);
        gap = s != null ? s.px(4) : dp(2);
        // one row height in both orientations: from the screen's short side, as small as the first landscape start
        rowH = Math.max(dp(26), Math.min(dp(34), cf.smallestScreenWidthDp * 0.078f * getResources().getDisplayMetrics().density));
        int h = Math.round(2 * pad + 2 * innerPad + rows.size() * rowH + (rows.size() - 1) * gap);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        place();
    }

    private void place() {
        int w = getWidth();
        if (w <= 0 || rows.isEmpty()) return;
        float left = pad + innerPad, right = w - pad - innerPad, top = pad + innerPad;
        float units = 0;
        for (int i = 0; i < rows.size(); i++) if (!stretch[i]) units = Math.max(units, sum(rows.get(i)));
        float unit = units > 0 ? (right - left + gap) / units : 0;
        for (int i = 0; i < rows.size(); i++) {
            Key[] row = rows.get(i);
            float u = stretch[i] || unit == 0 ? (right - left + gap) / sum(row) : unit;
            float x = left + ((right - left + gap) - u * sum(row)) / 2, y = top + i * (rowH + gap);
            for (Key k : row) {
                k.r.set(x, y, x + u * k.weight - gap, y + rowH);
                x += u * k.weight;
            }
        }
    }

    private static float sum(Key[] row) {
        float s = 0;
        for (Key k : row) s += k.weight;
        return s;
    }

    // ---- drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (frame != null) {
            frame.setBounds(0, 0, w, h);
            frame.draw(c);
        } else c.drawColor(0xFF313031);
        if (inner != null) {
            inner.setBounds(Math.round(pad), Math.round(pad), Math.round(w - pad), Math.round(h - pad));
            inner.draw(c);
        }
        for (Key[] row : rows) for (Key k : row) drawKey(c, k);
    }

    private void drawKey(Canvas c, Key k) {
        if (k.bg != null) {
            // state_enabled always: without it the button's "disabled" sprite (darker grey) was chosen (0.197)
            k.bg.setState(k.down ? new int[] {android.R.attr.state_enabled, android.R.attr.state_pressed}
                    : k.kind == SHIFT && shift > 0 ? new int[] {android.R.attr.state_enabled, android.R.attr.state_selected}
                    : new int[] {android.R.attr.state_enabled});
            k.bg.setBounds(Math.round(k.r.left), Math.round(k.r.top), Math.round(k.r.right), Math.round(k.r.bottom));
            k.bg.draw(c);
        } else {
            text.setColor(k.down ? 0xFFFF9F1C : 0xFF8E8E8E);
            c.drawRect(k.r, text);
        }
        boolean dark = k.kind != TEXT && k.kind != ENTER;
        boolean lit = k.down || (k.kind == SHIFT && shift > 0);
        text.setColor(dark && !lit ? 0xFFFFFFFF : 0xFF000000);
        text.setTypeface(skin != null ? (k.kind == TEXT && k.label.length() == 1 ? skin.semibold : skin.regular) : null);
        boolean small = k.label.length() > 1 && k.kind != BACKSPACE;
        text.setTextSize(k.r.height() * (small ? 0.32f : 0.42f));
        float y = k.r.centerY() - (text.descent() + text.ascent()) / 2 + (k.down ? 1 : 0);  // the game: text down on click
        c.drawText(k.label, k.r.centerX(), y, text);
    }

    // ---- touches: every finger on its own key (fast typing with two thumbs), the key acts on release

    private Key keyAt(float x, float y) {
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (Key[] row : rows)
            for (Key k : row) {
                float dx = Math.max(0, Math.max(k.r.left - x, x - k.r.right)), dy = Math.max(0, Math.max(k.r.top - y, y - k.r.bottom));
                float d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int i = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Touch t = new Touch();
                touches.put(e.getPointerId(i), t);
                press(t, keyAt(e.getX(i), e.getY(i)));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int p = 0; p < e.getPointerCount(); p++) {
                    Touch t = touches.get(e.getPointerId(p));
                    if (t == null || t.done || t.key == null || t.key == repeating) continue;
                    Key k = keyAt(e.getX(p), e.getY(p));
                    if (k != t.key && k != null && k.kind == TEXT && t.key.kind == TEXT) press(t, k);  // slid to the next letter
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                Touch t = touches.get(e.getPointerId(i));
                touches.remove(e.getPointerId(i));
                if (t != null) up(t, true);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
        }
        return true;
    }

    private void press(Touch t, Key k) {
        if (t.key != null) unpress(t.key);
        if (pressing == t) stopLongPress();
        t.key = k;
        if (k == null) return;
        k.down = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (k.kind == BACKSPACE || k.kind == LEFT || k.kind == RIGHT) {
            // at once, and again while held
            act(k);
            t.done = true;
            repeating = k;
            handler.removeCallbacks(repeat);
            handler.postDelayed(repeat, 400);
        } else if (k.kind == TEXT && k.text.length() == 1 && ALT_FROM.indexOf(k.text.charAt(0)) >= 0) {
            pressing = t;
            handler.removeCallbacks(longPress);
            handler.postDelayed(longPress, 400);
        }
        invalidate();
    }

    private void up(Touch t, boolean act) {
        Key k = t.key;
        if (k == null) return;
        if (pressing == t) stopLongPress();
        if (k == repeating) {
            handler.removeCallbacks(repeat);
            repeating = null;
        }
        unpress(k);
        if (act && !t.done) act(k);
        invalidate();
    }

    private void unpress(Key k) {
        for (int i = 0; i < touches.size(); i++) if (touches.valueAt(i).key == k) return;  // another finger holds it
        k.down = false;
    }

    /** All fingers off without typing (hidden, rebuilt, cancelled). */
    private void release() {
        handler.removeCallbacks(repeat);
        stopLongPress();
        repeating = null;
        for (int i = 0; i < touches.size(); i++) {
            Key k = touches.valueAt(i).key;
            if (k != null) k.down = false;
        }
        touches.clear();
        invalidate();
    }

    private void stopLongPress() {
        handler.removeCallbacks(longPress);
        pressing = null;
    }

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (repeating == null) return;
            act(repeating);
            handler.postDelayed(this, 50);
        }
    };

    private final Runnable longPress = () -> {
        Touch t = pressing;
        pressing = null;
        if (t == null || t.key == null || t.done) return;
        int a = ALT_FROM.indexOf(t.key.text.charAt(0));
        if (a < 0) return;
        t.done = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        type(ALT_TO.substring(a, a + 1));
    };

    // ---- what the keys do

    private void act(Key k) {
        switch (k.kind) {
            case TEXT:
                type(k.text);
                break;
            case BACKSPACE:
                SDLInputConnection.nativeGenerateScancodeForUnichar('\b');
                break;
            case ENTER:
                tap(KeyEvent.KEYCODE_ENTER);
                break;
            case LEFT:
                tap(KeyEvent.KEYCODE_DPAD_LEFT);
                break;
            case RIGHT:
                tap(KeyEvent.KEYCODE_DPAD_RIGHT);
                break;
            case SHIFT: {
                long now = android.os.SystemClock.uptimeMillis();
                shift = shift == 1 && now - lastShiftTap < 350 ? 2 : shift == 0 ? 1 : 0;  // twice quickly: caps lock
                lastShiftTap = now;
                relabel();
                break;
            }
            case SYMBOLS:
                symbols = !symbols;
                shift = 0;
                build();
                break;
            case LANG: {
                int at = 0;
                for (int i = 0; i < langs.length; i++) if (langs[i] == lang) at = i;
                lang = langs[(at + 1) % langs.length];
                getContext().getSharedPreferences("game", Context.MODE_PRIVATE).edit().putString("keyboardLang", LANGS[lang]).apply();
                symbols = false;
                build();
                break;
            }
            case CLOSE:
                owner.keyboardClosed();
                break;
        }
    }

    /** As the system keyboard: ASCII also as key presses (the game's shortcuts in fields), then the text. */
    private void type(String s) {
        String t = shift > 0 ? s.toUpperCase(Locale.ROOT) : s;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c > 0 && c < 128) SDLInputConnection.nativeGenerateScancodeForUnichar(c);
        }
        SDLInputConnection.nativeCommitText(t, 0);
        if (shift == 1 && Character.isLetter(s.charAt(0))) {
            shift = 0;
            relabel();
        }
    }

    private static void tap(int keycode) {
        SDLActivity.onNativeKeyDown(keycode);
        SDLActivity.onNativeKeyUp(keycode);
    }
}
