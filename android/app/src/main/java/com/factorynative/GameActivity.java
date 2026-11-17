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

    /**
     * Android back (button or gesture) = Escape in the game; the game is not
     * closed. Caught here, before SDL's view handler turns it into its own
     * "AC Back" key (unknown to the game) and consumes it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
            FX_DOTS = 7, FX_HATCH = 8, FX_TILES = 9, FX_LEFTBAR = 10;

    /** Colours of one look (argb ints, sizes in dp). Set with the chained methods. */
    private static final class KeyStyle {
        final String name;
        final int top, bottom, stroke, text;        // normal key: fill gradient, edge, label
        final int pTop, pBottom, pStroke, pText;    // pressed key
        final int radius, strokeDp;
        int highlight, lowlight;                    // 1 dp lines inside the top / bottom edge (0 = none)
        boolean bold, shadow, mono, icons;
        int iconSet;                                // icon look, see KeyIcon
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
    };

    private KeyStyle style = STYLES[0];
    private final java.util.List<TextView> styledKeys = new java.util.ArrayList<>();
    private View modeGroup;
    private Stick stickView;
    private android.widget.CheckBox[] styleBoxes;
    private View stylePanel;
    private Runnable pickerFold;

    private static int styleIndex(String name) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].name.equals(name)) return i;
        return 0;
    }

    /** Key / panel background: gradient with an edge, effect, bevel lines. */
    private android.graphics.drawable.Drawable background(KeyStyle s, boolean pressed, boolean panel) {
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
        int color = pressed ? s.pText : s.text;
        KeyIcon icon = s.icons ? new KeyIcon(t.getText().toString(), color, s.iconSet) : null;
        if (icon != null && !icon.empty()) {
            icon.setBounds(0, 0, dp(20), dp(20));
            t.setCompoundDrawables(null, icon, null, null);
            t.setTextSize(9);
            t.setPadding(0, dp(2), 0, 0);
        } else {
            t.setCompoundDrawables(null, null, null, null);
            t.setTextSize(t.getText().length() > 4 ? 13 : 14);
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
        if (stickView != null) stickView.invalidate();
        if (wheelPopup != null) wheelPopup.invalidate();
    }

    /** Picker tabs: title, then the style names in it. Every style is in one tab. */
    private static final String[][] TABS = {
        {"Игра", "Factorio", "Завод", "Ф: Слот", "Ф: Окно", "Ф: Зелёная", "Ф: Красная", "Ф: Опасность", "Ф: Чертёж",
                "Ф: Ночь", "Ф: Подсказка", "Ф: Вкладка", "Ф: Карта", "Ф: Тревога", "Ф: Достижение"},
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
            tabViews[t].setBackground(background(style, on, false));
            styleText(tabViews[t], style, on ? style.pText : style.text, !on);
        }
    }

    /**
     * Right edge: "Кнопки", a row of tabs (scrolls left/right), and the styles
     * of the chosen tab (scrolls up/down): checkbox with the name and a sample
     * key in that style. Only one style is on. A sideways swipe over the list
     * goes to the next / previous tab. A tap on the title folds it all away.
     */
    private View stylePicker() {
        android.content.SharedPreferences prefs = getSharedPreferences("game", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(2), dp(6), dp(4));
        TextView title = new TextView(this);
        title.setTextSize(13);
        title.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(title);

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
        box.addView(tabScroll, new LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT));

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
        box.addView(scroll, new LinearLayout.LayoutParams(dp(210), 0, 1f));

        pickerFold = () -> {
            boolean open = prefs.getBoolean("keyStyleOpen", true);
            tabScroll.setVisibility(open ? View.VISIBLE : View.GONE);
            scroll.setVisibility(open ? View.VISIBLE : View.GONE);
            title.setText(open ? "Кнопки ▾" : "Кнопки ▸");
            android.view.ViewGroup.LayoutParams lp = box.getLayoutParams();
            if (lp != null) {
                lp.height = open ? android.view.ViewGroup.LayoutParams.MATCH_PARENT : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                box.setLayoutParams(lp);
            }
        };
        title.setOnClickListener(v -> {
            prefs.edit().putBoolean("keyStyleOpen", !prefs.getBoolean("keyStyleOpen", true)).apply();
            pickerFold.run();
        });
        stylePanel = box;
        showTab.accept(prefs.getInt("keyStyleTab", tabOf(style.name)));
        return box;
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
        rmb.setGravity(Gravity.CENTER);
        paintKey(rmb, false);
        rmb.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (cursorMode) {  // right button held at the cursor
                        paintKey(v, true);
                        mouseButton(MotionEvent.BUTTON_SECONDARY, true);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (cursorMode) {
                        paintKey(v, false);
                        mouseButton(0, false);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {  // touch mode: arm for the next touch
                        rightClickArmed = !rightClickArmed;
                        paintKey(v, rightClickArmed);
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
        k.setGravity(Gravity.CENTER);
        paintKey(k, false);
        final float[] state = new float[2];  // last raw Y, accumulated offset
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    paintKey(v, true);
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
                    paintKey(v, false);
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
            ring.setColor(style.ring);
            knob.setColor(style.knob);
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
        style = STYLES[styleIndex(getSharedPreferences("game", MODE_PRIVATE).getString("keyStyleName", STYLES[0].name))];
        Stick stick = new Stick(this);
        stickView = stick;
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
        modeGroup = mode;
        mode.setPadding(dp(6), dp(2), dp(8), dp(2));
        android.widget.RadioButton touchBtn = new android.widget.RadioButton(this), cursorBtn = new android.widget.RadioButton(this);
        touchBtn.setText("Тач");
        cursorBtn.setText("Курсор");
        for (android.widget.RadioButton rb : new android.widget.RadioButton[] {touchBtn, cursorBtn}) {
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

        // Right edge, middle: key style picker.
        View picker = stylePicker();
        applyStyle(java.util.Arrays.asList(STYLES).indexOf(style));  // also paints the touch/cursor switch
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        sp.setMargins(0, dp(6), dp(8), dp(6));
        overlay.addView(picker, sp);
        pickerFold.run();

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
                    paintKey(rmbButton, false);
                    return true;
            }
            return true;
        });

        mLayout.addView(overlay, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
    }
}
