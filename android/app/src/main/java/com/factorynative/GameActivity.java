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
        if (mLayout != null) addKeyOverlay();
    }

    // ---- on-screen keys: held while the finger is down

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

        // Bottom-right: arrows
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        rp.setMargins(0, 0, dp(8), dp(8));
        overlay.addView(cluster("↑", KeyEvent.KEYCODE_DPAD_UP, "←", KeyEvent.KEYCODE_DPAD_LEFT,
                "↓", KeyEvent.KEYCODE_DPAD_DOWN, "→", KeyEvent.KEYCODE_DPAD_RIGHT), rp);

        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
    }
}
