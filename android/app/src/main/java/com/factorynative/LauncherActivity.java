package com.factorynative;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Test launcher: game folder path, file access, start. */
public class LauncherActivity extends Activity {
    private static final String DEFAULT_PATH = "/storage/emulated/0/Download/factorio";
    private EditText pathEdit, envEdit;
    private boolean envLoaded;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("FNative — лаунчер Factorio для Android (тест)\nНеофициальный, не одобрен Wube. Бесплатно и некоммерчески.");
        root.addView(title);

        TextView label = new TextView(this);
        label.setText("\nПапка игры (в ней bin и data) или путь к bin/arm64/factorio:");
        root.addView(label);

        pathEdit = new EditText(this);
        pathEdit.setSingleLine(true);
        pathEdit.setText(prefs.getString("gamePath", DEFAULT_PATH));
        root.addView(pathEdit);

        Button access = new Button(this);
        access.setText("Разрешить доступ к файлам");
        access.setOnClickListener(v -> requestPermissions(
                new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1));
        root.addView(access);

        Button start = new Button(this);
        start.setText("Запустить");
        start.setOnClickListener(v -> start());
        root.addView(start);

        status = new TextView(this);
        root.addView(status);

        addUpscaleSettings(root);

        addCoreSection(root);
        addCacheSection(root);
        addBenchSection(root);

        TextView envLabel = new TextView(this);
        envLabel.setText("\nПеременные (по одной на строку: ИМЯ=значение; строка с # выключена).\n"
                + "Сохраняются в Download/FactorioNative/env.txt при запуске.");
        root.addView(envLabel);

