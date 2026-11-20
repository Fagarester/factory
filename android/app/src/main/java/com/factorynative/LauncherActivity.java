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

/**
 * The app's entry. A tap on the icon starts the game at once; the first time (no game known yet) the game is
 * searched for, and if it is not found the "where is the game" screen opens (FirstRun). The settings below
 * open with the "Настройки" shortcut: hold the app icon (as in the Factory app).
 */
public class LauncherActivity extends Activity {
    static final String EXTRA_OPEN_SETTINGS = "open_settings";
    private static final String DEFAULT_PATH = "/storage/emulated/0/Download/factorio";
    private static final int REQ_ACCESS = 1, PICK_GAME_FOLDER = 13;
    private EditText pathEdit, envEdit;
    private boolean envLoaded, settingsMode;
    private TextView status;
    private SharedPreferences prefs;
    private FirstRun firstRun;
    private Runnable afterAccess;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Tr.init(this);  // the app's texts in the phone's language
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        GameSource.init(getFilesDir());
        publishSettingsShortcut();
        settingsMode = getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false);
        if (settingsMode) {
            // the theme is full screen (for the start); the settings keep the status bar
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            buildSettings();
            return;
        }
        // a normal start: the game known and in place starts right away, nothing of this screen shows
        if (hasAccess() && GameSource.isGameFolder(savedGameDir())) {
            launchGame(null, 0);
            finish();
            return;
        }
        // the logo for the screens below: from the app's files, or downloading while the questions are on screen
        new Thread(GameSource::downloadLogo, "fnative-logo").start();
        View cover = new View(this);
        cover.setBackgroundResource(R.drawable.loading_background);
        setContentView(cover);
        hideSystemBars();  // after setContentView: before it the window has no decor (v0.49 crashed here)
        firstRun = new FirstRun(this);
        if (hasAccess()) firstRun.start();
        else requestAccess(() -> firstRun.start());  // refused: the screen asks again on a choice
    }

    /** First start screens: the whole screen, no status or navigation bar (a swipe from the edge shows them for a while). */
    private void hideSystemBars() {
        getWindow().getDecorView();  // makes the decor: the insets controller is null without it
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // back from a picker, a dialog or Android's settings page: full screen again
        if (hasFocus && !settingsMode) hideSystemBars();
    }

    // Long press on the app icon -> "Настройки". A dynamic shortcut: it uses the real package name at run time.
    private void publishSettingsShortcut() {
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            Intent intent = new Intent(this, LauncherActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_OPEN_SETTINGS, true);
            android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, "settings")
                    .setShortLabel(getString(R.string.settings))
                    .setLongLabel(getString(R.string.settings))
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_settings))
                    .setIntent(intent)
                    .build();
            sm.setDynamicShortcuts(java.util.Collections.singletonList(shortcut));
        } catch (Exception ignored) {
            // no shortcut: the settings stay reachable only after a failed start
        }
    }

    /** The game found or chosen (FirstRun): remembered, hidden from the gallery, started; this screen closes. */
    void useGame(File dir) {
        prefs.edit().putString("gamePath", dir.getAbsolutePath()).apply();
        launchGame(null, 0);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (firstRun != null && firstRun.busy) return;  // unpacking / downloading: the files would stay half-written
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (code == PICK_GAME_FOLDER && pathEdit != null) {
            File picked = GameSource.gameFolderIn(GameSource.folderFromTree(data.getData()));
            if (picked != null) {
                pathEdit.setText(picked.getAbsolutePath());
                check();
                loadSaves();
            } else {
                status.setText(Tr.t("\nВ выбранной папке нет игры (нужен bin/arm64/factorio)."));
            }
        } else if (firstRun != null) {
            firstRun.onPicked(code, data.getData());
        }
    }

    /** The settings screen (the shortcut). */
    private void buildSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(Tr.t("FNative — лаунчер Factorio для Android (тест)\nНеофициальный, не одобрен Wube. Бесплатно и некоммерчески."));
        root.addView(title);

        TextView label = new TextView(this);
        label.setText(Tr.t("\nПапка игры (в ней bin и data) или путь к bin/arm64/factorio:"));
        root.addView(label);

        pathEdit = new EditText(this);
        pathEdit.setSingleLine(true);
        pathEdit.setText(prefs.getString("gamePath", DEFAULT_PATH));
        root.addView(pathEdit);

        Button pick = new Button(this);
        pick.setText(Tr.t("Выбрать папку игры…"));
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Download"));
            try {
                startActivityForResult(intent, PICK_GAME_FOLDER);
            } catch (Exception e) {
                status.setText(Tr.t("\nНе открыть выбор папки: ") + e.getMessage());
            }
        });
        root.addView(pick);

        Button access = new Button(this);
        access.setText(Tr.t("Разрешить доступ к файлам"));
        access.setOnClickListener(v -> requestAccess(null));
        root.addView(access);

        Button start = new Button(this);
        start.setText(Tr.t("Запустить"));
        start.setOnClickListener(v -> start());
        root.addView(start);

        status = new TextView(this);
        root.addView(status);

        addUpscaleSettings(root);

        addCacheSection(root);
        addBenchSection(root);

        TextView envLabel = new TextView(this);
        envLabel.setText(Tr.t("\nПеременные (по одной на строку: ИМЯ=значение; строка с # выключена).\n")
                + Tr.t("Сохраняются в Download/FactorioNative/env.txt при запуске."));
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
        // back from Android's "all files access" page: go on (allowed or not)
        if (accessAsked) {
            accessAsked = false;
            afterAccessAnswer();
        }
        if (!settingsMode) return;
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
        outputText.setText(Tr.t("Разрешение экрана: ") + o + Tr.t("% — выход ") + ow + "×" + oh
                + (o < 100 ? Tr.t(" (растягивает дисплей до ") + w + "×" + h + Tr.t(", бесплатно)") : Tr.t(" (родное)")));
        if (upscaleOn.isChecked()) {
            int pixels = Math.round(p * p * o * o / 1000000f);
            scaleText.setText(Tr.t("Масштаб рендера: ") + p + Tr.t("%\nИгра рисует ") + rw + "×" + rh + Tr.t(" → GSR/билинейный до ")
                    + ow + "×" + oh + (o < 100 ? Tr.t(" → дисплей до ") + w + "×" + h : "")
                    + Tr.t("\nРисуется ") + pixels + Tr.t("% пикселей от родного экрана"));
        } else {
            scaleText.setText(Tr.t("Масштаб рендера: ") + p + Tr.t("% — апскейл выключен, игра рисует ") + ow + "×" + oh);
        }
        boolean on = upscaleOn.isChecked();
        scaleBar.setEnabled(on);
        upGsr.setEnabled(on);
        upBilinear.setEnabled(on);
    }

    private void addUpscaleSettings(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText(Tr.t("\nРазрешение: выход игры (растягивает дисплей) и апскейл внутри него")
                + Tr.t(" (игра рисует меньше, GSR растягивает). Снижает нагрузку на видеокарту;")
                + Tr.t(" при апскейле интерфейс игры крупнее."));
        root.addView(title);

        outputText = new TextView(this);
        root.addView(outputText);
        outputBar = new android.widget.SeekBar(this);
        outputBar.setMax(10);  // 50, 55, ... 100 %
        outputBar.setProgress((prefs.getInt("outputScale", 100) - 50) / 5);
        root.addView(outputBar);

        angleOn = new android.widget.CheckBox(this);
        angleOn.setText(Tr.t("ANGLE (системный, OpenGL ES через Vulkan) — если на телефоне его нет, будет обычный драйвер"));
        angleOn.setChecked(prefs.getBoolean("angle", false));
        root.addView(angleOn);

        powerMeter = new android.widget.CheckBox(this);
        powerMeter.setText(Tr.t("Показывать HUD: FPS, UPS, ватты (+ график времени кадра)"));
        powerMeter.setChecked(prefs.getBoolean("powerMeter", true));
        root.addView(powerMeter);

        upscaleOn = new android.widget.CheckBox(this);
        upscaleOn.setText(Tr.t("Апскейл включён"));
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
        upGsr.setText(Tr.t("Snapdragon GSR — чётче (≈0,3 мс на кадр)"));
        upGsr.setId(View.generateViewId());
        upBilinear = new android.widget.RadioButton(this);
        upBilinear.setText(Tr.t("Билинейный — мягче, дешевле (≈0,15 мс)"));
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

    /** The settings screen's choices into the preferences (the game is started from those). */
    private void saveUiPrefs() {
        if (pathEdit == null) return;
        prefs.edit()
                .putString("gamePath", gameDir().getAbsolutePath())
                .putBoolean("upscaleOn", upscaleOn.isChecked())
                .putInt("renderScale", scalePercent())
                .putInt("outputScale", outputPercent())
                .putBoolean("powerMeter", powerMeter.isChecked())
                .putBoolean("angle", angleOn.isChecked())
                .putBoolean("atlasCache", atlasCache.isChecked())
                .putBoolean("etc2Cache", etc2Cache.isChecked())
                .putString("upscaler", upBilinear.isChecked() ? "bilinear" : "gsr")
                .apply();
    }

    /** Settings for the game process, as "--fn-env KEY=VALUE" arguments (from the preferences). */
    private List<String> settingArgs() {
        boolean bc = gpuHasBc();
        List<String> a = new ArrayList<>();
        a.add("--fn-env");
        a.add("FN_ANGLE=" + (prefs.getBoolean("angle", false) ? "1" : "0"));
        a.add("--fn-env");
        a.add("FNGL_ETC2_CACHE=" + (!bc && prefs.getBoolean("etc2Cache", false) ? etc2CacheDir().getAbsolutePath() : ""));
        if (prefs.getBoolean("upscaleOn", false)) {
            a.add("--fn-env");
            a.add("FN_RENDER_SCALE=" + (prefs.getInt("renderScale", 75) / 100f));
            a.add("--fn-env");
            a.add("FN_UPSCALER=" + ("bilinear".equals(prefs.getString("upscaler", "gsr")) ? "bilinear" : "gsr"));
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
            status.append(Tr.t("\nНе удалось прочитать env.txt: ") + e);
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
            status.setText(Tr.t("\nНе удалось записать env.txt: ") + e);
            return false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (settingsMode) saveUiPrefs();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_ACCESS) afterAccessAnswer();
    }

    private boolean accessAsked;

    private void afterAccessAnswer() {
        if (settingsMode) {
            check();
            loadEnv();
        }
        Runnable then = afterAccess;
        afterAccess = null;
        if (then == null) return;
        if (hasAccess() || firstRun == null) then.run();
        else firstRun.showSource();  // refused: the choices, which ask again
    }

    /**
     * One question, "access to files": our note, then Android's "all files access" page with its one switch
     * (the old storage permission is split by Android 13+ into photos/videos and music/audio questions).
     * Afterwards [then] runs (also when refused). Without that page (rare): the old permission.
     */
    void requestAccess(Runnable then) {
        afterAccess = then;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.storage_permission_title)
                .setMessage(R.string.storage_permission_needed)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> afterAccessAnswer())
                .setPositiveButton(R.string.storage_permission_allow, (d, w) -> {
                    try {
                        if (android.os.Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Android 10: no such page");
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.fromParts("package", getPackageName(), null)));
                        accessAsked = true;
                    } catch (Exception e) {
                        requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_ACCESS);
                    }
                })
                .show();
    }

    /** All files access, or the old storage permission (given to earlier versions: still enough). */
    boolean hasAccess() {
        return (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Game folder; also accepts the path of bin/arm64/factorio itself. */
    /** Logs, config and the game's write-data: readable from Termux and file managers. */
    private static File workDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/FactorioNative");
    }

    /** The game folder in the preferences (FirstRun or the settings put it there). */
    private File savedGameDir() {
        return new File(prefs.getString("gamePath", DEFAULT_PATH));
    }

    private File gameDir() {
        if (pathEdit == null) return savedGameDir();
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
            sb.append(Tr.t("Нет доступа к файлам: нажмите «Разрешить доступ к файлам».\n"));
            ok = false;
        }
        File bin = new File(gameDir(), "bin/arm64/factorio");
        File data = new File(gameDir(), "data");
        if (ok && !bin.canRead()) { sb.append(Tr.t("Не найден ")).append(bin).append('\n'); ok = false; }
        if (ok && !data.isDirectory()) { sb.append(Tr.t("Не найдена папка ")).append(data).append('\n'); ok = false; }
        if (ok) sb.append(Tr.t("Игра найдена.\n"));
        File ext = workDir();
        sb.append(Tr.t("\nСохранения и моды: ")).append(new File(gameDir(), "saves")).append(", ").append(new File(gameDir(), "mods"));
        sb.append(Tr.t("\nЖурнал загрузчика: ")).append(new File(ext, "fnative.log"));
        sb.append(Tr.t("\nЖурнал игры: ")).append(new File(gameDir(), "factorio-current.log"));
        status.setText(sb.toString());
        return ok;
    }

    private void start() {
        launch(null, 0);
    }

    /** From the settings screen: its choices saved and checked, then the game. */
    private void launch(File benchSave, int benchSeconds) {
        pathEdit.setText(gameDir().getAbsolutePath());
        saveUiPrefs();
        if (!check()) return;
        if (!saveEnv()) return;
        launchGame(benchSave, benchSeconds);
    }

    /**
     * Starts the game with the saved settings; with a save and seconds > 0 it runs the benchmark on that save.
     * The game folder gets a .nomedia so the gallery does not fill with its textures.
     */
    private void launchGame(File benchSave, int benchSeconds) {
        GameSource.ensureNoMedia(gameDir());
        // the game's atlas cache written before v0.113 has blocks lost by the Adreno readback (sprites jumping):
        // dropped once, the game writes it again
        if (!prefs.getBoolean("atlasCacheFix113", false)) {
            File old = atlasCacheFile();
            if (old.isFile()) old.delete();
            prefs.edit().putBoolean("atlasCacheFix113", true).apply();
        }
        File ext = workDir();
        // The game folder is the write-data folder, like the zip version of
        // Factorio: saves, mods and player data are the ones already there.
        File write = gameDir();
        File config = new File(ext, "config/config.ini");
        write.mkdirs();
        config.getParentFile().mkdirs();
        // the game's language follows the phone's on every start (English if the game lacks it), unless the player
        // picked another one in the game: a value in the config that the app did not write. The language there
        // before v0.118 counts as the app's once (v0.115 kept any language already set: the phone's change was lost).
        String locale = null, current = configValue(config, "[general]", "locale");
        String ours = prefs.getBoolean("gameLocaleFollow118", false) ? prefs.getString("gameLocaleOurs", null) : current;
        boolean playerChose = current != null && !current.isEmpty() && !current.equals("auto") && !current.equals(ours);
        if (!playerChose) {
            String want = gameLocale(new File(gameDir(), "data"));
            if (want == null) want = "en";
            if (!want.equals(current)) locale = want;
            ours = want;
        }
        try {
            writePaths(config, new File(gameDir(), "data").getAbsolutePath(), write.getAbsolutePath(),
                    // the game's atlas cache only with native BC: on Mali the BC readback it needs is not possible
                    gpuHasBc() && prefs.getBoolean("atlasCache", false), locale);
            prefs.edit().putBoolean("gameLocaleFollow118", true).putString("gameLocaleOurs", ours).apply();
        } catch (IOException e) {
            if (status != null) status.setText(Tr.t("\nНе удалось записать config.ini: ") + e);
            else android.widget.Toast.makeText(this, Tr.t("Не удалось записать config.ini: ") + e, android.widget.Toast.LENGTH_LONG).show();
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
                .putExtra(GameActivity.EXTRA_OUTPUT_SCALE, prefs.getInt("outputScale", 100) / 100f)
                .putExtra(GameActivity.EXTRA_POWER_METER, prefs.getBoolean("powerMeter", true));
        if (benchSave != null) {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
            String name = benchSave.getName().replaceAll("\\.zip$", "");
            String info = Tr.t("Сохранение: ") + benchSave.getName()
                    + Tr.t("\nВерсия: ") + appVersion()
                    + Tr.t("\nНастройки: экран ") + outputPercent() + Tr.t("%, апскейл ")
                    + (upscaleOn.isChecked() ? scalePercent() + "% " + (upBilinear.isChecked() ? "bilinear" : "GSR") : Tr.t("выкл"))
                    + ", ANGLE " + (angleOn.isChecked() ? Tr.t("вкл") : Tr.t("выкл"))
                    + Tr.t("\nПеременные: ") + String.join(" ", activeEnv());
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

    /** Whether the GPU has native BC (Adreno-like); probed once, then kept in the preferences. */
    private boolean gpuHasBc() {
        if (!prefs.contains("gpuHasBc")) {
            probeGpu();
            prefs.edit().putBoolean("gpuHasBc", gpuHasBc).putString("gpuName", String.valueOf(gpuName)).apply();
        }
        return prefs.getBoolean("gpuHasBc", false);
    }
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
        prefs.edit().putBoolean("gpuHasBc", gpuHasBc).putString("gpuName", String.valueOf(gpuName)).apply();
        TextView t = new TextView(this);
        t.setText(Tr.t("\nВидеокарта: ") + gpuName + (gpuHasBc ? Tr.t(" — BC-сжатие есть (тип Adreno)") : Tr.t(" — BC-сжатия нет (тип Mali)"))
                + Tr.t("\nКэш ускоряет загрузку со второго запуска. Доступна только галочка для вашей видеокарты."));
        root.addView(t);

        atlasCache = new android.widget.CheckBox(this);
        atlasCache.setText(Tr.t("Кэш атласов игры (Adreno / есть BC) — ~0,7 ГБ в папке игры"));
        atlasCache.setEnabled(gpuHasBc);
        atlasCache.setChecked(gpuHasBc && prefs.getBoolean("atlasCache", false));
        root.addView(atlasCache);

        etc2Cache = new android.widget.CheckBox(this);
        etc2Cache.setText(Tr.t("Кэш ETC2 (Mali / нет BC) — ~3 ГБ в Download/FactorioNative/etc2-cache"));
        etc2Cache.setEnabled(!gpuHasBc);
        etc2Cache.setChecked(!gpuHasBc && prefs.getBoolean("etc2Cache", false));
        root.addView(etc2Cache);

        cacheInfo = new TextView(this);
        root.addView(cacheInfo);
        Button clear = new Button(this);
        clear.setText(Tr.t("Очистить кэши"));
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
        cacheInfo.setText(String.format(java.util.Locale.ROOT, Tr.t("Размер: атласы игры %d МБ, ETC2 %d МБ"),
                sizeOf(atlasCacheFile()) >> 20, sizeOf(etc2CacheDir()) >> 20));
    }

    // ---- benchmark section: a save from the game's saves folder, 60 s

    private android.widget.Spinner benchSaves;
    private File[] saveFiles = new File[0];

    private void addBenchSection(LinearLayout root) {
        TextView t = new TextView(this);
        t.setText(Tr.t("\nЗамер производительности: игра загрузит сохранение, 10 с прогреет, 60 с запишет")
                + Tr.t(" FPS, UPS, ватты, ЦП и ГП, сама закроется. Итог — файл bench-*.txt в Download/FactorioNative.")
                + Tr.t(" Не трогайте экран во время замера."));
        root.addView(t);
        benchSaves = new android.widget.Spinner(this);
        root.addView(benchSaves);
        Button run = new Button(this);
        run.setText(Tr.t("Запустить замер"));
        run.setOnClickListener(v -> {
            int i = benchSaves.getSelectedItemPosition();
            if (i < 0 || i >= saveFiles.length) {
                status.setText(Tr.t("\nНет сохранений в ") + new File(gameDir(), "saves"));
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

    /** Factorio's languages (data/core/locale of 2.1): the phone's languages are matched against them. */
    private static final java.util.Set<String> GAME_LOCALES = new java.util.HashSet<>(java.util.Arrays.asList(
            "af", "ar", "be", "bg", "ca", "cs", "da", "de", "el", "en", "eo", "es-ES", "et", "eu", "fa", "fi", "fil",
            "fr", "fy-NL", "ga-IE", "he", "hr", "hu", "id", "is", "it", "ja", "ka", "kk", "ko", "lt", "lv", "nl", "no",
            "pl", "pt-BR", "pt-PT", "ro", "ru", "sk", "sl", "sq", "sr", "sv-SE", "th", "tr", "uk", "vi", "zh-CN", "zh-TW"));

    /**
     * The game's language for the phone's languages, in the player's order ("de", "pt-BR", "zh-TW"…): the first
     * one the list has and the game folder has too. Null if none.
     */
    static String gameLocale(File data) {
        android.os.LocaleList phone = android.os.LocaleList.getDefault();
        for (int i = 0; i < phone.size(); i++)
            for (String code : localeCandidates(phone.get(i)))
                if (GAME_LOCALES.contains(code) && new File(data, "core/locale/" + code).isDirectory()) return code;
        return null;
    }

    private static List<String> localeCandidates(java.util.Locale l) {
        String lang = l.getLanguage(), region = l.getCountry(), script = l.getScript();
        switch (lang) {  // Android's old and other codes for the same languages
            case "in": lang = "id"; break;
            case "iw": lang = "he"; break;
            case "nb": case "nn": lang = "no"; break;
            case "tl": lang = "fil"; break;
        }
        List<String> c = new ArrayList<>();
        if (lang.equals("zh")) {  // traditional characters: Taiwan, Hong Kong, Macau
            boolean traditional = script.equals("Hant") || (script.isEmpty() && (region.equals("TW") || region.equals("HK") || region.equals("MO")));
            c.add(traditional ? "zh-TW" : "zh-CN");
            return c;
        }
        if (!region.isEmpty()) c.add(lang + "-" + region);
        c.add(lang);
        // the languages the game keeps under one region only (es-ES, sv-SE, ga-IE…), and pt-BR / pt-PT for the other
        for (String g : GAME_LOCALES) if (g.startsWith(lang + "-") && !c.contains(g)) c.add(g);
        return c;
    }

    /** The value of [key] in [section] of the ini file, or null. */
    private static String configValue(File config, String section, String key) {
        if (!config.isFile()) return null;
        try {
            String sec = "";
            for (String line : Files.readAllLines(config.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("[")) sec = t;
                else if (sec.equals(section) && t.startsWith(key + "=")) return t.substring(key.length() + 1).trim();
            }
        } catch (IOException ignored) {
            // as if there were none
        }
        return null;
    }

    /**
     * Keeps the game's own config.ini; sets [path] and the atlas cache keys of [graphics]. The lines before the
     * first section stay first: the game wants "; version=13" as the first line, else it says "Configuration file
     * has invalid contents" (up to v0.54 [path] went above it, and the question came on every start).
     */
    private static void writePaths(File config, String readData, String writeData, boolean atlasCache, String locale) throws IOException {
        List<String> head = new ArrayList<>();
        List<String> out = new ArrayList<>();
        out.add("[path]");
        out.add("read-data=" + readData);
        out.add("write-data=" + writeData);
        String[] cacheKeys = {"cache-sprite-atlas=" + atlasCache, "compress-sprite-atlas-cache=true"};
        boolean graphicsSeen = false, generalSeen = false;
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
                    if (section.equals("[general]") && locale != null) {
                        generalSeen = true;
                        out.add("locale=" + locale);
                    }
                    continue;
                }
                if (section.isEmpty()) {  // before the first section: "; version=13"
                    head.add(line);
                    continue;
                }
                if (section.equals("[path]")) continue;
                if (section.equals("[graphics]") && (t.startsWith("cache-sprite-atlas=") || t.startsWith("compress-sprite-atlas-cache=")))
                    continue;
                if (section.equals("[general]") && locale != null && t.startsWith("locale=")) continue;
                out.add(line);
            }
        }
        if (!graphicsSeen) {
            out.add("[graphics]");
            java.util.Collections.addAll(out, cacheKeys);
        }
        if (locale != null && !generalSeen) {
            out.add("[general]");
            out.add("locale=" + locale);
        }
        // the second quick bar row has no keys by default: Ctrl + 1…0 for the app's quick bar panel (the
        // player's own binding, an uncommented line, stays)
        java.util.Set<String> bound = new java.util.HashSet<>();
        String sec = "";
        for (String l : out) {
            String t = l.trim();
            if (t.startsWith("[")) sec = t;
            else if (sec.equals("[controls]") && t.matches("quick-bar-button-\\d+-secondary=.+")) bound.add(t.substring(0, t.indexOf('=')));
        }
        List<String> keys = new ArrayList<>();
        for (int n = 1; n <= 10; n++)
            if (!bound.contains("quick-bar-button-" + n + "-secondary")) keys.add("quick-bar-button-" + n + "-secondary=CONTROL + " + (n % 10));
        int at = out.indexOf("[controls]");
        if (at >= 0) out.addAll(at + 1, keys);
        else if (!keys.isEmpty()) {
            out.add("[controls]");
            out.addAll(keys);
        }
        boolean versioned = false;
        for (String l : head) versioned |= l.trim().startsWith("; version=");
        // the config format of Factorio 2.x (a file the game wrote itself keeps its own line)
        if (!versioned) head.add(0, "; version=13");
        head.addAll(out);
        try (FileOutputStream f = new FileOutputStream(config)) {
            f.write((String.join("\n", head) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
