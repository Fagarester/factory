package com.factorynative;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Chat and walkie-talkie over our network (NetTunnel; work only while another player is on the line):
 * the gear menu's "Чат" page (history and input), lines over the game above the gear (fade in, 5 s, fade out),
 * and the mic button above the gear — held, the voice goes out at once (NetVoice); let go, it stops.
 */
final class NetChat {
    static final int REQ_MIC = 0x4E03;
    private static final int HISTORY = 200, SHOWN = 5;
    private static final long SHOW_MS = 5000, FADE_IN_MS = 250, FADE_OUT_MS = 700, TALK_QUIET_MS = 600;
    // the game's music and sounds while someone talks: 0.09 = twice the dB of 0.3 (−21 dB instead of −10.5 dB; 0.173 was
    // too little, the user 2026-11-25)
    private static final float DUCK = 0.09f;

    private final Activity a;
    private final NetPage net;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final NetVoice voice = new NetVoice();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private LinearLayout page, history, overlay, lines;  // overlay: the lines, then who types
    private TextView typingLine, typingBubble;  // "… печатает…": on the page above the input, over the game below the lines
    private final Map<Integer, String> typers = new java.util.LinkedHashMap<>();  // player → name, while typing
    private final Map<Integer, Long> typedSeen = new HashMap<>();
    private long typingSent;
    private static final long TYPING_EVERY_MS = 2000, TYPING_GONE_MS = 3500;
    private ScrollView historyScroll;
    private TextView note;
    private EditText input;
    private Button send;
    private ImageView mic;
    private final Map<Integer, TextView> talking = new HashMap<>();  // speaker → its "speaks" line over the game
    private final Map<Integer, Long> talkSeen = new HashMap<>();

    NetChat(Activity activity, NetPage net) {
        a = activity;
        this.net = net;
        buildPage();
        overlay = new LinearLayout(a);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.END | Gravity.BOTTOM);
        lines = new LinearLayout(a);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setGravity(Gravity.END | Gravity.BOTTOM);
        overlay.addView(lines, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        typingBubble = bubble("");
        typingBubble.setVisibility(View.GONE);
        overlay.addView(typingBubble);
        buildMic();
        refresh();
    }

    View page() {
        return page;
    }

    /** The lines over the game: the activity places it above the mic. */
    View overlay() {
        return overlay;
    }

    View mic() {
        return mic;
    }

    // ------------------------------------------------------------------ the page

