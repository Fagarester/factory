package com.factorynative;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the game folder between shared storage (Download/factorio) and the app's own storage (files/factorio).
 * File by file: copy, check the size, delete the original — so it needs free space for one file only, and a move
 * that was cut off (app killed, phone off) goes on at the next start (the state file names both folders).
 */
final class GameMover {
    interface Progress {
        void step(long doneBytes, long totalBytes, String file);
    }

    private GameMover() {}

    static File internalDir(Context c) {
        return new File(c.getFilesDir(), "factorio");
    }

    static boolean isInternal(Context c, File game) {
        return game != null && game.getAbsolutePath().startsWith(c.getFilesDir().getAbsolutePath());
    }

    /** Where the game goes back to in shared storage: Download/factorio, or factorio-2… when that is taken. */
    static File sharedTarget() {
        File dl = new File(Environment.getExternalStorageDirectory(), "Download");
        File f = new File(dl, "factorio");
        for (int i = 2; f.exists() && !isEmptyDir(f); i++) f = new File(dl, "factorio-" + i);
        return f;
    }

    private static boolean isEmptyDir(File f) {
        String[] k = f.list();
        return f.isDirectory() && (k == null || k.length == 0);
    }

    static File stateFile(Context c) {
        return new File(c.getFilesDir(), "game-move.txt");
    }

    /** {from, to} of a move begun and not finished, or null. */
    static File[] pending(Context c) {
        try {
            List<String> l = Files.readAllLines(stateFile(c).toPath(), StandardCharsets.UTF_8);
            return l.size() >= 2 ? new File[] {new File(l.get(0)), new File(l.get(1))} : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void begin(Context c, File from, File to) throws IOException {
        Files.write(stateFile(c).toPath(), (from.getAbsolutePath() + "\n" + to.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) s += size(x);
        return s;
    }

    private static long largest(File f) {
        if (f.isFile()) return f.length();
        long m = 0;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) m = Math.max(m, largest(x));
        return m;
    }

    /** Free space problem for moving from → to, or null when it fits. */
    static String spaceProblem(File from, File to) {
        File probe = to;
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        if (probe == null) return null;
        long free = new StatFs(probe.getAbsolutePath()).getAvailableBytes();
        // both places are usually the same flash partition: the move frees what it fills, one file at a time
        long need = largest(from) + (200L << 20);
        return free >= need ? null : String.format(java.util.Locale.ROOT, "мало места: свободно %.1f ГБ, нужно хотя бы %.1f ГБ",
                free / 1073741824.0, need / 1073741824.0);
    }

    /** Moves everything; on success the state file is gone and `from` no longer exists. */
    static void run(Context c, File from, File to, Progress p) throws IOException {
        long total = size(from) + size(to);  // `to` may hold what a cut-off move already brought
        long[] done = {size(to)};
        to.mkdirs();
        moveDir(from, to, done, total, p);
        deleteEmpty(from);
        stateFile(c).delete();
    }

    private static void moveDir(File from, File to, long[] done, long total, Progress p) throws IOException {
        File[] k = from.listFiles();
        if (k == null) return;
        List<File> dirs = new ArrayList<>();
        for (File f : k) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) {
                dirs.add(f);
                continue;
            }
            if (t.isFile() && t.length() == f.length()) {  // brought over before a cut-off: only the original goes
                if (!f.delete()) throw new IOException("не удалось удалить " + f);
                continue;
            }
            File part = new File(to, f.getName() + ".fnmove");
            try (FileInputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 20];
                long since = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done[0] += n;
                    since += n;
                    if (since >= 8 << 20) {
                        since = 0;
                        p.step(done[0], total, f.getName());
                    }
                }
                out.getFD().sync();
            }
            if (part.length() != f.length()) throw new IOException("копия не совпала по размеру: " + f);
            t.delete();
            if (!part.renameTo(t)) throw new IOException("не удалось переименовать " + part);
            t.setExecutable(f.canExecute());
            if (!f.delete()) throw new IOException("не удалось удалить " + f);
            p.step(done[0], total, f.getName());
        }
        for (File d : dirs) {
            File t = new File(to, d.getName());
            t.mkdirs();
            moveDir(d, t, done, total, p);
        }
    }

    private static void deleteEmpty(File d) {
        File[] k = d.listFiles();
        if (k != null) for (File f : k) if (f.isDirectory()) deleteEmpty(f);
        d.delete();  // only empty folders go
    }
}
