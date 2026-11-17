package com.factorynative;

import android.graphics.Color;
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
        outputScale = getIntent().getFloatExtra(EXTRA_OUTPUT_SCALE, 1f);
        if (mSurface != null && outputScale < 0.999f) {
            // Smaller surface buffer; the display compositor scales it to the screen.
            mSurface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyOutputScale(r - l, b - t));
        }
        if (mLayout != null) addKeyOverlay();
        if (mLayout != null && getIntent().getBooleanExtra(EXTRA_POWER_METER, false)) addHud();
        if (mLayout != null && getIntent().getIntExtra(EXTRA_BENCH_SECONDS, 0) > 0) startBench();
    }

    // ---- HUD: FPS, UPS (from libfnative) and battery watts, twice a second;
    // a checkbox shows a frame time graph.

    private static native void nativeStats(float[] out);
    private static native void nativeFrames(float[] out);
    private static native void nativeCounters(long[] out);
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

    private android.widget.CheckBox cpuBox, gpuBox;
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

        android.widget.CheckBox graphBox = hudBox("График");
        cpuBox = hudBox("ЦП –");
        gpuBox = hudBox("ГП –");
        for (android.widget.CheckBox b : new android.widget.CheckBox[] {graphBox, cpuBox, gpuBox}) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.setMargins(dp(6), 0, 0, 0);
            row.addView(b, bp);
        }
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
        hudHandler.removeCallbacks(benchTick);
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
        hudHandler.post(hudTick);
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
        cpuBox.setText(avg >= 0 ? "ЦП " + avg + "%" : game >= 0 ? "ЦП " + game + "%" : "ЦП –");
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
        gpuBox.setText(busy >= 0 ? "ГП " + busy + "%" : "ГП –");
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

    /** Android back (button or gesture) = Escape in the game; the game is not closed. */
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

    // ---- on-screen keys: held while the finger is down. All keys one size.

    private LinearLayout.LayoutParams keySize() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(42));
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        return p;
    }

    private TextView rmbKey() {
        TextView rmb = new TextView(this);
        rmb.setText("ПКМ");
        rmb.setTextColor(Color.WHITE);
        rmb.setTextSize(14);
        rmb.setGravity(Gravity.CENTER);
        rmb.setBackgroundColor(0x66000000);
        rmb.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (cursorMode) {  // right button held at the cursor
                        v.setBackgroundColor(0xAAFF8800);
                        mouseButton(MotionEvent.BUTTON_SECONDARY, true);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (cursorMode) {
                        v.setBackgroundColor(0x66000000);
                        mouseButton(0, false);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {  // touch mode: arm for the next touch
                        rightClickArmed = !rightClickArmed;
                        v.setBackgroundColor(rightClickArmed ? 0xAAFF8800 : 0x66000000);
                    }
                    return true;
            }
            return true;
        });
        rmbButton = rmb;
        return rmb;
    }

    // Wheel key: hold it and move the finger up/down; a wheel appears next to
    // the key and scrolls (one notch per 24 dp); releasing hides it.
    private Wheel wheelPopup;

    private TextView wheelKey() {
        TextView k = new TextView(this);
        k.setText("Колесо");
        k.setTextColor(Color.WHITE);
        k.setTextSize(13);
        k.setGravity(Gravity.CENTER);
        k.setBackgroundColor(0x66000000);
        final float[] state = new float[2];  // last raw Y, accumulated offset
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    v.setBackgroundColor(0xAAFF8800);
                    int[] at = new int[2], root = new int[2];
                    v.getLocationOnScreen(at);
                    ((View) wheelPopup.getParent()).getLocationOnScreen(root);
                    wheelPopup.setX(at[0] - root[0] + v.getWidth() + dp(6));
                    wheelPopup.setY(at[1] - root[1] + v.getHeight() / 2f - dp(85));
                    wheelPopup.offset = 0;
                    wheelPopup.setVisibility(View.VISIBLE);
                    state[0] = e.getRawY();
                    state[1] = 0;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
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
                    v.setBackgroundColor(0x66000000);
                    wheelPopup.setVisibility(View.GONE);
                    return true;
            }
            return true;
        });
        return k;
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

    /**
     * Dynamic stick: a touch in the stick zone (left 40% of the screen, below
     * the top 25%) becomes the stick centre; the finger direction holds W/A/S/D
     * (diagonals hold two). A short tap without movement is a normal click.
     */
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
            ring.setColor(0x88FFFFFF);
            knob.setColor(0x88FF8800);
        }

        private boolean inZone(float x, float y) {
            return x < getWidth() * 0.4f && y > getHeight() * 0.25f;
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
                    invalidate();
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
                            float x = mSurface != null ? mSurface.toBufferX(cx) : cx, y = mSurface != null ? mSurface.toBufferY(cy) : cy;
                            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
                            SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, x, y, false);
                            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, x, y, false);
                        }
                    }
                    return true;
            }
            return pointer >= 0;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (pointer < 0) return;
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
            bg.setColor(0x88000000);
            fg.setColor(0xCCFFFFFF);
            fg.setTextSize(dp(14));
            fg.setTextAlign(android.graphics.Paint.Align.CENTER);
            fg.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
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
        if (rmbButton != null) rmbButton.setBackgroundColor(0x66000000);
        if (cursorPad != null) cursorPad.invalidate();
    }

    private void sendMouse(int action) {
        float x = mSurface != null ? mSurface.toBufferX(curX) : curX, y = mSurface != null ? mSurface.toBufferY(curY) : curY;
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
        Stick stick = new Stick(this);
        overlay.addView(stick, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Wheel popup: shown while the wheel key is held.
        wheelPopup = new Wheel(this);
        wheelPopup.setVisibility(View.GONE);
        overlay.addView(wheelPopup, new FrameLayout.LayoutParams(dp(64), dp(170)));

        int m = dp(3);
        // Left column from the top: Tab F4 / touch-cursor switch / Q E R / wheel
        // (Escape is the Android back button / gesture, see onBackPressed)
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(key("Tab", KeyEvent.KEYCODE_TAB), keySize());
        top.addView(key("F4", KeyEvent.KEYCODE_F4), keySize());
        // rows need their own width: a vertical layout would stretch them to
        // its width and cut keys off
        left.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.RadioGroup mode = new android.widget.RadioGroup(this);
        mode.setOrientation(LinearLayout.HORIZONTAL);
        mode.setBackgroundColor(0x66000000);
        android.widget.RadioButton touchBtn = new android.widget.RadioButton(this), cursorBtn = new android.widget.RadioButton(this);
        touchBtn.setText("Тач");
        cursorBtn.setText("Курсор");
        for (android.widget.RadioButton rb : new android.widget.RadioButton[] {touchBtn, cursorBtn}) {
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(13);
            rb.setId(View.generateViewId());
            mode.addView(rb);
        }
        cursorMode = getSharedPreferences("game", MODE_PRIVATE).getBoolean("cursorMode", false);
        (cursorMode ? cursorBtn : touchBtn).setChecked(true);
        mode.setOnCheckedChangeListener((g, id) -> setCursorMode(id == cursorBtn.getId()));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.setMargins(m, m, m, m);
        left.addView(mode, mp);

        left.addView(key("Q", KeyEvent.KEYCODE_Q), keySize());
        left.addView(key("E", KeyEvent.KEYCODE_E), keySize());
        left.addView(key("R", KeyEvent.KEYCODE_R), keySize());
        left.addView(wheelKey(), keySize());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(8), dp(2), 0, 0);
        overlay.addView(left, lp);

        // Bottom-left: RMB, under it Shift Ctrl Alt Enter Space.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(rmbKey(), keySize());
        LinearLayout mods = new LinearLayout(this);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        mods.addView(key("Shift", KeyEvent.KEYCODE_SHIFT_LEFT), keySize());
        mods.addView(key("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT), keySize());
        mods.addView(key("Alt", KeyEvent.KEYCODE_ALT_LEFT), keySize());
        mods.addView(key("Enter", KeyEvent.KEYCODE_ENTER), keySize());
        mods.addView(key("Пробел", KeyEvent.KEYCODE_SPACE), keySize());
        bottom.addView(mods, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        bp.setMargins(dp(8), 0, 0, dp(4));
        overlay.addView(bottom, bp);

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
