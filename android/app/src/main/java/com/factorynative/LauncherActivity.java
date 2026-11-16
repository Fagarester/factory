package com.factorynative;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
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
    private EditText pathEdit;
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

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        check();
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
        String[] args = {
            new File(gameDir(), "bin/arm64/factorio").getAbsolutePath(),
            new File(ext, "fnative.log").getAbsolutePath(),
            "-c", config.getAbsolutePath(),
        };
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