    private void buildPage() {
        page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        note = new TextView(a);
        note.setTextSize(13);
        note.setPadding(dp(8), 0, dp(8), dp(6));
        page.addView(note);
        historyScroll = new ScrollView(a);
        history = new LinearLayout(a);
        history.setOrientation(LinearLayout.VERTICAL);
        history.setPadding(dp(8), 0, dp(8), 0);
        historyScroll.addView(history);
        page.addView(historyScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        typingLine = new TextView(a);
        typingLine.setTextSize(13);
        typingLine.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        typingLine.setPadding(dp(8), dp(2), dp(8), 0);
        typingLine.setVisibility(View.GONE);
        page.addView(typingLine);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(a);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(NetTunnel.MAX_CHAT)});
        input.setHint(Tr.t("Сообщение"));
        input.addTextChangedListener(new android.text.TextWatcher() {  // "I am typing", at most every 2 s
            @Override public void beforeTextChanged(CharSequence c, int st, int n, int after) {}
            @Override public void onTextChanged(CharSequence c, int st, int before, int n) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                long now = System.currentTimeMillis();
                if (e.toString().trim().isEmpty() || now - typingSent < TYPING_EVERY_MS || !net.chatLinked()) return;
                typingSent = now;
                net.sendTyping();
            }
        });
        input.setOnEditorActionListener((v, id, e) -> {
            if (id != EditorInfo.IME_ACTION_SEND) return false;
            sendInput();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        send = new Button(a);
        send.setAllCaps(false);
        send.setText(Tr.t("Отправить"));
        send.setTextSize(13);
        send.setMinHeight(dp(40));
        send.setMinimumHeight(dp(40));
        send.setOnClickListener(v -> sendInput());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(6);
        row.addView(send, sp);
        page.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void sendInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!net.chatLinked()) {
            toast(Tr.t("Чат работает, когда в сети есть другой игрок (вкладка «Сеть»)."));
            return;
        }
        net.sendChat(text);
        typingSent = 0;
        input.setText("");
        add(net.myName(), text, true);
    }

    /** The page's state follows the network (NetPage.refresh). */
    void refresh() {
        boolean on = net.chatLinked();
        note.setText(on ? Tr.t("Сообщения видят все игроки сети. Рация: держи микрофон над шестерёнкой и говори; двойной тап — говорить без удержания, ещё тап — выключить.")
                : Tr.t("Чат и рация работают, когда в сети есть другой игрок (вкладка «Сеть»)."));
        input.setEnabled(on);
        send.setEnabled(on);
        input.setAlpha(on ? 1f : 0.4f);
        send.setAlpha(on ? 1f : 0.4f);
        mic.setVisibility(net.chatActive() ? View.VISIBLE : View.GONE);
        if (!on && voice.recording()) micUp();
    }

    // ------------------------------------------------------------------ incoming (NetTunnel thread)

    void incoming(String from, String text) {
        if (text.isEmpty()) return;
        ui.post(() -> add(from, text, false));
    }

    void incomingTyping(int src, String from) {
        ui.post(() -> {
            typers.put(src, from);
            typedSeen.put(src, System.currentTimeMillis());
            showTyping();
        });
    }

    void incomingVoice(int src, String from, byte[] frame) {
        voice.play(src, frame);
        ui.post(() -> talks(src, from));
    }

    // ------------------------------------------------------------------ lines: the history and over the game

    /** Who types now: "A печатает…", "A, B печатают…", "A, B и ещё 2 печатают…"; gone 3.5 s after the last sign. */
    private void showTyping() {
        long now = System.currentTimeMillis();
        for (java.util.Iterator<Map.Entry<Integer, Long>> it = typedSeen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Long> e = it.next();
            if (now - e.getValue() > TYPING_GONE_MS) {
                typers.remove(e.getKey());
                it.remove();
            }
        }
        java.util.List<String> names = new java.util.ArrayList<>(typers.values());
        String text = null;
        if (!names.isEmpty()) {
            String who = names.size() <= 2 ? android.text.TextUtils.join(", ", names)
                    : String.format(Tr.t("%s и ещё %d"), names.get(0) + ", " + names.get(1), names.size() - 2);
            text = String.format(Tr.t(names.size() == 1 ? "%s печатает…" : "%s печатают…"), who);
        }
        typingLine.setVisibility(text != null ? View.VISIBLE : View.GONE);
        typingBubble.setVisibility(text != null ? View.VISIBLE : View.GONE);
        if (text != null) {
            typingLine.setText(text);
            typingBubble.setText("✎ " + text);
            ui.removeCallbacks(typingTick);
            ui.postDelayed(typingTick, 500);
        }
    }

    private final Runnable typingTick = this::showTyping;

    private void add(String from, String text, boolean mine) {
        if (!mine) {  // the line came: that player no longer types
            for (java.util.Iterator<Map.Entry<Integer, String>> it = typers.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, String> e = it.next();
                if (e.getValue().equals(from)) {
                    typedSeen.remove(e.getKey());
                    it.remove();
                }
            }
            showTyping();
        }
        TextView h = new TextView(a);
        h.setTextSize(14);
        h.setPadding(0, dp(2), 0, dp(2));
        h.setText(line(clock.format(new Date()) + "  ", from, text, mine ? 0xFF8FD18F : 0xFFFFC870));
        history.addView(h);
        while (history.getChildCount() > HISTORY) history.removeViewAt(0);
        historyScroll.post(() -> historyScroll.fullScroll(View.FOCUS_DOWN));
        show(line("", from, text, mine ? 0xFF8FD18F : 0xFFFFC870));
    }

    private static CharSequence line(String time, String from, String text, int nameColor) {
        SpannableStringBuilder b = new SpannableStringBuilder(time);
        int at = b.length();
        b.append(from).append(": ");
        b.setSpan(new ForegroundColorSpan(nameColor), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new StyleSpan(Typeface.BOLD), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(text);
        return b;
    }

    /** A line over the game: fades in, stays 5 s, fades out. */
    private void show(CharSequence text) {
        TextView t = bubble(text);
        lines.addView(t);
        while (lines.getChildCount() > SHOWN) lines.removeViewAt(0);
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        ui.postDelayed(() -> fadeOut(t), SHOW_MS);
    }

    private void fadeOut(TextView t) {
        if (t.getParent() == null) return;
        t.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> lines.removeView(t)).start();
    }

    private TextView bubble(CharSequence text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Color.WHITE);
        t.setMaxWidth(dp(320));
        t.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x8C000000);
        bg.setCornerRadius(dp(6));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        lp.gravity = Gravity.END;
        t.setLayoutParams(lp);
        return t;
    }

    /** "🔊 name": shown while that player's voice comes, goes 0.6 s after the last frame. */
    private void talks(int src, String from) {
        talkSeen.put(src, System.currentTimeMillis());
        if (talking.containsKey(src)) return;
        TextView t = bubble("🔊 " + from);
        talking.put(src, t);
        duck();
        lines.addView(t);
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                Long seen = talkSeen.get(src);
                if (seen != null && System.currentTimeMillis() - seen < TALK_QUIET_MS) {
                    ui.postDelayed(this, 200);
                    return;
                }
                talking.remove(src);
                talkSeen.remove(src);
                duck();
                fadeOut(t);
            }
        }, TALK_QUIET_MS);
    }

    // ------------------------------------------------------------------ the mic button (hold to talk)

    private void buildMic() {
        mic = new ImageView(a);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setPadding(dp(8), dp(8), dp(8), dp(8));
        micLook(false);
        // hold = talk while held; double tap = talk without holding (latched) until the next tap
        mic.setOnTouchListener((v, e) -> {
            long now = System.currentTimeMillis();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (latched) {  // a tap ends the hands-free talk
                        latched = false;
                        endTap = true;
                        micUp();
                        return true;
                    }
                    secondTap = now - lastTapUp < DOUBLE_TAP_MS;
                    downAt = now;
                    micDown();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (endTap) {
                        endTap = false;
                        return true;
                    }
                    boolean tap = now - downAt < TAP_MS && e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (tap && secondTap && voice.recording()) {
                        latched = true;  // the second tap of a double tap: keeps talking
                        lastTapUp = 0;
                        toast(Tr.t("Рация включена без удержания: нажми ещё раз, чтобы выключить"));
                        return true;
                    }
                    lastTapUp = tap ? now : 0;
                    micUp();
                    return true;
                default:
                    return true;  // the finger may slide off: it still talks until it lifts
            }
        });
    }

    private static final long TAP_MS = 250, DOUBLE_TAP_MS = 350;
    private boolean latched, secondTap, endTap;
    private long downAt, lastTapUp;
    private android.animation.ValueAnimator pulse;

    /** The mic button pulses while it sends (held or latched). */
    private void pulse(boolean on) {
        if (on && pulse == null) {
            pulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
            pulse.setDuration(550);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.addUpdateListener(an -> {
                float f = (float) an.getAnimatedValue();
                mic.setScaleX(1f + 0.18f * f);
                mic.setScaleY(1f + 0.18f * f);
                mic.setAlpha(0.7f + 0.3f * f);
            });
            pulse.start();
        } else if (!on && pulse != null) {
            pulse.cancel();
            pulse = null;
            mic.setScaleX(1f);
            mic.setScaleY(1f);
        }
    }

    private void micLook(boolean on) {
        pulse(on);
        mic.setColorFilter(on ? 0xFFFF5050 : 0xFFD0D0D0);
        mic.setAlpha(on ? 0.95f : 0.55f);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? 0x66FF3030 : 0x00000000);
        mic.setBackground(bg);
    }

    private void micDown() {
        if (!net.chatLinked()) {
            toast(Tr.t("Рация работает, когда в сети есть другой игрок."));
            return;
        }
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!voice.start(net::sendVoice)) {
            toast(Opus.ok() ? Tr.t("Микрофон не открылся") : Tr.t("Кодек Opus не загрузился: рация не работает"));
            return;
        }
        micLook(true);
        duck();
    }

    private void micUp() {
        latched = false;
        voice.stop();
        micLook(false);
        duck();
    }

    /** The game quieter while this phone talks or hears a player; back as set in the game after. */
    private void duck() {
        GameActivity.setGameVolume(voice.recording() || !talking.isEmpty() ? DUCK : 1f);
    }

    void onMicPermission(boolean granted) {
        toast(granted ? Tr.t("Микрофон разрешён: держи кнопку и говори.") : Tr.t("Без доступа к микрофону рация не работает."));
    }

    void onPause() {
        if (voice.recording()) micUp();
    }

    void destroy() {
        ui.removeCallbacksAndMessages(null);  // its own handler: typing and "talks" timers
        pulse(false);
        voice.close();
        GameActivity.setGameVolume(1f);
    }

    // ------------------------------------------------------------------ helpers

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
