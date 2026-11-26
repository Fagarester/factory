package com.factorynative;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * "Производительность" → "Сжатие текстур игры" (ported from the Factory app: GameCompression, GameCopier,
 * cpp/texcompress → fntex/, libtexcompress.so). The PNG files of the game's data/ become palette PNGs with fewer
 * colors, in place: light ×2 (≥ 128 colors, dithering) or strong ×5 (≥ 32 colors). Saves disk space only — the game
 * decodes every PNG to full color and compresses it for the GPU itself, so video memory and FPS stay the same.
 * There is no way back but a new copy of the game. The level done is kept in the game folder (MARKER).
 */
final class TextureCompressor {
    static final int ORIGINAL = 0, LIGHT = 1, STRONG = 2;
    static final String MARKER = "fnative-texture-compression.txt";
    private static boolean tried, loaded;

    static final int OK = 0, SKIP_FORMAT = 1, SKIP_SMALL = 2, SKIP_GAIN = 3;  // < 0: error, nothing written

    private TextureCompressor() {}

    static synchronized boolean available() {
        if (!tried) {
            tried = true;
            try {
                System.loadLibrary("texcompress");
                loaded = true;
            } catch (Throwable e) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** ratio: wanted old/new size; minColors: palette floor; kmeans: refinement passes; dither 0..100. */
    static native int shrinkPng(String src, String dst, int ratio, int minColors, int kmeans, int dither);

    /** The level the game folder has been shrunk to (ORIGINAL when never). */
    static int level(File gameDir) {
        try {
            String s = new String(Files.readAllBytes(new File(gameDir, MARKER).toPath()), StandardCharsets.UTF_8).trim();
            return s.startsWith("strong") ? STRONG : s.startsWith("light") ? LIGHT : ORIGINAL;
        } catch (Exception e) {
            return ORIGINAL;
        }
    }

    private static final Pattern LUT_NAME = Pattern.compile("(^|[-_/])lut([-_.]|$)");

    /** Never shrunk: menu / GUI atlases, color lookup tables (must stay exact), menu / logo / background images,
     * icons and technology pictures (as in the Factory app). path: from the game folder, with "/". */
    static boolean isProtected(String path) {
        path = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (!path.startsWith("/")) path = "/" + path;
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.contains("/core/graphics/")) return true;
        if (path.contains("/color_luts/") || LUT_NAME.matcher(path).find()) return true;
        if (path.contains("/gui") || name.contains("gui")) return true;
        if (name.contains("menu") || name.contains("background") || name.contains("logo")) return true;
        return path.contains("/graphics/icons/") || path.contains("/graphics/technology/");
    }

    static final class Result {
        boolean ok;
        int total, shrunk, kept, protectedCount, errors;
        long inBytes, outBytes;
        String report = "";
    }

    private static void collect(File dir, List<File> out) {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) out.add(f);
        }
    }

    /** Shrinks the PNG files of gameDir/data where they are (each through a temporary file: a stop leaves whole
     * files); progress gets 0..100. Only data/ (not mods, saves, screenshots). */
    static Result compressInPlace(File gameDir, int level, IntConsumer progress) {
        Result r = new Result();
        List<File> all = new ArrayList<>(), pngs = new ArrayList<>();
        collect(new File(gameDir, "data"), all);
        String root = gameDir.getPath();
        for (File f : all) {
            if (isProtected(f.getPath().substring(root.length()))) r.protectedCount++;
            else {
                pngs.add(f);
                r.inBytes += f.length();
            }
        }
        r.total = all.size();
        int ratio = level == STRONG ? 5 : 2, minColors = level == STRONG ? 32 : 128, kmeans = 3, dither = level == STRONG ? 0 : 70;
        if (!available() || pngs.isEmpty()) {
            r.ok = false;
            return r;
        }
        long total = Math.max(1, r.inBytes);
        AtomicLong done = new AtomicLong(), out = new AtomicLong();
        AtomicInteger shrunk = new AtomicInteger(), errors = new AtomicInteger(), kept = new AtomicInteger(), last = new AtomicInteger(-1);
        int threads = Math.max(2, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));  // shrinking needs memory
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        for (File png : pngs) {
            jobs.add(pool.submit(() -> {
                long size = png.length();
                File tmp = new File(png.getPath() + ".shrinking");
                int rc;
                try {
                    rc = shrinkPng(png.getPath(), tmp.getPath(), ratio, minColors, kmeans, dither);
                } catch (Throwable e) {
                    rc = -1;
                }
                if (rc == OK && tmp.renameTo(png)) shrunk.incrementAndGet();
                else {
                    if (rc < 0) errors.incrementAndGet();
                    else kept.incrementAndGet();
                    tmp.delete();
                }
                out.addAndGet(png.length());
                int p = (int) Math.min(100, done.addAndGet(size) * 100 / total);
                if (p != last.getAndSet(p)) progress.accept(p);
            }));
        }
        pool.shutdown();
        boolean failed = false;
        for (Future<?> j : jobs) {
            try {
                j.get();
            } catch (Exception e) {
                failed = true;
            }
        }
        r.shrunk = shrunk.get();
        r.errors = errors.get();
        r.kept = kept.get();
        r.outBytes = out.get();
        r.ok = !failed && r.errors == 0;
        String name = level == STRONG ? "strong x5" : "light x2";
        try {  // the level only goes up: strong after light stays strong
            if (r.shrunk > 0 || level(gameDir) < level)
                Files.write(new File(gameDir, MARKER).toPath(), (name + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // the marker is only for the screen
        }
        r.report = String.format(Locale.ROOT, "Texture compression in place: %s\nPNG files in data/: %d, shrunk %d, kept (no gain/format) %d,"
                        + " protected (menu/GUI/LUT/icons) %d, errors %d\nshrinkable PNG: %d MB -> %d MB\n",
                name, r.total, r.shrunk, r.kept, r.protectedCount, r.errors, r.inBytes >> 20, r.outBytes >> 20);
        return r;
    }
}
