package com.factorynative;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

/** Runs the game: SDL3 + libfnative.so, which loads the official Linux binary. */
public class GameActivity extends SDLActivity {
    static final String EXTRA_ARGS = "args";
    /** Output resolution as a fraction of the screen (1 = native). */
    static final String EXTRA_OUTPUT_SCALE = "outputScale";
    static final String EXTRA_POWER_METER = "powerMeter";
    private float outputScale = 1f;
    private int fixedW, fixedH;

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "fnative"};
    }

    @Override
    protected String getMainSharedObject() {
        return getApplicationInfo().nativeLibraryDir + "/libfnative.so";
    }

    @Override
    protected String[] getArguments() {
        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return args != null ? args : new String[0];
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        outputScale = getIntent().getFloatExtra(EXTRA_OUTPUT_SCALE, 1f);
        if (mSurface != null && outputScale < 0.999f) {
            // Smaller surface buffer; the display compositor scales it to the screen.
            mSurface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyOutputScale(r - l, b - t));
        }
        if (mLayout != null) addKeyOverlay();
        if (mLayout != null && getIntent().getBooleanExtra(EXTRA_POWER_METER, false)) addPowerMeter();
    }

    // ---- power meter: battery current x voltage, once a second.
    // "W avg / now": average of the last 60 s, then the last reading.

    private final android.os.Handler meterHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final float[] meterHistory = new float[60];
    private int meterCount, meterPos;
    private TextView meterText;

    private static long readLong(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private void addPowerMeter() {
        meterText = new TextView(this);
        meterText.setTextColor(Color.WHITE);
        meterText.setTextSize(14);
        meterText.setBackgroundColor(0x66000000);
        meterText.setPadding(dp(8), dp(2), dp(8), dp(2));
        meterText.setText("W …");
        FrameLayout overlay = new FrameLayout(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.setMargins(0, dp(4), 0, 0);
        overlay.addView(meterText, p);
        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT));
        meterHandler.post(meterTick);
    }

    private final Runnable meterTick = new Runnable() {
        @Override public void run() {
            updatePowerMeter();
            meterHandler.postDelayed(this, 1000);
        }
    };

    private void updatePowerMeter() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        android.content.Intent bat = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (bm == null || bat == null) return;
        int plugged = bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        // Prefer the kernel's battery values (pack voltage: dual-cell phones may
        // report one cell's voltage through BatteryManager), else BatteryManager.
        long uv = readLong("/sys/class/power_supply/battery/voltage_now");  // microvolts
        long ua = readLong("/sys/class/power_supply/battery/current_now");  // microamps
        if (uv <= 0 || ua == Long.MIN_VALUE) {
            uv = bat.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) * 1000L;
            if (uv > 0 && uv < 1000000) uv *= 1000;  // a few devices report volts
            ua = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }
        if (ua == Long.MIN_VALUE || ua == 0 || uv <= 0) { meterText.setText("W нет данных"); return; }
        double volts = uv / 1e6, amps = Math.abs(ua) / 1e6;
        if (amps * volts < 0.05) amps *= 1000;  // device reports milliamps
        float watts = (float) (amps * volts);
        if (plugged != 0) {
            meterCount = 0;
            meterPos = 0;
            meterText.setText(String.format(java.util.Locale.ROOT, "W зарядка (%.1f)", watts));
            return;
        }
        meterHistory[meterPos] = watts;
        meterPos = (meterPos + 1) % meterHistory.length;
        if (meterCount < meterHistory.length) meterCount++;
        float sum = 0;
        for (int i = 0; i < meterCount; i++) sum += meterHistory[i];
        meterText.setText(String.format(java.util.Locale.ROOT, "W %.1f / %.1f", sum / meterCount, watts));
    }

    @Override
    protected void onDestroy() {
        meterHandler.removeCallbacks(meterTick);
        super.onDestroy();
    }

    private void applyOutputScale(int viewW, int viewH) {
        if (viewW <= 0 || viewH <= 0) return;
        int w = Math.max(1, Math.round(viewW * outputScale)), h = Math.max(1, Math.round(viewH * outputScale));
        if (w == fixedW && h == fixedH) return;
        fixedW = w;
        fixedH = h;
        mSurface.getHolder().setFixedSize(w, h);
    }

    // ---- on-screen keys: held while the finger is down

    private boolean rightClickArmed, rightClickDown;
    private TextView rmbButton;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView key(String label, int keycode) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0x66000000);
        b.setOnTouchListener((View v, MotionEvent e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackgroundColor(0xAAFF8800);
                    SDLActivity.onNativeKeyDown(keycode);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackgroundColor(0x66000000);
                    SDLActivity.onNativeKeyUp(keycode);
                    return true;
            }
            return true;
        });
        return b;
    }

    /** 3x2 cluster: top row has only the middle key (W / Up). */
    private GridLayout cluster(String up, int upKey, String left, int leftKey, String down, int downKey,
                               String right, int rightKey) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        g.setRowCount(2);
        int s = dp(52), m = dp(3);
        String[] labels = {null, up, null, left, down, right};
        int[] codes = {0, upKey, 0, leftKey, downKey, rightKey};
        for (int i = 0; i < 6; i++) {
            View v = labels[i] == null ? new View(this) : key(labels[i], codes[i]);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = s;
            p.height = s;
            p.setMargins(m, m, m, m);
            g.addView(v, p);
        }
        return g;
    }

    private void addKeyOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        // Top-left row: Esc Tab F4 Q E R
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Esc", "Tab", "F4", "Q", "E", "R"};
        int[] codes = {KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_F4,
                       KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(40));
            p.setMargins(dp(3), dp(3), dp(3), dp(3));
            top.addView(key(labels[i], codes[i]), p);
        }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tp.setMargins(dp(8), dp(24), 0, 0);
        overlay.addView(top, tp);

        // Bottom-left: WASD
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(8), 0, 0, dp(8));
        overlay.addView(cluster("W", KeyEvent.KEYCODE_W, "A", KeyEvent.KEYCODE_A, "S", KeyEvent.KEYCODE_S,
                "D", KeyEvent.KEYCODE_D), lp);

        // Bottom-right: arrows, then the right mouse button (RMB)
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.BOTTOM);
        right.addView(cluster("↑", KeyEvent.KEYCODE_DPAD_UP, "←", KeyEvent.KEYCODE_DPAD_LEFT,
                "↓", KeyEvent.KEYCODE_DPAD_DOWN, "→", KeyEvent.KEYCODE_DPAD_RIGHT));
        TextView rmb = new TextView(this);
        rmb.setText("ПКМ");
        rmb.setTextColor(Color.WHITE);
        rmb.setTextSize(14);
        rmb.setGravity(Gravity.CENTER);
        rmb.setBackgroundColor(0x66000000);
        rmb.setOnClickListener(v -> {
            rightClickArmed = !rightClickArmed;
            v.setBackgroundColor(rightClickArmed ? 0xAAFF8800 : 0x66000000);
        });
        rmbButton = rmb;
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(64), dp(52));
        bp.setMargins(dp(6), dp(3), dp(3), dp(3));
        right.addView(rmb, bp);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        rp.setMargins(0, 0, dp(8), dp(8));
        overlay.addView(right, rp);

        // While RMB is armed, the next touch on the game is a right click
        // (held while the finger is down), then the mode switches off.
        overlay.setOnTouchListener((View v, MotionEvent e) -> {
            if (!rightClickArmed && !rightClickDown) return false;
            // overlay covers the surface view: view pixels -> buffer pixels
            float x = mSurface != null ? mSurface.toBufferX(e.getX()) : e.getX();
            float y = mSurface != null ? mSurface.toBufferY(e.getY()) : e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    rightClickDown = true;
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
                    SDLActivity.onNativeMouse(MotionEvent.BUTTON_SECONDARY, MotionEvent.ACTION_DOWN, x, y, false);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    SDLActivity.onNativeMouse(MotionEvent.BUTTON_SECONDARY, MotionEvent.ACTION_MOVE, x, y, false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, x, y, false);
                    rightClickDown = false;
                    rightClickArmed = false;
                    rmbButton.setBackgroundColor(0x66000000);
                    return true;
            }
            return true;
        });

        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
    }
}
