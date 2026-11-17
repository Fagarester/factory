package com.factorynative;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The first start, as in the Factory app: a quick search for the game (GameSource); found, it starts at once.
 * Not found: the "where is the game" screen — the game folder, the archive from factorio.com (unpacked into
 * Download) or the free demo. FNative runs the game from its folder, so nothing is copied into the app.
 */
final class FirstRun {
    static final int PICK_FOLDER = 11, PICK_ARCHIVE = 12;

    private final LauncherActivity a;
    /** Unpacking or downloading: back does not leave (the files would stay half-written). */
    boolean busy;
    private boolean introShown;

    FirstRun(LauncherActivity activity) {
        a = activity;
    }

    private void keepScreenOn(boolean on) {
        if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Search; the game folder found starts, else an archive in Download is unpacked, else the player chooses. */
    void start() {
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (!a.hasAccess()) {
            showSource();
            return;
        }
        Progress p = new Progress(R.string.game_source_searching, null);
        p.indeterminate();
        new Thread(() -> {
            File folder = GameSource.findFolder();
            File archive = folder == null ? notRejected(GameSource.findArchive()) : null;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (folder != null) a.useGame(folder);
                else if (archive != null) unpack(() -> new FileInputStream(archive), archive.length(), archive);
                else showSource();
            });
        }, "fnative-search").start();
    }

    // ---- the "where is the game" screen (layout game_source, from Factory)

    void showSource() {
        busy = false;
        keepScreenOn(false);
        a.setContentView(R.layout.game_source);
        View root = a.findViewById(android.R.id.content);

        a.findViewById(R.id.BTPickFolder).setOnClickListener(v -> withAccess(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOAD_DOCUMENT);
            startPicker(intent, PICK_FOLDER);
        }));
        a.findViewById(R.id.BTPickArchive).setOnClickListener(v -> withAccess(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOAD_DOCUMENT);
            startPicker(intent, PICK_ARCHIVE);
        }));
        a.findViewById(R.id.BTDownloadDemo).setOnClickListener(v -> withAccess(this::downloadDemo));

        TextView link = a.findViewById(R.id.TVDownloadLink);
        link.setText(Html.fromHtml("<u>" + TextUtils.htmlEncode(a.getString(R.string.game_source_buy)) + "</u>", Html.FROM_HTML_MODE_LEGACY));
        link.setOnClickListener(v -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GameSource.DOWNLOAD_PAGE)));
            } catch (Exception e) {
                android.widget.Toast.makeText(a, GameSource.DOWNLOAD_PAGE, android.widget.Toast.LENGTH_LONG).show();
            }
        });

        // the logo from factorio.com (its place stays empty without internet); the intro the first time
        ImageView logoView = a.findViewById(R.id.IVDemoLogo);
        android.util.DisplayMetrics m = a.getResources().getDisplayMetrics();
        int logoWidth = Math.round(Math.max(m.widthPixels, m.heightPixels) * 0.38f);
        logoView.getLayoutParams().width = logoWidth;
        logoView.getLayoutParams().height = Math.round(logoWidth * 153f / 921f);
        fitToScreen(a.findViewById(R.id.LLSourceContent));
        SourceIntro intro = new SourceIntro(!introShown);
        introShown = true;
        Bitmap known = GameSource.downloadedLogo();
        if (known != null) intro.logoReady(known);
        else new Thread(() -> {
            Bitmap logo = GameSource.downloadLogo();
            a.runOnUiThread(() -> {
                if (a.findViewById(R.id.IVDemoLogo) == logoView) intro.logoReady(logo);
            });
        }, "fnative-logo").start();

        // back from the browser: an archive downloaded into Download meanwhile is taken at once
        final boolean[] looking = {false};
        root.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus || looking[0] || busy || !a.hasAccess() || a.findViewById(R.id.IVDemoLogo) != logoView) return;
            looking[0] = true;
            new Thread(() -> {
                File found = notRejected(GameSource.findArchive());
                if (found != null) {  // a browser still writing it: the size must hold still
                    long size = found.length();
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (found.length() != size || System.currentTimeMillis() - found.lastModified() < 3000) found = null;
                }
                File archive = found;
                a.runOnUiThread(() -> {
                    looking[0] = false;
                    if (archive == null || busy || a.findViewById(R.id.IVDemoLogo) != logoView) return;
                    unpack(() -> new FileInputStream(archive), archive.length(), archive);
                });
            }, "fnative-archive-look").start();
        });
    }

    /** No scrolling: content taller than the screen (a low screen) is scaled down around the middle. */
    private static void fitToScreen(View content) {
        content.post(() -> {
            View parent = (View) content.getParent();
            int w = parent.getWidth(), h = parent.getHeight();
            if (w <= 0 || h <= 0) return;
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int need = content.getMeasuredHeight();
            content.requestLayout();
            float s = need > h ? (float) h / need : 1f;
            content.setPivotX(w / 2f);
            content.setPivotY(h / 2f);
            content.setScaleX(s);
            content.setScaleY(s);
        });
    }

    /** The game is read and written as files: without the access, ask first (or Android's app settings). */
    private void withAccess(Runnable then) {
        if (a.hasAccess()) {
            then.run();
            return;
        }
        a.requestAccess(() -> {
            // allowed: the search may now find the game by itself
            if (a.hasAccess()) start();
        });
    }

    /** The system's pickers open in Download. */
    private static final Uri DOWNLOAD_DOCUMENT = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download");

    private void startPicker(Intent intent, int code) {
        try {
            a.startActivityForResult(intent, code);
        } catch (Exception e) {
            android.widget.Toast.makeText(a, String.valueOf(e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** A picker's answer (LauncherActivity.onActivityResult). */
    void onPicked(int code, Uri uri) {
        if (code == PICK_FOLDER) {
            File picked = GameSource.folderFromTree(uri);
            File game = GameSource.gameFolderIn(picked);
            if (game == null) {
                new AlertDialog.Builder(a)
                        .setTitle(R.string.game_not_found)
                        .setMessage(picked == null ? a.getString(R.string.game_source_folder_unreadable)
                                : a.getString(R.string.game_source_not_game_folder, picked.getPath()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            a.useGame(game);
        } else if (code == PICK_ARCHIVE) {
            unpack(() -> a.getContentResolver().openInputStream(uri), contentSize(uri), null);
        }
    }

    private long contentSize(Uri uri) {
        try (Cursor c = a.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
            // unknown size: the progress stays at 0 % until the end
        }
        return -1;
    }

    /**
     * The intro, as in Factory: the logo alone in the middle, then the words under it; a tap lifts both up, the
     * words turn into the question and the choices rise from below. Without the intro the choices rise at once.
     */
    private class SourceIntro {
        private static final int LOGO_TO_WORDS_MS = 900, NO_LOGO_WAIT_MS = 2500, TAP_HINT_AFTER_MS = 2200;
        private final View content, top, introWords, title, link, tapHint;
        private final ImageView logo;
        private final android.view.ViewGroup cards;
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final float rise;
        private boolean opened, wordsShown;
        private android.animation.ObjectAnimator pulse;

        SourceIntro(boolean playIntro) {
            content = a.findViewById(R.id.LLSourceContent);
            top = a.findViewById(R.id.LLSourceTop);
            introWords = a.findViewById(R.id.TVSourceIntro);
            title = a.findViewById(R.id.LLSourceTitle);
            link = a.findViewById(R.id.LLSourceLink);
            tapHint = a.findViewById(R.id.TVSourceTap);
            logo = a.findViewById(R.id.IVDemoLogo);
            cards = a.findViewById(R.id.LLSourceCards);
            rise = 28 * a.getResources().getDisplayMetrics().density;

            introWords.setAlpha(0f);
            title.setAlpha(0f);
            link.setAlpha(0f);
            for (int i = 0; i < cards.getChildCount(); i++) cards.getChildAt(i).setVisibility(View.INVISIBLE);
            link.setVisibility(View.INVISIBLE);
            if (!playIntro) {
                open();
                return;
            }
            // the logo and the words in the middle of the screen until the tap
            top.setVisibility(View.INVISIBLE);
            content.post(() -> {
                top.setTranslationY(content.getHeight() / 2f - (top.getTop() + top.getHeight() / 2f));
                top.setVisibility(View.VISIBLE);
            });
            content.setOnClickListener(v -> open());
            // no logo (no internet, slow): the words come anyway
            handler.postDelayed(this::showWords, NO_LOGO_WAIT_MS);
        }

        private boolean logoShown;

        void logoReady(Bitmap bitmap) {
            if (logoShown) return;
            logoShown = bitmap != null;
            if (bitmap != null) {
                logo.setImageBitmap(bitmap);
                logo.setAlpha(0f);
                logo.setVisibility(View.VISIBLE);
                logo.animate().alpha(1f).setDuration(600).start();
            }
            if (!opened && !wordsShown) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(this::showWords, bitmap != null ? LOGO_TO_WORDS_MS : 0);
            }
        }

        private void showWords() {
            if (opened || wordsShown) return;
            wordsShown = true;
            introWords.animate().alpha(1f).setDuration(900).start();
            handler.postDelayed(() -> {
                if (opened) return;
                pulse = android.animation.ObjectAnimator.ofFloat(tapHint, "alpha", 0f, 0.85f, 0.3f);
                pulse.setDuration(1800);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.start();
            }, TAP_HINT_AFTER_MS);
        }

        private void open() {
            if (opened) return;
            opened = true;
            handler.removeCallbacksAndMessages(null);
            content.setOnClickListener(null);
            content.setClickable(false);
            if (pulse != null) pulse.cancel();
            tapHint.animate().alpha(0f).setDuration(250).start();

            android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator();
            top.animate().translationY(0f).setDuration(700).setInterpolator(ease).start();
            introWords.animate().alpha(0f).setDuration(300).start();
            title.animate().alpha(1f).setStartDelay(300).setDuration(600).start();
            for (int i = 0; i < cards.getChildCount(); i++) {
                View card = cards.getChildAt(i);
                card.setVisibility(View.VISIBLE);
                card.setAlpha(0f);
                card.setTranslationY(rise);
                card.animate().alpha(1f).translationY(0f).setStartDelay(400 + 110L * i).setDuration(550).setInterpolator(ease).start();
            }
            link.setVisibility(View.VISIBLE);
            link.animate().alpha(1f).setStartDelay(850).setDuration(600).start();
        }
    }

    // ---- the loading screen: logo, text, bar, percent, optional cancel

    private class Progress {
        final ProgressBar bar;
        final TextView percent;

        Progress(int textRes, Runnable cancel) {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundResource(R.drawable.loading_background);
            box.setPadding((int) (48 * d), 0, (int) (48 * d), 0);
            ImageView logo = new ImageView(a);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap known = GameSource.downloadedLogo();
            if (known != null) logo.setImageBitmap(known);
            box.addView(logo, new LinearLayout.LayoutParams((int) (300 * d), (int) (50 * d)));
            TextView text = new TextView(a);
            text.setText(textRes);
            text.setTextColor(0xFFFFE6C0);
            text.setTextSize(16);
            text.setGravity(Gravity.CENTER);
            text.setPadding(0, (int) (20 * d), 0, (int) (10 * d));
            box.addView(text);
            bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF29A1F));
            box.addView(bar, new LinearLayout.LayoutParams((int) (420 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            percent = new TextView(a);
            percent.setTextColor(0xFFB8B0A8);
            percent.setTextSize(13);
            percent.setGravity(Gravity.CENTER);
            box.addView(percent);
            if (cancel != null) {
                Button b = new Button(a);
                b.setText(R.string.cancel);
                b.setOnClickListener(v -> cancel.run());
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                bp.topMargin = (int) (16 * d);
                box.addView(b, bp);
            }
            a.setContentView(box);
            if (known == null) new Thread(() -> {
                Bitmap web = GameSource.downloadLogo();
                if (web != null) a.runOnUiThread(() -> logo.setImageBitmap(web));
            }, "fnative-logo").start();
        }

        void indeterminate() {
            bar.setIndeterminate(true);
        }

        void set(int p) {
            bar.setIndeterminate(false);
            bar.setProgress(p);
            percent.setText(p + " %");
        }
    }

    // ---- the archive: tar.xz from factorio.com, unpacked into Download

    private interface Opener {
        InputStream open() throws IOException;
    }

    /** An archive that failed is not taken by the search again (it would be unpacked over and over). */
    private static String rejected;

    private static File notRejected(File archive) {
        return archive != null && (archive.getPath() + archive.length()).equals(rejected) ? null : archive;
    }

    /**
     * Into Download/.factorio.unpacking (hidden: the gallery does not scan it meanwhile), then renamed to
     * Download/factorio (factorio-2… when that name is taken). The archive itself stays.
     */
    private void unpack(Opener opener, long size, File archive) {
        busy = true;
        keepScreenOn(true);
        Progress p = new Progress(R.string.unpacking_game, null);
        p.set(0);
        new Thread(() -> {
            File downloads = GameSource.downloads();
            File staging = new File(downloads, ".factorio.unpacking");
            String failure = null;
            File game = null;
            try {
                deleteTree(staging);
                // the game unpacked is about 3.5 times its archive
                if (size > 0 && downloads.getUsableSpace() < size * 4) {
                    throw new IOException(String.format(Locale.ROOT, "мало места: нужно около %.1f ГБ, свободно %.1f ГБ",
                            size * 4 / 1e9, downloads.getUsableSpace() / 1e9));
                }
                if (!staging.mkdirs()) throw new IOException("не создать " + staging);
                untarXz(opener, size, staging, pct -> a.runOnUiThread(() -> p.set(pct)));
                File unpacked = GameSource.gameFolderIn(staging);
                if (unpacked == null) throw new IOException(a.getString(R.string.game_source_archive_no_game));
                game = new File(downloads, "factorio");
                for (int n = 2; game.exists(); n++) game = new File(downloads, "factorio-" + n);
                if (!unpacked.renameTo(game)) throw new IOException("не переименовать " + unpacked + " в " + game);
                GameSource.ensureNoMedia(game);
            } catch (Throwable e) {
                android.util.Log.e("FNative", "Cannot unpack the game", e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
                if (archive != null) rejected = archive.getPath() + archive.length();
            }
            deleteTree(staging);
            String message = failure;
            File ready = game;
            a.runOnUiThread(() -> {
                busy = false;
                keepScreenOn(false);
                if (a.isFinishing()) return;
                if (message != null) {
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.game_unpack_failed)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, (d, w) -> showSource())
                            .setOnCancelListener(d -> showSource())
                            .show();
                    return;
                }
                a.useGame(ready);
            });
        }, "fnative-unpack").start();
    }

    private interface Percent {
        void on(int percent);
    }

    private static void untarXz(Opener opener, long size, File dest, Percent percent) throws IOException {
        String root = dest.getCanonicalPath();
        byte[] buffer = new byte[1 << 20];
        final long[] read = {0};
        final int[] shown = {-1};
        try (InputStream raw = opener.open();
             InputStream counted = new java.io.FilterInputStream(new BufferedInputStream(raw, 1 << 20)) {
                 @Override
                 public int read(byte[] b, int off, int len) throws IOException {
                     int n = super.read(b, off, len);
                     if (n > 0 && size > 0) {
                         read[0] += n;
                         int p = (int) (read[0] * 100 / size);
                         if (p != shown[0]) {
                             shown[0] = p;
                             percent.on(p);
                         }
                     }
                     return n;
                 }
             };
             InputStream tar = new BufferedInputStream(new XZInputStream(counted), 1 << 20)) {
            byte[] header = new byte[512];
            String longName = null;
            while (true) {
                if (!readFully(tar, header, 512)) throw new IOException("архив оборван");
                if (isZero(header)) break;  // end of the archive
                String name = longName != null ? longName : headerName(header);
                longName = null;
                long len = octal(header, 124, 12);
                char type = (char) header[156];
                if (type == 'L' || type == 'x') {
                    // GNU long name / pax header: the next entry's name
                    byte[] data = readData(tar, len);
                    if (type == 'L') longName = cString(data, 0, data.length);
                    else longName = paxPath(data);
                    continue;
                }
                File target = new File(dest, name);
                // the archive comes from the user: nothing may land outside dest
                String path = target.getCanonicalPath();
                if (!path.equals(root) && !path.startsWith(root + File.separator))
                    throw new IOException("файл вне папки распаковки: " + name);
                if (type == '5') {
                    target.mkdirs();
                } else if (type == '0' || type == 0 || type == '7') {
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream out = new FileOutputStream(target)) {
                        long left = len;
                        while (left > 0) {
                            int n = tar.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (n < 0) throw new IOException("архив оборван: " + name);
                            out.write(buffer, 0, n);
                            left -= n;
                        }
                    }
                    skip(tar, (512 - len % 512) % 512);
                } else {
                    // links ('1', '2') and others: the shared storage has no links, the game does not need them
                    android.util.Log.w("FNative", "skipped tar entry " + name + " type " + type);
                    skip(tar, (len + 511) / 512 * 512);
                }
            }
        }
    }

    // ---- a small tar reader: ustar, GNU long names, pax paths (what factorio.com's archives use)

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] b = new byte[8192];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) throw new IOException("архив оборван");
            n -= r;
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > (1 << 20)) throw new IOException("слишком длинный заголовок tar");
        byte[] data = new byte[(int) size];
        if (!readFully(in, data, data.length)) throw new IOException("архив оборван");
        skip(in, (512 - size % 512) % 512);
        return data;
    }

    private static boolean isZero(byte[] h) {
        for (byte b : h) if (b != 0) return false;
        return true;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** ustar: prefix (345, 155 bytes) + "/" + name (0, 100 bytes). */
    private static String headerName(byte[] h) {
        String name = cString(h, 0, 100);
        boolean ustar = h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r';
        String prefix = ustar ? cString(h, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static long octal(byte[] h, int off, int len) throws IOException {
        if ((h[off] & 0x80) != 0) {  // GNU base-256 for big sizes
            long v = h[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (h[off + i] & 0xFF);
            return v;
        }
        String s = cString(h, off, len).trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            throw new IOException("плохой заголовок tar");
        }
    }

    /** pax records "LEN key=value\n": the path, or null. */
    private static String paxPath(byte[] data) {
        String all = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        for (String rec : all.split("\n")) {
            int sp = rec.indexOf(' '), eq = rec.indexOf('=');
            if (sp > 0 && eq > sp && rec.substring(sp + 1, eq).equals("path")) return rec.substring(eq + 1);
        }
        return null;
    }

    private static void deleteTree(File f) {
        File[] children = f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    // ---- the free demo: downloaded into Download (a cut download goes on from file.part), then unpacked

    private void downloadDemo() {
        busy = true;
        keepScreenOn(true);
        AtomicBoolean cancelled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<QuickHttps.Response> open = new java.util.concurrent.atomic.AtomicReference<>();
        Progress p = new Progress(R.string.downloading_demo, () -> {
            cancelled.set(true);
            // a read waiting on a dead network ends at once (closing a TLS socket writes: not on the main thread)
            QuickHttps.Response r = open.get();
            if (r != null) new Thread(r::close, "fnative-demo-cancel").start();
        });
        p.set(0);
        new Thread(() -> {
            File archive = null;
            String failure = null;
            try {
                archive = fetchDemo(GameSource.downloads(), cancelled, open, pct -> a.runOnUiThread(() -> p.set(pct)));
            } catch (Throwable e) {
                if (!cancelled.get()) {
                    android.util.Log.e("FNative", "Cannot download the demo", e);
                    failure = e.getMessage() != null ? e.getMessage() : e.toString();
                }
            }
            File ready = archive;
            String message = failure;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (cancelled.get()) {
                    showSource();
                } else if (message != null) {
                    busy = false;
                    keepScreenOn(false);
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.demo_download_failed)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, (d, w) -> showSource())
                            .setOnCancelListener(d -> showSource())
                            .show();
                } else {
                    unpack(() -> new FileInputStream(ready), ready.length(), ready);
                }
            });
        }, "fnative-demo").start();
    }

    /** Downloads the demo's archive into [folder] (as in Factory); returns it once complete. */
    private static File fetchDemo(File folder, AtomicBoolean cancelled,
                                  java.util.concurrent.atomic.AtomicReference<QuickHttps.Response> connection, Percent listener) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("не создать " + folder);
        QuickHttps.Response response = QuickHttps.open(GameSource.DEMO_DOWNLOAD, null, 5000, 30000);
        connection.set(response);
        try {
            if (cancelled.get()) throw new IOException("cancelled");
            if (response.status != 200) throw new IOException("factorio.com: ответ " + response.status);
            String name = response.fileName();
            // the name comes from the server: only a plain file name of a game archive
            if (!name.matches("[A-Za-z0-9._-]+\\.tar\\.xz")) name = "factorio-demo_linux-arm64.tar.xz";
            File target = new File(folder, name);
            long total = response.length();
            if (total <= 0) throw new IOException("factorio.com не сообщил размер");
            if (target.isFile() && target.length() == total) return target;

            File part = new File(folder, name + ".part");
            long done = part.isFile() ? part.length() : 0;
            if (done >= total) {
                part.delete();
                done = 0;
            }
            if (done > 0) {
                // what's there already: only the rest (same name = same version)
                response.close();
                response = QuickHttps.open(response.url.toString(), done, 5000, 30000);
                connection.set(response);
                String range = response.headers.get("content-range");
                if (response.status != 206 || range == null || !range.startsWith("bytes " + done + "-") || !range.endsWith("/" + total)) {
                    response.close();
                    done = 0;
                    response = QuickHttps.open(GameSource.DEMO_DOWNLOAD, null, 5000, 30000);
                    connection.set(response);
                    if (response.status != 200 || response.length() != total) throw new IOException("factorio.com: ответ " + response.status);
                }
            }

            // the archive and the game unpacked from it must fit
            long needed = (total - done) + 4 * total;
            if (folder.getUsableSpace() < needed)
                throw new IOException(String.format(Locale.ROOT, "мало места: нужно около %.1f ГБ, свободно %.1f ГБ",
                        needed / 1e9, folder.getUsableSpace() / 1e9));

            byte[] buffer = new byte[1 << 16];
            int shown = -1;
            try (OutputStream out = new FileOutputStream(part, done > 0)) {
                while (done < total) {
                    if (cancelled.get()) throw new IOException("cancelled");
                    int n = response.body.read(buffer);
                    if (n < 0) throw new IOException("загрузка оборвалась на " + done + " из " + total + " байт; повторите — продолжится с того же места");
                    out.write(buffer, 0, n);
                    done += n;
                    int pct = (int) (done * 100 / total);
                    if (pct != shown) {
                        shown = pct;
                        listener.on(pct);
                    }
                }
            }
            target.delete();
            if (!part.renameTo(target)) throw new IOException("не переименовать " + part);
            return target;
        } finally {
            response.close();
        }
    }
}