        envEdit = new EditText(this);
        envEdit.setSingleLine(false);
        envEdit.setMinLines(6);
        envEdit.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        envEdit.setTypeface(android.graphics.Typeface.MONOSPACE);
        envEdit.setTextSize(13);
        envEdit.setHorizontallyScrolling(true);
        root.addView(envEdit);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
        loadEnv();
        if (hasAccess()) loadSaves();
        updateCacheInfo();
    }

    private static File envFile() {
        return new File(workDir(), "env.txt");
    }

    // ---- graphics settings: render scale + upscaler

    private android.widget.CheckBox upscaleOn, powerMeter, angleOn;
    private android.widget.SeekBar scaleBar, outputBar;
    private TextView outputText;
    private TextView scaleText;
    private android.widget.RadioButton upGsr, upBilinear;

    private int outputPercent() {
        return 50 + outputBar.getProgress() * 5;  // 50..100 %
    }

    private int scalePercent() {
        return 50 + scaleBar.getProgress() * 5;  // 50..95 %
    }

    private void updateScaleText() {
        int p = scalePercent(), o = outputPercent();
        // Full screen size, landscape like the game.
        android.graphics.Point size = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int w = Math.max(size.x, size.y), h = Math.min(size.x, size.y);
        int ow = Math.round(w * o / 100f), oh = Math.round(h * o / 100f);
        int rw = Math.round(ow * p / 100f), rh = Math.round(oh * p / 100f);
        outputText.setText("Разрешение экрана: " + o + "% — выход " + ow + "×" + oh
                + (o < 100 ? " (растягивает дисплей до " + w + "×" + h + ", бесплатно)" : " (родное)"));
        if (upscaleOn.isChecked()) {
            int pixels = Math.round(p * p * o * o / 1000000f);
            scaleText.setText("Масштаб рендера: " + p + "%\nИгра рисует " + rw + "×" + rh + " → GSR/билинейный до "
                    + ow + "×" + oh + (o < 100 ? " → дисплей до " + w + "×" + h : "")
                    + "\nРисуется " + pixels + "% пикселей от родного экрана");
        } else {
            scaleText.setText("Масштаб рендера: " + p + "% — апскейл выключен, игра рисует " + ow + "×" + oh);
        }
        boolean on = upscaleOn.isChecked();
        scaleBar.setEnabled(on);
        upGsr.setEnabled(on);
        upBilinear.setEnabled(on);
    }

    private void addUpscaleSettings(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("\nРазрешение: выход игры (растягивает дисплей) и апскейл внутри него"
                + " (игра рисует меньше, GSR растягивает). Снижает нагрузку на видеокарту;"
                + " при апскейле интерфейс игры крупнее.");
        root.addView(title);

        outputText = new TextView(this);
        root.addView(outputText);
        outputBar = new android.widget.SeekBar(this);
        outputBar.setMax(10);  // 50, 55, ... 100 %
        outputBar.setProgress((prefs.getInt("outputScale", 100) - 50) / 5);
        root.addView(outputBar);

        angleOn = new android.widget.CheckBox(this);
        angleOn.setText("ANGLE (системный, OpenGL ES через Vulkan) — если на телефоне его нет, будет обычный драйвер");
        angleOn.setChecked(prefs.getBoolean("angle", false));
        root.addView(angleOn);

        powerMeter = new android.widget.CheckBox(this);
        powerMeter.setText("Показывать HUD: FPS, UPS, ватты (+ график времени кадра)");
        powerMeter.setChecked(prefs.getBoolean("powerMeter", true));
        root.addView(powerMeter);

        upscaleOn = new android.widget.CheckBox(this);
        upscaleOn.setText("Апскейл включён");
        upscaleOn.setChecked(prefs.getBoolean("upscaleOn", false));
        root.addView(upscaleOn);

        scaleText = new TextView(this);
        root.addView(scaleText);

        scaleBar = new android.widget.SeekBar(this);
        scaleBar.setMax(9);  // 50, 55, ... 95 %
        scaleBar.setProgress((prefs.getInt("renderScale", 75) - 50) / 5);
        root.addView(scaleBar);

        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        upGsr = new android.widget.RadioButton(this);
        upGsr.setText("Snapdragon GSR — чётче (≈0,3 мс на кадр)");
        upGsr.setId(View.generateViewId());
        upBilinear = new android.widget.RadioButton(this);
        upBilinear.setText("Билинейный — мягче, дешевле (≈0,15 мс)");
        upBilinear.setId(View.generateViewId());
        group.addView(upGsr);
        group.addView(upBilinear);
        if ("bilinear".equals(prefs.getString("upscaler", "gsr"))) upBilinear.setChecked(true);
        else upGsr.setChecked(true);
        root.addView(group);

        upscaleOn.setOnCheckedChangeListener((b, checked) -> updateScaleText());
        outputBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) { updateScaleText(); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        scaleBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) { updateScaleText(); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        updateScaleText();
    }

    /** Settings for the game process, as "--fn-env KEY=VALUE" arguments. */
    private List<String> settingArgs() {
        prefs.edit()
                .putBoolean("upscaleOn", upscaleOn.isChecked())
                .putInt("renderScale", scalePercent())
                .putInt("outputScale", outputPercent())
                .putBoolean("powerMeter", powerMeter.isChecked())
                .putBoolean("angle", angleOn.isChecked())
                .putBoolean("atlasCache", atlasCache.isChecked())
                .putBoolean("etc2Cache", etc2Cache.isChecked())
                .putBoolean("coresOn", coresOn.isChecked())
                .putLong("cpuMask", coreMask())
                .putString("upscaler", upBilinear.isChecked() ? "bilinear" : "gsr")
                .apply();
        List<String> a = new ArrayList<>();
        a.add("--fn-env");
        a.add("FN_ANGLE=" + (angleOn.isChecked() ? "1" : "0"));
        a.add("--fn-env");
        a.add("FN_CPU_MASK=" + (coresOn.isChecked() && coreMask() != 0 ? Long.toHexString(coreMask()) : ""));
        a.add("--fn-env");
        a.add("FNGL_ETC2_CACHE=" + (etc2Cache.isEnabled() && etc2Cache.isChecked() ? etc2CacheDir().getAbsolutePath() : ""));
        if (upscaleOn.isChecked()) {
            a.add("--fn-env");
            a.add("FN_RENDER_SCALE=" + (scalePercent() / 100f));
            a.add("--fn-env");
            a.add("FN_UPSCALER=" + (upBilinear.isChecked() ? "bilinear" : "gsr"));
        } else {
            a.add("--fn-env");
            a.add("FN_RENDER_SCALE=1");
        }
        return a;
    }

    /** Shows env.txt in the editor (once file access is granted). */
    private void loadEnv() {
        if (envLoaded || !hasAccess()) return;
        try {
            File f = envFile();
            envEdit.setText(f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "");
            envLoaded = true;
        } catch (IOException e) {
            status.append("\nНе удалось прочитать env.txt: " + e);
        }
    }

    private boolean saveEnv() {
        if (!envLoaded) return true;  // never read: keep the file as it is
        try {
            envFile().getParentFile().mkdirs();
            try (FileOutputStream f = new FileOutputStream(envFile())) {
                f.write(envEdit.getText().toString().getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException e) {
            status.setText("\nНе удалось записать env.txt: " + e);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        check();
        loadEnv();
    }

    private boolean hasAccess() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Game folder; also accepts the path of bin/arm64/factorio itself. */
    /** Logs, config and the game's write-data: readable from Termux and file managers. */
    private static File workDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/FactorioNative");
    }

    private File gameDir() {
        String p = pathEdit.getText().toString().trim();
        while (p.endsWith("/") || p.endsWith(".")) p = p.substring(0, p.length() - 1);
        File f = new File(p);
        if (f.getName().equals("factorio") && f.isFile()) {
            File root = f.getParentFile() != null && f.getParentFile().getParentFile() != null
                    ? f.getParentFile().getParentFile().getParentFile() : null;
            if (root != null) return root;
        }
        return f;
    }

    /** Shows whether the game can start; returns true when it can. */
    private boolean check() {
        StringBuilder sb = new StringBuilder("\n");
        boolean ok = true;
        if (!hasAccess()) {
            sb.append("Нет доступа к файлам: нажмите «Разрешить доступ к файлам».\n");
            ok = false;
        }
        File bin = new File(gameDir(), "bin/arm64/factorio");
        File data = new File(gameDir(), "data");
        if (ok && !bin.canRead()) { sb.append("Не найден ").append(bin).append('\n'); ok = false; }
        if (ok && !data.isDirectory()) { sb.append("Не найдена папка ").append(data).append('\n'); ok = false; }
        if (ok) sb.append("Игра найдена.\n");
        File ext = workDir();
        sb.append("\nСохранения и моды: ").append(new File(gameDir(), "saves")).append(", ").append(new File(gameDir(), "mods"));
        sb.append("\nЖурнал загрузчика: ").append(new File(ext, "fnative.log"));
        sb.append("\nЖурнал игры: ").append(new File(gameDir(), "factorio-current.log"));
        status.setText(sb.toString());
        return ok;
    }

    private void start() {
        launch(null, 0);
    }

    /** Starts the game; with a save and seconds > 0 it runs the benchmark on that save. */
    private void launch(File benchSave, int benchSeconds) {
        prefs.edit().putString("gamePath", gameDir().getAbsolutePath()).apply();
        pathEdit.setText(gameDir().getAbsolutePath());
        if (!check()) return;
        if (!saveEnv()) return;
        File ext = workDir();
        // The game folder is the write-data folder, like the zip version of
        // Factorio: saves, mods and player data are the ones already there.
        File write = gameDir();
        File config = new File(ext, "config/config.ini");
        write.mkdirs();
        config.getParentFile().mkdirs();
        try {
            writePaths(config, new File(gameDir(), "data").getAbsolutePath(), write.getAbsolutePath(),
                    // the game's atlas cache only with native BC: on Mali the BC readback it needs is not possible
                    atlasCache.isEnabled() && atlasCache.isChecked());
        } catch (IOException e) {
            status.setText("\nНе удалось записать config.ini: " + e);
            return;
        }
        List<String> settings = settingArgs();
        List<String> argList = new ArrayList<>();
        argList.add(new File(gameDir(), "bin/arm64/factorio").getAbsolutePath());
        argList.add(new File(ext, "fnative.log").getAbsolutePath());
        argList.addAll(settings);
        argList.add("-c");
        argList.add(config.getAbsolutePath());
        if (benchSave != null) {
            argList.add("--load-game");
            argList.add(benchSave.getAbsolutePath());
        }
        String[] args = argList.toArray(new String[0]);
        Intent game = new Intent(this, GameActivity.class).putExtra(GameActivity.EXTRA_ARGS, args)
                .putExtra(GameActivity.EXTRA_OUTPUT_SCALE, outputPercent() / 100f)
                .putExtra(GameActivity.EXTRA_POWER_METER, powerMeter.isChecked());
        if (benchSave != null) {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
            String name = benchSave.getName().replaceAll("\\.zip$", "");
            String info = "Сохранение: " + benchSave.getName()
                    + "\nВерсия: " + appVersion()
                    + "\nНастройки: экран " + outputPercent() + "%, апскейл "
                    + (upscaleOn.isChecked() ? scalePercent() + "% " + (upBilinear.isChecked() ? "bilinear" : "GSR") : "выкл")
                    + ", ANGLE " + (angleOn.isChecked() ? "вкл" : "выкл")
                    + ", ядра " + (coresOn.isChecked() && coreMask() != 0 ? coreList(coreMask()) : "все")
                    + "\nПеременные: " + String.join(" ", activeEnv());
            game.putExtra(GameActivity.EXTRA_BENCH_SECONDS, benchSeconds)
                    .putExtra(GameActivity.EXTRA_BENCH_OUT, new File(ext, "bench-" + name + "-" + stamp + ".txt").getAbsolutePath())
                    .putExtra(GameActivity.EXTRA_BENCH_INFO, info);
        }
        startActivity(game);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Uncommented lines of the variables field. */
    private List<String> activeEnv() {
        List<String> out = new ArrayList<>();
        for (String l : envEdit.getText().toString().split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("#") && t.contains("=")) out.add(t);
        }
        return out;
    }

    // ---- GPU probe and texture caches

    private String gpuName = "?";
    private boolean gpuHasBc;
    private android.widget.CheckBox atlasCache, etc2Cache;

    /** Short-lived EGL context: renderer name and whether BC (S3TC) is native. */
    private void probeGpu() {
        android.opengl.EGLDisplay d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!android.opengl.EGL14.eglInitialize(d, v, 0, v, 1)) return;
        int[] attrs = {android.opengl.EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, android.opengl.EGL14.EGL_SURFACE_TYPE,
                android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE};
        android.opengl.EGLConfig[] cfg = new android.opengl.EGLConfig[1];
        int[] n = new int[1];
        android.opengl.EGL14.eglChooseConfig(d, attrs, 0, cfg, 0, 1, n, 0);
        if (n[0] < 1) return;
        android.opengl.EGLSurface surf = android.opengl.EGL14.eglCreatePbufferSurface(d, cfg[0],
                new int[] {android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE}, 0);
        android.opengl.EGLContext ctx = android.opengl.EGL14.eglCreateContext(d, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT,
                new int[] {android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, android.opengl.EGL14.EGL_NONE}, 0);
        if (android.opengl.EGL14.eglMakeCurrent(d, surf, surf, ctx)) {
            gpuName = android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_RENDERER);
            int[] num = new int[1];
            android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_NUM_EXTENSIONS, num, 0);
            for (int i = 0; i < num[0]; i++)
                if ("GL_EXT_texture_compression_s3tc".equals(android.opengl.GLES30.glGetStringi(android.opengl.GLES30.GL_EXTENSIONS, i)))
                    gpuHasBc = true;
        }
        android.opengl.EGL14.eglMakeCurrent(d, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_CONTEXT);
        android.opengl.EGL14.eglDestroyContext(d, ctx);
        android.opengl.EGL14.eglDestroySurface(d, surf);
    }

    private static File etc2CacheDir() {
        return new File(workDir(), "etc2-cache");
    }

    private File atlasCacheFile() {
        return new File(gameDir(), "atlas-cache.dat");
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] l = f.listFiles();
        if (l != null) for (File c : l) s += sizeOf(c);
        return s;
    }

    private TextView cacheInfo;

    private void addCacheSection(LinearLayout root) {
        probeGpu();
        TextView t = new TextView(this);
        t.setText("\nВидеокарта: " + gpuName + (gpuHasBc ? " — BC-сжатие есть (тип Adreno)" : " — BC-сжатия нет (тип Mali)")
                + "\nКэш ускоряет загрузку со второго запуска. Доступна только галочка для вашей видеокарты.");
        root.addView(t);

        atlasCache = new android.widget.CheckBox(this);
        atlasCache.setText("Кэш атласов игры (Adreno / есть BC) — ~0,7 ГБ в папке игры");
        atlasCache.setEnabled(gpuHasBc);
        atlasCache.setChecked(gpuHasBc && prefs.getBoolean("atlasCache", false));
        root.addView(atlasCache);

        etc2Cache = new android.widget.CheckBox(this);
        etc2Cache.setText("Кэш ETC2 (Mali / нет BC) — ~3 ГБ в Download/FactorioNative/etc2-cache");
        etc2Cache.setEnabled(!gpuHasBc);
        etc2Cache.setChecked(!gpuHasBc && prefs.getBoolean("etc2Cache", false));
        root.addView(etc2Cache);

        cacheInfo = new TextView(this);
        root.addView(cacheInfo);
        Button clear = new Button(this);
        clear.setText("Очистить кэши");
        clear.setOnClickListener(v -> {
            atlasCacheFile().delete();
            File[] l = etc2CacheDir().listFiles();
            if (l != null) for (File f : l) f.delete();
            updateCacheInfo();
        });
        root.addView(clear);
    }

    private void updateCacheInfo() {
        if (cacheInfo == null || !hasAccess()) return;
        cacheInfo.setText(String.format(java.util.Locale.ROOT, "Размер: атласы игры %d МБ, ETC2 %d МБ",
                sizeOf(atlasCacheFile()) >> 20, sizeOf(etc2CacheDir()) >> 20));
    }

    // ---- core selection: run the game only on chosen cores (sched_setaffinity)

    private android.widget.CheckBox coresOn;
    private final List<android.widget.CheckBox> coreBoxes = new ArrayList<>();
    private TextView coresText;

    private static String readLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String l = r.readLine();
            return l != null ? l.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void addCoreSection(LinearLayout root) {
        TextView t = new TextView(this);
        t.setText("\nЯдра: игра работает только на отмеченных ядрах (все её потоки). Выключено — система решает сама.");
        root.addView(t);
        coresOn = new android.widget.CheckBox(this);
        coresOn.setText("Выбор ядер");
        coresOn.setChecked(prefs.getBoolean("coresOn", false));
        root.addView(coresOn);

        int n = Runtime.getRuntime().availableProcessors();
        long savedMask = prefs.getLong("cpuMask", 0);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        long lastMax = -1;
        LinearLayout row = null;
        for (int c = 0; c < n && c < 64; c++) {
            String max = readLine("/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_max_freq");
            long mhz = max != null ? Long.parseLong(max) / 1000 : -1;
            if (row == null || mhz != lastMax) {  // a new cluster: cores with the same top frequency
                TextView h = new TextView(this);
                h.setText(mhz > 0 ? "до " + mhz + " МГц:" : "ядра:");
                grid.addView(h);
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
                lastMax = mhz;
            }
            android.widget.CheckBox b = new android.widget.CheckBox(this);
            b.setText("cpu" + c);
            b.setChecked((savedMask >> c & 1) != 0);
            b.setOnCheckedChangeListener((v, on) -> updateCoresText());
            row.addView(b);
            coreBoxes.add(b);
        }
        root.addView(grid);
        coresText = new TextView(this);
        root.addView(coresText);
        coresOn.setOnCheckedChangeListener((v, on) -> updateCoresText());
        updateCoresText();
    }

    private static String coreList(long m) {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < 64; c++) if ((m >> c & 1) != 0) b.append(b.length() > 0 ? "," : "cpu").append(c);
        return b.toString();
    }

    private long coreMask() {
        long m = 0;
        for (int c = 0; c < coreBoxes.size(); c++) if (coreBoxes.get(c).isChecked()) m |= 1L << c;
        return m;
    }

    private void updateCoresText() {
        boolean on = coresOn.isChecked();
        for (android.widget.CheckBox b : coreBoxes) b.setEnabled(on);
        long m = coreMask();
        coresText.setText(!on ? "Сейчас: все ядра (система решает)"
                : m == 0 ? "Не отмечено ни одного ядра — будут все ядра"
                : "Сейчас: " + Long.bitCount(m) + " из " + coreBoxes.size() + " ядер");
    }

    // ---- benchmark section: a save from the game's saves folder, 60 s

    private android.widget.Spinner benchSaves;
    private File[] saveFiles = new File[0];

    private void addBenchSection(LinearLayout root) {
        TextView t = new TextView(this);
        t.setText("\nЗамер производительности: игра загрузит сохранение, 10 с прогреет, 60 с запишет"
                + " FPS, UPS, ватты, ЦП и ГП, сама закроется. Итог — файл bench-*.txt в Download/FactorioNative."
                + " Не трогайте экран во время замера.");
        root.addView(t);
        benchSaves = new android.widget.Spinner(this);
        root.addView(benchSaves);
        Button run = new Button(this);
        run.setText("Запустить замер");
        run.setOnClickListener(v -> {
            int i = benchSaves.getSelectedItemPosition();
            if (i < 0 || i >= saveFiles.length) {
                status.setText("\nНет сохранений в " + new File(gameDir(), "saves"));
                return;
            }
            prefs.edit().putString("benchSave", saveFiles[i].getName()).apply();
            launch(saveFiles[i], 60);
        });
        root.addView(run);
    }

    /** Fills the save list, newest first. */
    private void loadSaves() {
        File[] files = new File(gameDir(), "saves").listFiles((d, n) -> n.endsWith(".zip"));
        if (files == null) files = new File[0];
        java.util.Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        saveFiles = files;
        String[] names = new String[files.length];
        int selected = 0;
        String last = prefs.getString("benchSave", "");
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName();
            if (names[i].equals(last)) selected = i;
        }
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        benchSaves.setAdapter(a);
        if (files.length > 0) benchSaves.setSelection(selected);
    }

    /** Keeps the game's own config.ini; sets [path] and the atlas cache keys of [graphics]. */
    private static void writePaths(File config, String readData, String writeData, boolean atlasCache) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("[path]");
        out.add("read-data=" + readData);
        out.add("write-data=" + writeData);
        String[] cacheKeys = {"cache-sprite-atlas=" + atlasCache, "compress-sprite-atlas-cache=true"};
        boolean graphicsSeen = false;
        if (config.exists()) {
            String section = "";
            for (String line : Files.readAllLines(config.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    section = t;
                    if (section.equals("[path]")) continue;
                    out.add(line);
                    if (section.equals("[graphics]")) {
                        graphicsSeen = true;
                        java.util.Collections.addAll(out, cacheKeys);
                    }
                    continue;
                }
                if (section.equals("[path]")) continue;
                if (section.equals("[graphics]") && (t.startsWith("cache-sprite-atlas=") || t.startsWith("compress-sprite-atlas-cache=")))
                    continue;
                out.add(line);
            }
        }
        if (!graphicsSeen) {
            out.add("[graphics]");
            java.util.Collections.addAll(out, cacheKeys);
        }
        try (FileOutputStream f = new FileOutputStream(config)) {
            f.write((String.join("\n", out) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
