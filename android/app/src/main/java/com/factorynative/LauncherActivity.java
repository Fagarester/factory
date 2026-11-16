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
        title.setText("Factorio Native (тест)\nНеофициальный порт, не одобрен Wube. Бесплатно и некоммерчески.");
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
    }

    private static File envFile() {
        return new File(workDir(), "env.txt");
    }

    // ---- graphics settings: render scale + upscaler

    private android.widget.CheckBox upscaleOn;
    private android.widget.SeekBar scaleBar;
    private TextView scaleText;
    private android.widget.RadioButton upGsr, upBilinear;

    private int scalePercent() {
        return 50 + scaleBar.getProgress() * 5;  // 50..95 %
    }

    private void updateScaleText() {
        int p = scalePercent();
        int pixels = Math.round(p * p / 100f);
        // Full screen size, landscape like the game.
        android.graphics.Point size = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int w = Math.max(size.x, size.y), h = Math.min(size.x, size.y);
        int rw = Math.round(w * p / 100f), rh = Math.round(h * p / 100f);
        if (upscaleOn.isChecked())
            scaleText.setText("Масштаб рендера: " + p + "% (рисуется " + pixels + "% пикселей)\n"
                    + "Игра рисует " + rw + "×" + rh + " → растягивается до " + w + "×" + h);
        else
            scaleText.setText("Масштаб рендера: " + p + "% — апскейл выключен, игра рисует " + w + "×" + h);
        boolean on = upscaleOn.isChecked();
        scaleBar.setEnabled(on);
        upGsr.setEnabled(on);
        upBilinear.setEnabled(on);
    }

    private void addUpscaleSettings(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("\nАпскейл: игра рисует в меньшем разрешении, затем кадр растягивается на экран."
                + " Снижает нагрузку на видеокарту; интерфейс игры становится крупнее.");
        root.addView(title);

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
                .putString("upscaler", upBilinear.isChecked() ? "bilinear" : "gsr")
                .apply();
        List<String> a = new ArrayList<>();
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
            writePaths(config, new File(gameDir(), "data").getAbsolutePath(), write.getAbsolutePath());
        } catch (IOException e) {
            status.setText("\nНе удалось записать config.ini: " + e);
            return;
        }
        List<String> argList = new ArrayList<>();
        argList.add(new File(gameDir(), "bin/arm64/factorio").getAbsolutePath());
        argList.add(new File(ext, "fnative.log").getAbsolutePath());
        argList.addAll(settingArgs());
        argList.add("-c");
        argList.add(config.getAbsolutePath());
        String[] args = argList.toArray(new String[0]);
        startActivity(new Intent(this, GameActivity.class).putExtra(GameActivity.EXTRA_ARGS, args));
    }

    /** Keeps the game's own config.ini, only sets the [path] entries. */
    private static void writePaths(File config, String readData, String writeData) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("[path]");
        out.add("read-data=" + readData);
        out.add("write-data=" + writeData);
        if (config.exists()) {
            boolean inPath = false;
            for (String line : Files.readAllLines(config.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("[")) inPath = t.equals("[path]");
                if (!inPath) out.add(line);
            }
        }
        try (FileOutputStream f = new FileOutputStream(config)) {
            f.write((String.join("\n", out) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
