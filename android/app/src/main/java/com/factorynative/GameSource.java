package com.factorynative;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the game is (from the Factory app's GameSource): a folder with the unpacked Linux arm64 build, or the
 * archive as factorio.com gives it (factorio-space-age_linux-arm64_2.1.25.tar.xz). FNative runs the game from
 * its folder, it does not copy it.
 */
final class GameSource {
    static final String DOWNLOAD_PAGE = "https://www.factorio.com/download";
    /** The free demo from factorio.com: factorio-demo_linux-arm64_….tar.xz */
    static final String DEMO_DOWNLOAD = "https://www.factorio.com/get-download/stable/demo/linux-arm64";

    /** factorio_linux-arm64_2.1.24.tar.xz, factorio-space-age_linux-arm64_2.1.24.tar.xz… (not the headless server). */
    private static final Pattern ARCHIVE = Pattern.compile("(?i)factorio.*linux.*arm64.*\\.tar\\.xz");
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)\\.tar\\.xz$", Pattern.CASE_INSENSITIVE);

    /** The light search: Download a few folders deep, the storage's top two levels, a few seconds at most. */
    private static final int DOWNLOAD_DEPTH = 3;
    private static final int STORAGE_DEPTH = 2;
    private static final int MAX_FOLDERS = 4000;
    private static final long MAX_SEARCH_MS = 4000;
    /** Folders full of photos, music or other apps' data: the game is not there. */
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList("android", "dcim", "pictures", "movies", "music", "ringtones",
            "alarms", "notifications", "podcasts", "audiobooks", "recordings", "screenshots", "telegram", "whatsapp"));

    private GameSource() {}

    static File downloads() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /** The unpacked game: its Linux arm64 program and its data. */
    static boolean isGameFolder(File dir) {
        return dir != null && new File(dir, "bin/arm64/factorio").isFile() && new File(dir, "data/core").isDirectory();
    }

    /** The folder itself or one of the folders right in it (the player picked the folder around "factorio"). */
    static File gameFolderIn(File dir) {
        if (dir == null) return null;
        if (isGameFolder(dir)) return dir;
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) for (File child : children) if (isGameFolder(child)) return child;
        return null;
    }

    /**
     * An empty .nomedia in a game folder on the shared storage: the gallery does not show the game's textures
     * (thousands of PNG files). Done on every start; nothing happens when it is there or can't be written.
     */
    static void ensureNoMedia(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            if (!dir.getCanonicalPath().startsWith(Environment.getExternalStorageDirectory().getCanonicalPath())) return;
            File marker = new File(dir, ".nomedia");
            if (!marker.exists()) new FileOutputStream(marker).close();
        } catch (Exception ignored) {
            // the gallery may show the textures; the game itself does not care
        }
    }

    /** The newest game archive right in Download (no subfolders), or null. */
    static File findArchive() {
        File[] files = downloads().listFiles((dir, name) -> ARCHIVE.matcher(name).matches() && !name.toLowerCase(Locale.ROOT).contains("headless"));
        if (files == null) return null;
        File best = null;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (best == null) best = file;
            else {
                // the full game before the demo, then the newest
                int order = Boolean.compare(isDemo(best.getName()), isDemo(file.getName()));
                if (order == 0) order = compareVersions(version(file.getName()), version(best.getName()));
                if (order > 0 || (order == 0 && file.lastModified() > best.lastModified())) best = file;
            }
        }
        return best;
    }

    static boolean isDemo(String name) {
        return name.toLowerCase(Locale.ROOT).contains("demo");
    }

    private static int[] version(String name) {
        Matcher m = VERSION.matcher(name);
        if (!m.find()) return new int[0];
        String[] parts = m.group(1).split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * The game folder: Download/factorio first, then any folder a few levels into Download (named in any language),
     * then the storage's top two levels. Breadth first, so the shallowest one wins. Null if none.
     */
    static File findFolder() {
        File usual = new File(downloads(), "factorio");
        if (isGameFolder(usual)) return usual;
        long deadline = SystemClock.uptimeMillis() + MAX_SEARCH_MS;
        int[] budget = {MAX_FOLDERS};
        File found = search(downloads(), DOWNLOAD_DEPTH, deadline, budget, null);
        if (found == null) found = search(Environment.getExternalStorageDirectory(), STORAGE_DEPTH, deadline, budget, downloads());
        return found;
    }

    private static File search(File root, int maxDepth, long deadline, int[] budget, File skip) {
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            int depth = depths.poll();
            if (budget[0]-- <= 0 || SystemClock.uptimeMillis() > deadline) return null;
            if (depth > 0 && isGameFolder(dir)) return dir;
            if (depth >= maxDepth) continue;
            File[] children = dir.listFiles(File::isDirectory);
            if (children == null) continue;
            Arrays.sort(children);
            for (File child : children) {
                String name = child.getName();
                if (name.startsWith(".") || SKIPPED.contains(name.toLowerCase(Locale.ROOT)) || child.equals(skip)) continue;
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue;
                queue.add(child);
                depths.add(depth + 1);
            }
        }
        return null;
    }

    /**
     * The path of a folder picked with the system's folder picker ("primary:Download/factorio", "1234-ABCD:Games",
     * "raw:/storage/…"); the game is read as files, so it needs the path. Null if it has none.
     */
    static File folderFromTree(Uri uri) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception e) {
            return null;
        }
        if (id == null) return null;
        if (id.startsWith("raw:")) return new File(id.substring(4));
        if (id.equals("downloads")) return downloads();
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon), path = id.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume) ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
        return path.isEmpty() ? base : new File(base, path);
    }

    // ---- the logo from factorio.com (the app carries no game art)

    private static final String WEB_LOGO = "https://cdn.factorio.com/assets/img/web/factorio-logo2.png";
    private static Bitmap webLogo;
    /** The logo once downloaded, kept in the app's files: shown at once on later screens and starts. */
    private static File logoFile;

    static void init(File appFiles) {
        logoFile = new File(appFiles, "factorio-logo.png");
    }

    static synchronized Bitmap downloadedLogo() {
        if (webLogo == null && logoFile != null && logoFile.isFile()) webLogo = BitmapFactory.decodeFile(logoFile.getPath());
        return webLogo;
    }

    /**
     * The logo: the kept copy, else from factorio.com (QuickHttps: all the server's addresses at once — one by
     * one a broken IPv6 cost Factory 30 s), or null without internet.
     */
    static Bitmap downloadLogo() {
        Bitmap known = downloadedLogo();
        if (known != null) return known;
        try {
            byte[] data = QuickHttps.get(WEB_LOGO, 5000, 10000, 4 << 20);
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) return null;
            if (logoFile != null) {
                File part = new File(logoFile.getPath() + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(data);
                }
                part.renameTo(logoFile);
            }
            synchronized (GameSource.class) {
                webLogo = bitmap;
            }
            return bitmap;
        } catch (Throwable e) {
            return null;
        }
    }
}
