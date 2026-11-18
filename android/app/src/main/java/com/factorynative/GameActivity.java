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

/** Runs the game: SDL3 + libfnative.so, which loads the official Linux binary. */
public class GameActivity extends SDLActivity {
    static final String EXTRA_ARGS = "args";
    /** Output resolution as a fraction of the screen (1 = native). */
    static final String EXTRA_OUTPUT_SCALE = "outputScale";
    static final String EXTRA_POWER_METER = "powerMeter";
    static final String EXTRA_BENCH_SECONDS = "benchSeconds";
    static final String EXTRA_BENCH_OUT = "benchOut";
    static final String EXTRA_BENCH_INFO = "benchInfo";
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
        hideSystemBarsNow();  // at once: the game sets full screen only later, the status bar strip showed meanwhile
        outputScale = getIntent().getFloatExtra(EXTRA_OUTPUT_SCALE, 1f);
        if (mSurface != null && outputScale < 0.999f) {
            // Smaller surface buffer; the display compositor scales it to the screen.
            mSurface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyOutputScale(r - l, b - t));
        }
        self = this;
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this::liftForKeyboard);
        if (mLayout != null) addKeyOverlay();
        if (mLayout != null) addHud();  // shown or not by the gear menu's setting (first: the launcher's)
        if (mLayout != null) addMenu();
        if (mLayout != null && getIntent().getIntExtra(EXTRA_BENCH_SECONDS, 0) > 0) startBench();
    }

    // ---- HUD: FPS, UPS (from libfnative) and battery watts, twice a second;
    // a checkbox shows a frame time graph.

    private static native void nativeStats(float[] out);
    private static native void nativeFrames(float[] out);
    private static native void nativeCounters(long[] out);
    /** The 20 item names of the game's two visible quick bar rows ("" empty), or null without a game (libfnative). */
    private static native String[] nativeQuickBar();
    /** 1 in a game, 0 in the menus, -1 unknown (libfnative, GameView hooks). */
    private static native int nativeInGame();

    // ---- the keys only in a game: hidden in the menus (pausing does not hide them)
    private View keyColumn;
    private boolean keysShown = true;

    private long gameStateSince;  // when the checks began

    private final Runnable gameStateTick = new Runnable() {
        @Override public void run() {
            int state;
            try {
                state = nativeInGame();
            } catch (UnsatisfiedLinkError e) {
                state = -1;  // the game library is not loaded yet
            }
            // unknown (still starting, or a game version without the hooks): hidden at first, shown after 15 s
            if (state < 0) state = android.os.SystemClock.uptimeMillis() - gameStateSince > 15000 ? 1 : 0;
            showKeys(state != 0);
            hudHandler.postDelayed(this, 300);
        }
    };

    private void showKeys(boolean on) {
        if (on == keysShown || keyColumn == null) return;
        keysShown = on;
        for (View v : new View[] {keyColumn, stickView}) {
            if (v == null) continue;
            v.animate().cancel();
            if (on) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(200).start();
            } else {
                v.animate().alpha(0f).setDuration(200).withEndAction(() -> v.setVisibility(View.GONE)).start();
            }
        }
    }
    private final float[] graphFrames = new float[120];

    private final android.os.Handler hudHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final float[] stats = new float[2 + 120];
    private TextView hudText;
    private FrameGraph graph;

    private static long readLong(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private android.widget.CheckBox graphBox, cpuBox, gpuBox;  // in the gear menu
    private TextView cpuLoad, gpuLoad;  // on the HUD strip
    private View hudRoot;
    private TextView cpuPanel, gpuPanel;

    private android.widget.CheckBox hudBox(String label) {
        android.widget.CheckBox b = new android.widget.CheckBox(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        return b;
    }

    private TextView hudPanel() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setBackgroundColor(0x55000000);
        t.setPadding(dp(6), dp(2), dp(6), dp(4));
        t.setVisibility(View.GONE);
        return t;
    }

    private void addHud() {
        // One strip that never changes size; panels open under it, side by
        // side, flush with the strip (no gaps, nothing in the strip moves).
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0x77000000);
        row.setPadding(dp(8), 0, dp(4), 0);

        hudText = new TextView(this);
        hudText.setTextColor(Color.WHITE);
        hudText.setTextSize(14);
        hudText.setTypeface(android.graphics.Typeface.MONOSPACE);
        hudText.setText("FPS – · UPS – · – W");
        row.addView(hudText);

        // the switches for these are in the gear menu ("Производительность")
        graphBox = hudBox("График времени кадра");
        cpuBox = hudBox("ЦП по ядрам");
        gpuBox = hudBox("Частота ГП");
        cpuLoad = new TextView(this);
        gpuLoad = new TextView(this);
        for (TextView t : new TextView[] {cpuLoad, gpuLoad}) {
            t.setTextColor(Color.WHITE);
            t.setTextSize(14);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.setMargins(dp(10), dp(2), 0, dp(2));
            row.addView(t, tp);
        }
        cpuLoad.setText("ЦП –");
        gpuLoad.setText("ГП –");
        column.addView(row);

        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        graph = new FrameGraph(this);
        graph.setVisibility(View.GONE);
        panels.addView(graph, new LinearLayout.LayoutParams(dp(260), dp(90)));
        cpuPanel = hudPanel();
        panels.addView(cpuPanel);
        gpuPanel = hudPanel();
        panels.addView(gpuPanel);
        column.addView(panels);

        graphBox.setOnCheckedChangeListener((b, on) -> {
            graph.setVisibility(on ? View.VISIBLE : View.GONE);
            hudHandler.removeCallbacks(graphTick);
            if (on) hudHandler.post(graphTick);
        });
        cpuBox.setOnCheckedChangeListener((b, on) -> cpuPanel.setVisibility(on ? View.VISIBLE : View.GONE));
        gpuBox.setOnCheckedChangeListener((b, on) -> gpuPanel.setVisibility(on ? View.VISIBLE : View.GONE));

        FrameLayout overlay = new FrameLayout(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        overlay.addView(column, p);
        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT));
        hudRoot = overlay;
        setHud(getSharedPreferences("game", MODE_PRIVATE).getBoolean("hud", getIntent().getBooleanExtra(EXTRA_POWER_METER, false)));
    }

    /** HUD on or off (the gear menu); remembered. Hidden, it reads nothing. */
    private void setHud(boolean on) {
        getSharedPreferences("game", MODE_PRIVATE).edit().putBoolean("hud", on).apply();
        if (hudRoot == null) return;
        hudRoot.setVisibility(on ? View.VISIBLE : View.GONE);
        hudHandler.removeCallbacks(hudTick);
        hudHandler.removeCallbacks(graphTick);
        if (on) {
            hudHandler.post(hudTick);
            if (graph.getVisibility() == View.VISIBLE) hudHandler.post(graphTick);
        }
    }

    // ---- CPU / GPU load (read what the system allows; "–" otherwise)

    private static String readLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String l = r.readLine();
            return l != null ? l.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private long[] prevCoreBusy, prevCoreTotal;
    private long prevProcTicks, prevProcTime;
    private final int cores = Runtime.getRuntime().availableProcessors();

    /** Per-core busy %, from /proc/stat (null when the system hides it). */
    private int[] coreLoads() {
        long[] busy = new long[cores], total = new long[cores];
        boolean any = false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.startsWith("cpu") || l.startsWith("cpu ")) continue;
                String[] f = l.trim().split("\\s+");
                int c = Integer.parseInt(f[0].substring(3));
                if (c >= cores) continue;
                long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]), sum = 0;
                for (int i = 1; i < f.length && i <= 8; i++) sum += Long.parseLong(f[i]);
                busy[c] = sum - idle;
                total[c] = sum;
                any = true;
            }
        } catch (Exception e) {
            return null;
        }
        if (!any) return null;
        int[] load = null;
        if (prevCoreTotal != null) {
            load = new int[cores];
            for (int c = 0; c < cores; c++) {
                long dt = total[c] - prevCoreTotal[c];
                load[c] = dt > 0 ? (int) Math.round(100.0 * (busy[c] - prevCoreBusy[c]) / dt) : 0;
            }
        }
        prevCoreBusy = busy;
        prevCoreTotal = total;
        return load;
    }

    /** CPU time of the game process, % of all cores. */
    private int processLoad() {
        String l = readLine("/proc/self/stat");
        if (l == null) return -1;
        String[] f = l.substring(l.lastIndexOf(')') + 2).split(" ");
        long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);  // utime + stime (fields 14, 15)
        long now = android.os.SystemClock.elapsedRealtime();
        int r = -1;
        if (prevProcTime > 0 && now > prevProcTime)
            // ticks are 1/100 s: % = (ticks / 100) / (ms / 1000) / cores * 100
            r = (int) Math.round((ticks - prevProcTicks) * 1000.0 / ((now - prevProcTime) * (double) cores));
        prevProcTicks = ticks;
        prevProcTime = now;
        return r;
    }

    private void updateCpu() {
        int[] loads = coreLoads();
        int avg = -1;
        if (loads != null) {
            int sum = 0;
            for (int v : loads) sum += v;
            avg = sum / cores;
        }
        int game = processLoad();
        cpuLoad.setText(avg >= 0 ? "ЦП " + avg + "%" : game >= 0 ? "ЦП " + game + "%" : "ЦП –");
        if (cpuPanel.getVisibility() != View.VISIBLE) return;
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cores; c++) {
            String cur = readLine("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq");
            String max = readLine("/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_max_freq");
            sb.append(String.format(java.util.Locale.ROOT, "cpu%d %5s/%-5s МГц %4s", c,
                    cur != null ? Long.parseLong(cur) / 1000 : "–", max != null ? Long.parseLong(max) / 1000 : "–",
                    loads != null ? loads[c] + "%" : "–"));
            if (c < cores - 1) sb.append('\n');
        }
        if (game >= 0) sb.append(String.format(java.util.Locale.ROOT, "\nигра  %d%% всех ядер", game));
        cpuPanel.setText(sb);
    }

    // GPU: Adreno (kgsl), Mali (MediaTek / Samsung / generic devfreq) paths.
    private static final String[] GPU_BUSY = {
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/kernel/gpu/gpu_busy",
    };
    private static final String[] GPU_FREQ = {
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        "/sys/kernel/ged/hal/current_freqency",
        "/sys/class/misc/mali0/device/devfreq/cur_freq",
        "/sys/kernel/gpu/gpu_clock",
    };
    private static final String[] GPU_MAX = {
        "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
        "/sys/class/misc/mali0/device/devfreq/max_freq",
        "/sys/kernel/gpu/gpu_max_clock",
    };

    private static final String[] GPU_NAMES = {"Adreno (kgsl)", "Mali (MediaTek GED)", "Mali", "GPU"};
    private static int lastFound = -1;

    private static long firstNumber(String[] paths) {
        for (int i = 0; i < paths.length; i++) {
            String p = paths[i];
            String l = readLine(p);
            if (l == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(l);
            if (m.find()) { lastFound = i; return Long.parseLong(m.group()); }
        }
        lastFound = -1;
        return -1;
    }

    private static long toMhz(long v) {
        return v > 10_000_000 ? v / 1_000_000 : v > 10_000 ? v / 1000 : v;  // Hz, kHz or MHz
    }

    private void updateGpu() {
        long busy = firstNumber(GPU_BUSY);
        int which = lastFound;
        gpuLoad.setText(busy >= 0 ? "ГП " + busy + "%" : "ГП –");
        if (gpuPanel.getVisibility() != View.VISIBLE) return;
        long cur = firstNumber(GPU_FREQ), max = firstNumber(GPU_MAX);
        String name = which >= 0 ? GPU_NAMES[which] : "GPU";
        gpuPanel.setText(String.format(java.util.Locale.ROOT, "%s\nчастота %s/%s МГц\nзагрузка %s",
                name, cur > 0 ? toMhz(cur) : "–", max > 0 ? toMhz(max) : "–",
                busy >= 0 ? busy + "%" : "–"));
    }

    private final Runnable hudTick = new Runnable() {
        @Override public void run() {
            updateHud();
            hudHandler.postDelayed(this, 500);
        }
    };

    // ~30 updates a second while the graph is shown (smooth scrolling, cheap).
    private final Runnable graphTick = new Runnable() {
        @Override public void run() {
            try {
                nativeFrames(graphFrames);
            } catch (UnsatisfiedLinkError e) {
                return;
            }
            graph.setFrames(graphFrames, 0);
            hudHandler.postDelayed(this, 33);
        }
    };

    private void updateHud() {
        try {
            nativeStats(stats);
        } catch (UnsatisfiedLinkError e) {
            return;
        }
        String watts = batteryWatts();
        hudText.setText(String.format(java.util.Locale.ROOT, "FPS %2.0f · UPS %2.0f · %s", stats[0], stats[1], watts));
        updateCpu();
        updateGpu();

    }

    /** Whole phone, from the battery: current x voltage. */
    private String batteryWatts() {
        android.content.Intent bat = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bat == null || bm == null) return "– W";
        // Kernel values first (pack voltage: dual-cell phones may report one
        // cell's voltage through BatteryManager), else BatteryManager.
        long uv = readLong("/sys/class/power_supply/battery/voltage_now");
        long ua = readLong("/sys/class/power_supply/battery/current_now");
        if (uv <= 0 || ua == Long.MIN_VALUE) {
            uv = bat.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) * 1000L;
            if (uv > 0 && uv < 1000000) uv *= 1000;  // a few devices report volts
            ua = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }
        if (ua == Long.MIN_VALUE || ua == 0 || uv <= 0) return "– W";
        double volts = uv / 1e6, amps = Math.abs(ua) / 1e6;
        if (amps * volts < 0.05) amps *= 1000;  // device reports milliamps
        boolean charging = bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return String.format(java.util.Locale.ROOT, charging ? "зарядка" : "%.1f W", amps * volts);
    }

    /** Frame times as bars; lines at 16.7 ms (60 FPS) and 33.3 ms (30 FPS). */
    private static class FrameGraph extends View {
        private final android.graphics.Paint bar = new android.graphics.Paint(), line = new android.graphics.Paint(),
                text = new android.graphics.Paint();
        private float[] frames = new float[0];

        FrameGraph(android.content.Context c) {
            super(c);
            setBackgroundColor(0x55000000);
            line.setColor(0x88FFFFFF);
            line.setStrokeWidth(2);
            text.setColor(0xCCFFFFFF);
            text.setTextSize(22);
            text.setAntiAlias(true);
        }

        void setFrames(float[] src, int from) {
            if (frames.length != src.length - from) frames = new float[src.length - from];
            System.arraycopy(src, from, frames, 0, frames.length);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), max = 50f;  // ms at the top
            int n = frames.length;
            if (n == 0) return;
            float bw = w / n;
            for (int i = 0; i < n; i++) {
                float ms = frames[i];
                if (ms <= 0) continue;
                bar.setColor(ms <= 17.5f ? 0xAA44DD44 : ms <= 34f ? 0xAAFFCC00 : 0xAAFF4444);
                float top = h - Math.min(ms, max) / max * h;
                c.drawRect(i * bw, top, (i + 1) * bw - 1, h, bar);
            }
            for (float ms : new float[] {16.7f, 33.3f}) {
                float y = h - ms / max * h;
                c.drawLine(0, y, w, y, line);
                c.drawText(ms < 20 ? "16.7" : "33.3", 4, y - 4, text);
            }
            float last = frames[n - 1];
            c.drawText(String.format(java.util.Locale.ROOT, "%.1f ms", last), w - 110, 24, text);
        }
    }

    // ---- benchmark: wait for the map, 10 s warm-up, then N seconds of
    // one-second samples; writes a report and closes the game.

    private static final int WARMUP = 10;
    private final long[] counters = new long[2], prevCounters = new long[2];
    private final java.util.ArrayList<float[]> samples = new java.util.ArrayList<>();
    private int benchSeconds, benchPhase, benchClock;  // phase 0 wait for ticks, 1 warm-up, 2 measuring
    private TextView benchText;

    private void startBench() {
        benchSeconds = getIntent().getIntExtra(EXTRA_BENCH_SECONDS, 60);
        benchText = new TextView(this);
        benchText.setTextColor(Color.WHITE);
        benchText.setTextSize(14);
        benchText.setBackgroundColor(0x99000000);
        benchText.setPadding(dp(10), dp(4), dp(10), dp(4));
        benchText.setText("Замер: ждём загрузку карты…");
        FrameLayout overlay = new FrameLayout(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.setMargins(0, 0, 0, dp(60));
        overlay.addView(benchText, p);
        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        hudHandler.postDelayed(benchTick, 1000);
    }

    private final Runnable benchTick = new Runnable() {
        @Override public void run() {
            try {
                nativeCounters(counters);
            } catch (UnsatisfiedLinkError e) {
                return;
            }
            long ticks = counters[0] - prevCounters[0], frames = counters[1] - prevCounters[1];
            boolean first = prevCounters[1] == 0;
            prevCounters[0] = counters[0];
            prevCounters[1] = counters[1];
            if (!first) {
                if (benchPhase == 0 && ticks > 10) { benchPhase = 1; benchClock = 0; }
                if (benchPhase == 1 && ++benchClock >= WARMUP) { benchPhase = 2; benchClock = 0; }
                else if (benchPhase == 2) {
                    long cur = firstNumber(GPU_FREQ);
                    samples.add(new float[] {frames, ticks, wattsNow(), cpuNow(), firstNumber(GPU_BUSY),
                            cur > 0 ? toMhz(cur) : -1, maxCoreMhz()});
                    if (++benchClock >= benchSeconds) { finishBench(); return; }
                }
                benchText.setText(benchPhase == 0 ? "Замер: ждём загрузку карты…"
                        : benchPhase == 1 ? "Замер: прогрев " + (WARMUP - benchClock) + " с"
                        : "Замер: " + benchClock + " / " + benchSeconds + " с");
            }
            hudHandler.postDelayed(this, 1000);
        }
    };

    private float wattsNow() {
        String w = batteryWatts();
        try {
            return Float.parseFloat(w.replace(" W", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private float cpuNow() {
        int[] loads = coreLoads();
        if (loads != null) {
            int sum = 0;
            for (int v : loads) sum += v;
            return sum / (float) cores;
        }
        return processLoad();
    }

    private float maxCoreMhz() {
        long best = -1;
        for (int c = 0; c < cores; c++) {
            String v = readLine("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq");
            if (v != null) best = Math.max(best, Long.parseLong(v) / 1000);
        }
        return best;
    }

    private static float avg(java.util.List<float[]> l, int i) {
        float s = 0;
        int n = 0;
        for (float[] v : l) if (v[i] >= 0) { s += v[i]; n++; }
        return n > 0 ? s / n : -1;
    }

    private void finishBench() {
        int n = samples.size();
        float[] fps = new float[n];
        for (int i = 0; i < n; i++) fps[i] = samples.get(i)[0];
        float[] sorted = fps.clone();
        java.util.Arrays.sort(sorted);
        float minUps = Float.MAX_VALUE;
        for (float[] v : samples) minUps = Math.min(minUps, v[1]);
        StringBuilder r = new StringBuilder();
        java.util.Locale L = java.util.Locale.ROOT;
        r.append("Factorio Native — замер\n");
        r.append(getIntent().getStringExtra(EXTRA_BENCH_INFO)).append('\n');
        r.append(String.format(L, "Устройство: %s %s, Android %s", android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE));
        if (android.os.Build.VERSION.SDK_INT >= 31)
            r.append(String.format(L, ", SoC %s %s", android.os.Build.SOC_MANUFACTURER, android.os.Build.SOC_MODEL));
        r.append(String.format(L, "\nЗамер: %d с после %d с прогрева\n\n", n, WARMUP));
        r.append(String.format(L, "FPS   среднее %.1f, медиана %.0f, минимум %.0f, максимум %.0f\n",
                avg(samples, 0), sorted[n / 2], sorted[0], sorted[n - 1]));
        r.append(String.format(L, "UPS   среднее %.1f, минимум %.0f\n", avg(samples, 1), minUps));
        r.append(String.format(L, "Ватты среднее %.1f W (весь телефон, по батарее; на зарядке не считается)\n", avg(samples, 2)));
        r.append(String.format(L, "ЦП    среднее %.0f%%   макс. частота ядра среднее %.0f МГц\n", avg(samples, 3), avg(samples, 6)));
        r.append(String.format(L, "ГП    среднее %.0f%%   частота среднее %.0f МГц\n\n", avg(samples, 4), avg(samples, 5)));
        r.append("сек,fps,ups,ватты,цп_%,гп_%,гп_мгц,цп_макс_мгц\n");
        for (int i = 0; i < n; i++) {
            float[] v = samples.get(i);
            r.append(String.format(L, "%d,%.0f,%.0f,%.2f,%.0f,%.0f,%.0f,%.0f\n", i + 1, v[0], v[1], v[2], v[3], v[4], v[5], v[6]));
        }
        String out = getIntent().getStringExtra(EXTRA_BENCH_OUT);
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(out)) {
            f.write(r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            benchText.setText("Замер готов: " + new java.io.File(out).getName());
        } catch (java.io.IOException e) {
            benchText.setText("Замер: не удалось записать " + out + ": " + e);
            return;
        }
        // The game process ends with the benchmark (nothing to save).
        hudHandler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 3000);
    }

    /**
     * Android back (button or gesture) = Escape in the game; the game is not
     * closed. Caught here, before SDL's view handler turns it into its own
     * "AC Back" key (unknown to the game) and consumes it.
     */
    // ---- the screen keyboard and back: one path (SDLDummyEdit.onKeyPreIme → backPreIme)
    private static GameActivity self;
    private static boolean backTaken;  // the back press that came while the keyboard was up

    /** Back while the keyboard is up: taken (down and up), and on release the keyboard is put away once. */
    public static boolean backPreIme(KeyEvent e) {
        GameActivity a = self;
        if (a == null) return false;
        boolean shown = a.keyboardShown();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            backTaken = shown;
            return shown;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && backTaken) {
            backTaken = false;
            a.hideKeyboard();
            return true;
        }
        return false;
    }

    // ---- the keyboard over the game (landscape): the game's picture is lifted so the place of typing — the cursor
    // (cursor mode) or the last touch of the game (touch mode) — stays above the keyboard, and follows the cursor.
    // The keys stay; the overlay's points are moved by the lift before they go to the game (surfaceY).

    private float lastGameTouchY = -1;  // overlay y of the last touch that went to the game (not a key)
    private int keyboardLift;           // pixels the picture is lifted now

    /** An overlay point's y on the (lifted) game picture. */
    private float surfaceY(float overlayY) {
        return mSurface != null ? overlayY - mSurface.getTranslationY() : overlayY;
    }

    private int keyboardHeight() {
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30 && decor.getRootWindowInsets() != null
                && decor.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()))
            return decor.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime()).bottom;
        return 0;
    }

    private void setLift(int lift, boolean animate) {
        keyboardLift = lift;
        mSurface.animate().cancel();
        if (animate) mSurface.animate().translationY(-lift).setDuration(160).start();
        else mSurface.setTranslationY(-lift);
    }

    /**
     * While the keyboard is up the cursor pushes the camera at the edges of the part above the keyboard: down at the
     * bottom edge lifts the picture (at most until the game's bottom shows), up at the top edge lowers it again.
     * The cursor never goes under the keyboard.
     */
    private void cameraFollowCursor() {
        if (mSurface == null || curY < 0) return;
        int kb = keyboardHeight();
        if (kb <= 0) return;
        float visibleBottom = getWindow().getDecorView().getHeight() - kb - dp(16), top = dp(24);
        int maxLift = kb + dp(16);
        if (curY > visibleBottom) {
            int lift = (int) Math.min(maxLift, keyboardLift + (curY - visibleBottom));
            curY -= lift - keyboardLift;
            setLift(lift, false);
            curY = Math.min(curY, visibleBottom);
        } else if (curY < top && keyboardLift > 0) {
            int lift = (int) Math.max(0, keyboardLift - (top - curY));
            curY += keyboardLift - lift;
            setLift(lift, false);
        }
    }

    /** Lifts (or lowers) the picture when the keyboard comes or goes (layout changes). */
    private void liftForKeyboard() {
        if (mSurface == null) return;
        int kb = keyboardHeight(), lift = 0;
        if (kb > 0) {
            float focus = cursorMode && curY >= 0 ? curY + keyboardLift : lastGameTouchY;  // a point of the picture
            if (focus >= 0) {
                float visibleBottom = getWindow().getDecorView().getHeight() - kb - dp(16);
                lift = (int) Math.max(0, focus + dp(36) - visibleBottom);
            }
        }
        // only when the keyboard comes (lift from 0) or goes (back to 0); in between the cursor moves the camera
        if (lift == keyboardLift || (kb > 0 && keyboardLift > 0)) return;
        // the cursor goes up with the picture: it stays on the same point of the game
        if (cursorMode && curY >= 0) curY = Math.max(0, curY - (lift - keyboardLift));
        setLift(lift, true);
        // the cursor sits on the picture: it moves with it
        if (cursorMode && cursorPad != null) cursorPad.invalidate();
    }

    private void hideSystemBarsNow() {
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** Whether the screen keyboard is on screen (the window's IME insets; SDL's text field focused as a fallback). */
    private boolean keyboardShown() {
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30 && decor.getRootWindowInsets() != null)
            return decor.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
        return mTextEdit != null && mTextEdit.hasFocus() && mTextEdit.getWidth() > 0;
    }

    /**
     * Puts the keyboard away but leaves the text input on: told "focus lost", SDL stopped the input, the game's
     * field still had focus and the game started it again 33 ms later — the keyboard came back (keyboard.log,
     * v0.87). SDL is told only that the keyboard is hidden.
     */
    void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View token = mTextEdit != null ? mTextEdit : getWindow().getDecorView();
        if (imm != null) imm.hideSoftInputFromWindow(token.getWindowToken(), 0);
        SDLActivity.onNativeScreenKeyboardHidden();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (tutorial != null && tutorial.getVisibility() == View.VISIBLE) tutorial.observe(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && ev.getX() > dp(80) && keyboardLift == 0)
            lastGameTouchY = ev.getY();  // a touch of the game (right of the key column): the field typed into
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && tutorial != null && tutorial.getVisibility() == View.VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_UP) tutorial.finish();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && menuOpen()) {
            if (event.getAction() == KeyEvent.ACTION_UP) showMenu(false);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && (backTaken || keyboardShown())) {
            // the keyboard is up but SDL's text field did not get back first: the same single path
            if (backPreIme(event)) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyboardShown()) {
                backTaken = true;
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            else if (event.getAction() == KeyEvent.ACTION_UP)
                SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
    }

    @Override
    protected void onDestroy() {
        hudHandler.removeCallbacks(hudTick);
        hudHandler.removeCallbacks(graphTick);
        hudHandler.removeCallbacks(benchTick);
        hudHandler.removeCallbacks(gameStateTick);
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

    // ---- key styles: many looks, picked in a scrolling list at the right edge.

    private static final int FX_NONE = 0, FX_RIVETS = 1, FX_STRIPES = 2, FX_GRID = 3, FX_GLOW = 4, FX_SLATS = 5, FX_SCAN = 6,
            FX_DOTS = 7, FX_HATCH = 8, FX_TILES = 9, FX_LEFTBAR = 10,
            FX_CORNERS = 11, FX_WAVES = 12, FX_CIRCUIT = 13, FX_SHINE = 14, FX_NOISE = 15, FX_DOUBLE = 16,
            FX_TOPBAR = 17, FX_CHEVRON = 18;

    /** Colours of one look (argb ints, sizes in dp). Set with the chained methods. */
    private static final class KeyStyle {
        final String name;
        final int top, bottom, stroke, text;        // normal key: fill gradient, edge, label
        final int pTop, pBottom, pStroke, pText;    // pressed key
        final int radius, strokeDp;
        int highlight, lowlight;                    // 1 dp lines inside the top / bottom edge (0 = none)
        boolean bold, shadow, mono, icons;
        int iconSet;                                // icon look, see KeyIcon
        int iconColor, iconDp = 20;                 // icon colour (0 = label colour), icon size
        boolean caps, noLabel, circle;              // CAPS labels; icon without label; round key
        int effect = FX_NONE, effectColor;          // rivets / hazard stripes / grid / glow on the key
        int ring, knob, wheelBg, wheelFg, accent;   // stick, wheel popup, checkbox tint
        int panelTop, panelBottom, panelText;       // background of the picker and touch/cursor switch

        KeyStyle(String name, int top, int bottom, int stroke, int text,
                 int pTop, int pBottom, int pStroke, int pText, int radius, int strokeDp) {
            this.name = name; this.top = top; this.bottom = bottom; this.stroke = stroke; this.text = text;
            this.pTop = pTop; this.pBottom = pBottom; this.pStroke = pStroke; this.pText = pText;
            this.radius = radius; this.strokeDp = strokeDp;
            ring = (stroke & 0xFFFFFF) | 0xAA000000;
            knob = (pTop & 0xFFFFFF) | 0x88000000;
            wheelBg = bottom;
            wheelFg = text;
            accent = pTop | 0xFF000000;
            panelTop = top; panelBottom = bottom; panelText = text;
        }

        KeyStyle bevel(int hi, int lo) { highlight = hi; lowlight = lo; return this; }
        KeyStyle bold() { bold = true; return this; }
        KeyStyle shadow() { shadow = true; return this; }
        KeyStyle mono() { mono = true; return this; }
        KeyStyle icons() { return icons(0); }
        KeyStyle icons(int set) { icons = true; iconSet = set; return this; }
        KeyStyle iconColor(int c) { iconColor = c; return this; }
        KeyStyle iconDp(int d) { iconDp = d; return this; }
        KeyStyle caps() { caps = true; return this; }
        KeyStyle noLabel() { noLabel = true; return this; }
        KeyStyle circle() { circle = true; return this; }
        KeyStyle fx(int e, int c) { effect = e; effectColor = c; return this; }
        KeyStyle stick(int r, int k) { ring = r; knob = k; return this; }
        KeyStyle accent(int a) { accent = a; return this; }
        KeyStyle panel(int t, int b, int txt) { panelTop = t; panelBottom = b; panelText = txt; wheelBg = b; wheelFg = txt; return this; }
        /** The dark grey game frame with cream text. */
        KeyStyle gameFrame() { return panel(0xF0414041, 0xF0313031, 0xFFFFE6C0); }
    }

    private static final KeyStyle[] STYLES = {
        // As the original game GUI: light grey bevelled buttons with black bold text,
        // orange when pressed; panels are the dark grey game frames with cream titles.
        new KeyStyle("Factorio", 0xF29A9A9A, 0xF27C7C7C, 0xFF1E1E1E, 0xFF000000,
                0xFFFFC85A, 0xFFF29A1F, 0xFF1E1E1E, 0xFF000000, 2, 1)
                .bevel(0xB0E0E0E0, 0x70000000).bold().gameFrame().stick(0xAAE0E0E0, 0xCCF29A1F).accent(0xFFF29A1F),
        // Factorio-like dark: grey panels, orange when pressed
        new KeyStyle("Завод", 0xDD5C5C5C, 0xDD363636, 0xFF141414, 0xFFFFFFFF,
                0xFFFFB347, 0xFFE0880F, 0xFF5A3300, 0xFF1A1000, 3, 1)
                .bevel(0x50FFFFFF, 0).bold().shadow().stick(0x99D8D8D8, 0xCCE8962A).accent(0xFFFFA52E),
        // Frosted glass: light, rounded, thin white edge
        new KeyStyle("Стекло", 0x40FFFFFF, 0x26FFFFFF, 0x99FFFFFF, 0xFFFFFFFF,
                0xCCFFFFFF, 0xB3FFFFFF, 0xFFFFFFFF, 0xFF111111, 14, 1)
                .shadow().stick(0xAAFFFFFF, 0x66FFFFFF).accent(0xFFFFFFFF),
        // Neon: dark pills with a cyan outline, filled cyan when pressed
        new KeyStyle("Неон", 0xC00A1218, 0xC0060A0E, 0xFF19E3FF, 0xFF8AF4FF,
                0xF019E3FF, 0xF012B8D0, 0xFFC8FAFF, 0xFF00141A, 21, 2).bold(),
        // Shadow: flat dark, no edge
        new KeyStyle("Тень", 0x88000000, 0x88000000, 0x00000000, 0xFFFFFFFF,
                0xE6FFFFFF, 0xE6FFFFFF, 0x00000000, 0xFF000000, 8, 0)
                .stick(0x88FFFFFF, 0x66FFFFFF).accent(0xFFFFFFFF),
        // Outline: almost transparent, thick white edge
        new KeyStyle("Контур", 0x14000000, 0x14000000, 0xCCFFFFFF, 0xFFFFFFFF,
                0xDDFFFFFF, 0xDDFFFFFF, 0xFFFFFFFF, 0xFF000000, 6, 2)
                .bold().shadow().panel(0x66000000, 0x66000000, 0xFFFFFFFF),
        // Khaki: olive military panels, sand when pressed
        new KeyStyle("Хаки", 0xD8525A26, 0xD8363C17, 0xFF15170A, 0xFFEDE6B8,
                0xFFDCC872, 0xFFB9A24A, 0xFF3A3210, 0xFF1A1806, 4, 1)
                .bevel(0x40FFFFE0, 0x50000000).bold().shadow(),
        // Space: navy with a blue edge
        new KeyStyle("Космос", 0xD81E2E52, 0xD80F1932, 0xFF4F82D6, 0xFFD3E3FF,
                0xFF5AA0F0, 0xFF3478D0, 0xFFCFE4FF, 0xFF06142A, 10, 1).shadow(),
        // Signal: dark red, bright red when pressed
        new KeyStyle("Сигнал", 0xD8481414, 0xD8280808, 0xFFE04848, 0xFFFFD6D6,
                0xFFFF5A4A, 0xFFD02E22, 0xFFFFD0C8, 0xFF200000, 6, 1).bold(),
        // Terminal: black with green monospace text
        new KeyStyle("Терминал", 0xD8000A02, 0xD8000A02, 0xFF33FF66, 0xFF33FF66,
                0xFF33FF66, 0xFF22CC4C, 0xFF33FF66, 0xFF001A06, 0, 1).mono().bold(),
        // Copper: copper plates
        new KeyStyle("Медь", 0xE6C27C42, 0xE6814A22, 0xFF3A1E0A, 0xFFFFFFFF,
                0xFFFFCB8A, 0xFFE89A50, 0xFF3A1E0A, 0xFF2A1404, 4, 1)
                .bevel(0x70FFE0C0, 0x60000000).bold().shadow(),
        // Steel: light steel plates with dark text, yellow when pressed
        new KeyStyle("Сталь", 0xE6C4CBD2, 0xE67E868F, 0xFF22262A, 0xFF101418,
                0xFFFFE27A, 0xFFF0C030, 0xFF22262A, 0xFF101418, 4, 1)
                .bevel(0xA0FFFFFF, 0x60000000).bold().accent(0xFFF0C030),
        // Belt: yellow transport belt, dark when pressed
        new KeyStyle("Конвейер", 0xE6F5C842, 0xE6CC9C1C, 0xFF261C00, 0xFF1A1200,
                0xFF3A3A3A, 0xFF242424, 0xFFF5C842, 0xFFF5C842, 3, 1)
                .bevel(0x80FFF0B0, 0x50000000).bold()
                .panel(0xE0303030, 0xE0242424, 0xFFF5C842).accent(0xFFF5C842),

        // ---- Factorio set: icons on the keys, game frames, borders and effects
        // Inventory slot: recessed dark square, cream icons
        new KeyStyle("Ф: Слот", 0xF0232323, 0xF0353535, 0xFF101010, 0xFFE8DCC0,
                0xFFFFC85A, 0xFFF29A1F, 0xFF1E1E1E, 0xFF000000, 0, 1)
                .bevel(0xA0000000, 0x40FFFFFF).bold().icons().gameFrame().accent(0xFFF29A1F),
        // Window: dark frame with rivets, cream text, orange when pressed
        new KeyStyle("Ф: Окно", 0xF0464546, 0xF0313031, 0xFF0E0E0E, 0xFFFFE6C0,
                0xFFFFC85A, 0xFFF29A1F, 0xFF1E1E1E, 0xFF000000, 2, 1)
                .bevel(0x50FFFFFF, 0x90000000).bold().icons().fx(FX_RIVETS, 0xFF9A9A9A).gameFrame().accent(0xFFF29A1F),
        // Green confirm button of the game
        new KeyStyle("Ф: Зелёная", 0xF270C062, 0xF2469E3A, 0xFF1E1E1E, 0xFF000000,
                0xFFA8EC84, 0xFF74C850, 0xFF1E1E1E, 0xFF000000, 2, 1)
                .bevel(0xB0D8F8C8, 0x70000000).bold().icons().gameFrame().accent(0xFF74C850),
        // Red cancel button of the game
        new KeyStyle("Ф: Красная", 0xF2E2523F, 0xF2B0301F, 0xFF1E1E1E, 0xFF000000,
                0xFFFF8A6A, 0xFFE2523F, 0xFF1E1E1E, 0xFF000000, 2, 1)
                .bevel(0xB0FFC8B8, 0x70000000).bold().icons().gameFrame().accent(0xFFFF6A50),
        // Hazard concrete: yellow and black stripes around a dark key
        new KeyStyle("Ф: Опасность", 0xF0303030, 0xF0262626, 0xFF101010, 0xFFF5C842,
                0xFFF5C842, 0xFFD8A820, 0xFF101010, 0xFF101010, 2, 1)
                .bold().icons().fx(FX_STRIPES, 0xFFF5C842).gameFrame().accent(0xFFF5C842),
        // Blueprint: blue paper with a grid, white lines
        new KeyStyle("Ф: Чертёж", 0xE02A5486, 0xE01C3C66, 0xFFB8D4F4, 0xFFFFFFFF,
                0xFFE8F2FF, 0xFFC4DCF8, 0xFFFFFFFF, 0xFF1C3C66, 2, 1)
                .bold().icons().fx(FX_GRID, 0x30FFFFFF).panel(0xE02A5486, 0xE01C3C66, 0xFFFFFFFF).accent(0xFFB8D4F4),
        // Vulcanus: dark rock with lava glow
        new KeyStyle("Вулканус", 0xF02C1610, 0xF0180A06, 0xFFE0602A, 0xFFFFB070,
                0xFFFF8A3A, 0xFFD24E12, 0xFFFFD0A0, 0xFF1A0600, 3, 1)
                .bold().shadow().icons().fx(FX_GLOW, 0xC0FF5A10).gameFrame().accent(0xFFFF7A2A),
        // Fulgora: purple night with lightning blue
        new KeyStyle("Фулгора", 0xF02A1A3C, 0xF0160C26, 0xFFB078FF, 0xFFE4D0FF,
                0xFFC898FF, 0xFF8E52E8, 0xFFF0E4FF, 0xFF10041E, 3, 1)
                .bold().shadow().icons().fx(FX_GLOW, 0x907AA8FF).gameFrame().accent(0xFFB078FF),
        // Gleba: organic green with pink edges, round
        new KeyStyle("Глеба", 0xF02A3C1E, 0xF0182612, 0xFFD87A9C, 0xFFCCEAA4,
                0xFFF0A4C0, 0xFFD87A9C, 0xFFFFE0EA, 0xFF1E0A12, 12, 1)
                .bold().shadow().icons().fx(FX_GLOW, 0x8070C040).gameFrame().accent(0xFFD87A9C),
        // Aquilo: ice blue with frost on top, dark text
        new KeyStyle("Аквило", 0xF0D8E8F4, 0xF094B0CC, 0xFF2A4058, 0xFF102030,
                0xFF5AB4EC, 0xFF2E86C4, 0xFFE8F6FF, 0xFFFFFFFF, 3, 1)
                .bevel(0xE0FFFFFF, 0x60000000).bold().icons().fx(FX_RIVETS, 0xFF5A7894)
                .panel(0xF03A5470, 0xF0243A52, 0xFFE8F6FF).accent(0xFF5AB4EC),
        // Night: dark key, lamp light from above, cream icons
        new KeyStyle("Ф: Ночь", 0xF0141414, 0xF00A0A0A, 0xFF3A3A3A, 0xFFFFE6A0,
                0xFFFFE07A, 0xFFE8B430, 0xFF3A3A3A, 0xFF000000, 4, 1)
                .bold().icons().fx(FX_GLOW, 0x60FFD27A).gameFrame().stick(0x88FFE6A0, 0x88FFD060).accent(0xFFFFD060),

        // ---- materials: icon set 1 (thick blocky lines)
        new KeyStyle("Железо", 0xF08C9AA8, 0xF0586674, 0xFF1C2228, 0xFF0E141A,
                0xFFC8D6E4, 0xFF9AAABA, 0xFF1C2228, 0xFF000000, 2, 1)
                .bevel(0xB0E0ECF8, 0x70000000).bold().icons(1).gameFrame().accent(0xFF9AAABA),
        new KeyStyle("Уголь", 0xF0262626, 0xF0101010, 0xFF000000, 0xFFC4C4C4,
                0xFF6A6A6A, 0xFF484848, 0xFF000000, 0xFFFFFFFF, 4, 1)
                .bevel(0x40FFFFFF, 0).bold().shadow().icons(1).fx(FX_GLOW, 0x30FFFFFF).gameFrame().accent(0xFFC4C4C4),
        new KeyStyle("Камень", 0xF0B8A882, 0xF0857654, 0xFF2A2418, 0xFF1E180C,
                0xFFE2D4AE, 0xFFC0B088, 0xFF2A2418, 0xFF000000, 3, 1)
                .bevel(0x90F0E8D0, 0x60000000).bold().icons(1).gameFrame().accent(0xFFE2D4AE),
        new KeyStyle("Уран", 0xF0142614, 0xF00A160A, 0xFF5CFF4A, 0xFF9CFF8A,
                0xFF7CFF5A, 0xFF40D02A, 0xFFD0FFC8, 0xFF052005, 6, 1)
                .bold().icons(1).fx(FX_GLOW, 0x9050FF30).gameFrame(),
        new KeyStyle("Пластик", 0xF0F4F4F4, 0xF0CFCFCF, 0xFF5A5A5A, 0xFF202020,
                0xFFFFC85A, 0xFFF29A1F, 0xFF5A5A5A, 0xFF000000, 10, 1)
                .bevel(0xFFFFFFFF, 0x40000000).bold().icons(1).panel(0xF0E8E8E8, 0xF0C4C4C4, 0xFF202020).accent(0xFFF29A1F),

        // ---- science packs: icon set 2 (icons in a round badge)
        new KeyStyle("Кр. наука", 0xF0C83228, 0xF08A1A12, 0xFF2A0806, 0xFFFFE0D8,
                0xFFFF6A5A, 0xFFD83A2C, 0xFF2A0806, 0xFF000000, 4, 1)
                .bevel(0x70FFB0A0, 0x70000000).bold().shadow().icons(2).gameFrame(),
        new KeyStyle("Зел. наука", 0xF04AB03A, 0xF02A7020, 0xFF0A2006, 0xFFE8FFE0,
                0xFF8AE870, 0xFF5AC040, 0xFF0A2006, 0xFF000000, 4, 1)
                .bevel(0x70C8FFB0, 0x70000000).bold().shadow().icons(2).gameFrame(),
        new KeyStyle("Син. наука", 0xF02A6ED8, 0xF01A4490, 0xFF061430, 0xFFE0ECFF,
                0xFF6AA8FF, 0xFF3A7AE0, 0xFF061430, 0xFF000000, 4, 1)
                .bevel(0x70B0D0FF, 0x70000000).bold().shadow().icons(2).gameFrame(),
        new KeyStyle("Фиол. наука", 0xF09A3AC8, 0xF0622080, 0xFF1E0628, 0xFFF4E0FF,
                0xFFC87AF0, 0xFF9A4AD0, 0xFF1E0628, 0xFF000000, 4, 1)
                .bevel(0x70E8C0FF, 0x70000000).bold().shadow().icons(2).gameFrame(),
        new KeyStyle("Жёлт. наука", 0xF0E8C030, 0xF0A88418, 0xFF2A2006, 0xFF201800,
                0xFFFFE680, 0xFFF0C840, 0xFF2A2006, 0xFF000000, 4, 1)
                .bevel(0x90FFF4C0, 0x60000000).bold().icons(2).gameFrame().accent(0xFFF0C840),

        // ---- buildings: icon set 3 (dark icons on a filled signal square)
        new KeyStyle("Сборщик", 0xF0606A72, 0xF03C444C, 0xFF14181C, 0xFFF0D060,
                0xFFF0D060, 0xFFD0A830, 0xFF14181C, 0xFF000000, 3, 1)
                .bevel(0x60FFFFFF, 0x80000000).bold().icons(3).fx(FX_RIVETS, 0xFF8A949C).gameFrame().accent(0xFFF0D060),
        new KeyStyle("Печь", 0xF05A4A3A, 0xF0302418, 0xFF140E08, 0xFFFFC890,
                0xFFFFA040, 0xFFE07020, 0xFF140E08, 0xFF000000, 4, 1)
                .bold().shadow().icons(3).fx(FX_GLOW, 0xB0FF7A20).gameFrame(),
        new KeyStyle("Ж/д", 0xF04A443A, 0xF02C2822, 0xFF100E0A, 0xFFE0D8C8,
                0xFFC8C0B0, 0xFF9A9284, 0xFF100E0A, 0xFF000000, 1, 1)
                .bevel(0x30FFFFFF, 0x80000000).bold().icons(3).fx(FX_SLATS, 0x50000000).gameFrame().accent(0xFFC8C0B0),
        new KeyStyle("Солн. панель", 0xF0202C48, 0xF0141C30, 0xFF8A94A8, 0xFFC8D8FF,
                0xFF6A8AD8, 0xFF4A68B8, 0xFFC8D8FF, 0xFFFFFFFF, 1, 1)
                .bold().icons(3).fx(FX_GRID, 0x50A0B8F0).gameFrame().accent(0xFF6A8AD8),
        new KeyStyle("Реактор", 0xF0384238, 0xF0222A22, 0xFF0E120E, 0xFFB8FF9A,
                0xFF9AFF6A, 0xFF60D040, 0xFF0E120E, 0xFF000000, 6, 1)
                .bevel(0x40FFFFFF, 0x80000000).bold().icons(3).fx(FX_RIVETS, 0xFF6A7A6A).gameFrame().accent(0xFF9AFF6A),

        // ---- moods: icon set 4 (thin glowing lines)
        new KeyStyle("Кусаки", 0xF03A2018, 0xF0201008, 0xFFC0603A, 0xFFFFB090,
                0xFFE07A4A, 0xFFB04A20, 0xFFFFD0B0, 0xFF000000, 12, 1)
                .bold().shadow().icons(4).fx(FX_GLOW, 0x80B03A1A).gameFrame(),
        new KeyStyle("Пустыня", 0xF0D8B880, 0xF0B08A50, 0xFF4A3418, 0xFF3A2410,
                0xFFFFE0A0, 0xFFE8C070, 0xFF4A3418, 0xFF2A1808, 6, 1)
                .bevel(0x90FFF4D8, 0x50000000).bold().icons(4).panel(0xF0806040, 0xF0604428, 0xFFFFE8C0).accent(0xFFE8C070),
        new KeyStyle("Сумерки", 0xF0503A70, 0xF0B0603A, 0xFF201028, 0xFFFFE8D0,
                0xFFFFB070, 0xFFE07A40, 0xFF201028, 0xFF000000, 8, 1)
                .bold().shadow().icons(4).gameFrame().accent(0xFFFFB070),
        new KeyStyle("Монитор", 0xF0140C00, 0xF0140C00, 0xFFFFB000, 0xFFFFB000,
                0xFFFFB000, 0xFFD08C00, 0xFFFFD880, 0xFF1A0E00, 2, 1)
                .mono().bold().icons(4).fx(FX_SCAN, 0x38FFB000).panel(0xF0140C00, 0xF0140C00, 0xFFFFB000).accent(0xFFFFB000),
        new KeyStyle("Платформа", 0xF0D0D4DA, 0xF0A4AAB2, 0xFF30363E, 0xFF1A2028,
                0xFF4AA8FF, 0xFF2A80E0, 0xFF30363E, 0xFFFFFFFF, 3, 1)
                .bevel(0xF0FFFFFF, 0x60000000).bold().icons(4).fx(FX_RIVETS, 0xFF7A828C)
                .panel(0xF0464C56, 0xF02E343C, 0xFFE8F0FF).accent(0xFF4AA8FF),
        // ---- more science packs: icon set 6 (diamond badge)
        new KeyStyle("Воен. наука", 0xF04A4A4A, 0xF02A2A2A, 0xFF0E0E0E, 0xFFE8E8E8,
                0xFFA0A0A0, 0xFF787878, 0xFF0E0E0E, 0xFF000000, 4, 1)
                .bevel(0x60FFFFFF, 0x70000000).bold().shadow().icons(6).gameFrame().accent(0xFFA0A0A0),
        new KeyStyle("Косм. наука", 0xF0F0F0F0, 0xF0C0C8D0, 0xFF303840, 0xFF101010,
                0xFF9AD0FF, 0xFF6AAAE8, 0xFF303840, 0xFF000000, 4, 1)
                .bevel(0xFFFFFFFF, 0x50000000).bold().icons(6).gameFrame().accent(0xFF6AAAE8),
        new KeyStyle("Металлург.", 0xF0E07A28, 0xF09A4A10, 0xFF2A1004, 0xFFFFFFFF,
                0xFFFFAA60, 0xFFE07A28, 0xFF2A1004, 0xFF000000, 4, 1)
                .bevel(0x70FFD0A0, 0x70000000).bold().shadow().icons(6).gameFrame(),
        new KeyStyle("Электромаг.", 0xF0D03A9A, 0xF0901A68, 0xFF2A0618, 0xFFFFFFFF,
                0xFFFF7ACA, 0xFFD03A9A, 0xFF2A0618, 0xFF000000, 4, 1)
                .bevel(0x70FFB0E0, 0x70000000).bold().shadow().icons(6).gameFrame(),
        new KeyStyle("Агро наука", 0xF0A8C83A, 0xF0708A20, 0xFF1A2006, 0xFF1A2006,
                0xFFD8F07A, 0xFFA8C83A, 0xFF1A2006, 0xFF000000, 4, 1)
                .bevel(0x80F0FFC0, 0x60000000).bold().icons(6).gameFrame(),
        new KeyStyle("Крио наука", 0xF03AA8E0, 0xF01A6A9A, 0xFF06202E, 0xFFFFFFFF,
                0xFF8AD8FF, 0xFF3AA8E0, 0xFF06202E, 0xFF000000, 4, 1)
                .bevel(0x80C8F0FF, 0x60000000).bold().shadow().icons(6).gameFrame(),
        new KeyStyle("Прометий", 0xF03A1A5A, 0xF01A0A2E, 0xFFB070FF, 0xFFE0C8FF,
                0xFF9A60E8, 0xFF6A30C0, 0xFFE0C8FF, 0xFFFFFFFF, 4, 1)
                .bold().shadow().icons(6).fx(FX_GLOW, 0x80A050FF).gameFrame(),

        // ---- more buildings: icon set 5 (double outline)
        new KeyStyle("Лаборатория", 0xF0D8DCE0, 0xF0989CA4, 0xFF22262A, 0xFF14181C,
                0xFF6AC8FF, 0xFF3A9AE0, 0xFF22262A, 0xFF000000, 6, 1)
                .bevel(0xE0FFFFFF, 0x60000000).bold().icons(5).fx(FX_RIVETS, 0xFF7A828C).gameFrame().accent(0xFF3A9AE0),
        new KeyStyle("Буровая", 0xF07A5A3A, 0xF04A3420, 0xFF1A1006, 0xFFFFD890,
                0xFFFFB347, 0xFFE0880F, 0xFF1A1006, 0xFF000000, 3, 1)
                .bold().shadow().icons(5).fx(FX_HATCH, 0x30000000).gameFrame(),
        new KeyStyle("Трубы", 0xF0506878, 0xF0304250, 0xFF0A1218, 0xFFC8E8FF,
                0xFF7AC8F0, 0xFF4A9AC8, 0xFF0A1218, 0xFF000000, 8, 1)
                .bevel(0x50FFFFFF, 0x70000000).bold().shadow().icons(5).fx(FX_LEFTBAR, 0xFF7AC8F0).gameFrame(),
        new KeyStyle("Сундук", 0xF09A6A3A, 0xF06A4420, 0xFF2A1A08, 0xFFFFE8C0,
                0xFFFFC870, 0xFFE09A3A, 0xFF2A1A08, 0xFF000000, 2, 1)
                .bevel(0x50FFE0B0, 0x70000000).bold().shadow().icons(5).fx(FX_SCAN, 0x28000000).gameFrame(),
        new KeyStyle("Манипулятор", 0xF0404040, 0xF0282828, 0xFF0E0E0E, 0xFFF0C030,
                0xFFF0C030, 0xFFD0A020, 0xFF0E0E0E, 0xFF000000, 3, 1)
                .bevel(0x40FFFFFF, 0x80000000).bold().icons(5).fx(FX_LEFTBAR, 0xFFF0C030).gameFrame().accent(0xFFF0C030),
        new KeyStyle("Бетон", 0xF0909090, 0xF0727272, 0xFF202020, 0xFF141414,
                0xFFFFC85A, 0xFFF29A1F, 0xFF202020, 0xFF000000, 1, 1)
                .bold().icons(5).fx(FX_TILES, 0x40000000).gameFrame().accent(0xFFF29A1F),
        new KeyStyle("Радар", 0xF03A4A3A, 0xF0202C20, 0xFF0A120A, 0xFF9AFF9A,
                0xFF7AE07A, 0xFF4AB04A, 0xFF0A120A, 0xFF000000, 10, 1)
                .bold().icons(5).fx(FX_DOTS, 0x409AFF9A).gameFrame().accent(0xFF7AE07A),
        new KeyStyle("Ракетная шахта", 0xF0E8E8E8, 0xF0A8A8A8, 0xFF202020, 0xFF101010,
                0xFFFF6A4A, 0xFFD83A20, 0xFF202020, 0xFFFFFFFF, 2, 1)
                .bevel(0xF0FFFFFF, 0x60000000).bold().icons(5).fx(FX_HATCH, 0x18000000).gameFrame().accent(0xFFFF6A4A),

        // ---- more planets: icon set 7 (hexagon badge)
        new KeyStyle("Нувис", 0xF04A6A2A, 0xF02A4018, 0xFF101A08, 0xFFE8F0C8,
                0xFFA8D870, 0xFF78B040, 0xFF101A08, 0xFF000000, 4, 1)
                .bold().shadow().icons(7).fx(FX_DOTS, 0x30000000).gameFrame(),
        new KeyStyle("Пепел", 0xF0302826, 0xF0181412, 0xFF6A3A20, 0xFFFF8A4A,
                0xFFFF8A4A, 0xFFD85A20, 0xFF2A1004, 0xFF000000, 3, 1)
                .bold().shadow().icons(7).fx(FX_HATCH, 0x30FF6A20).gameFrame(),
        new KeyStyle("Руины", 0xF05A4A60, 0xF0302838, 0xFF14101A, 0xFFFFE0A0,
                0xFFFFE0A0, 0xFFE0B860, 0xFF14101A, 0xFF000000, 2, 1)
                .bold().shadow().icons(7).fx(FX_TILES, 0x30000000).gameFrame().accent(0xFFFFE0A0),
        new KeyStyle("Болото", 0xF03A4A2A, 0xF01E2A16, 0xFFF0A0C8, 0xFFF0A0C8,
                0xFFF0A0C8, 0xFFC87AA0, 0xFFFFE0F0, 0xFF1E0A12, 14, 1)
                .bold().shadow().icons(7).fx(FX_DOTS, 0x40F0A0C8).gameFrame(),
        new KeyStyle("Аммиак", 0xF0A8E0E0, 0xF06AA8B0, 0xFF0A2028, 0xFF0A2028,
                0xFF4ACAD8, 0xFF2A9AA8, 0xFF0A2028, 0xFFFFFFFF, 3, 1)
                .bevel(0xE0FFFFFF, 0x50000000).bold().icons(7).fx(FX_SCAN, 0x30FFFFFF)
                .panel(0xF03A6A70, 0xF0244A50, 0xFFE0FAFF).accent(0xFF4ACAD8),
        new KeyStyle("Орбита", 0xF0080810, 0xF0000004, 0xFF6A7AA0, 0xFFE0E8FF,
                0xFF6A8AD8, 0xFF3A5AB0, 0xFFE0E8FF, 0xFFFFFFFF, 6, 1)
                .bold().icons(7).fx(FX_DOTS, 0x70FFFFFF).gameFrame().accent(0xFF8AA0E0),
        new KeyStyle("Астероид", 0xF06A5A4A, 0xF03A3028, 0xFF14100C, 0xFFFFE0C0,
                0xFFC8B8A0, 0xFF9A8A70, 0xFF14100C, 0xFF000000, 8, 1)
                .bevel(0x40FFFFFF, 0x80000000).bold().shadow().icons(7).fx(FX_HATCH, 0x25000000).gameFrame(),

        // ---- more moods: icon set 8 (two-colour shifted lines)
        new KeyStyle("Рассвет", 0xF0FFB08A, 0xF0E06A7A, 0xFF4A1A24, 0xFF2A0A14,
                0xFFFFE0A0, 0xFFFFB070, 0xFF4A1A24, 0xFF2A0A14, 10, 1)
                .bold().icons(8).gameFrame().accent(0xFFFFB08A),
        new KeyStyle("Океан", 0xF01A6A8A, 0xF00A3A5A, 0xFF04141E, 0xFFC8F4FF,
                0xFF6AD0F0, 0xFF3AA0C8, 0xFFE0F8FF, 0xFF001018, 10, 1)
                .bold().shadow().icons(8).fx(FX_SCAN, 0x18FFFFFF).gameFrame(),
        new KeyStyle("Лес", 0xF02A5A2A, 0xF0143014, 0xFF061006, 0xFFC8F0B0,
                0xFF8AD06A, 0xFF5AA040, 0xFF061006, 0xFF000000, 6, 1)
                .bold().shadow().icons(8).fx(FX_GLOW, 0x5090FF60).gameFrame(),
        new KeyStyle("Шоколад", 0xF06A3A20, 0xF03A1E10, 0xFF1A0A04, 0xFFFFE0C0,
                0xFFC88A5A, 0xFF9A5A30, 0xFF1A0A04, 0xFFFFFFFF, 8, 1)
                .bevel(0x40FFD0A0, 0x80000000).bold().shadow().icons(8).gameFrame(),
        new KeyStyle("Лаванда", 0xF0B8A0E0, 0xF08A70C0, 0xFF2A1A40, 0xFF1A1030,
                0xFFE0D0FF, 0xFFC0A8F0, 0xFF2A1A40, 0xFF1A1030, 12, 1)
                .bevel(0x80FFFFFF, 0x40000000).bold().icons(8).panel(0xF06A5090, 0xF04A3870, 0xFFF0E8FF).accent(0xFFC0A8F0),
        new KeyStyle("Мята", 0xF0A0F0D0, 0xF060C0A0, 0xFF0A2A1E, 0xFF0A2A1E,
                0xFFE0FFF0, 0xFFA0F0D0, 0xFF0A2A1E, 0xFF0A2A1E, 12, 1)
                .bevel(0x90FFFFFF, 0x40000000).bold().icons(8).panel(0xF03A7A64, 0xF0245A48, 0xFFE8FFF6).accent(0xFF60C0A0),
        new KeyStyle("Ретро", 0xF0E8D8B0, 0xF0C8B888, 0xFF3A2010, 0xFF3A2010,
                0xFFD04A2A, 0xFFA83418, 0xFF3A2010, 0xFFFFF0D8, 4, 1)
                .bevel(0x90FFFFFF, 0x50000000).bold().icons(8).fx(FX_LEFTBAR, 0xFFD04A2A)
                .panel(0xF05A4030, 0xF03A2818, 0xFFFFF0D8).accent(0xFFD04A2A),
        new KeyStyle("Синтвейв", 0xF02A0A4A, 0xF0A02A7A, 0xFFFF5AC8, 0xFFFFF0FF,
                0xFFFF5AC8, 0xFFC02A98, 0xFFFFE0F8, 0xFF1A0020, 4, 1)
                .bold().icons(8).fx(FX_SCAN, 0x20FFFFFF).gameFrame().accent(0xFFFF5AC8),

        // ---- more simple ones
        new KeyStyle("Белый", 0xD8FFFFFF, 0xD8FFFFFF, 0x00000000, 0xFF000000,
                0xFF404040, 0xFF404040, 0x00000000, 0xFFFFFFFF, 8, 0)
                .bold().panel(0xD0FFFFFF, 0xD0FFFFFF, 0xFF000000).stick(0xAAFFFFFF, 0x88FFFFFF).accent(0xFF404040),
        new KeyStyle("Чёрный", 0xF0000000, 0xF0000000, 0x00000000, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0x00000000, 0xFF000000, 8, 0)
                .bold().stick(0xAAFFFFFF, 0x88FFFFFF).accent(0xFFFFFFFF),
        new KeyStyle("Пилюля", 0xB0505050, 0xB0303030, 0x60FFFFFF, 0xFFFFFFFF,
                0xE0FFFFFF, 0xE0E0E0E0, 0xFFFFFFFF, 0xFF000000, 21, 1)
                .shadow().icons(0).accent(0xFFFFFFFF),
        new KeyStyle("Квадрат", 0x90000000, 0x90000000, 0xEEFFFFFF, 0xFFFFFFFF,
                0xEEFFFFFF, 0xEEFFFFFF, 0xFFFFFFFF, 0xFF000000, 0, 2)
                .bold().icons(5).accent(0xFFFFFFFF),
        new KeyStyle("Дымка", 0x30000000, 0x30000000, 0x00000000, 0xFFFFFFFF,
                0x80FFFFFF, 0x80FFFFFF, 0x00000000, 0xFF000000, 10, 0)
                .shadow().panel(0x55000000, 0x55000000, 0xFFFFFFFF).stick(0x66FFFFFF, 0x44FFFFFF).accent(0xFFFFFFFF),

        // ---- more game GUI looks
        new KeyStyle("Ф: Подсказка", 0xF0242324, 0xF0242324, 0xFF0E0E0E, 0xFFFFE6C0,
                0xFFFFC85A, 0xFFF29A1F, 0xFF0E0E0E, 0xFF000000, 2, 1)
                .bold().icons(0).fx(FX_LEFTBAR, 0xFFF29A1F).gameFrame().accent(0xFFF29A1F),
        new KeyStyle("Ф: Вкладка", 0xF0313031, 0xF0262526, 0xFF0E0E0E, 0xFFFFE6C0,
                0xFFC8C8C8, 0xFF9A9A9A, 0xFF0E0E0E, 0xFF000000, 2, 1)
                .bevel(0x30FFFFFF, 0x90000000).bold().icons(5).gameFrame().accent(0xFFC8C8C8),
        new KeyStyle("Ф: Карта", 0xF0263A26, 0xF0182618, 0xFF0A120A, 0xFFC8F0C8,
                0xFF8AD08A, 0xFF5AA05A, 0xFF0A120A, 0xFF000000, 2, 1)
                .bold().icons(7).fx(FX_GRID, 0x2050FF50).gameFrame().accent(0xFF8AD08A),
        new KeyStyle("Ф: Тревога", 0xF03A1010, 0xF0260808, 0xFF100404, 0xFFFFD040,
                0xFFFFD040, 0xFFE0A820, 0xFF100404, 0xFF000000, 2, 1)
                .bold().icons(6).fx(FX_STRIPES, 0xFFE03A2A).gameFrame().accent(0xFFFFD040),
        new KeyStyle("Ф: Достижение", 0xF0E8C060, 0xF0A8842A, 0xFF2A1A00, 0xFF2A1A00,
                0xFFFFF0A0, 0xFFF0D060, 0xFF2A1A00, 0xFF000000, 4, 1)
                .bevel(0xC0FFF8D0, 0x70000000).bold().icons(8).gameFrame().accent(0xFFE8C060),
        // ---- generated from a base colour and an accent: tone(name, base, accent, icon set, effect, effect colour, radius)
        // metals
        tone("Золото", 0xFFD4A82A, 0xFFFFE07A, 1, FX_SHINE, 0x60FFFFFF, 3),
        tone("Серебро", 0xFFB8BEC6, 0xFF7AC8FF, 5, FX_SHINE, 0x70FFFFFF, 3),
        tone("Бронза", 0xFF9A6A30, 0xFFFFB060, 12, FX_SHINE, 0x40FFFFFF, 3),
        tone("Латунь", 0xFFC8A04A, 0xFFFFD070, 0, FX_RIVETS, 0xFF8A7030, 2),
        tone("Титан", 0xFF6A7078, 0xFF9AD0FF, 11, FX_HATCH, 0x20FFFFFF, 2),
        tone("Хром", 0xFFD8DCE0, 0xFF3A9AE0, 13, FX_SHINE, 0x90FFFFFF, 8),
        tone("Ржавчина", 0xFF8A4A26, 0xFFE07A3A, 9, FX_NOISE, 0x60301008, 2),
        tone("Вольфрам", 0xFF3A3E4A, 0xFF8AA0C8, 12, FX_DOUBLE, 0x508AA0C8, 2),
        tone("Литий", 0xFFD0C8D8, 0xFFE88AB8, 10, FX_DOTS, 0x30000000, 4),
        tone("Голмий", 0xFFC86AA8, 0xFFFFA0D8, 6, FX_GLOW, 0x60FF80D0, 4),
        // fluids
        tone("Нефть", 0xFF1A1418, 0xFF8A6AA0, 10, FX_SHINE, 0x30FFFFFF, 10),
        tone("Вода", 0xFF2A7AC8, 0xFF8AD0FF, 4, FX_WAVES, 0x40FFFFFF, 10),
        tone("Пар", 0xFFC8D0D8, 0xFFFFFFFF, 13, FX_WAVES, 0x50FFFFFF, 12),
        tone("Кислота", 0xFFC8C83A, 0xFFF0F07A, 9, FX_DOTS, 0x40000000, 6),
        tone("Смазка", 0xFF3A8A3A, 0xFF8AE08A, 2, FX_WAVES, 0x30000000, 12),
        tone("Лава", 0xFF5A1A08, 0xFFFF7A20, 12, FX_GLOW, 0xC0FF5A10, 4),
        tone("Тяж. нефть", 0xFF8A3A10, 0xFFE09A4A, 7, FX_SHINE, 0x30FFFFFF, 10),
        tone("Лёгк. нефть", 0xFFC89A3A, 0xFFFFD07A, 1, FX_WAVES, 0x30000000, 10),
        tone("Петрогаз", 0xFF4A3A5A, 0xFFB08AD8, 11, FX_DOTS, 0x30FFFFFF, 10),
        tone("Фторкетон", 0xFF3AA8B0, 0xFFA0F0F8, 6, FX_SHINE, 0x50FFFFFF, 8),
        // logistics
        tone("Жёлтая лента", 0xFFD8A820, 0xFFFFE070, 0, FX_CHEVRON, 0x50000000, 2),
        tone("Красная лента", 0xFFB8301E, 0xFFFF7A5A, 0, FX_CHEVRON, 0x50000000, 2),
        tone("Синяя лента", 0xFF2A70C0, 0xFF7AC0FF, 0, FX_CHEVRON, 0x50000000, 2),
        tone("Турбо лента", 0xFF5AA83A, 0xFFB0F07A, 0, FX_CHEVRON, 0x50000000, 2),
        tone("Робот", 0xFF5A6068, 0xFFFFC85A, 11, FX_RIVETS, 0xFF9AA0A8, 8),
        tone("Сеть", 0xFF2A2A30, 0xFFB070E0, 7, FX_CIRCUIT, 0x60B070E0, 3),
        tone("Поезд", 0xFF6A2A20, 0xFFE0A050, 3, FX_SLATS, 0x30000000, 4),
        tone("Цистерна", 0xFF7A8088, 0xFF5AB0E0, 5, FX_TOPBAR, 0xFF5AB0E0, 10),
        tone("Строитель", 0xFF3A3A40, 0xFFF0C030, 12, FX_STRIPES, 0xFFF0C030, 4),
        tone("Запрос", 0xFF3A5A9A, 0xFF8AB8FF, 10, FX_TOPBAR, 0xFF8AB8FF, 2),
        // military
        tone("Турель", 0xFF5A5A30, 0xFFE0C860, 1, FX_RIVETS, 0xFF8A8A50, 2),
        tone("Лазер", 0xFF201010, 0xFFFF3A3A, 4, FX_GLOW, 0x90FF2A2A, 2),
        tone("Огнемёт", 0xFF3A1A08, 0xFFFF9A2A, 12, FX_GLOW, 0xA0FF7A10, 4),
        tone("Танк", 0xFF4A5230, 0xFFC8D07A, 9, FX_NOISE, 0x40000000, 2),
        tone("Артиллерия", 0xFF3A3A3A, 0xFFFF6A3A, 11, FX_CORNERS, 0xFFFF6A3A, 0),
        tone("Ракета", 0xFF6A2018, 0xFFFFB04A, 10, FX_TOPBAR, 0xFFFFB04A, 2),
        tone("Броня", 0xFF3A4048, 0xFF9AB8D8, 5, FX_DOUBLE, 0x609AB8D8, 4),
        tone("Стена", 0xFF8A8A80, 0xFFFFC85A, 1, FX_TILES, 0x50000000, 0),
        tone("Мина", 0xFF2A2A20, 0xFFFF3A2A, 6, FX_STRIPES, 0xFFE0A020, 12),
        tone("Камуфляж", 0xFF4A5A30, 0xFFA8B870, 9, FX_NOISE, 0x60202A10, 3),
        // power and circuits
        tone("Провод", 0xFF8A4A20, 0xFFFFA060, 13, FX_WAVES, 0x40FFA060, 4),
        tone("Красный провод", 0xFF4A0E0E, 0xFFFF4A4A, 13, FX_CIRCUIT, 0x80FF4A4A, 3),
        tone("Зелёный провод", 0xFF0E3A14, 0xFF4AFF6A, 13, FX_CIRCUIT, 0x804AFF6A, 3),
        tone("Комбинатор", 0xFF3A3A3A, 0xFFFFC85A, 3, FX_CIRCUIT, 0x50FFC85A, 2),
        tone("Аккумулятор", 0xFF4A4A50, 0xFF7AE0FF, 1, FX_TOPBAR, 0xFF7AE0FF, 2),
        tone("Подстанция", 0xFF5A5A60, 0xFFFFE07A, 11, FX_CORNERS, 0xFFFFE07A, 2),
        tone("Двигатель", 0xFF5A4A3A, 0xFFE0C8A0, 12, FX_RIVETS, 0xFF9A8A70, 6),
        tone("Молния", 0xFF1A1A3A, 0xFFB0C8FF, 8, FX_GLOW, 0x907AA8FF, 3),
        tone("Плазма", 0xFF3A0A4A, 0xFFFF7AFF, 4, FX_GLOW, 0xA0E04AFF, 21),
        tone("Лампа", 0xFF2A2A1A, 0xFFFFF0A0, 4, FX_GLOW, 0xA0FFF0A0, 10),
        // nature
        tone("Дерево", 0xFF6A4A2A, 0xFFC8A070, 9, FX_SCAN, 0x30000000, 4),
        tone("Трава", 0xFF3A7A2A, 0xFFA8E07A, 0, FX_NOISE, 0x40204010, 6),
        tone("Песок", 0xFFE0C890, 0xFFFFE8B0, 12, FX_NOISE, 0x40806040, 6),
        tone("Скала", 0xFF6A6460, 0xFFC8C0B8, 1, FX_NOISE, 0x50000000, 2),
        tone("Снег", 0xFFF0F4F8, 0xFF8AC8F0, 13, FX_DOTS, 0x30A0B0C0, 10),
        tone("Глубина", 0xFF0A2A4A, 0xFF4AA0E0, 4, FX_WAVES, 0x304AA0E0, 10),
        tone("Мох", 0xFF4A6A30, 0xFF9AC86A, 10, FX_DOTS, 0x40203010, 14),
        tone("Осень", 0xFFB8601E, 0xFFFFB04A, 8, FX_NOISE, 0x50601E08, 8),
        tone("Сакура", 0xFFF0B8C8, 0xFFFF7AA8, 7, FX_DOTS, 0x50FFFFFF, 14),
        tone("Пещера", 0xFF2A2420, 0xFFE0A050, 12, FX_GLOW, 0x50E0A050, 2),
        // plain colours
        tone("Красный", 0xFFD02A2A, 0xFFFF8A8A, 0, FX_SHINE, 0x40FFFFFF, 10),
        tone("Оранжевый", 0xFFE07A1A, 0xFFFFC07A, 1, FX_SHINE, 0x40FFFFFF, 10),
        tone("Жёлтый", 0xFFE8C81A, 0xFFFFF07A, 5, FX_SHINE, 0x40FFFFFF, 10),
        tone("Лайм", 0xFF8AD02A, 0xFFD0FF8A, 9, FX_SHINE, 0x40FFFFFF, 10),
        tone("Бирюза", 0xFF1AB8A0, 0xFF8AF0E0, 10, FX_SHINE, 0x40FFFFFF, 10),
        tone("Голубой", 0xFF3AB0F0, 0xFFB0E8FF, 11, FX_SHINE, 0x40FFFFFF, 10),
        tone("Синий", 0xFF2A4AD0, 0xFF8AA8FF, 12, FX_SHINE, 0x40FFFFFF, 10),
        tone("Индиго", 0xFF4A2AA0, 0xFFA88AFF, 13, FX_SHINE, 0x40FFFFFF, 10),
        tone("Пурпур", 0xFFA02AC0, 0xFFE88AFF, 6, FX_SHINE, 0x40FFFFFF, 10),
        tone("Розовый", 0xFFF07AB0, 0xFFFFC8E0, 7, FX_SHINE, 0x40FFFFFF, 10),
        // tech
        tone("HUD", 0xFF0A1A20, 0xFF4AE0FF, 11, FX_CORNERS, 0xFF4AE0FF, 0).mono(),
        tone("Голограмма", 0xFF0A2A3A, 0xFF7AF0FF, 8, FX_SCAN, 0x307AF0FF, 4),
        tone("Матрица", 0xFF001400, 0xFF3AFF5A, 9, FX_SCAN, 0x203AFF5A, 0).mono(),
        tone("Киберпанк", 0xFF1A0A2A, 0xFFFFE01A, 8, FX_CORNERS, 0xFFFF2A8A, 0),
        tone("Карбон", 0xFF1A1A1A, 0xFFE0E0E0, 5, FX_HATCH, 0x18FFFFFF, 4),
        tone("Хайтек", 0xFFE8ECF0, 0xFF2A8AE0, 11, FX_DOUBLE, 0x602A8AE0, 6),
        tone("Нуар", 0xFF101010, 0xFFF0F0F0, 12, FX_DOUBLE, 0x80FFFFFF, 0),
        tone("Вейпор", 0xFFFF8AD0, 0xFF7AE8FF, 8, FX_SHINE, 0x50FFFFFF, 6),
        tone("Эскиз", 0xFFF4F0E8, 0xFF2A4A8A, 13, FX_GRID, 0x302A4A8A, 2),
        tone("Пиксель", 0xFF2A2A3A, 0xFFFFC85A, 9, FX_NONE, 0, 0).mono(),

        // ---- console / mobile game look: big glyphs, CAPS labels, square or round keys
        new KeyStyle("Пульт тёмный", 0xF04A4A4A, 0xF02E2E2E, 0xFF101010, 0xFFF0F0F0,
                0xFFFFC444, 0xFFEC9A1E, 0xFF101010, 0xFF101010, 3, 1)
                .bevel(0x50FFFFFF, 0x90000000).bold().icons(14).caps().fx(FX_DOUBLE, 0x40000000)
                .panel(0xF0262626, 0xF01A1A1A, 0xFFF0F0F0).stick(0xAAB0B0B0, 0xCCD8D8D8).accent(0xFFEC9A1E),
        new KeyStyle("Пульт светлый", 0xF8DCDCDC, 0xF8A8A8A8, 0xFF1A1A1A, 0xFF141414,
                0xFFFFC444, 0xFFEC9A1E, 0xFF1A1A1A, 0xFF101010, 3, 1)
                .bevel(0xF0FFFFFF, 0x80000000).bold().icons(15).iconDp(24).caps()
                .panel(0xF0262626, 0xF01A1A1A, 0xFFF0F0F0).stick(0xAAB0B0B0, 0xCCE0E0E0).accent(0xFFEC9A1E),
        new KeyStyle("Пульт оранж.", 0xFFFFC04A, 0xFFE8961E, 0xFF2A1A00, 0xFF141414,
                0xFFFFE0A0, 0xFFFFC04A, 0xFF2A1A00, 0xFF101010, 3, 1)
                .bevel(0xC0FFF0C0, 0x70000000).bold().icons(14).caps()
                .panel(0xF0262626, 0xF01A1A1A, 0xFFF0F0F0).accent(0xFFFFC04A),
        new KeyStyle("Индастриал", 0xF0383C42, 0xF01E2024, 0xFF60656B, 0xFFF0F0F0,
                0xFFFFB040, 0xFFE88A10, 0xFF2A1800, 0xFF101010, 4, 1)
                .bevel(0x40FFFFFF, 0xA0000000).bold().shadow().icons(14).iconColor(0xFFFFA530).caps()
                .fx(FX_DOUBLE, 0x30FFA530).gameFrame().stick(0xCCFFA530, 0xCCFFB040).accent(0xFFFFA530),
        new KeyStyle("Индастр. круг", 0xF0383C42, 0xF01E2024, 0xFFFFA530, 0xFFF0F0F0,
                0xFFFFB040, 0xFFE88A10, 0xFFFFD090, 0xFF101010, 21, 2)
                .bevel(0x40FFFFFF, 0).bold().shadow().icons(14).iconColor(0xFFFFA530).caps().circle()
                .gameFrame().stick(0xCCFFA530, 0xCCFFB040).accent(0xFFFFA530),
        new KeyStyle("Индастр. оранж", 0xFFFFAA3A, 0xFFD87A14, 0xFF3A2000, 0xFF1A0E00,
                0xFFFFD890, 0xFFFFAA3A, 0xFF3A2000, 0xFF1A0E00, 6, 1)
                .bevel(0xC0FFE0B0, 0x80000000).bold().icons(14).caps().gameFrame().stick(0xCCFFA530, 0xCCFFAA3A).accent(0xFFFFAA3A),
        new KeyStyle("Пульт синий", 0xF0263A5A, 0xF0162438, 0xFF0A1220, 0xFFE0ECFF,
                0xFF7ADAFF, 0xFF3AA8E0, 0xFF0A1220, 0xFF101010, 4, 1)
                .bevel(0x40FFFFFF, 0x90000000).bold().shadow().icons(14).iconColor(0xFF5AD0FF).caps().gameFrame().accent(0xFF5AD0FF),
        new KeyStyle("Пульт зелёный", 0xFF6ADA4A, 0xFF3AA024, 0xFF0E2A08, 0xFFFFFFFF,
                0xFFB0FF90, 0xFF6ADA4A, 0xFF0E2A08, 0xFF101010, 4, 1)
                .bevel(0x80E0FFD0, 0x80000000).bold().shadow().icons(14).caps().gameFrame().accent(0xFF6ADA4A),
        new KeyStyle("Хотбар", 0xF0242424, 0xF0303030, 0xFF6A6A6A, 0xFFE8E8E8,
                0xFFFFC444, 0xFFEC9A1E, 0xFF6A6A6A, 0xFF101010, 2, 1)
                .bevel(0xA0000000, 0x30FFFFFF).bold().icons(15).iconDp(26).noLabel().gameFrame().accent(0xFFEC9A1E),
        new KeyStyle("Только значки", 0xF04A4A4A, 0xF02E2E2E, 0xFF101010, 0xFFF0F0F0,
                0xFFFFC444, 0xFFEC9A1E, 0xFF101010, 0xFF101010, 3, 1)
                .bevel(0x50FFFFFF, 0x90000000).bold().icons(14).iconDp(26).noLabel()
                .panel(0xF0262626, 0xF01A1A1A, 0xFFF0F0F0).stick(0xAAB0B0B0, 0xCCD8D8D8).accent(0xFFEC9A1E),
        new KeyStyle("Значки светлые", 0xF8DCDCDC, 0xF8A8A8A8, 0xFF1A1A1A, 0xFF141414,
                0xFFFFC444, 0xFFEC9A1E, 0xFF1A1A1A, 0xFF101010, 3, 1)
                .bevel(0xF0FFFFFF, 0x80000000).bold().icons(15).iconDp(26).noLabel()
                .panel(0xF0262626, 0xF01A1A1A, 0xFFF0F0F0).stick(0xAAB0B0B0, 0xCCE0E0E0).accent(0xFFEC9A1E),
        new KeyStyle("Круг светлый", 0xF8E4E4E4, 0xF8A0A0A0, 0xFF1A1A1A, 0xFF141414,
                0xFFFFC444, 0xFFEC9A1E, 0xFF1A1A1A, 0xFF101010, 21, 1)
                .bevel(0xC0FFFFFF, 0).bold().icons(15).caps().circle()
                .panel(0xF0262626, 0xF01A1A1A, 0xFFF0F0F0).stick(0xAAE0E0E0, 0xCCE0E0E0).accent(0xFFEC9A1E),
        new KeyStyle("Круг оранж.", 0xFFFFC04A, 0xFFD8801A, 0xFF2A1A00, 0xFF141414,
                0xFFFFE6B0, 0xFFFFC04A, 0xFF2A1A00, 0xFF101010, 21, 1)
                .bevel(0xC0FFF0C0, 0).bold().icons(14).caps().circle().gameFrame().accent(0xFFFFC04A),
        new KeyStyle("Круг неон", 0xF00C1A20, 0xF0060C10, 0xFF3AE0FF, 0xFFFFFFFF,
                0xFF7AF0FF, 0xFF3AC8E8, 0xFFD0FAFF, 0xFF041418, 21, 2)
                .bold().icons(14).iconColor(0xFF3AE0FF).caps().circle().gameFrame().accent(0xFF3AE0FF),
        new KeyStyle("Алерт", 0xF0301414, 0xF01C0A0A, 0xFF6A2A2A, 0xFFF0F0F0,
                0xFFFF6A50, 0xFFE03A2A, 0xFF2A0606, 0xFF101010, 3, 1)
                .bevel(0x30FFFFFF, 0x90000000).bold().shadow().icons(14).iconColor(0xFFFF5A3A).caps().gameFrame().accent(0xFFFF5A3A),
        new KeyStyle("Пульт стекло", 0x90303030, 0x90181818, 0x60FFFFFF, 0xFFFFFFFF,
                0xE0FFFFFF, 0xD0E0E0E0, 0xFFFFFFFF, 0xFF101010, 6, 1)
                .bold().shadow().icons(14).caps().panel(0x80202020, 0x80101010, 0xFFFFFFFF).accent(0xFFFFFFFF),
        new KeyStyle("Пульт матовый", 0xF01A1A1A, 0xF01A1A1A, 0xFF3A3A3A, 0xFFA8A8A8,
                0xFFE0E0E0, 0xFFC0C0C0, 0xFF3A3A3A, 0xFF101010, 2, 1)
                .bold().icons(14).iconColor(0xFFD8D8D8).caps().gameFrame().accent(0xFFC0C0C0),
        new KeyStyle("Стальной круг", 0xF0D0D4D8, 0xF08A9096, 0xFF2A2E32, 0xFF141414,
                0xFFFFC444, 0xFFEC9A1E, 0xFF2A2E32, 0xFF101010, 21, 1)
                .bevel(0xE0FFFFFF, 0).bold().icons(15).caps().circle().gameFrame().accent(0xFFEC9A1E),
        new KeyStyle("Пульт жёлтый", 0xFFFFD84A, 0xFFE0B020, 0xFF2A2000, 0xFF141000,
                0xFF4A4A4A, 0xFF2A2A2A, 0xFFFFD84A, 0xFFFFD84A, 3, 1)
                .bevel(0xC0FFF4C0, 0x70000000).bold().icons(14).caps()
                .panel(0xF0303030, 0xF0222222, 0xFFFFD84A).accent(0xFFFFD84A),
        new KeyStyle("Пульт ретро", 0xF02A2010, 0xF0181008, 0xFFC88A2A, 0xFFFFC870,
                0xFFFFC870, 0xFFE0A040, 0xFF3A2000, 0xFF1A0E00, 3, 1)
                .bold().mono().icons(15).iconColor(0xFFFFB040).caps().fx(FX_SCAN, 0x20FFB040).gameFrame().accent(0xFFFFB040),
    };

    /** Colour toward white (f > 0) or black (f < 0); alpha kept. */
    private static int shade(int c, float f) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (f >= 0) {
            r += (int) ((255 - r) * f); g += (int) ((255 - g) * f); b += (int) ((255 - b) * f);
        } else {
            r = (int) (r * (1 + f)); g = (int) (g * (1 + f)); b = (int) (b * (1 + f));
        }
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static float lum(int c) {
        return (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
    }

    /** WCAG relative luminance and contrast ratio. */
    private static double relLum(int c) {
        double[] v = {((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(int a, int b) {
        double x = relLum(a), y = relLum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static int alpha(int c, int a) {
        return (c & 0xFFFFFF) | (a << 24);
    }

    /** A whole style from a base colour and an accent (pressed key, stick, checkboxes). */
    private static KeyStyle tone(String name, int base, int accent, int iconSet, int fx, int fxColor, int radius) {
        int top = shade(base, 0.14f), bottom = shade(base, -0.22f);
        // label: dark shade of the base or light tint of the accent, whichever reads better on both gradient ends
        int dark = shade(base, -0.85f), lightText = shade(accent, 0.75f);
        boolean light = Math.min(contrast(dark, top), contrast(dark, bottom))
                > Math.min(contrast(lightText, top), contrast(lightText, bottom));
        int edge = alpha(shade(base, -0.75f), 0xFF);
        int text = alpha(light ? dark : lightText, 0xFF);
        int pText = lum(accent) > 0.55f ? 0xFF101010 : 0xFFFFFFFF;
        KeyStyle s = new KeyStyle(name, alpha(top, 0xF0), alpha(bottom, 0xF0), edge, text,
                alpha(shade(accent, 0.2f), 0xFF), alpha(accent, 0xFF), edge, pText, radius, 1)
                .bevel(light ? 0xB0FFFFFF : 0x40FFFFFF, 0x70000000).bold().icons(iconSet).gameFrame()
                .stick(alpha(accent, 0xAA), alpha(accent, 0x88)).accent(alpha(accent, 0xFF));
        if (!light) s.shadow();
        if (fx != FX_NONE) s.fx(fx, fxColor);
        return s;
    }

    private KeyStyle style = STYLES[0];
    private final java.util.List<TextView> styledKeys = new java.util.ArrayList<>();
    private View modeGroup;
    private Stick stickView;
    private android.widget.CheckBox[] styleBoxes;
    private View stylePanel;

    private static int styleIndex(String name) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].name.equals(name)) return i;
        return 0;
    }

    /** Key / panel background: gradient with an edge, effect, bevel lines. */
    private android.graphics.drawable.Drawable background(KeyStyle s, boolean pressed, boolean panel) {
        return background(s, pressed, panel, true);
    }

    /** Round key: the largest circle in the bounds, gradient fill, edge, light arc on top. */
    private class CircleBg extends android.graphics.drawable.Drawable {
        private final KeyStyle s;
        private final boolean pressed;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        CircleBg(KeyStyle s, boolean pressed) { this.s = s; this.pressed = pressed; }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), sw = dp((float) s.strokeDp);
            float r = Math.min(b.width(), b.height()) / 2f - sw / 2 - dp(0.5f);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(0x60000000);  // drop shadow
            c.drawCircle(cx, cy + dp(1.5f), r, p);
            p.setShader(new android.graphics.LinearGradient(0, cy - r, 0, cy + r,
                    pressed ? s.pTop : s.top, pressed ? s.pBottom : s.bottom, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            if (!pressed && s.highlight != 0) {
                p.setColor(s.highlight);
                p.setStrokeWidth(dp(1.2f));
                float k = r - sw - dp(1.5f);
                c.drawArc(cx - k, cy - k, cx + k, cy + k, 200, 140, false, p);
            }
            if (sw > 0) {
                p.setColor(pressed ? s.pStroke : s.stroke);
                p.setStrokeWidth(sw);
                c.drawCircle(cx, cy, r, p);
            }
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private android.graphics.drawable.Drawable background(KeyStyle s, boolean pressed, boolean panel, boolean round) {
        if (round && s.circle && !panel) return new CircleBg(s, pressed);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                panel ? new int[] {s.panelTop, s.panelBottom} : pressed ? new int[] {s.pTop, s.pBottom} : new int[] {s.top, s.bottom});
        g.setCornerRadius(dp((float) s.radius));
        if (s.strokeDp > 0) g.setStroke(dp(s.strokeDp), pressed ? s.pStroke : s.stroke);
        java.util.List<android.graphics.drawable.Drawable> layers = new java.util.ArrayList<>();
        layers.add(g);
        if (!panel && s.effect != FX_NONE && !(pressed && s.effect == FX_GLOW)) layers.add(new Effect(s));
        int first = layers.size();
        boolean bevel = !pressed && (s.highlight != 0 || s.lowlight != 0);
        if (bevel) {
            // a light line inside the top edge, a dark one inside the bottom edge
            android.graphics.drawable.GradientDrawable hi = new android.graphics.drawable.GradientDrawable(),
                    lo = new android.graphics.drawable.GradientDrawable();
            hi.setColor(s.highlight);
            lo.setColor(s.lowlight);
            layers.add(hi);
            layers.add(lo);
        }
        if (layers.size() == 1) return g;
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                layers.toArray(new android.graphics.drawable.Drawable[0]));
        if (bevel) {
            int e = dp(s.strokeDp), side = e + dp(Math.max(1, s.radius / 2));
            l.setLayerInset(first, side, e, side, 0);
            l.setLayerHeight(first, dp(1));
            l.setLayerGravity(first, Gravity.TOP | Gravity.FILL_HORIZONTAL);
            l.setLayerInset(first + 1, side, 0, side, e);
            l.setLayerHeight(first + 1, dp(1));
            l.setLayerGravity(first + 1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        }
        return l;
    }

    /** Rivets in the corners, hazard stripes around the edge, a grid, or a glow from below. */
    private class Effect extends android.graphics.drawable.Drawable {
        private final KeyStyle s;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Effect(KeyStyle s) { this.s = s; }

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

    /**
     * Line icons in the game's manner. Drawn in a 24 x 24 box: stroked lines
     * plus optional filled parts. Sets: 0 one tone with a dark rim, 1 thick
     * blocky lines with a drop shadow, 2 in a round badge, 3 dark on a filled
     * signal square, 4 thin glowing lines. Sets 2 and 4 use other glyphs for
     * Tab, Q, E, R and the wheel.
     */
    private class KeyIcon extends android.graphics.drawable.Drawable {
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

    private void styleText(TextView t, KeyStyle s, int color, boolean shadow) {
        t.setTextColor(color);
        t.setTypeface(s.mono ? android.graphics.Typeface.MONOSPACE : android.graphics.Typeface.DEFAULT,
                s.bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (shadow && s.shadow) t.setShadowLayer(dp(2f), 0, dp(1f), 0xCC000000);
        else t.setShadowLayer(0, 0, 0, 0);
    }

    /** Draws a key in style s: background, label, and in icon styles an icon over a small label. */
    private void paintKeyAs(TextView t, KeyStyle s, boolean pressed) {
        t.setBackground(background(s, pressed, false));
        // the key's own label is kept in the content description (the text may be CAPS or empty)
        if (t.getContentDescription() == null) t.setContentDescription(t.getText());
        String label = t.getContentDescription().toString();
        int color = pressed ? s.pText : s.text;
        int iconInk = pressed || s.iconColor == 0 ? color : s.iconColor;
        KeyIcon icon = s.icons ? new KeyIcon(label, iconInk, s.iconSet) : null;
        if (icon != null && !icon.empty()) {
            icon.setBounds(0, 0, dp(s.iconDp), dp(s.iconDp));
            t.setCompoundDrawables(null, icon, null, null);
            t.setText(s.noLabel ? "" : s.caps ? label.toUpperCase() : label);
            t.setTextSize(s.caps ? 8.5f : 9);
            t.setPadding(0, s.noLabel ? 0 : dp(2), 0, 0);
        } else {
            t.setCompoundDrawables(null, null, null, null);
            t.setText(s.caps ? label.toUpperCase() : label);
            t.setTextSize(label.length() > 4 ? 13 : 14);
            t.setPadding(0, 0, 0, 0);
        }
        styleText(t, s, color, !pressed);
    }

    /** Draws a key in the current style; remembers the state for restyling. */
    private void paintKey(View v, boolean pressed) {
        v.setTag(pressed);
        if (!(v instanceof TextView)) { v.setBackground(background(style, pressed, false)); return; }
        TextView t = (TextView) v;
        if (!styledKeys.contains(t)) styledKeys.add(t);
        paintKeyAs(t, style, pressed);
    }

    /** Panel (picker, touch/cursor switch) background and its labels/checkboxes. */
    private void paintPanel(android.view.ViewGroup g) {
        g.setBackground(background(style, false, true));
        tintGroup(g);
    }

    private void tintGroup(android.view.ViewGroup g) {
        for (int k = 0; k < g.getChildCount(); k++) {
            View c = g.getChildAt(k);
            if ("preview".equals(c.getTag()) || "tab".equals(c.getTag())) continue;  // previews and tabs keep their own look
            if (c instanceof android.widget.CompoundButton)
                ((android.widget.CompoundButton) c).setButtonTintList(android.content.res.ColorStateList.valueOf(style.accent));
            if (c instanceof TextView) styleText((TextView) c, style, style.panelText, true);
            else if (c instanceof android.view.ViewGroup) tintGroup((android.view.ViewGroup) c);
        }
    }

    private void applyStyle(int i) {
        style = STYLES[Math.max(0, Math.min(STYLES.length - 1, i))];
        getSharedPreferences("game", MODE_PRIVATE).edit().putString("keyStyleName", style.name).apply();
        for (TextView t : styledKeys) paintKey(t, Boolean.TRUE.equals(t.getTag()));
        if (modeGroup != null) paintPanel((android.view.ViewGroup) modeGroup);
        if (stylePanel != null) paintPanel((android.view.ViewGroup) stylePanel);
        paintTabs();
        paintMenuTabs();
        if (stylePanel != null && stylePanel.getBackground() != null) stylePanel.getBackground().setAlpha(225);  // see-through
        if (stickView != null) stickView.invalidate();
        if (wheelPopup != null) wheelPopup.invalidate();
    }

    /** Picker tabs: title, then the style names in it. Every style is in one tab. */
    private static final String[][] TABS = {
        {"Игра", "Factorio", "Завод", "Ф: Слот", "Ф: Окно", "Ф: Зелёная", "Ф: Красная", "Ф: Опасность", "Ф: Чертёж",
                "Ф: Ночь", "Ф: Подсказка", "Ф: Вкладка", "Ф: Карта", "Ф: Тревога", "Ф: Достижение"},
        {"Пульт", "Пульт тёмный", "Пульт светлый", "Пульт оранж.", "Индастриал", "Индастр. круг", "Индастр. оранж",
                "Пульт синий", "Пульт зелёный", "Хотбар", "Только значки", "Значки светлые", "Круг светлый", "Круг оранж.",
                "Круг неон", "Алерт", "Пульт стекло", "Пульт матовый", "Стальной круг", "Пульт жёлтый", "Пульт ретро"},
        {"Простые", "Стекло", "Неон", "Тень", "Контур", "Хаки", "Космос", "Сигнал", "Терминал",
                "Белый", "Чёрный", "Пилюля", "Квадрат", "Дымка"},
        {"Материалы", "Медь", "Сталь", "Конвейер", "Железо", "Уголь", "Камень", "Уран", "Пластик"},
        {"Наука", "Кр. наука", "Зел. наука", "Син. наука", "Фиол. наука", "Жёлт. наука", "Воен. наука",
                "Косм. наука", "Металлург.", "Электромаг.", "Агро наука", "Крио наука", "Прометий"},
        {"Здания", "Сборщик", "Печь", "Ж/д", "Солн. панель", "Реактор", "Лаборатория", "Буровая", "Трубы",
                "Сундук", "Манипулятор", "Бетон", "Радар", "Ракетная шахта"},
        {"Планеты", "Вулканус", "Фулгора", "Глеба", "Аквило", "Платформа", "Нувис", "Пепел", "Руины",
                "Болото", "Аммиак", "Орбита", "Астероид"},
        {"Настроение", "Кусаки", "Пустыня", "Сумерки", "Монитор", "Рассвет", "Океан", "Лес", "Шоколад",
                "Лаванда", "Мята", "Ретро", "Синтвейв"},
        {"Металлы", "Золото", "Серебро", "Бронза", "Латунь", "Титан", "Хром", "Ржавчина", "Вольфрам", "Литий", "Голмий"},
        {"Жидкости", "Нефть", "Вода", "Пар", "Кислота", "Смазка", "Лава", "Тяж. нефть", "Лёгк. нефть", "Петрогаз", "Фторкетон"},
        {"Логистика", "Жёлтая лента", "Красная лента", "Синяя лента", "Турбо лента", "Робот", "Сеть", "Поезд",
                "Цистерна", "Строитель", "Запрос"},
        {"Война", "Турель", "Лазер", "Огнемёт", "Танк", "Артиллерия", "Ракета", "Броня", "Стена", "Мина", "Камуфляж"},
        {"Энергия", "Провод", "Красный провод", "Зелёный провод", "Комбинатор", "Аккумулятор", "Подстанция",
                "Двигатель", "Молния", "Плазма", "Лампа"},
        {"Природа", "Дерево", "Трава", "Песок", "Скала", "Снег", "Глубина", "Мох", "Осень", "Сакура", "Пещера"},
        {"Цвета", "Красный", "Оранжевый", "Жёлтый", "Лайм", "Бирюза", "Голубой", "Синий", "Индиго", "Пурпур", "Розовый"},
        {"Техно", "HUD", "Голограмма", "Матрица", "Киберпанк", "Карбон", "Хайтек", "Нуар", "Вейпор", "Эскиз", "Пиксель"},
    };

    private static int tabOf(String name) {
        for (int t = 0; t < TABS.length; t++)
            for (int k = 1; k < TABS[t].length; k++) if (TABS[t][k].equals(name)) return t;
        return 0;
    }

    private int pickerTab;
    private TextView[] tabViews;

    private void paintTabs() {
        if (tabViews == null) return;
        for (int t = 0; t < tabViews.length; t++) {
            boolean on = t == pickerTab;
            tabViews[t].setBackground(background(style, on, false, false));
            styleText(tabViews[t], style, on ? style.pText : style.text, !on);
        }
    }

    /**
     * The gear menu's "Темы" page: a row of tabs (scrolls left/right), and the
     * styles of the chosen tab (scrolls up/down): checkbox with the name and a
     * sample key in that style. Only one style is on. A sideways swipe over the
     * list goes to the next / previous tab.
     */
    private View stylePicker() {
        android.content.SharedPreferences prefs = getSharedPreferences("game", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabViews = new TextView[TABS.length];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView[] scrollRef = new android.widget.ScrollView[1];
        styleBoxes = new android.widget.CheckBox[STYLES.length];

        java.util.function.IntConsumer showTab = new java.util.function.IntConsumer() {
            @Override
            public void accept(int t) {
                pickerTab = (t + TABS.length) % TABS.length;
                prefs.edit().putInt("keyStyleTab", pickerTab).apply();
                paintTabs();
                View tv = tabViews[pickerTab];
                tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, tv.getLeft() - dp(24)), 0));
                list.removeAllViews();
                java.util.Arrays.fill(styleBoxes, null);
                int chosenRow = -1;
                for (int k = 1; k < TABS[pickerTab].length; k++) {
                    final int n = styleIndex(TABS[pickerTab][k]);
                    LinearLayout row = new LinearLayout(GameActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    android.widget.CheckBox b = new android.widget.CheckBox(GameActivity.this);
                    b.setText(STYLES[n].name);
                    b.setTextSize(13);
                    b.setChecked(STYLES[n] == style);
                    if (STYLES[n] == style) chosenRow = k - 1;
                    View.OnClickListener pick = v -> {
                        for (android.widget.CheckBox o : styleBoxes) if (o != null) o.setChecked(o == styleBoxes[n]);  // a tap on the current one keeps it on
                        applyStyle(n);
                    };
                    b.setOnClickListener(pick);
                    styleBoxes[n] = b;
                    row.addView(b, new LinearLayout.LayoutParams(dp(150), dp(40)));
                    TextView sample = new TextView(GameActivity.this);
                    sample.setTag("preview");
                    sample.setText("R");
                    sample.setGravity(Gravity.CENTER);
                    paintKeyAs(sample, STYLES[n], false);
                    sample.setOnClickListener(pick);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(48), dp(36));
                    sp.setMargins(dp(4), dp(2), dp(2), dp(2));
                    row.addView(sample, sp);
                    list.addView(row);
                }
                tintGroup(list);
                final int rowIndex = chosenRow;
                android.widget.ScrollView scroll = scrollRef[0];
                scroll.post(() -> {  // show the chosen style, else the top
                    View r = rowIndex >= 0 ? list.getChildAt(rowIndex) : null;
                    scroll.scrollTo(0, r != null ? Math.max(0, r.getTop() - scroll.getHeight() / 3) : 0);
                });
            }
        };

        for (int t = 0; t < TABS.length; t++) {
            final int n = t;
            TextView tv = new TextView(this);
            tv.setTag("tab");
            tv.setText(TABS[t][0]);
            tv.setTextSize(13);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(10), 0, dp(10), 0);
            tv.setOnClickListener(v -> showTab.accept(n));
            tabViews[t] = tv;
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            tp.setMargins(dp(2), dp(2), dp(2), dp(4));
            tabRow.addView(tv, tp);
        }
        tabScroll.addView(tabRow);
        box.addView(tabScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // sideways swipe over the list = next / previous tab; up/down still scrolls
        android.view.GestureDetector swipe = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < dp(400) || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                showTab.accept(pickerTab + (vx < 0 ? 1 : -1));
                return true;
            }
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (swipe.onTouchEvent(e)) {  // a swipe is not a tap on the row under the finger
                    MotionEvent c = MotionEvent.obtain(e);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(c);
                    c.recycle();
                    return true;
                }
                return super.dispatchTouchEvent(e);
            }
        };
        scroll.setScrollbarFadingEnabled(false);
        scrollRef[0] = scroll;
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        showTab.accept(prefs.getInt("keyStyleTab", tabOf(style.name)));
        return box;
    }

    // ---- the gear (bottom-right corner) and its menu over the on-screen keys:
    // Управление (touch / cursor), Темы (key styles), Производительность (HUD).

    private View menuScrim, menuPanel;
    private final View[] menuPages = new View[3];
    private TextView[] menuTabs;
    private int menuPage;

    private boolean menuOpen() {
        return menuPanel != null && menuPanel.getVisibility() == View.VISIBLE;
    }

    private void showMenu(boolean on) {
        menuScrim.setVisibility(on ? View.VISIBLE : View.GONE);
        menuPanel.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void showMenuPage(int i) {
        menuPage = i;
        getSharedPreferences("game", MODE_PRIVATE).edit().putInt("menuPage", i).apply();
        for (int k = 0; k < menuPages.length; k++) menuPages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        paintMenuTabs();
    }

    private void paintMenuTabs() {
        if (menuTabs == null) return;
        for (int t = 0; t < menuTabs.length; t++) {
            boolean on = t == menuPage;
            menuTabs[t].setBackground(background(style, on, false, false));
            styleText(menuTabs[t], style, on ? style.pText : style.text, !on);
        }
    }

    private TextView menuNote(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(dp(8), dp(2), dp(8), dp(8));
        return t;
    }

    private void addMenu() {
        android.content.SharedPreferences prefs = getSharedPreferences("game", MODE_PRIVATE);
        FrameLayout layer = new FrameLayout(this);  // not clickable: touches pass to the game while closed

        menuScrim = new View(this);
        menuScrim.setBackgroundColor(0x40000000);
        menuScrim.setClickable(true);  // a tap beside the menu closes it, the game gets nothing
        menuScrim.setOnClickListener(v -> showMenu(false));
        layer.addView(menuScrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(8));
        panel.setClickable(true);
        menuPanel = panel;

        // header: the three pages and a close mark
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"Управление", "Темы", "Производительность"};
        menuTabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int n = i;
            TextView t = new TextView(this);
            t.setTag("tab");
            t.setText(names[i]);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), 0, dp(12), 0);
            t.setOnClickListener(v -> showMenuPage(n));
            menuTabs[i] = t;
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            tp.setMargins(0, 0, dp(6), 0);
            head.addView(t, tp);
        }
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> showMenu(false));
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(36)));
        panel.addView(head);

        FrameLayout pages = new FrameLayout(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        pp.topMargin = dp(8);
        panel.addView(pages, pp);

        // Управление: touch / cursor
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioGroup mode = new android.widget.RadioGroup(this);
        mode.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioButton touchBtn = new android.widget.RadioButton(this), cursorBtn = new android.widget.RadioButton(this);
        touchBtn.setText("Тач");
        cursorBtn.setText("Курсор");
        for (android.widget.RadioButton rb : new android.widget.RadioButton[] {touchBtn, cursorBtn}) {
            rb.setTextSize(15);
            rb.setId(View.generateViewId());
            mode.addView(rb);
            if (rb == touchBtn) mode.addView(menuNote("Касание экрана — клик в этом месте."));
        }
        mode.addView(menuNote("Экран как тачпад: палец двигает курсор, касание — клик, касание и сразу ведение — перетаскивание."));
        cursorMode = prefs.getBoolean("cursorMode", false);
        (cursorMode ? cursorBtn : touchBtn).setChecked(true);
        mode.setOnCheckedChangeListener((g, id) -> setCursorMode(id == cursorBtn.getId()));
        controls.addView(mode);

        // cursor size, 50..300 %
        TextView sizeText = menuNote("");
        sizeText.setTextSize(15);
        android.widget.SeekBar size = new android.widget.SeekBar(this);
        size.setMax(25);  // 50, 60, ... 300 %
        int pct = prefs.getInt("cursorScale", 100);
        cursorScale = pct / 100f;
        size.setProgress((pct - 50) / 10);
        sizeText.setText("Размер курсора: " + pct + "%");
        size.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {
                int p = 50 + v * 10;
                cursorScale = p / 100f;
                sizeText.setText("Размер курсора: " + p + "%");
                prefs.edit().putInt("cursorScale", p).apply();
                if (cursorPad != null) cursorPad.invalidate();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        controls.addView(sizeText);
        controls.addView(size, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.Button learn = new android.widget.Button(this);
        learn.setText("Начать обучение управлению");
        learn.setOnClickListener(v -> {
            showMenu(false);
            startTutorial();
        });
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpL.topMargin = dp(12);
        controls.addView(learn, lpL);
        android.widget.ScrollView controlsScroll = new android.widget.ScrollView(this);
        controlsScroll.addView(controls);
        menuPages[0] = controlsScroll;

        // Темы: the key styles
        menuPages[1] = stylePicker();

        // Производительность: the HUD and its panels
        LinearLayout perf = new LinearLayout(this);
        perf.setOrientation(LinearLayout.VERTICAL);
        android.widget.CheckBox hudOn = new android.widget.CheckBox(this);
        hudOn.setText("HUD сверху: FPS, UPS, ватты, загрузка ЦП и ГП");
        hudOn.setTextSize(15);
        hudOn.setChecked(prefs.getBoolean("hud", getIntent().getBooleanExtra(EXTRA_POWER_METER, false)));
        hudOn.setOnCheckedChangeListener((b, on) -> setHud(on));
        perf.addView(hudOn);
        perf.addView(menuNote("Панели под HUD:"));
        for (android.widget.CheckBox b : new android.widget.CheckBox[] {graphBox, cpuBox, gpuBox}) {
            if (b == null) continue;
            b.setTypeface(android.graphics.Typeface.DEFAULT);
            b.setTextSize(15);
            perf.addView(b);
        }
        android.widget.ScrollView perfScroll = new android.widget.ScrollView(this);
        perfScroll.addView(perf);
        menuPages[2] = perfScroll;

        for (View page : menuPages)
            pages.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(560), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        panelLp.setMargins(dp(16), dp(16), dp(16), dp(16));
        layer.addView(panel, panelLp);

        // the gear: grey, see-through, no key around it
        android.widget.ImageView gear = new android.widget.ImageView(this);
        gearView = gear;
        gear.setImageResource(R.drawable.ic_settings);
        gear.setColorFilter(0xFFD0D0D0);
        gear.setAlpha(0.55f);
        gear.setPadding(dp(6), dp(6), dp(6), dp(6));
        gear.setOnClickListener(v -> showMenu(!menuOpen()));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.END);
        gp.setMargins(0, 0, dp(6), dp(4));
        layer.addView(gear, gp);

        mLayout.addView(layer, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        stylePanel = panel;
        showMenuPage(Math.max(0, Math.min(2, prefs.getInt("menuPage", 1))));
        showMenu(false);
        applyStyle(java.util.Arrays.asList(STYLES).indexOf(style));  // paints the keys and the menu
    }

    // ---- the controls tutorial (gear menu, "Управление"): steps over the screen — the key in question stays lit,
    // the rest is dimmed, a card explains it and a see-through finger shows the gesture, again and again.

    private View enterKey, qKey, rmbKeyView, rKeyView, ctrlKey, shiftKeyView, gearView;
    private static final int G_NONE = 0, G_TAP = 1, G_HOLD = 2, G_SWIPE = 3, G_DRAG = 4, G_STICK = 5, G_UPDOWN = 6,
            G_RING = 7, G_WHEEL = 8, G_QUICKBAR = 9;

    private static final class TutStep {
        final View target, target2;
        final boolean stickZone;
        final int gesture;
        final String title, text, result;  // result: the card a drag shows over its target ("Z", "F", "Ctrl\nShift")

        TutStep(View target, View target2, boolean stickZone, int gesture, String title, String text) {
            this(target, target2, stickZone, gesture, null, title, text);
        }

        TutStep(View target, View target2, boolean stickZone, int gesture, String result, String title, String text) {
            this.target = target; this.target2 = target2; this.stickZone = stickZone; this.gesture = gesture;
            this.result = result; this.title = title; this.text = text;
        }
    }

    private java.util.List<TutStep> tutorialSteps() {
        java.util.List<TutStep> s = new java.util.ArrayList<>();
        s.add(new TutStep(null, null, false, G_NONE, "Обучение управлению",
                "Покажу, что делает каждая кнопка. Полупрозрачный палец повторяет жест. Нажимайте «Далее»."));
        s.add(new TutStep(null, null, true, G_STICK, "Стик — левая часть экрана",
                "Коснитесь и ведите палец: персонаж идёт (W A S D). Центр стика — там, где коснулись. Уведёте палец далеко — центр потянется следом, как на поводке."));
        s.add(new TutStep(null, null, true, G_TAP, "Короткое касание",
                "Короткое касание в зоне стика — обычный клик в этом месте (в режиме «Курсор» — клик под курсором)."));
        s.add(new TutStep(enterKey, null, false, G_TAP, "Enter",
                "Касание — Enter: чат, сесть в транспорт."));
        s.add(new TutStep(enterKey, null, false, G_SWIPE, "Enter → Tab",
                "Удержание или свайп вправо — Tab. Им закрываются подсказки игры."));
        s.add(new TutStep(qKey, null, false, G_TAP, "Q — пипетка",
                "Касание — Q: взять в руку такой же предмет, как под курсором, или убрать предмет из руки."));
        s.add(new TutStep(qKey, null, false, G_SWIPE, "Q → E — инвентарь",
                "Удержание или свайп вправо — E: открыть и закрыть инвентарь."));
        s.add(new TutStep(rmbKeyView, null, false, G_TAP, "ПКМ — правая кнопка мыши",
                "Режим «Тач»: коснитесь ПКМ, затем места в игре — там будет правый клик (добыча, отмена). Режим «Курсор»: ПКМ держит правую кнопку под курсором."));
        s.add(new TutStep(rmbKeyView, null, false, G_STICK, "ПКМ на бегу",
                "Удержание и движение пальцем — палец работает как стик. В режиме «Курсор» правая кнопка при этом держится: можно добывать и бежать."));
        s.add(new TutStep(rKeyView, null, false, G_TAP, "R — поворот",
                "Касание — повернуть предмет в руке. Можно касаться несколько раз подряд."));
        s.add(new TutStep(rKeyView, null, false, G_WHEEL, "R → колесо",
                "Удержание — рядом появится колесо: палец вверх — приблизить, вниз — отдалить."));
        s.add(new TutStep(altKey, null, false, G_TAP, "Alt",
                "Касание — Alt: подробный режим (значки рецептов и содержимого над машинами)."));
        s.add(new TutStep(altKey, null, false, G_HOLD, "Alt → Пробел — стрельба",
                "Удержание на месте — Пробел: стрелять, пока держите."));
        s.add(new TutStep(altKey, null, false, G_STICK, "Стрельба на бегу",
                "Удержание и движение пальцем — стрельба и бег одним пальцем: палец работает как стик."));
        s.add(new TutStep(shiftKeyView, null, false, G_HOLD, "Shift",
                "Держите Shift и кликайте другим пальцем: перенос стака, призраки, копирование настроек машин."));
        s.add(new TutStep(shiftKeyView, null, false, G_RING, "Shift → кольцо",
                "Протяните Shift вправо дальше ширины кнопки — откроется кольцо из 8 кнопок. Выбор — направлением пальца, в центре — отмена. Кнопки кольца пока пустые."));
        s.add(new TutStep(shiftKeyView, ctrlKey, false, G_DRAG, "Z", "Shift на Ctrl — Z",
                "Не отпуская, ведите Shift вниз по «рельсе» до самого Ctrl — держится Z: выбросить предмет из руки. Над Ctrl появится карточка «Z»."));
        s.add(new TutStep(shiftKeyView, altKey, false, G_DRAG, "F", "Shift на Alt — F",
                "Ведите Shift вверх до самого Alt — держится F: подобрать предметы с земли."));
        s.add(new TutStep(ctrlKey, null, false, G_HOLD, "Ctrl",
                "Держите Ctrl и кликайте: загрузить или забрать всё из машины, перенести все предметы одного типа."));
        s.add(new TutStep(ctrlKey, shiftKeyView, false, G_DRAG, "Ctrl\nShift", "Ctrl на Shift — Ctrl + Shift",
                "Ведите Ctrl вверх до самого Shift — держатся Ctrl и Shift: постройка чертежа со сносом деревьев и камней."));
        s.add(new TutStep(ctrlKey, null, false, G_QUICKBAR, "Ctrl → панель предметов",
                "Свайп вправо — снизу по центру поднимается панель быстрого доступа с иконками (поверх панели игры). Касание слота — взять предмет, касание мимо — закрыть."));
        s.add(new TutStep(gearView, null, false, G_TAP, "Шестерёнка",
                "Меню: «Управление» (Тач / Курсор, размер курсора, это обучение), «Темы» кнопок, «Производительность» (HUD)."));
        s.add(new TutStep(null, null, false, G_NONE, "Кнопка «Назад» Android",
                "Жест или кнопка «Назад» — это Esc: меню игры, закрыть окно. Обучение пройдено!"));
        return s;
    }

    private Tutorial tutorial;

    private void startTutorial() {
        if (tutorial == null) {
            tutorial = new Tutorial(this);
            mLayout.addView(tutorial, new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        }
        tutorial.start(tutorialSteps());
    }

    private class Tutorial extends FrameLayout {
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
                int col = i == sel ? style.pTop : style.top;
                scene.setColor(col);
                scene.setAlpha(alpha * (col >>> 24) / 255);
                c.drawCircle(bx, by, rr, scene);
                scene.setStyle(android.graphics.Paint.Style.STROKE);
                scene.setStrokeWidth(dp(i == sel ? 2.5f : 1.5f));
                scene.setColor(i == sel ? style.pStroke : style.stroke);
                scene.setAlpha(alpha);
                c.drawCircle(bx, by, rr, scene);
            }
        }

        /** The R key's wheel beside the key; its ridges move with [offset]. */
        private void drawWheel(android.graphics.Canvas c, android.graphics.RectF key, float alpha, float offset) {
            if (alpha <= 0f) return;
            float w = dp(64f), h = dp(170f), l = key.right + dp(6f), t = key.centerY() - dp(85f);
            scene.setStyle(android.graphics.Paint.Style.FILL);
            scene.setColor(style.wheelBg);
            scene.setAlpha((int) (((style.wheelBg >>> 24)) * alpha));
            c.drawRoundRect(l, t, l + w, t + h, dp(12f), dp(12f), scene);
            scene.setColor(style.wheelFg);
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
            String[] lines = text.split("\n");
            float w = Math.max(dp(56f), lines.length > 1 ? dp(64f) : dp(56f)), h = lines.length > 1 ? dp(52f) : dp(42f);
            float pop = k < 1f ? 0.7f + 0.3f * k + (float) Math.sin(k * Math.PI) * 0.08f : 1f;
            float cx = over.centerX(), bottom = over.top - dp(6f);
            c.save();
            c.scale(pop, pop, cx, bottom);
            scene.setStyle(android.graphics.Paint.Style.FILL);
            scene.setColor(style.pTop);
            c.drawRoundRect(cx - w / 2, bottom - h, cx + w / 2, bottom, dp((float) style.radius), dp((float) style.radius), scene);
            scene.setStyle(android.graphics.Paint.Style.STROKE);
            scene.setStrokeWidth(dp(1.5f));
            scene.setColor(style.pStroke);
            c.drawRoundRect(cx - w / 2, bottom - h, cx + w / 2, bottom, dp((float) style.radius), dp((float) style.radius), scene);
            scene.setStyle(android.graphics.Paint.Style.FILL);
            scene.setColor(style.pText);
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

        Tutorial(android.content.Context c) {
            super(c);
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
            quit.setText("Выйти");
            back = new android.widget.Button(c);
            back.setText("Назад");
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
            count.setText((i + 1) + " / " + steps.size() + (target(s) != null ? "  ·  кнопку в рамке можно попробовать самому" : ""));
            trying = false;
            resumeAt = 0;
            title.setText(s.title);
            body.setText(s.text);
            back.setEnabled(i > 0);
            next.setText(i + 1 < steps.size() ? "Далее" : "Готово");
            t0 = android.os.SystemClock.uptimeMillis();
            if (s.gesture == G_QUICKBAR) {
                String[] names = null;
                try {
                    names = nativeQuickBar();
                } catch (UnsatisfiedLinkError e) {
                    // no game memory reader: empty slots
                }
                for (int k = 0; k < 20; k++)
                    demoIcons[k] = names != null && k < names.length && !names[k].isEmpty() ? itemIcon(names[k]) : null;
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
            if (s.stickZone && stickView != null) return rectOf(stickView);
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
                            scene.setColor(style.pTop);
                            scene.setAlpha(200);
                            c.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, dp((float) style.radius), dp((float) style.radius), scene);
                            scene.setColor(style.pText);
                            scene.setTextSize(dp(14f));
                            scene.setTextAlign(android.graphics.Paint.Align.CENTER);
                            CharSequence label = s.target.getContentDescription() != null ? s.target.getContentDescription() : ((TextView) s.target).getText();
                            c.drawText(String.valueOf(label), x, y + dp(5f), scene);
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

    // ---- on-screen keys: held while the finger is down. All keys one size.

    private LinearLayout.LayoutParams keySize() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(42));
        p.setMargins(dp(3), dp(1), dp(3), dp(2));  // 3 dp between keys
        return p;
    }

    /**
     * RMB. Touch mode: a tap arms a right click for the next touch on the game. Cursor mode: the right button is
     * held at the cursor (pressed 120 ms after the touch; a quicker tap is a click). Held and moved, the finger
     * steers like the stick (W A S D around the touch, on a leash) — in cursor mode with the right button still held:
     * mining while running.
     */
    private TextView rmbKey() {
        MarkedKey rmb = new MarkedKey(this);
        rmb.marks = MARK_CROSS;  // it steers like the stick
        rmb.setText("ПКМ");
        rmb.setGravity(Gravity.CENTER);
        paintKey(rmb, false);
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final float[] st = new float[2];  // the stick's centre (raw)
        final boolean[] pressed = new boolean[1], steer = new boolean[1], held = new boolean[4];  // held: W A S D
        final int[] keys = {KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D};
        final View[] key = new View[1];
        final Runnable press = () -> {
            pressed[0] = true;
            paintKey(key[0], true);
            mouseButton(MotionEvent.BUTTON_SECONDARY, true);
        };
        final java.util.function.BiConsumer<Integer, Boolean> set = (i, on) -> {
            if (held[i] == on) return;
            held[i] = on;
            if (on) SDLActivity.onNativeKeyDown(keys[i]); else SDLActivity.onNativeKeyUp(keys[i]);
        };
        rmb.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    key[0] = v;
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    pressed[0] = steer[0] = false;
                    if (cursorMode) h.postDelayed(press, 120);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1], len = (float) Math.hypot(dx, dy);
                    if (!steer[0] && len > dp(14)) {
                        steer[0] = true;
                        if (cursorMode && !pressed[0]) {  // moved before the 120 ms: the right button now
                            h.removeCallbacks(press);
                            press.run();
                        }
                        if (!cursorMode) paintKey(v, true);
                    }
                    if (!steer[0]) return true;
                    float r = dp(60);
                    if (len > r) {  // on a leash
                        st[0] += dx / len * (len - r);
                        st[1] += dy / len * (len - r);
                        dx = e.getRawX() - st[0];
                        dy = e.getRawY() - st[1];
                        len = r;
                    }
                    if (stickView != null) stickView.external(true, st[0], st[1], st[0] + dx, st[1] + dy);
                    boolean live = len > dp(14);
                    double a = Math.atan2(dy, dx), cos = Math.cos(a), sin = Math.sin(a);
                    set.accept(0, live && sin < -0.38);  // up = W
                    set.accept(2, live && sin > 0.38);   // down = S
                    set.accept(1, live && cos < -0.38);  // left = A
                    set.accept(3, live && cos > 0.38);   // right = D
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(press);
                    for (int i = 0; i < 4; i++) set.accept(i, false);
                    if (steer[0] && stickView != null) stickView.external(false, 0, 0, 0, 0);
                    if (cursorMode) {
                        if (!pressed[0] && !steer[0] && e.getActionMasked() == MotionEvent.ACTION_UP) press.run();  // a quick tap: a click
                        paintKey(v, false);
                        mouseButton(0, false);
                    } else if (steer[0]) {
                        paintKey(v, rightClickArmed);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {  // touch mode: arm for the next touch
                        rightClickArmed = !rightClickArmed;
                        paintKey(v, rightClickArmed);
                    }
                    pressed[0] = steer[0] = false;
                    return true;
            }
            return true;
        });
        rmbButton = rmb;
        return rmb;
    }

    /** A button of the ring (Shift swiped right) was chosen (0 = up, then clockwise), or -1. No actions yet. */
    private void onRadialChoice(int i) {
        if (i >= 0) android.util.Log.i("FNative", "radial button " + i);
    }

    private RadialMenu radial;

    /**
     * The RMB ring: 8 round buttons around a centre (raw screen points in, overlay points drawn). The finger's
     * direction picks one (none in the middle); beyond the ring the centre follows the finger on a leash.
     */
    private class RadialMenu extends View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float cx, cy;  // raw
        private int sel = -1;
        private float shown;  // 0..1, opening animation
        private android.animation.ValueAnimator anim;

        RadialMenu(android.content.Context c) {
            super(c);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            setVisibility(GONE);
        }

        float ring() { return dp(78f); }

        void open(float rawX, float rawY) {
            cx = rawX;
            cy = rawY;
            sel = -1;
            setVisibility(VISIBLE);
            animateTo(1f, null);
        }

        void close() {
            animateTo(0f, () -> setVisibility(GONE));
        }

        private void animateTo(float to, Runnable end) {
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(shown, to);
            anim.setDuration(to > shown ? 180 : 140);
            anim.setInterpolator(to > shown ? new android.view.animation.OvershootInterpolator(1.3f)
                    : new android.view.animation.AccelerateInterpolator());
            anim.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            if (end != null) anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (shown == 0f) end.run();
                }
            });
            anim.start();
        }

        void move(float rawX, float rawY) {
            float dx = rawX - cx, dy = rawY - cy, len = (float) Math.hypot(dx, dy), leash = ring() + dp(30f);
            if (len > leash) {  // pulled along, as the stick
                cx += dx / len * (len - leash);
                cy += dy / len * (len - leash);
                dx = rawX - cx;
                dy = rawY - cy;
                len = leash;
            }
            int s = -1;
            if (len > dp(30f)) {
                double a = Math.toDegrees(Math.atan2(dx, -dy));  // 0 = up, clockwise
                s = (int) Math.round(((a + 360) % 360) / 45.0) % 8;
            }
            sel = s;
            invalidate();  // every move: the centre may have been pulled along
        }

        int selected() { return sel; }
        float centreRawX() { return cx; }
        float centreRawY() { return cy; }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (shown <= 0f) return;
            int[] at = new int[2];
            getLocationOnScreen(at);
            float ox = cx - at[0], oy = cy - at[1], r = ring() * shown, br = dp(24f) * Math.min(1f, shown);
            int alpha = (int) (255 * Math.min(1f, shown));
            // a faint disc behind the buttons
            fill.setColor(0x40000000);
            fill.setAlpha((int) (0x40 * Math.min(1f, shown)));
            c.drawCircle(ox, oy, r + br + dp(6f), fill);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45 - 90);
                float bx = ox + r * (float) Math.cos(a), by = oy + r * (float) Math.sin(a);
                boolean on = i == sel;
                fill.setColor(on ? style.pTop : style.top);
                fill.setAlpha(alpha * ((on ? style.pTop : style.top) >>> 24) / 255);
                c.drawCircle(bx, by, on ? br * 1.15f : br, fill);
                edge.setColor(on ? style.pStroke : style.stroke);
                edge.setAlpha(alpha);
                edge.setStrokeWidth(dp(on ? 2.5f : 1.5f));
                c.drawCircle(bx, by, on ? br * 1.15f : br, edge);
            }
        }
    }

    // R + wheel in one key: a tap (or several) rotates (R); held (or moved up/down) it becomes the wheel:
    // a wheel appears next to the key and scrolls with the finger (one notch per 24 dp; up = zoom in).
    // The key shows "R" with a small "+" above and "−" below on its right side.
    private Wheel wheelPopup;
    private static final int HOLD_MS = 300;

    private TextView rWheelKey() {
        TextView k = new TextView(this) {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                super.onDraw(c);
                p.setColor(getCurrentTextColor());
                p.setTextSize(dp(13f));
                p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                float x = getWidth() - dp(9f);
                c.drawText("+", x, dp(14f), p);
                c.drawText("\u2212", x, getHeight() - dp(5f), p);
            }
        };
        k.setText("R");
        k.setGravity(Gravity.CENTER);
        paintKey(k, false);
        final float[] state = new float[3];  // last raw Y, accumulated offset, raw Y at the touch
        final boolean[] wheel = new boolean[1];
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final View[] key = new View[1];
        final Runnable startWheel = () -> {
            if (wheel[0]) return;
            wheel[0] = true;
            View v = key[0];
            int[] at = new int[2], root = new int[2];
            v.getLocationOnScreen(at);
            ((View) wheelPopup.getParent()).getLocationOnScreen(root);
            wheelPopup.setX(at[0] - root[0] + v.getWidth() + dp(6));
            wheelPopup.setY(at[1] - root[1] + v.getHeight() / 2f - dp(85));
            wheelPopup.offset = 0;
            showWheel(true);
        };
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    key[0] = v;
                    paintKey(v, true);
                    wheel[0] = false;
                    state[0] = state[2] = e.getRawY();
                    state[1] = 0;
                    h.postDelayed(startWheel, HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // moved up/down before the hold ends: the wheel at once
                    if (!wheel[0] && Math.abs(e.getRawY() - state[2]) > dp(12)) {
                        h.removeCallbacks(startWheel);
                        startWheel.run();
                    }
                    if (!wheel[0]) return true;
                    float dy = e.getRawY() - state[0];
                    state[0] = e.getRawY();
                    state[1] += dy;
                    wheelPopup.offset += dy;
                    float step = dp(24);
                    while (state[1] <= -step) { wheelNotch(1); state[1] += step; }  // finger up = wheel up
                    while (state[1] >= step) { wheelNotch(-1); state[1] -= step; }
                    wheelPopup.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(startWheel);
                    paintKey(v, false);
                    if (wheel[0]) {
                        showWheel(false);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        // a tap: rotate
                        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_R);
                        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_R);
                    }
                    wheel[0] = false;
                    return true;
            }
            return true;
        });
        return k;
    }

    /**
     * Two keys in one: a tap sends [tapCode]; held (HOLD_MS) it holds [holdCode] until released. The second key's
     * name is shown small in the top-right corner (Enter, held = Tab: the game's tips close with Tab; Q, held = E; Alt, held = Space).
     */
    static final int MARK_UP = 1, MARK_DOWN = 2, MARK_CROSS = 4, MARK_RIGHT = 8, MARK_DRUM = 16;

    /**
     * A key with small hints in the label colour: a word in the top-right corner (what holding gives) and marks on
     * the right side: arrows up / down (where the key can be dragged), a cross (the key steers like the stick).
     */
    private class MarkedKey extends TextView {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        String corner;
        int marks;

        MarkedKey(android.content.Context c) {
            super(c);
        }

        private void triangle(android.graphics.Canvas c, float x, float y, float s, int dir) {  // dir 0 up 1 down 2 left 3 right
            path.reset();
            switch (dir) {
                case 0: path.moveTo(x, y - s); path.lineTo(x + s, y + s * 0.6f); path.lineTo(x - s, y + s * 0.6f); break;
                case 1: path.moveTo(x, y + s); path.lineTo(x + s, y - s * 0.6f); path.lineTo(x - s, y - s * 0.6f); break;
                case 2: path.moveTo(x - s, y); path.lineTo(x + s * 0.6f, y - s); path.lineTo(x + s * 0.6f, y + s); break;
                default: path.moveTo(x + s, y); path.lineTo(x - s * 0.6f, y - s); path.lineTo(x - s * 0.6f, y + s); break;
            }
            path.close();
            c.drawPath(path, p);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            p.setColor(getCurrentTextColor());
            if (corner != null) {
                p.setTextSize(dp(9f));
                p.setTextAlign(android.graphics.Paint.Align.RIGHT);
                c.drawText(corner, getWidth() - dp(4f), dp(11f), p);
            }
            float x = getWidth() - dp(8f), mid = getHeight() / 2f, s = dp(3f);
            p.setAlpha(210);
            if ((marks & MARK_UP) != 0 && (marks & MARK_DOWN) != 0) {
                float lift = (marks & MARK_DRUM) != 0 ? dp(5f) : 0;  // clear of the drum in the corner
                triangle(c, x, mid - dp(6f) - lift, s, 0);
                triangle(c, x, mid + dp(6f) - lift, s, 1);
            } else if ((marks & MARK_UP) != 0) {
                triangle(c, x, mid - dp(7f), s, 0);  // a little high: clear of the label
            } else if ((marks & MARK_DOWN) != 0) {
                triangle(c, x, mid, s, 1);
            }
            if ((marks & MARK_RIGHT) != 0) triangle(c, x, mid + dp(4f), s, 3);  // swipe right = the held action
            if ((marks & MARK_DRUM) != 0) {  // a revolver drum: a swipe right opens the ring of buttons
                // in the bottom-right corner, clear of the label
                float dx = getWidth() - dp(8.5f), dy = getHeight() - dp(8.5f), r = dp(5.5f);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.2f));
                c.drawCircle(dx, dy, r, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(i * 60 - 90);
                    c.drawCircle(dx + r * 0.58f * (float) Math.cos(a), dy + r * 0.58f * (float) Math.sin(a), dp(1.1f), p);
                }
                c.drawCircle(dx, dy, dp(0.8f), p);
            }
            if ((marks & MARK_CROSS) != 0) {  // a small d-pad, low on the right (the corner word is above)
                float cy = getHeight() - dp(11f), d = dp(4.5f), t = dp(2.2f);
                triangle(c, x, cy - d, t, 0);
                triangle(c, x, cy + d, t, 1);
                triangle(c, x - d, cy, t, 2);
                triangle(c, x + d, cy, t, 3);
            }
            p.setAlpha(255);
        }
    }

    private TextView tapHoldKey(String label, int tapCode, int holdCode, String holdMark) {
        MarkedKey k = new MarkedKey(this);
        k.corner = holdMark;
        k.marks = MARK_RIGHT;  // a swipe right gives the held key at once
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        paintKey(k, false);
        final boolean[] held = new boolean[1];
        final float[] downX = new float[1];
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable hold = () -> {
            held[0] = true;
            SDLActivity.onNativeKeyDown(holdCode);
        };
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    paintKey(v, true);
                    held[0] = false;
                    downX[0] = e.getRawX();
                    h.postDelayed(hold, HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // a swipe right, as Ctrl's: the key slides right on a rail; past its width the held key comes
                    // and the key springs back (the held key stays until the finger is up)
                    if (held[0]) return true;
                    float dx = e.getRawX() - downX[0];
                    if (dx > dp(10)) h.removeCallbacks(hold);  // sliding: not a hold
                    v.setTranslationX(Math.max(0, Math.min(dx, v.getWidth() * 1.1f)));
                    if (v.getTranslationX() >= v.getWidth()) {
                        hold.run();
                        v.animate().translationX(0).setDuration(260)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(hold);
                    paintKey(v, false);
                    boolean slid = v.getTranslationX() > dp(8);  // a swipe not pulled through: nothing
                    if (v.getTranslationX() != 0)
                        v.animate().translationX(0).setDuration(220)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                    if (slid && !held[0]) {
                        held[0] = false;
                        return true;
                    }
                    if (held[0]) {
                        SDLActivity.onNativeKeyUp(holdCode);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        SDLActivity.onNativeKeyDown(tapCode);
                        SDLActivity.onNativeKeyUp(tapCode);
                    }
                    held[0] = false;
                    return true;
            }
            return true;
        });
        return k;
    }

    /**
     * Alt / Space / stick in one key: a tap is Alt; held still, Space (shooting) until released; pressed and moved
     * (at once or after the hold), Space stays held and the finger steers like the stick (W A S D around the point
     * of the touch; the finger may go anywhere on the screen). Shooting while running needs one finger.
     */
    private TextView altSpaceKey() {
        TextView k = tapHoldKey("Alt", KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SPACE, "Пробел");
        ((MarkedKey) k).marks = MARK_CROSS;  // it steers like the stick
        final float[] st = new float[2];  // raw point of the touch
        final boolean[] space = new boolean[1], steer = new boolean[1], held = new boolean[4];  // held: W A S D
        final int[] keys = {KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable hold = () -> {
            space[0] = true;
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_SPACE);
        };
        final java.util.function.BiConsumer<Integer, Boolean> set = (i, on) -> {
            if (held[i] == on) return;
            held[i] = on;
            if (on) SDLActivity.onNativeKeyDown(keys[i]); else SDLActivity.onNativeKeyUp(keys[i]);
        };
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    paintKey(v, true);
                    space[0] = steer[0] = false;
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    h.postDelayed(hold, HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1], len = (float) Math.hypot(dx, dy);
                    if (!steer[0] && len > dp(14)) {
                        // moved: Space held at once, the finger is the stick from here on
                        h.removeCallbacks(hold);
                        if (!space[0]) hold.run();
                        steer[0] = true;
                    }
                    if (!steer[0]) return true;
                    float r = dp(60);
                    if (len > r) {  // on a leash, as the stick: the centre follows the finger one radius behind
                        st[0] += dx / len * (len - r);
                        st[1] += dy / len * (len - r);
                        dx = e.getRawX() - st[0];
                        dy = e.getRawY() - st[1];
                        len = r;
                    }
                    float kx = st[0] + dx, ky = st[1] + dy;
                    if (stickView != null) stickView.external(true, st[0], st[1], kx, ky);
                    boolean live = len > dp(14);
                    double a = Math.atan2(dy, dx), cos = Math.cos(a), sin = Math.sin(a);
                    set.accept(0, live && sin < -0.38);  // up = W
                    set.accept(2, live && sin > 0.38);   // down = S
                    set.accept(1, live && cos < -0.38);  // left = A
                    set.accept(3, live && cos > 0.38);   // right = D
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(hold);
                    paintKey(v, false);
                    for (int i = 0; i < 4; i++) set.accept(i, false);
                    if (steer[0] && stickView != null) stickView.external(false, 0, 0, 0, 0);
                    if (space[0]) {
                        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SPACE);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ALT_LEFT);  // a tap: Alt
                        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ALT_LEFT);
                    }
                    space[0] = steer[0] = false;
                    return true;
            }
            return true;
        });
        return k;
    }

    /** The wheel comes in fading and growing from the key's side, and fades out. */
    private void showWheel(boolean on) {
        wheelPopup.animate().cancel();
        wheelPopup.setPivotX(0);
        wheelPopup.setPivotY(dp(85));
        if (on) {
            wheelPopup.setAlpha(0f);
            wheelPopup.setScaleX(0.6f);
            wheelPopup.setScaleY(0.6f);
            wheelPopup.setVisibility(View.VISIBLE);
            wheelPopup.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        } else {
            wheelPopup.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(140)
                    .withEndAction(() -> wheelPopup.setVisibility(View.GONE)).start();
        }
    }

    // ---- the quick bar panel (Ctrl swiped right): the game's two visible quick bar rows with the items' icons,
    // read from the game's memory (libfnative quickbar.c). A tap on a slot picks it (row 1: keys 1…0, row 2:
    // Ctrl + 1…0, which the launcher binds when the player has not); a tap beside the panel closes it.

    private View qbLayer, qbPanel;
    private final android.widget.ImageView[] qbIcons = new android.widget.ImageView[20];
    private final TextView[] qbNames = new TextView[20];
    private final java.util.Map<String, android.graphics.Bitmap> qbIconCache = new java.util.HashMap<>();

    /** The game folder: args[0] is <game>/bin/arm64/factorio. */
    private File gameDir() {
        String[] args = getArguments();
        if (args.length == 0) return null;
        File bin = new File(args[0]).getParentFile();
        return bin != null && bin.getParentFile() != null ? bin.getParentFile().getParentFile() : null;
    }

    /** The item's icon from the game's files (first mip level, 64 x 64), or null. */
    private android.graphics.Bitmap itemIcon(String name) {
        if (qbIconCache.containsKey(name)) return qbIconCache.get(name);
        android.graphics.Bitmap icon = null;
        File game = gameDir();
        if (game != null) for (String mod : new String[] {"base", "space-age", "quality", "elevated-rails", "core"}) {
            File f = new File(game, "data/" + mod + "/graphics/icons/" + name + ".png");
            if (!f.isFile()) continue;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (b != null) {
                int side = Math.min(b.getWidth(), b.getHeight());
                icon = side < b.getWidth() || side < b.getHeight() ? android.graphics.Bitmap.createBitmap(b, 0, 0, side, side) : b;
            }
            break;
        }
        qbIconCache.put(name, icon);
        return icon;
    }

    private android.graphics.drawable.Drawable qbSlotBackground() {
        // like the game's slots: dark, a little sunk (darker top edge)
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF262626, 0xFF3A3A3A});
        g.setCornerRadius(dp(2f));
        g.setStroke(dp(1), 0xFF141414);
        return g;
    }

    private void addQuickBarPanel(FrameLayout overlay) {
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);  // a tap beside the panel closes it, the game gets nothing
        layer.setOnClickListener(v -> closeQuickBar());
        layer.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setColor(0xF0313031);  // the game's frame
        frame.setCornerRadius(dp(4f));
        frame.setStroke(dp(1), 0xFF0E0E0E);
        panel.setBackground(frame);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 10; i++) {
                final int n = r * 10 + i;
                FrameLayout slot = new FrameLayout(this);
                slot.setBackground(qbSlotBackground());
                slot.setOnClickListener(v -> pickQuickBarSlot(n));
                android.widget.ImageView icon = new android.widget.ImageView(this);
                icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                icon.setPadding(dp(4), dp(4), dp(4), dp(4));
                slot.addView(icon, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                TextView name = new TextView(this);  // no icon file: the item's name, small
                name.setTextColor(0xFFE0E0E0);
                name.setTextSize(7);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(3);
                slot.addView(name, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                TextView key = new TextView(this);  // the slot's key, as on the game's bar
                key.setText(String.valueOf((i + 1) % 10));
                key.setTextColor(0xB0FFFFFF);
                key.setTextSize(8);
                key.setPadding(dp(2), 0, 0, 0);
                slot.addView(key, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
                qbIcons[n] = icon;
                qbNames[n] = name;
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(40), dp(40));
                sp.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(slot, sp);
            }
            panel.addView(row);
        }
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.setMargins(0, 0, 0, dp(2));  // bottom centre, over the game's own quick bar
        layer.addView(panel, pp);
        overlay.addView(layer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        qbLayer = layer;
        qbPanel = panel;
    }

    private void openQuickBar() {
        String[] names = null;
        try {
            names = nativeQuickBar();
        } catch (UnsatisfiedLinkError e) {
            // an older libfnative: empty slots
        }
        for (int i = 0; i < 20; i++) {
            String n = names != null && i < names.length ? names[i] : "";
            android.graphics.Bitmap b = n.isEmpty() ? null : itemIcon(n);
            qbIcons[i].setImageBitmap(b);
            qbNames[i].setText(b == null ? n.replace('-', ' ') : "");
        }
        qbLayer.setVisibility(View.VISIBLE);
        qbPanel.animate().cancel();
        qbPanel.setAlpha(0f);
        qbPanel.post(() -> {
            qbPanel.setTranslationX(0);
            qbPanel.setTranslationY(qbPanel.getHeight() + dp(8));  // up from below the screen's edge
            qbPanel.setAlpha(1f);
            qbPanel.animate().translationY(0).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        });
    }

    private void closeQuickBar() {
        qbPanel.animate().cancel();
        qbPanel.animate().translationY(qbPanel.getHeight() + dp(8)).setDuration(180)
                .withEndAction(() -> qbLayer.setVisibility(View.GONE)).start();
    }

    /** Slot n (0..19): row 1 = keys 1…0, row 2 = Ctrl + 1…0; then the panel closes. */
    private void pickQuickBarSlot(int n) {
        int i = n % 10, key = i == 9 ? KeyEvent.KEYCODE_0 : KeyEvent.KEYCODE_1 + i;
        boolean second = n >= 10;
        if (second) SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_CTRL_LEFT);
        SDLActivity.onNativeKeyDown(key);
        SDLActivity.onNativeKeyUp(key);
        if (second) SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_CTRL_LEFT);
        closeQuickBar();
    }

    private TextView altKey;  // Shift dragged onto it holds F
    private TextView comboCard;  // over the key a dragged key lies on: what is held ("Z", "F", "Ctrl / Shift")

    /** The result card pops up above [target], in the key style, pressed look. */
    private void showComboCard(View target, String text) {
        if (comboCard == null) return;
        comboCard.setText(text);
        comboCard.setContentDescription(text);  // paintKeyAs takes the label from here
        paintKeyAs(comboCard, style, true);
        comboCard.setTextSize(text.contains("\n") ? 12 : 16);
        comboCard.setPadding(dp(8), dp(4), dp(8), dp(4));
        comboCard.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.max(comboCard.getMeasuredWidth(), dp(56)), h = Math.max(comboCard.getMeasuredHeight(), dp(42));
        int[] at = new int[2], root = new int[2];
        target.getLocationOnScreen(at);
        ((View) comboCard.getParent()).getLocationOnScreen(root);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) comboCard.getLayoutParams();
        lp.width = w;
        lp.height = h;
        comboCard.setLayoutParams(lp);
        comboCard.setX(at[0] - root[0] + (target.getWidth() - w) / 2f);
        comboCard.setY(at[1] - root[1] - h - dp(6));
        comboCard.animate().cancel();
        comboCard.setPivotX(w / 2f);
        comboCard.setPivotY(h);
        comboCard.setAlpha(0f);
        comboCard.setScaleX(0.7f);
        comboCard.setScaleY(0.7f);
        comboCard.setVisibility(View.VISIBLE);
        comboCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
    }

    private void hideComboCard() {
        if (comboCard == null || comboCard.getVisibility() != View.VISIBLE) return;
        comboCard.animate().cancel();
        comboCard.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(120)
                .withEndAction(() -> comboCard.setVisibility(View.GONE)).start();
    }

    /**
     * Shift and Ctrl: each held while the finger is down, and dragged (finger kept down) the key follows the finger:
     * Shift onto Ctrl = Z (drop), Shift onto Alt = F (pick up) — Shift itself is let go meanwhile; Ctrl onto Shift =
     * Ctrl + Shift (blueprints, forced building). Off the other key, its action stops; released, all are, and the
     * key springs back to its place.
     */
    private TextView[] modifierPair() {
        MarkedKey shift = new MarkedKey(this), ctrl = new MarkedKey(this);
        shift.marks = MARK_UP | MARK_DOWN | MARK_DRUM;  // onto Alt (F) or onto Ctrl (Z); swipe right: the ring
        ctrl.marks = MARK_UP | MARK_RIGHT;  // onto Shift (Ctrl + Shift), swipe right (the quick bar panel)
        shift.setText("Shift");
        ctrl.setText("Ctrl");
        TextView[] pair = {shift, ctrl};
        for (int i = 0; i < 2; i++) {
            final TextView self = pair[i];
            final boolean isShift = i == 0;
            final int own = isShift ? KeyEvent.KEYCODE_SHIFT_LEFT : KeyEvent.KEYCODE_CTRL_LEFT;
            self.setGravity(Gravity.CENTER);
            paintKey(self, false);
            final float[] start = new float[2];
            final int[] over = {-1};  // index into the targets below, -1: none
            final boolean[] qbOpened = new boolean[1];  // Ctrl swiped right: the quick bar panel
            final int[] axis = {0};  // rail: 0 not yet, 1 up/down, 2 right
            final boolean[] ringOpen = new boolean[1], shiftUp = new boolean[1];  // Shift's right rail
            self.setOnTouchListener((v, e) -> {
                // the keys to drop onto and what they hold: {view, key code, whether this key stays held}
                View[] views = isShift ? new View[] {ctrl, altKey} : new View[] {shift};
                int[] codes = isShift ? new int[] {KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_F} : new int[] {KeyEvent.KEYCODE_SHIFT_LEFT};
                String[] results = isShift ? new String[] {"Z", "F"} : new String[] {"Ctrl\nShift"};
                boolean keepOwn = !isShift;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        start[0] = e.getRawX() - v.getTranslationX();
                        start[1] = e.getRawY() - v.getTranslationY();
                        over[0] = -1;
                        qbOpened[0] = false;
                        ringOpen[0] = shiftUp[0] = false;
                        axis[0] = 0;
                        v.setAlpha(1f);
                        v.setScaleX(1f);
                        v.setScaleY(1f);
                        v.setTranslationZ(dp(8));  // drawn over the other keys while dragged
                        paintKey(v, true);
                        SDLActivity.onNativeKeyDown(own);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (qbOpened[0]) return true;
                        if (ringOpen[0]) {  // the ring follows the finger
                            radial.move(e.getRawX(), e.getRawY());
                            return true;
                        }
                        // on rails: Shift up/down (to Alt / to Ctrl) or right (the ring); Ctrl up (to Shift) or
                        // right (the quick bar panel); the first clear movement picks the rail
                        float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                        if (axis[0] == 0 && Math.hypot(dx, dy) > dp(10))
                            axis[0] = dx > Math.abs(dy) ? 2 : 1;
                        float up = 0, downPitch = 0;  // distances to the key above / below (key centre to centre)
                        View above = isShift ? altKey : shift, below = isShift ? ctrl : null;
                        if (above != null) up = v.getTop() - above.getTop();
                        if (below != null) downPitch = below.getTop() - v.getTop();
                        if (axis[0] == 1) {
                            v.setTranslationX(0);
                            v.setTranslationY(Math.max(-up, Math.min(dy, downPitch)));
                        } else if (axis[0] == 2) {
                            v.setTranslationY(0);
                            v.setTranslationX(Math.max(0, Math.min(dx, v.getWidth() * 1.1f)));
                            if (isShift) {
                                // Shift's right rail: the key shrinks over the second half of its width, then the ring
                                if (!shiftUp[0]) {  // sliding to the ring: Shift is not held meanwhile
                                    shiftUp[0] = true;
                                    SDLActivity.onNativeKeyUp(own);
                                }
                                float tx = Math.min(v.getTranslationX(), v.getWidth()), half = v.getWidth() / 2f;
                                float k = tx <= half ? 1f : 1f - 0.45f * (tx - half) / half;
                                v.setScaleX(k);
                                v.setScaleY(k);
                                if (dx > v.getWidth()) {
                                    ringOpen[0] = true;
                                    v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150).start();
                                    radial.open(e.getRawX(), e.getRawY());
                                }
                                return true;
                            }
                        }
                        // Ctrl along the right rail past one key width: Ctrl let go, the quick bar panel comes up
                        if (axis[0] == 2 && v.getTranslationX() >= v.getWidth()) {
                            qbOpened[0] = true;
                            SDLActivity.onNativeKeyUp(own);
                            paintKey(v, false);
                            v.animate().translationX(0).translationY(0).translationZ(0).setDuration(260)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                            openQuickBar();
                            return true;
                        }
                        // a combo only once the key is (nearly) on the other key: 80 % of the way
                        int now = -1;
                        float ty = v.getTranslationY();
                        if (axis[0] == 1 && up > 0 && ty <= -0.8f * up) now = isShift ? 1 : 0;  // Shift → Alt (F), Ctrl → Shift
                        if (axis[0] == 1 && downPitch > 0 && ty >= 0.8f * downPitch) now = 0;  // Shift → Ctrl (Z)
                        if (now != over[0]) {
                            if (over[0] >= 0) {
                                SDLActivity.onNativeKeyUp(codes[over[0]]);
                                paintKey(views[over[0]], false);
                                if (!keepOwn) SDLActivity.onNativeKeyDown(own);  // back to plain Shift
                            }
                            over[0] = now;
                            if (now >= 0) {
                                if (!keepOwn) SDLActivity.onNativeKeyUp(own);
                                paintKey(views[now], true);
                                SDLActivity.onNativeKeyDown(codes[now]);
                                showComboCard(views[now], results[now]);
                            } else {
                                hideComboCard();
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (qbOpened[0]) {  // the panel is open; Ctrl was let go already
                            qbOpened[0] = false;
                            return true;
                        }
                        if (ringOpen[0] || shiftUp[0]) {  // Shift's right rail: the ring (or a swipe not pulled through)
                            if (ringOpen[0]) {
                                int chosen = e.getActionMasked() == MotionEvent.ACTION_UP ? radial.selected() : -1;
                                radial.close();
                                onRadialChoice(chosen);
                            }
                            ringOpen[0] = shiftUp[0] = false;
                            paintKey(v, false);
                            v.animate().translationX(0).translationY(0).translationZ(0).scaleX(1f).scaleY(1f).alpha(1f)
                                    .setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
                            return true;
                        }
                        if (over[0] >= 0) {
                            SDLActivity.onNativeKeyUp(codes[over[0]]);
                            paintKey(views[over[0]], false);
                            if (keepOwn) SDLActivity.onNativeKeyUp(own);
                        } else {
                            SDLActivity.onNativeKeyUp(own);
                        }
                        over[0] = -1;
                        hideComboCard();
                        paintKey(v, false);
                        v.animate().translationX(0).translationY(0).translationZ(0).setDuration(260)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                        return true;
                }
                return true;
            });
        }
        return pair;
    }

    private static void wheelNotch(float amount) {
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0, amount, false);
    }

    private boolean rightClickArmed, rightClickDown;
    private TextView rmbButton;

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView key(String label, int keycode) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        paintKey(b, false);
        b.setOnTouchListener((View v, MotionEvent e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    paintKey(v, true);
                    SDLActivity.onNativeKeyDown(keycode);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    paintKey(v, false);
                    SDLActivity.onNativeKeyUp(keycode);
                    return true;
            }
            return true;
        });
        return b;
    }

    /**
     * Dynamic stick: a touch in the stick zone (left 40% of the screen, below
     * the top 25%) becomes the stick centre; the finger direction holds W/A/S/D
     * (diagonals hold two). A short tap without movement is a normal click.
     * The view covers only that zone: Android gives every further finger over a
     * view that already holds one to that view without asking, so a full-screen
     * stick took the cursor finger while held (v0.53 and before).
     */
    private static final long STICK_SHOW_MS = 100;

    private class Stick extends View {
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                knob = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float cx, cy, kx, ky;
        private int pointer = -1;
        private long downTime;
        private boolean moved;
        private final boolean[] held = new boolean[4];  // W A S D
        private final int[] keys = {KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D};

        Stick(android.content.Context c) {
            super(c);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
        }

        private boolean inZone(float x, float y) {
            return x >= 0 && y >= 0 && x < getWidth() && y < getHeight();  // the view is the zone
        }

        private void set(int i, boolean on) {
            if (held[i] == on) return;
            held[i] = on;
            if (on) SDLActivity.onNativeKeyDown(keys[i]); else SDLActivity.onNativeKeyUp(keys[i]);
        }

        private void releaseAll() {
            for (int i = 0; i < 4; i++) set(i, false);
        }

        private void steer(float x, float y) {
            float dx = x - cx, dy = y - cy, r = dp(60), dead = dp(14);
            float len = (float) Math.hypot(dx, dy);
            if (len > r) {
                // on a leash: the centre follows the finger, staying one radius behind it, so turning
                // back needs only about a radius of movement, not the whole way the finger went
                cx += dx / len * (len - r);
                cy += dy / len * (len - r);
                dx = x - cx;
                dy = y - cy;
                len = r;
            }
            if (len > dead) moved = true;
            kx = cx + (len > r ? dx / len * r : dx);
            ky = cy + (len > r ? dy / len * r : dy);
            if (len < dead) { releaseAll(); return; }
            // 8 directions: a key is held when the angle is within 67.5 deg of its axis
            double a = Math.atan2(dy, dx);
            double cos = Math.cos(a), sin = Math.sin(a);
            set(0, sin < -0.38);  // up = W
            set(2, sin > 0.38);   // down = S
            set(1, cos < -0.38);  // left = A
            set(3, cos > 0.38);   // right = D
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int idx = e.getActionIndex();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (rightClickArmed && pointer < 0) return false;  // RMB mode takes the next touch
                    // Only one finger is the stick; any other touch goes on to the
                    // views below (cursor pad, game) — also while the stick is held.
                    if (pointer >= 0 || !inZone(e.getX(idx), e.getY(idx))) return false;
                    pointer = e.getPointerId(idx);
                    cx = kx = e.getX(idx);
                    cy = ky = e.getY(idx);
                    downTime = e.getEventTime();
                    moved = false;
                    postInvalidateDelayed(STICK_SHOW_MS);  // the ring shows after 100 ms: a quick tap does not flash it
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pointer < 0) return false;
                    int i = e.findPointerIndex(pointer);
                    if (i >= 0) { steer(e.getX(i), e.getY(i)); invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pointer < 0 || e.getPointerId(idx) != pointer) return false;
                    releaseAll();
                    pointer = -1;
                    invalidate();
                    if (!moved && e.getEventTime() - downTime < 300 && e.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                        // tap: left click at the touch point (cursor mode: at the cursor)
                        if (cursorMode) {
                            mouseButton(MotionEvent.BUTTON_PRIMARY, true);
                            mouseButton(0, false);
                        } else {
                            float ox = cx + getLeft(), oy = cy + getTop();  // the stick's zone -> the screen
                            float x = mSurface != null ? mSurface.toBufferX(ox) : ox, y = mSurface != null ? mSurface.toBufferY(surfaceY(oy)) : oy;
                            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
                            SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, x, y, false);
                            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, x, y, false);
                        }
                    }
                    return true;
            }
            return pointer >= 0;
        }

        // the Alt/Space key steering (raw screen points): drawn like this stick
        private boolean ext;
        private float ecx, ecy, ekx, eky;

        void external(boolean on, float rawCx, float rawCy, float rawKx, float rawKy) {
            int[] at = new int[2];
            getLocationOnScreen(at);
            ext = on;
            ecx = rawCx - at[0];
            ecy = rawCy - at[1];
            ekx = rawKx - at[0];
            eky = rawKy - at[1];
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            ring.setColor(style.ring);
            knob.setColor(style.knob);
            if (ext) {
                c.drawCircle(ecx, ecy, dp(60), ring);
                c.drawCircle(ekx, eky, dp(24), knob);
            }
            if (pointer < 0) return;
            if (!moved && android.os.SystemClock.uptimeMillis() - downTime < STICK_SHOW_MS) return;
            c.drawCircle(cx, cy, dp(60), ring);
            c.drawCircle(kx, ky, dp(24), knob);
        }
    }

    /** Wheel drawing for the wheel key: ridges follow the finger. */
    private class Wheel extends View {
        private final android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                fg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float offset;

        Wheel(android.content.Context c) {
            super(c);
            fg.setTextSize(dp(14));
            fg.setTextAlign(android.graphics.Paint.Align.CENTER);
            fg.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            bg.setColor(style.wheelBg);
            fg.setColor(style.wheelFg);
            float w = getWidth(), h = getHeight(), r = dp(12);
            c.drawRoundRect(0, 0, w, h, r, r, bg);
            c.drawText("▲", w / 2, dp(22), fg);
            c.drawText("▼", w / 2, h - dp(10), fg);
            float gap = dp(12), shift = ((offset % gap) + gap) % gap;
            for (float y = dp(32) + shift; y < h - dp(28); y += gap) c.drawLine(w * 0.3f, y, w * 0.7f, y, fg);
        }
    }

    // ---- cursor mode: touchpad-style mouse

    private boolean cursorMode;
    private CursorPad cursorPad;
    private float curX = -1, curY = -1;  // cursor in view pixels
    private int mouseButtons;

    private void setCursorMode(boolean on) {
        cursorMode = on;
        getSharedPreferences("game", MODE_PRIVATE).edit().putBoolean("cursorMode", on).apply();
        rightClickArmed = false;
        if (rmbButton != null) paintKey(rmbButton, false);
        if (cursorPad != null) cursorPad.invalidate();
    }

    private void sendMouse(int action) {
        float x = mSurface != null ? mSurface.toBufferX(curX) : curX, y = mSurface != null ? mSurface.toBufferY(surfaceY(curY)) : curY;
        SDLActivity.onNativeMouse(mouseButtons, action, x, y, false);
    }

    /** Presses (button != 0) or releases all buttons at the cursor. */
    private void mouseButton(int button, boolean down) {
        if (curX < 0) { curX = cursorPad.getWidth() / 2f; curY = cursorPad.getHeight() / 2f; }
        if (down) {
            mouseButtons = button;
            sendMouse(MotionEvent.ACTION_DOWN);
        } else {
            mouseButtons = 0;
            sendMouse(MotionEvent.ACTION_UP);
        }
    }

    /**
     * Touchpad: dragging moves the cursor (x1.5), a tap clicks at the cursor,
     * tap then touch again and drag = drag with the left button held.
     */
    private float cursorScale = 1f;

    private class CursorPad extends View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path arrow = new android.graphics.Path();
        private float lastX, lastY, travel;
        private long downTime, lastTapTime;
        private boolean dragging;

        CursorPad(android.content.Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xFF000000);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setStrokeWidth(dp(1.5f));
            float u = dp(1);
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 18 * u);
            arrow.lineTo(5 * u, 13.5f * u);
            arrow.lineTo(8.5f * u, 21 * u);
            arrow.lineTo(11.5f * u, 19.5f * u);
            arrow.lineTo(8 * u, 12.5f * u);
            arrow.lineTo(14 * u, 12.5f * u);
            arrow.close();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!cursorMode) return false;
            if (curX < 0) { curX = getWidth() / 2f; curY = getHeight() / 2f; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    travel = 0;
                    downTime = e.getEventTime();
                    dragging = downTime - lastTapTime < 300;  // second touch right after a tap
                    if (dragging) mouseButton(MotionEvent.BUTTON_PRIMARY, true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = (e.getX() - lastX) * 1.5f, dy = (e.getY() - lastY) * 1.5f;
                    lastX = e.getX();
                    lastY = e.getY();
                    travel += Math.abs(dx) + Math.abs(dy);
                    curX = Math.max(0, Math.min(getWidth() - 1, curX + dx));
                    curY = Math.max(0, Math.min(getHeight() - 1, curY + dy));
                    cameraFollowCursor();
                    sendMouse(MotionEvent.ACTION_MOVE);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        mouseButton(0, false);
                        dragging = false;
                        lastTapTime = 0;
                    } else if (travel < dp(8) && e.getEventTime() - downTime < 250 && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        mouseButton(MotionEvent.BUTTON_PRIMARY, true);
                        mouseButton(0, false);
                        lastTapTime = e.getEventTime();
                    }
                    return true;
            }
            return true;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (!cursorMode || curX < 0) return;
            c.save();
            c.translate(curX, curY);
            c.scale(cursorScale, cursorScale);  // the gear menu's "Размер курсора"
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
            c.restore();
        }
    }


    private void addKeyOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        // Cursor mode: the whole screen is a touchpad (under the stick and keys).
        cursorPad = new CursorPad(this);
        overlay.addView(cursorPad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Left part of the screen: dynamic stick for W A S D. Added before the
        // keys so the keys above it get their touches.
        style = STYLES[styleIndex(getSharedPreferences("game", MODE_PRIVATE).getString("keyStyleName", STYLES[0].name))];
        Stick stick = new Stick(this);
        stickView = stick;
        overlay.addView(stick, new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM | Gravity.START));
        overlay.setClipChildren(false);  // the ring may reach past the zone's edge
        // the zone: left 40 % of the screen, below the top quarter
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int zw = Math.round((r - l) * 0.4f), zh = Math.round((b - t) * 0.75f);
            FrameLayout.LayoutParams zp = (FrameLayout.LayoutParams) stick.getLayoutParams();
            if (zp.width != zw || zp.height != zh) {
                zp.width = zw;
                zp.height = zh;
                v.post(() -> stick.setLayoutParams(zp));
            }
        });

        // Wheel popup: shown while the wheel key is held.
        wheelPopup = new Wheel(this);
        wheelPopup.setVisibility(View.GONE);
        overlay.addView(wheelPopup, new FrameLayout.LayoutParams(dp(64), dp(170)));

        int m = dp(3);
        // Left column (touch / cursor is in the gear menu)
        // (Escape is the Android back button / gesture, see onBackPressed; Tab and F4 removed in v0.56)
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        // from the top: Enter(hold = Tab), Q(hold = E), RMB, R(hold = wheel), Alt(hold = Space), Shift, Ctrl
        enterKey = tapHoldKey("Enter", KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, "Tab");
        left.addView(enterKey, keySize());
        qKey = tapHoldKey("Q", KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, "E");  // tap = Q, hold = E
        left.addView(qKey, keySize());
        rmbKeyView = rmbKey();
        left.addView(rmbKeyView, keySize());
        rKeyView = rWheelKey();
        left.addView(rKeyView, keySize());
        altKey = altSpaceKey();
        left.addView(altKey, keySize());  // tap = Alt, hold = Space, hold and move = Space + stick
        TextView[] mods = modifierPair();  // Shift onto Ctrl = Z, Shift onto Alt = F, Ctrl onto Shift = Ctrl + Shift
        shiftKeyView = mods[0];
        ctrlKey = mods[1];
        left.addView(mods[0], keySize());
        left.addView(mods[1], keySize());
        left.setClipChildren(false);  // a dragged key may leave the column a little

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(8), dp(2), 0, 0);
        overlay.addView(left, lp);
        keyColumn = left;
        // hidden until a game runs: no flash of the keys while the game starts
        keysShown = false;
        left.setVisibility(View.GONE);
        left.setAlpha(0f);
        stick.setVisibility(View.GONE);
        stick.setAlpha(0f);
        gameStateSince = android.os.SystemClock.uptimeMillis();
        hudHandler.post(gameStateTick);

        addQuickBarPanel(overlay);

        radial = new RadialMenu(this);
        overlay.addView(radial, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        comboCard = new TextView(this);
        comboCard.setGravity(Gravity.CENTER);
        comboCard.setVisibility(View.GONE);
        overlay.addView(comboCard, new FrameLayout.LayoutParams(dp(56), dp(42)));

        // While RMB is armed, the next touch on the game is a right click
        // (held while the finger is down), then the mode switches off.
        overlay.setOnTouchListener((View v, MotionEvent e) -> {
            if (!rightClickArmed && !rightClickDown) return false;
            // overlay covers the surface view: view pixels -> buffer pixels
            float x = mSurface != null ? mSurface.toBufferX(e.getX()) : e.getX();
            float y = mSurface != null ? mSurface.toBufferY(surfaceY(e.getY())) : e.getY();
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
                    paintKey(rmbButton, false);
                    return true;
            }
            return true;
        });

        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
    }
}
