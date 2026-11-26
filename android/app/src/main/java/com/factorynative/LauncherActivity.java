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
    // from the game after its own update (GameActivity.restartTick): start it again once its process has ended
    static final String EXTRA_RESTART_GAME = "restart_game";
    // from the game's gear menu: move the game folder "internal" (the app's storage) or "shared" (Download), then start it
    static final String EXTRA_MOVE_GAME = "move_game";
    private static final String DEFAULT_PATH = "/storage/emulated/0/Download/factorio";
    private static final int REQ_ACCESS = 1;
    private boolean settingsMode;
    private TextView status;
    private SharedPreferences prefs;
    private FirstRun firstRun;
    private Runnable afterAccess;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Tr.init(this);  // the app's texts in the phone's language
        prefs = launcherPrefs();
        GameSource.init(getFilesDir());
        publishSettingsShortcut();
        settingsMode = getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false);
        if (settingsMode) {
            // the theme is full screen (for the start); the settings keep the status bar
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            buildSettings();
            return;
        }
        // a move of the game folder: asked from the game, or one cut off before (it goes on first)
        String moveTo = getIntent().getStringExtra(EXTRA_MOVE_GAME);
        if (hasAccess() && (moveTo != null || GameMover.pending(this) != null)) {
            moveScreen();
            whenGameGone(System.currentTimeMillis(), () -> startMove(moveTo));
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_RESTART_GAME, false) && hasAccess() && GameSource.isGameFolder(gameDir())) {
            View cover = new View(this);
            cover.setBackgroundResource(R.drawable.loading_background);
            setContentView(cover);
            whenGameGone(System.currentTimeMillis(), () -> {
                launchGame(null, 0);
                finish();
            });
            return;
        }
        // a normal start: the game known and in place starts right away, nothing of this screen shows
        if (hasAccess() && GameSource.isGameFolder(gameDir())) {
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

    /** Waits (up to 5 s) for the game's process to end (after its update, before a move), then `then`. */
    private void whenGameGone(long since, Runnable then) {
        boolean alive = false;
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list = am != null ? am.getRunningAppProcesses() : null;
        if (list != null)
            for (android.app.ActivityManager.RunningAppProcessInfo p : list) alive |= p.processName.equals(getPackageName() + ":game");
        if (alive && System.currentTimeMillis() - since < 5000) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> whenGameGone(since, then), 200);
            return;
        }
        then.run();
    }

    // ---- moving the game folder (GameMover): a screen with progress, then the game from its new place

    private TextView moveWhere, movePercent, moveFile, moveText;
    private android.widget.ProgressBar moveBar;
    private LinearLayout moveBox;

    /** Multi-process: the game's "Графика" page (another process) writes the resolution settings here too. */
    @SuppressWarnings("deprecation")
    private android.content.SharedPreferences launcherPrefs() {
        return getSharedPreferences("launcher", MODE_MULTI_PROCESS);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The move's screen, in the look of the first start (FirstRun): dark glow, logo, orange title and bar. */
    private void moveScreen() {
        moveBox = new LinearLayout(this);
        moveBox.setOrientation(LinearLayout.VERTICAL);
        moveBox.setGravity(android.view.Gravity.CENTER);
        moveBox.setBackgroundResource(R.drawable.loading_background);
        moveBox.setPadding(dp(48), dp(16), dp(48), dp(16));
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.graphics.Bitmap known = GameSource.downloadedLogo();
        if (known != null) logo.setImageBitmap(known);
        else new Thread(() -> {
            android.graphics.Bitmap web = GameSource.downloadLogo();
            if (web != null) runOnUiThread(() -> logo.setImageBitmap(web));
        }, "fnative-logo").start();
        moveBox.addView(logo, new LinearLayout.LayoutParams(dp(300), dp(50)));
        TextView title = moveLine(20, 0xFFF29A1F);
        title.setText(Tr.t("Перенос игры"));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(18), 0, dp(4));
        moveWhere = moveLine(15, 0xFFFFE6C0);
        moveWhere.setPadding(0, 0, 0, dp(12));
        moveBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        moveBar.setMax(1000);
        moveBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF29A1F));
        moveBox.addView(moveBar, new LinearLayout.LayoutParams(dp(420), LinearLayout.LayoutParams.WRAP_CONTENT));
        movePercent = moveLine(13, 0xFFB8B0A8);
        moveFile = moveLine(11, 0xFF8A8278);
        moveFile.setSingleLine();
        moveFile.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        moveText = moveLine(13, 0xFFFFB050);
        moveText.setPadding(0, dp(14), 0, 0);
        moveText.setText(Tr.t("Жду закрытия игры…"));
        setContentView(moveBox);
    }

    private TextView moveLine(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(android.view.Gravity.CENTER);
        moveBox.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private String placeName(File dir) {
        return GameMover.isInternal(this, dir) ? Tr.t("внутренняя память") : Tr.t("общая память (Download)");
    }

    private void startMove(String where) {
        File[] pend = GameMover.pending(this);
        File from, to;
        if (pend != null) {
            from = pend[0];
            to = pend[1];
        } else {
            from = gameDir();
            if (!GameSource.isGameFolder(from)) {
                moveFailed(Tr.t("Игра не найдена: ") + from, false);
                return;
            }
            boolean internal = "internal".equals(where);
            if (internal == GameMover.isInternal(this, from)) {
                launchGame(null, 0);  // already there
                finish();
                return;
            }
            to = internal ? GameMover.internalDir(this) : GameMover.sharedTarget();
            String space = GameMover.spaceProblem(from, to);
            if (space != null) {
                moveFailed(Tr.t("Перенос не начат: ") + space, true);
                return;
            }
            try {
                GameMover.begin(this, from, to);
            } catch (IOException e) {
                moveFailed(Tr.t("Перенос не начат: ") + e.getMessage(), true);
                return;
            }
        }
        File src = from, dst = to;
        moveWhere.setText(placeName(src) + "  →  " + placeName(dst));
        moveText.setText(Tr.t("Не закрывайте приложение. Если перенос прервётся, он продолжится при следующем запуске."));
        long start = System.currentTimeMillis();
        new Thread(() -> {
            try {
                GameMover.run(this, src, dst, (done, total, file) -> runOnUiThread(() -> {
                    int permille = total > 0 ? (int) (done * 1000 / total) : 0;
                    moveBar.setProgress(permille);
                    double s = Math.max(0.1, (System.currentTimeMillis() - start) / 1000.0);
                    movePercent.setText(String.format(java.util.Locale.ROOT, Tr.t("%d %% · %.2f из %.2f ГБ · %.0f МБ/с"),
                            permille / 10, done / 1073741824.0, total / 1073741824.0, done / 1048576.0 / s));
                    moveFile.setText(file);
                }));
                runOnUiThread(() -> {
                    prefs.edit().putString("gamePath", dst.getAbsolutePath()).apply();
                    android.widget.Toast.makeText(this, Tr.t("Игра перенесена: ") + dst, android.widget.Toast.LENGTH_LONG).show();
                    launchGame(null, 0);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> moveFailed(Tr.t("Перенос прервался: ") + e.getMessage()
                        + Tr.t("\nОн продолжится при следующем запуске приложения (или нажми «Повторить»)."), false));
            }
        }, "fnative-move").start();
    }

    /** A choice in the look of the first start's cards. */
    private TextView moveButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(16);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackgroundResource(R.drawable.source_card);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private void moveFailed(String why, boolean canPlay) {
        moveText.setText(why);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER);
        row.setPadding(0, dp(16), 0, 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.setMargins(dp(8), 0, dp(8), 0);
        row.addView(moveButton(Tr.t("Повторить"), v -> {
            moveBox.removeView(row);
            startMove(getIntent().getStringExtra(EXTRA_MOVE_GAME));
        }), bl);
        if (canPlay)  // nothing moved yet: the game is whole where it was
            row.addView(moveButton(Tr.t("Запустить игру"), v -> {
                launchGame(null, 0);
                finish();
            }), bl);
        moveBox.addView(row);
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

    // Long press on the app icon -> "Тесты" (the benchmark and the lab run; the settings are in the game's gear menu).
    // A dynamic shortcut: it uses the real package name at run time.
    private void publishSettingsShortcut() {
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            Intent intent = new Intent(this, LauncherActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_OPEN_SETTINGS, true);
            android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, "settings")
                    .setShortLabel(getString(R.string.tests))
                    .setLongLabel(getString(R.string.tests))
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
        if (firstRun != null) firstRun.onPicked(code, data.getData());
    }

    /** The settings screen (the shortcut): measurements only (a benchmark and the lab run); the settings are in
     * the game's gear menu. */
    private void buildSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        access = new Button(this);  // only while there is no file access (the saves cannot be listed without it)
        access.setText(Tr.t("Разрешить доступ к файлам"));
        access.setOnClickListener(v -> requestAccess(null));
        root.addView(access);

        status = new TextView(this);
        root.addView(status);

        addBenchSection(root);

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
        // the game's "Графика" page (another process) may have changed settings: read them again
        prefs = launcherPrefs();
        if (!settingsMode) return;
        check();
        if (hasAccess()) loadSaves();
        labResumed();  // a lab run goes on
    }

    private static File envFile() {
        return new File(workDir(), "env.txt");
    }

    /** Settings for the game process, as "--fn-env KEY=VALUE" arguments (from the preferences). */
    private List<String> settingArgs() {
        boolean bc = gpuHasBc();
        List<String> a = new ArrayList<>();
        a.add("--fn-env");
        a.add("FN_ANGLE=" + (angleWanted() ? "1" : "0"));
        a.add("--fn-env");
        // mimalloc again (0.196, the user's choice; off in 0.195): with its freed memory returned at once
        // (loader/alloc.c purge_delay 0) the main menu takes 1.07 GB instead of 3.8 GB, ~1 % slower than before
        a.add("FN_MALLOC=mimalloc");
        a.add("--fn-env");
        a.add("FN_RENDERER=" + (vulkanWanted() ? "nvk" : "gles"));  // one Vulkan renderer: nvk (the GL bridge is gone, 0.141)
        a.add("--fn-env");
        a.add("FNGL_ETC2_CACHE=");  // the old ETC2 upload cache is gone (0.141): the game's own atlas cache instead
        a.add("--fn-env");
        a.add("FN_PROF_DIR=" + workDir().getAbsolutePath());
        if (appVersion().contains("-prof")) {  // a profiling test build: threads.log, prof.log, sys.log unless env.txt says 0
            a.add("--fn-env");
            a.add("FN_PROF_AUTO=1");
        }
        a.add("--fn-env");
        a.add("FN_ETC2_DIRECT_STATUS=" + etc2DirectStatus().getAbsolutePath());
        a.add("--fn-env");
        a.add("FN_ETC2_DIRECT_KEY=" + gameKey());
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

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_ACCESS) afterAccessAnswer();
    }

    private boolean accessAsked;

    private void afterAccessAnswer() {
        if (settingsMode) check();
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

    /** The game folder in the preferences (FirstRun or the game's location move put it there). */
    private File gameDir() {
        return new File(prefs.getString("gamePath", DEFAULT_PATH));
    }

    private Button access;

    /** Whether the game can start: true when it can; else the reason in the status line. */
    private boolean check() {
        if (access != null) access.setVisibility(hasAccess() ? View.GONE : View.VISIBLE);
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
        status.setText(ok ? "" : sb.toString());
        return ok;
    }

    /** From the settings screen: checked, then the game with the save measured. */
    private void launch(File benchSave, int benchSeconds) {
        if (!check()) return;
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
        // 0.141-0.144 with the Vulkan renderer on a GPU with BC wrote the game's atlas cache from zeros (nvk read the
        // compressed atlases back as zeros): dropped once there, the game writes it again
        if (gpuHasBc() && !prefs.getBoolean("atlasCacheFix145", false)) {
            File old = atlasCacheFile();
            if (old.isFile()) old.delete();
            prefs.edit().putBoolean("atlasCacheFix145", true).apply();
        }
        // the network log and test reports (up to 0.159) held addresses and times of games: deleted once
        if (!prefs.getBoolean("netLogsRemoved160", false)) {
            File[] l = workDir().listFiles((d, n) -> n.equals("net.log") || n.startsWith("net-test-"));
            if (l != null) for (File f : l) f.delete();
            prefs.edit().putBoolean("netLogsRemoved160", true).apply();
        }
        // the old ETC2 upload cache (FNGL_ETC2_CACHE, up to 0.140) is not used any more: its files go once
        if (!prefs.getBoolean("oldEtc2CacheRemoved141", false)) {
            File[] l = oldEtc2CacheDir().listFiles();
            if (l != null) for (File f : l) f.delete();
            oldEtc2CacheDir().delete();
            prefs.edit().putBoolean("oldEtc2CacheRemoved141", true).apply();
        }
        // The game's atlas cache holds BC (GPU with BC) or the ETC2 blocks the game wrote itself (no BC: libfnsdl
        // tex/direct.c). Dropped when that changes, and without BC when the last start with this game binary could not
        // make the game write ETC2: that cache was written from textures that cannot be read back.
        boolean directFailed = !gpuHasBc() && etc2DirectFailed();
        String cacheFormat = gpuHasBc() ? "bc" : "etc2";
        if (!cacheFormat.equals(prefs.getString("atlasCacheFormat", "bc")) || directFailed) {
            File old = atlasCacheFile();
            if (old.isFile()) old.delete();
        }
        // "Сжатие текстур" (ASTC, 0.188-0.193) was taken out: its encoded atlases and files go once
        if (!prefs.getBoolean("astcRemoved194", false)) {
            File[] sizes = new File(getFilesDir(), "astc-cache").listFiles();
            if (sizes != null)
                for (File d : sizes) {
                    File[] l = d.listFiles();
                    if (l != null) for (File f : l) f.delete();
                    d.delete();
                }
            new File(getFilesDir(), "astc-cache").delete();
            new File(workDir(), "astc-block.txt").delete();
            new File(workDir(), "atlas-cache-bad").delete();
            prefs.edit().remove("astcBlock").putBoolean("astcRemoved194", true).apply();
        }
        prefs.edit().putString("atlasCacheFormat", cacheFormat).apply();
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
                    atlasCacheWanted() && !directFailed, locale);
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
                    + Tr.t("\nНастройки: экран ") + prefs.getInt("outputScale", 100) + Tr.t("%, апскейл ")
                    + (prefs.getBoolean("upscaleOn", false) ? prefs.getInt("renderScale", 75) + "% "
                            + ("bilinear".equals(prefs.getString("upscaler", "gsr")) ? "bilinear" : "GSR") : Tr.t("выкл"))
                    + ", ANGLE " + (angleWanted() ? Tr.t("вкл") : Tr.t("выкл"))
                    + ", mimalloc " + (prefs.getBoolean("mimalloc", true) ? Tr.t("вкл") : Tr.t("выкл"))
                    + Tr.t(", рендер ") + (vulkanWanted() ? "Vulkan" : "OpenGL ES")
                    + Tr.t("\nПеременные: ") + String.join(" ", activeEnv());
            game.putExtra(GameActivity.EXTRA_BENCH_SECONDS, benchSeconds)
                    .putExtra(GameActivity.EXTRA_BENCH_OUT, (labOut != null ? labOut : new File(ext, "bench-" + name + "-" + stamp + ".txt")).getAbsolutePath())
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

    /** Uncommented lines of env.txt. */
    private List<String> activeEnv() {
        List<String> out = new ArrayList<>();
        String text;
        try {
            text = new String(Files.readAllBytes(envFile().toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        for (String l : text.split("\n")) {
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

    /** The old ETC2 upload cache of FNGL_ETC2_CACHE (up to 0.140, ~2.5 GB): no longer used, deleted once. */
    private static File oldEtc2CacheDir() {
        return new File(workDir(), "etc2-cache");
    }

    /** The game's atlas cache: on by default (0.141: also switched on once where it had been left off). */
    private boolean atlasCacheWanted() {
        if (!prefs.getBoolean("atlasCacheDefault141", false))
            prefs.edit().putBoolean("atlasCache", true).putBoolean("atlasCacheDefault141", true).apply();
        return prefs.getBoolean("atlasCache", true);
    }

    /** The game binary as "size-mtime": libfnsdl writes back whether the game wrote ETC2 itself with it. */
    private String gameKey() {
        File b = new File(gameDir(), "bin/arm64/factorio");
        return b.length() + "-" + b.lastModified();
    }

    private static File etc2DirectStatus() {
        return new File(workDir(), "etc2-direct.txt");
    }

    /** The last start with this game binary on a GPU without BC could not make the game write ETC2 (another game version). */
    private boolean etc2DirectFailed() {
        try {
            String s = new String(Files.readAllBytes(etc2DirectStatus().toPath()), StandardCharsets.UTF_8).trim();
            return s.equals(gameKey() + " off");
        } catch (IOException e) {
            return false;
        }
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
        addLabRun(root);
    }

    /** Vulkan for this start: a lab run's step decides, else the gear menu's choice. */
    private boolean vulkanWanted() {
        return labOverride >= 0 ? labOverride == LAB_VULKAN : prefs.getBoolean("vulkan", false);
    }

    /** ANGLE for this start (OpenGL ES only): a lab run's step decides, else the gear menu's choice. */
    private boolean angleWanted() {
        return labOverride >= 0 ? labOverride == LAB_ANGLE : prefs.getBoolean("angle", false) && !vulkanWanted();
    }

    // ---- the lab run (engineering settings; from FNative Lab). The chosen save measured with each experiment in turn, each in a fresh game
    // process (the game ends itself after its measurement and the launcher comes back: onResume starts the next),
    // a cool-down pause between, several rounds in alternating order (the phone's heat does not favour one), then
    // a summary against the base. State in the preferences ("labRun*").

    // The renderers to compare, chosen with check boxes (kept as a bit mask in "labRunCfgs"); the first chosen is the
    // base of the summary. The other settings stay as set in the gear menu. File names keep these numbers.
    private static final String[] LAB_CFG_NAMES = {"OpenGL ES", "OpenGL ES (ANGLE)", "Vulkan"};
    private static final int LAB_GLES = 0, LAB_ANGLE = 1, LAB_VULKAN = 2;
    private static final int LAB_COOL_S = 1;  // the pause between steps (was 30 s for cooling; the user's choice, 2026-11-25)
    private int labOverride = -1;  // while a lab run starts a game: its step's renderer
    private final android.widget.CheckBox[] labCfgBoxes = new android.widget.CheckBox[LAB_CFG_NAMES.length];
    private File labOut;        // while a lab run starts a game: its step's result file
    private android.widget.Spinner labRounds;
    private TextView labStatus;
    private final android.os.Handler labHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable labNext = this::labStep;

    private void addLabRun(LinearLayout root) {
        TextView t = new TextView(this);
        t.setText("\nЛабораторный прогон: выбранное сохранение по очереди с каждым отмеченным рендером, каждый раз игра"
                + " запускается заново; пауза " + LAB_COOL_S + " с; круги в чередующемся порядке. Первый отмеченный — база"
                + " сравнения. Итог — Download/FactorioNative/lab-*/summary.txt. Телефон не на зарядке (ватты по батарее),"
                + " экран не трогать.");
        root.addView(t);
        int mask = prefs.getInt("labRunCfgs", 1 << LAB_GLES | 1 << LAB_VULKAN);
        for (int c = 0; c < LAB_CFG_NAMES.length; c++) {
            labCfgBoxes[c] = new android.widget.CheckBox(this);
            labCfgBoxes[c].setText(LAB_CFG_NAMES[c]);
            labCfgBoxes[c].setChecked((mask & 1 << c) != 0);
            root.addView(labCfgBoxes[c]);
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView rt = new TextView(this);
        rt.setText("Кругов: ");
        row.addView(rt);
        labRounds = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[] {"1", "2", "3", "4"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        labRounds.setAdapter(a);
        labRounds.setSelection(Math.max(0, Math.min(3, prefs.getInt("labRunRounds", 2) - 1)));
        row.addView(labRounds);
        root.addView(row);
        Button start = new Button(this);
        start.setText("Лабораторный прогон");
        start.setOnClickListener(v -> labStart());
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("Остановить прогон");
        stop.setOnClickListener(v -> labStop("Прогон остановлен."));
        root.addView(stop);
        labStatus = new TextView(this);
        root.addView(labStatus);
        labShowState();
    }

    /** The renderers of the run (LAB_GLES, LAB_ANGLE, LAB_VULKAN), in order. */
    private int[] labCfgs() {
        int mask = prefs.getInt("labRunCfgs", 1 << LAB_GLES | 1 << LAB_VULKAN), n = Integer.bitCount(mask), k = 0;
        int[] c = new int[n];
        for (int i = 0; i < LAB_CFG_NAMES.length; i++)
            if ((mask & 1 << i) != 0) c[k++] = i;
        return c;
    }

    private int labTotal() {
        return prefs.getInt("labRunRounds", 2) * labCfgs().length;
    }

    /** The renderer of step [s]: rounds alternate forward and backward. */
    private int labCfgOf(int s) {
        int[] c = labCfgs();
        int round = s / c.length, pos = s % c.length;
        return c[round % 2 == 0 ? pos : c.length - 1 - pos];
    }

    private File labDir() {
        return new File(workDir(), "lab-" + prefs.getString("labRunStamp", "x"));
    }

    private File labStepFile(int s) {
        return new File(labDir(), String.format(java.util.Locale.ROOT, "r%d-%d.txt", s / labCfgs().length + 1, labCfgOf(s)));
    }

    private void labStart() {
        int i = benchSaves.getSelectedItemPosition();
        if (i < 0 || i >= saveFiles.length) {
            status.setText(Tr.t("\nНет сохранений в ") + new File(gameDir(), "saves"));
            return;
        }
        int mask = 0;
        for (int c = 0; c < labCfgBoxes.length; c++)
            if (labCfgBoxes[c].isChecked()) mask |= 1 << c;
        if (mask == 0) {
            labStatus.setText("Отметьте хотя бы один рендер.");
            return;
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        prefs.edit().putInt("labRunCfgs", mask).putString("benchSave", saveFiles[i].getName()).putString("labRunSave", saveFiles[i].getName())
                .putString("labRunStamp", stamp).putInt("labRunRounds", labRounds.getSelectedItemPosition() + 1)
                .putInt("labRunStep", 0).putBoolean("labRunActive", true).apply();
        labDir().mkdirs();
        labStep();
    }

    private void labStop(String why) {
        labHandler.removeCallbacks(labNext);
        prefs.edit().putBoolean("labRunActive", false).apply();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (labStatus != null) labStatus.setText(why);
    }

    /** Starts the current step's game. */
    private void labStep() {
        if (!prefs.getBoolean("labRunActive", false)) return;
        int s = prefs.getInt("labRunStep", 0);
        File save = new File(new File(gameDir(), "saves"), prefs.getString("labRunSave", ""));
        if (!save.isFile()) {
            labStop("Прогон остановлен: нет сохранения " + save);
            return;
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);  // the pause must not sleep
        prefs.edit().putLong("labRunLaunched", System.currentTimeMillis()).apply();
        labOverride = labCfgOf(s);
        labOut = labStepFile(s);
        try {
            launch(save, 60);
        } finally {
            labOverride = -1;
            labOut = null;
        }
    }

    /** Back from a step's game (onResume): its result, then the pause and the next step, or the summary. */
    private void labResumed() {
        if (!prefs.getBoolean("labRunActive", false)) return;
        long launched = prefs.getLong("labRunLaunched", 0);
        if (System.currentTimeMillis() - launched < 5000) return;  // the game is only starting
        int s = prefs.getInt("labRunStep", 0);
        // the step's log (the next start writes a new one): what really was on
        File log = new File(workDir(), "fnative.log"), keep = new File(labDir(), labStepFile(s).getName().replace(".txt", ".log"));
        if (log.isFile() && !keep.exists()) {
            try {
                Files.copy(log.toPath(), keep.toPath());
            } catch (IOException ignored) {
                // the results matter more than the log
            }
        }
        if (!labStepFile(s).isFile()) {
            labStop("Прогон прерван: замер " + (s + 1) + " из " + labTotal() + " не записан (игра закрыта раньше?). Готовые — в " + labDir());
            labSummary(true);
            return;
        }
        s++;
        prefs.edit().putInt("labRunStep", s).apply();
        if (s >= labTotal()) {
            labStop("Прогон закончен.");
            labSummary(false);
            return;
        }
        labShowState();
        labHandler.removeCallbacks(labNext);
        labHandler.postDelayed(labNext, LAB_COOL_S * 1000L);
    }

    private void labShowState() {
        if (labStatus == null || !prefs.getBoolean("labRunActive", false)) return;
        int s = prefs.getInt("labRunStep", 0);
        labStatus.setText(String.format(java.util.Locale.ROOT, "Прогон: шаг %d из %d (%s, круг %d), пауза %d с перед ним…",
                s + 1, labTotal(), LAB_CFG_NAMES[labCfgOf(s)], s / labCfgs().length + 1, LAB_COOL_S));
    }

    /** {fps, ups, watts, cpu %, gpu %, update thread CPU ms per tick, game CPU ms per tick} from a step's "#lab" line, or null. */
    private static float[] labResult(File f) {
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (!line.startsWith("#lab ")) continue;
                float[] v = new float[7];
                String[] keys = {"fps", "ups", "w", "cpu", "gpu", "tickms", "gamems"};
                for (String part : line.substring(5).trim().split(" ")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) continue;
                    for (int k = 0; k < keys.length; k++)
                        if (part.substring(0, eq).equals(keys[k])) v[k] = Float.parseFloat(part.substring(eq + 1));
                }
                return v;
            }
        } catch (Exception ignored) {
            // no result
        }
        return null;
    }

    /** summary.txt: each configuration's mean over the rounds, its spread, and the difference to the base. */
    private void labSummary(boolean partial) {
        int rounds = prefs.getInt("labRunRounds", 2), n = LAB_CFG_NAMES.length, base = labCfgs()[0];
        int[] cfgs = labCfgs();
        float[][] sum = new float[n][7], min = new float[n][7], max = new float[n][7];
        int[] count = new int[n];
        int[][] got = new int[n][7];  // values per metric: -1 = not measured (watts on the charger, GPU load) is left out
        int[] fellBack = new int[n];  // Vulkan steps that ran on OpenGL ES (no usable device)
        for (int s = 0; s < rounds * cfgs.length; s++) {
            float[] v = labResult(labStepFile(s));
            if (v == null) continue;
            int c = labCfgOf(s);
            for (int k = 0; k < 7; k++) {
                if (v[k] < 0) continue;
                sum[c][k] += v[k];
                min[c][k] = got[c][k] == 0 ? v[k] : Math.min(min[c][k], v[k]);
                max[c][k] = got[c][k] == 0 ? v[k] : Math.max(max[c][k], v[k]);
                got[c][k]++;
            }
            count[c]++;
            try {
                File log = new File(labDir(), labStepFile(s).getName().replace(".txt", ".log"));
                if (log.isFile() && new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).contains("Vulkan is not usable"))
                    fellBack[c]++;
            } catch (IOException ignored) {
                // no log: not known
            }
        }
        for (int c = 0; c < n; c++)
            for (int k = 0; k < 7; k++)
                if (got[c][k] > 0) sum[c][k] /= got[c][k];  // sum = mean from here on
                else sum[c][k] = -1;
        java.util.Locale L = java.util.Locale.ROOT;
        StringBuilder r = new StringBuilder();
        r.append(String.format(L, "FNative — лабораторный прогон %s%s\nСохранение: %s, кругов %d, замер 60 с после 10 с прогрева, пауза %d с\n",
                prefs.getString("labRunStamp", ""), partial ? " (прерван, неполный)" : "", prefs.getString("labRunSave", ""), rounds, LAB_COOL_S));
        r.append(String.format(L, "Устройство: %s %s, Android %s\n\n", android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE));
        r.append("Конфигурация                   | замеров |  UPS (мин–макс)      ΔUPS  |  FPS   |  Ватты (мин–макс)    ΔВт  | ЦП % |"
                + " шаг, мс ЦП (мин–макс)  Δшаг  | игра, мс ЦП на шаг\n");
        float baseUps = sum[base][1], baseW = sum[base][2], baseTick = sum[base][5];
        StringBuilder notes = new StringBuilder();
        for (int c : cfgs) {
            if (count[c] == 0) {
                r.append(String.format(L, "%-30s |    0    |  —\n", LAB_CFG_NAMES[c]));
                continue;
            }
            float ups = sum[c][1], w = sum[c][2], tick = sum[c][5];
            String watts = w < 0 ? "   —  (на зарядке)          " : String.format(L, "%5.2f (%4.2f–%4.2f) %+6.1f%%", w, min[c][2], max[c][2],
                    baseW > 0 ? (w / baseW - 1) * 100 : 0);
            r.append(String.format(L, "%-30s |    %d    | %5.1f (%4.1f–%4.1f) %+6.1f%% | %5.1f  | %s | %4.0f |"
                            + " %5.2f (%5.2f–%5.2f) %+6.1f%% | %6.2f\n",
                    LAB_CFG_NAMES[c], count[c], ups, min[c][1], max[c][1], baseUps > 0 ? (ups / baseUps - 1) * 100 : 0,
                    sum[c][0], watts, sum[c][3], tick, min[c][5], max[c][5], baseTick > 0 ? (tick / baseTick - 1) * 100 : 0, sum[c][6]));
            if (got[c][2] > 0 && got[c][2] < count[c])
                notes.append(String.format(L, "%s: ватты по %d из %d замеров (остальные — на зарядке).\n", LAB_CFG_NAMES[c], got[c][2], count[c]));
            if (fellBack[c] > 0)
                notes.append(String.format(L, "%s: Vulkan не включился в %d из %d замеров — это был OpenGL ES (причина в логе шага).\n",
                        LAB_CFG_NAMES[c], fellBack[c], count[c]));
        }
        if (notes.length() > 0) r.append("\n").append(notes);
        r.append("\nΔ — от базы. Разница меньше разброса (мин–макс) — скорее шум, чем эффект.\n"
                + "Шаг — время ЦП потока симуляции на один шаг игры (меньше — дешевле; видно и при 60 UPS); игра — ЦП всех потоков на шаг.\n"
                + "Логи шагов: r<круг>-<конфигурация>.log рядом.\n");
        File out = new File(labDir(), "summary.txt");
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(out)) {
            f.write(r.toString().getBytes(StandardCharsets.UTF_8));
            if (labStatus != null) labStatus.setText(labStatus.getText() + "\nИтог: " + out + "\n\n" + r);
        } catch (IOException e) {
            if (labStatus != null) labStatus.setText(labStatus.getText() + "\nИтог не записан: " + e);
        }
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
