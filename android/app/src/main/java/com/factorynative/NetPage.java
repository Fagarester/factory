package com.factorynative;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The gear menu's "Сеть" page: create a network (this phone hosts, QR invite) or connect (scan the host's QR),
 * presets and manual settings, the network test, players and statistics, a status line at the bottom.
 * The tunnel itself is NetTunnel; it lives as long as the game process.
 */
final class NetPage implements NetTunnel.Listener {
    static final int REQ_CAMERA = 0x4E02;

    private final Activity a;
    private final SharedPreferences prefs;
    private final File workDir;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private NetTunnel tunnel;
    private boolean guestMode;  // "Подключиться" pressed: the scanning part is shown
    private int preset;
    private NetConfig manual;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private boolean refreshPosted;
    private String shownInvite, shownAnswer, playersSig = "";
    private Bitmap inviteBitmap, answerBitmap;

    private LinearLayout setupBox;  // presets and manual settings: only before "Создать сеть" (a guest takes the host's)
    private LinearLayout root, content, manualBox, hostBox, guestBox, answerBox, playersBox, scanBox;
    private TextView status, presetNote, pinView, inviteInfo, codeView, connectedInfo, answerInfo, probeView, logView, playersHead;
    private Button createBtn, connectBtn, stopBtn, probeBtn;
    private ImageView qrView, answerQr;
    private NetCamera camera;
    private final java.util.Map<Long, TextView> rowText = new java.util.HashMap<>();

    NetPage(Activity activity, File workDir) {
        a = activity;
        this.workDir = workDir;
        prefs = a.getSharedPreferences("game", Context.MODE_PRIVATE);
        preset = Math.max(0, Math.min(NetConfig.MANUAL, prefs.getInt("netPreset", 0)));
        manual = NetConfig.load(prefs.getString("netManual", null));
        build();
        refresh();
    }

    View view() {
        return root;
    }

    // ------------------------------------------------------------------ building

    private void build() {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        status = new TextView(a);
        status.setTextSize(13);
        status.setPadding(dp(8), dp(6), dp(8), dp(2));
        status.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(status);

        // the two main buttons (or "stop")
        LinearLayout row = hrow();
        createBtn = button(Tr.t("Создать сеть"), v -> create());
        connectBtn = button(Tr.t("Подключиться"), v -> {
            guestMode = true;
            refresh();
        });
        stopBtn = button(Tr.t("Остановить сеть"), v -> stopTunnel());
        row.addView(createBtn, weight());
        row.addView(connectBtn, weight());
        row.addView(stopBtn, weight());
        content.addView(row);
        content.addView(note(Tr.t("Один телефон создаёт сеть и показывает QR-приглашение, остальные его сканируют. "
                + "Чужих серверов нет, всё зашифровано (AES-256-GCM). Игроков может быть сколько угодно (лимит — в настройках).")));

        // the camera box (scanning an invite or an answer)
        scanBox = new LinearLayout(a);
        scanBox.setOrientation(LinearLayout.VERTICAL);
        camera = new NetCamera(a);
        scanBox.addView(camera, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        scanBox.addView(button(Tr.t("Закрыть камеру"), v -> stopCamera()));
        scanBox.setVisibility(View.GONE);
        content.addView(scanBox);

        // host: the invite
        hostBox = section(Tr.t("Приглашение"));
        qrView = new ImageView(a);
        qrView.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(220), dp(220));
        ql.gravity = Gravity.CENTER_HORIZONTAL;
        hostBox.addView(qrView, ql);
        pinView = new TextView(a);
        pinView.setTextSize(22);
        pinView.setGravity(Gravity.CENTER);
        pinView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hostBox.addView(pinView);
        inviteInfo = note("");
        hostBox.addView(inviteInfo);
        LinearLayout hb = hrow();
        hb.addView(button(Tr.t("Поделиться"), v -> share(inviteBitmap, tunnel != null ? tunnel.inviteText : null, true)), weight());
        hb.addView(button(Tr.t("Сохранить"), v -> save(inviteBitmap, "invite")), weight());
        hb.addView(button(Tr.t("Копировать код"), v -> copy(tunnel != null ? tunnel.inviteText : null)), weight());
        hostBox.addView(hb);
        hostBox.addView(button(Tr.t("Новое приглашение (старое перестанет пускать)"), v -> {
            if (tunnel != null && tunnel.host) tunnel.newInvite();
        }));
        codeView = note("");
        codeView.setTextIsSelectable(true);
        codeView.setTypeface(Typeface.MONOSPACE);
        hostBox.addView(codeView);
        hostBox.addView(note(Tr.t("Если гость не может подключиться, он покажет ответный QR — отсканируй его:")));
        hostBox.addView(scanRow());
        hostBox.addView(note(Tr.t("В игре: Мультиплеер → Создать игру (порт сервера 34197). Если игра требует учётную запись "
                + "factorio.com, отключи в настройках сервера проверку пользователей (Verify user identity).")));
        content.addView(hostBox);

        // guest: scanning, the answer, the game's address
        guestBox = section(Tr.t("Подключение"));
        guestBox.addView(note(Tr.t("Отсканируй QR-приглашение хоста камерой, выбери фото/скриншот с QR или вставь присланный код. "
                + "Настраивать ничего не нужно: настройки сети придут из QR хоста.")));
        guestBox.addView(scanRow());
        connectedInfo = new TextView(a);
        connectedInfo.setTextSize(16);
        connectedInfo.setTypeface(Typeface.DEFAULT_BOLD);
        connectedInfo.setPadding(dp(8), dp(8), dp(8), dp(4));
        guestBox.addView(connectedInfo);
        answerBox = new LinearLayout(a);
        answerBox.setOrientation(LinearLayout.VERTICAL);
        answerInfo = note(Tr.t("Хост не отвечает напрямую. Покажи ему этот ответный QR (или отправь фото): он отсканирует его "
                + "в своей вкладке «Сеть», и телефоны пробьются друг к другу."));
        answerBox.addView(answerInfo);
        answerQr = new ImageView(a);
        answerQr.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(200), dp(200));
        al.gravity = Gravity.CENTER_HORIZONTAL;
        answerBox.addView(answerQr, al);
        LinearLayout ab = hrow();
        ab.addView(button(Tr.t("Поделиться"), v -> share(answerBitmap, tunnel != null ? tunnel.answerText : null, false)), weight());
        ab.addView(button(Tr.t("Сохранить"), v -> save(answerBitmap, "answer")), weight());
        ab.addView(button(Tr.t("Копировать код"), v -> copy(tunnel != null ? tunnel.answerText : null)), weight());
        answerBox.addView(ab);
        guestBox.addView(answerBox);
        content.addView(guestBox);

        // players / the connection
        playersBox = section("");
        playersHead = (TextView) playersBox.getChildAt(0);
        content.addView(playersBox);

        // name, presets, manual settings
        LinearLayout setup = section(Tr.t("Настройки сети (для создания)"));
        setupBox = setup;
        LinearLayout nameRow = hrow();
        nameRow.addView(note(Tr.t("Имя для хоста:")));
        EditText name = new EditText(a);
        name.setSingleLine();
        name.setText(prefs.getString("netName", Build.MODEL));
        name.addTextChangedListener(new SimpleWatcher(s -> prefs.edit().putString("netName", s).apply()));
        nameRow.addView(name, weight());
        content.addView(nameRow, 1);  // under the main buttons: the host sees it for a guest too
        setup.addView(note(Tr.t("Пресет (применяется при создании сети):")));
        RadioGroup pg = new RadioGroup(a);
        for (int i = 0; i < NetConfig.PRESETS.length; i++) {
            RadioButton rb = new RadioButton(a);
            rb.setText(Tr.t(NetConfig.PRESETS[i]));
            rb.setId(1000 + i);
            pg.addView(rb);
        }
        pg.check(1000 + preset);
        presetNote = note("");
        pg.setOnCheckedChangeListener((g, id) -> {
            int was = preset;
            preset = id - 1000;
            if (preset == NetConfig.MANUAL && !prefs.contains("netManual") && was != NetConfig.MANUAL) {
                manual = NetConfig.preset(was);  // start the manual settings from the preset in use
                saveManual();
            }
            prefs.edit().putInt("netPreset", preset).apply();
            showPreset();
        });
        setup.addView(pg);
        setup.addView(presetNote);
        manualBox = new LinearLayout(a);
        manualBox.setOrientation(LinearLayout.VERTICAL);
        setup.addView(manualBox);
        content.addView(setup);
        showPreset();

        // the network test
        LinearLayout test = section(Tr.t("Тест сети"));
        test.addView(note(Tr.t("Проверяет, как к этому телефону можно подключиться: адреса, IPv6, тип NAT (STUN), проброс порта на роутере (UPnP). "
                + "Сделай его на телефоне, который будет хостом. Отчёт сохраняется в Download/FactorioNative.")));
        probeBtn = button(Tr.t("Запустить тест сети"), v -> probe());
        test.addView(probeBtn);
        probeView = note("");
        probeView.setTypeface(Typeface.MONOSPACE);
        probeView.setTextIsSelectable(true);
        test.addView(probeView);
        content.addView(test);

        LinearLayout log = section(Tr.t("Журнал"));
        logView = note("");
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        log.addView(logView);
        content.addView(log);
    }

    private LinearLayout scanRow() {
        LinearLayout r = hrow();
        r.addView(button(Tr.t("Камера"), v -> startCamera()), weight());
        r.addView(button(Tr.t("Из фото"), v -> pickPhoto()), weight());
        r.addView(button(Tr.t("Вставить код"), v -> paste()), weight());
        return r;
    }

    private void showPreset() {
        presetNote.setText(Tr.t(NetConfig.PRESET_NOTES[preset]));
        manualBox.removeAllViews();
        if (preset != NetConfig.MANUAL) return;
        NetConfig c = manual;
        manualBox.addView(note(Tr.t("Пути подключения:")));
        check(Tr.t("Локальная сеть (одна Wi-Fi)"), c.lan, v -> c.lan = v);
        check(Tr.t("IPv6 напрямую"), c.ipv6, v -> c.ipv6 = v);
        check(Tr.t("Проброс порта на роутере (UPnP)"), c.upnp, v -> c.upnp = v);
        check(Tr.t("Пробивка NAT через STUN"), c.stun, v -> c.stun = v);
        text(Tr.t("STUN-серверы (через пробел):"), c.stunServers, v -> c.stunServers = v);
        manualBox.addView(note(Tr.t("Защита:")));
        check(Tr.t("Подтверждать каждого нового игрока"), c.approve, v -> c.approve = v);
        check(Tr.t("PIN из 6 цифр (не в QR, сообщи его отдельно)"), c.pin, v -> c.pin = v);
        check(Tr.t("Одноразовое приглашение (одно на игрока)"), c.oneUse, v -> c.oneUse = v);
        number(Tr.t("Срок приглашения, минут (0 — без срока)"), c.inviteMinutes, v -> c.inviteMinutes = v);
        number(Tr.t("Игроков не больше (0 — без лимита)"), c.maxPlayers, v -> c.maxPlayers = v);
        manualBox.addView(note(Tr.t("Шифрование: AES-256-GCM, ключи на каждого игрока. Выключить нельзя.")));
        manualBox.addView(note(Tr.t("Порты и время:")));
        number(Tr.t("UDP-порт туннеля (0 — случайный)"), c.port, v -> c.port = v);
        number(Tr.t("Порт сервера игры у хоста"), c.gamePort, v -> c.gamePort = v);
        number(Tr.t("Порт для игры у гостя (127.0.0.1:…)"), c.localPort, v -> c.localPort = v);
        number(Tr.t("Пинг и поддержка связи, с"), c.pingSeconds, v -> c.pingSeconds = v);
        number(Tr.t("Пробивка NAT длится, с"), c.punchSeconds, v -> c.punchSeconds = v);
        number(Tr.t("Хост: «нет связи» после тишины, с"), c.timeoutSeconds, v -> c.timeoutSeconds = v);
        number(Tr.t("Хост: ждать вернувшегося игрока, мин"), c.rejoinMinutes, v -> c.rejoinMinutes = v);
        manualBox.addView(button(Tr.t("Сбросить к «Стандарту»"), v -> {
            manual = NetConfig.preset(0);
            saveManual();
            showPreset();
        }));
    }

    private NetConfig config() {
        return preset == NetConfig.MANUAL ? NetConfig.load(manual.save()) : NetConfig.preset(preset);
    }

    private void saveManual() {
        prefs.edit().putString("netManual", manual.save()).apply();
    }

    // ------------------------------------------------------------------ actions

    private void create() {
        stopCamera();
        NetTunnel old = tunnel;
        tunnel = null;
        guestMode = false;
        startTunnel(old, new NetTunnel(config(), name(), this));
    }

    private void connect(NetCodes.Invite inv, String pin) {
        NetTunnel old = tunnel;
        tunnel = null;
        guestMode = true;
        startTunnel(old, new NetTunnel(NetConfig.forGuest(inv), name(), inv, pin, this));  // the host's settings, from the QR
    }

    private void startTunnel(NetTunnel old, NetTunnel t) {
        logLines.clear();
        new Thread(() -> {
            if (old != null) old.stop();
            try {
                t.start();
                ui.post(() -> {
                    tunnel = t;
                    watchNetwork(true);
                    refresh();
                });
            } catch (Exception e) {
                ui.post(() -> toast(Tr.t("Сеть не запустилась: ") + e.getMessage()));
            }
        }, "fnet-start").start();
    }

    private void stopTunnel() {
        stopCamera();
        watchNetwork(false);
        NetTunnel t = tunnel;
        tunnel = null;
        guestMode = false;
        if (t != null) new Thread(t::stop, "fnet-stop").start();
        refresh();
    }

    /** A scanned, picked or pasted text. */
    private void handle(String text) {
        char k = NetCodes.kind(text);
        if (k == 'J') {
            if (tunnel != null && tunnel.host) {
                toast(Tr.t("Это приглашение, а сеть создал ты сам. Чтобы подключиться к другому хосту, останови свою сеть."));
                return;
            }
            NetCodes.Invite inv = NetCodes.decodeInvite(text);
            if (inv == null) {
                toast(Tr.t("Код повреждён"));
                return;
            }
            if (inv.expiry != 0 && inv.expiry < System.currentTimeMillis() / 1000)
                toast(Tr.t("Приглашение, похоже, устарело — пробую всё равно"));
            if (inv.pin()) askPin(pin -> connect(inv, pin));
            else connect(inv, "");
        } else if (k == 'A') {
            NetTunnel t = tunnel;
            if (t == null || !t.host) {
                toast(Tr.t("Это ответный код гостя: его сканирует хост."));
                return;
            }
            new Thread(() -> {
                String err = t.acceptAnswer(text);
                ui.post(() -> toast(err == null ? Tr.t("Ответный код принят: стучусь к гостю") : Tr.t("Ответный код не принят: ") + err));
            }, "fnet-answer").start();
        } else toast(Tr.t("Это не код FNative"));
    }

    private void askPin(Consumer<String> then) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("000000");
        new AlertDialog.Builder(a).setTitle(Tr.t("PIN хоста"))
                .setMessage(Tr.t("Хост видит 6 цифр у себя на экране. Спроси их у него."))
                .setView(e)
                .setPositiveButton(Tr.t("Подключиться"), (d, w) -> then.accept(e.getText().toString().trim()))
                .setNegativeButton(Tr.t("Отмена"), null).show();
    }

    private void startCamera() {
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        scanBox.setVisibility(View.VISIBLE);
        camera.start(text -> {
            scanBox.setVisibility(View.GONE);
            handle(text);
        }, why -> {
            scanBox.setVisibility(View.GONE);
            toast(why);
        });
    }

    void onCameraPermission(boolean granted) {
        if (granted) startCamera();
        else toast(Tr.t("Без камеры: выбери фото с QR или вставь код"));
    }

    private void stopCamera() {
        camera.stop();
        scanBox.setVisibility(View.GONE);
    }

    private void pickPhoto() {
        NetPickActivity.callback = uri -> {
            if (uri == null) return;
            new Thread(() -> {
                String text = null;
                try {
                    text = NetQr.read(loadBitmap(uri));
                } catch (Exception e) {
                    // not an image
                }
                String t = text;
                ui.post(() -> {
                    if (t == null) toast(Tr.t("На фото не найден QR-код"));
                    else handle(t);
                });
            }, "fnet-photo").start();
        };
        a.startActivity(new Intent(a, NetPickActivity.class));
    }

    /** A picked image, at most ~2000 px on its long side. */
    private Bitmap loadBitmap(Uri uri) throws Exception {
        ContentResolver cr = a.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > 2000) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        try (InputStream in = cr.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, o2);
        }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence clip = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
            clip = cm.getPrimaryClip().getItemAt(0).coerceToText(a);
        if (clip != null && NetCodes.kind(clip.toString()) != 0) {
            handle(clip.toString());
            return;
        }
        EditText e = new EditText(a);
        e.setHint("FNJ1… / FNA1…");
        new AlertDialog.Builder(a).setTitle(Tr.t("Код"))
                .setView(e)
                .setPositiveButton("OK", (d, w) -> handle(e.getText().toString()))
                .setNegativeButton(Tr.t("Отмена"), null).show();
    }

    private void copy(String text) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FNative", text));
        toast(Tr.t("Скопировано"));
    }

    /** The QR with a caption, for sharing and the gallery. */
    private Bitmap card(Bitmap qr, String caption) {
        int w = qr.getWidth(), pad = w / 12;
        Bitmap b = Bitmap.createBitmap(w, w + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        c.drawBitmap(qr, 0, 0, null);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setTextSize(pad * 0.7f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(caption, w / 2f, w + pad, p);
        return b;
    }

    private Uri saveToGallery(Bitmap bmp, String kind) {
        if (bmp == null) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        ContentResolver cr = a.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "fnative-" + kind + "-" + stamp + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FNative");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        try {
            Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            try (OutputStream o = cr.openOutputStream(u)) {
                card(bmp, "invite".equals(kind) ? Tr.t("FNative: приглашение в сеть") : Tr.t("FNative: ответный код")).compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(u, v, null, null);
            return u;
        } catch (Exception e) {
            toast(Tr.t("Не сохранено: ") + e.getMessage());
            return null;
        }
    }

    private void save(Bitmap bmp, String kind) {
        if (saveToGallery(bmp, kind) != null) toast(Tr.t("Сохранено в Галерею: Pictures/FNative"));
    }

    private void share(Bitmap bmp, String code, boolean invite) {
        if (bmp == null || code == null) return;
        Uri u = saveToGallery(bmp, invite ? "invite" : "answer");
        Intent s = new Intent(Intent.ACTION_SEND);
        String pin = invite && tunnel != null && !tunnel.pinCode.isEmpty() ? Tr.t("\nPIN скажу отдельно.") : "";
        s.putExtra(Intent.EXTRA_TEXT, (invite ? Tr.t("Приглашение в сеть FNative (⚙ → Сеть → Подключиться → Вставить код):\n")
                : Tr.t("Ответный код FNative (⚙ → Сеть → Вставить код):\n")) + code + pin);
        if (u != null) {
            s.setType("image/png");
            s.putExtra(Intent.EXTRA_STREAM, u);
            s.setClipData(ClipData.newRawUri("", u));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else s.setType("text/plain");
        a.startActivity(Intent.createChooser(s, Tr.t("Поделиться")));
    }

    private void probe() {
        probeBtn.setEnabled(false);
        probeView.setText(Tr.t("Проверяю… (около 5 секунд)"));
        NetConfig c = config();
        c.lan = c.ipv6 = c.upnp = c.stun = true;  // the test looks at all the ways
        if (c.stunServers.trim().isEmpty()) c.stunServers = new NetConfig().stunServers;
        String kind = networkKind();
        new Thread(() -> {
            String r = "Сеть: " + kind + "\n" + NetProbe.run(c);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File f = new File(workDir, "net-test-" + stamp + ".txt");
            String saved;
            try {
                workDir.mkdirs();
                try (FileWriter w = new FileWriter(f)) {
                    w.write("FNative — тест сети " + stamp + "\nУстройство: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                            + Build.VERSION.RELEASE + "\n" + r);
                }
                saved = "\nОтчёт: " + f;
            } catch (Exception e) {
                saved = "\nОтчёт не записан: " + e.getMessage();
            }
            String out = r + saved;
            ui.post(() -> {
                probeView.setText(out);
                probeBtn.setEnabled(true);
            });
        }, "fnet-probe").start();
    }

    private ConnectivityManager.NetworkCallback netWatch;
    private String netSig;  // the default network and its addresses, last seen
    private final Runnable netChanged = () -> {
        NetTunnel t = tunnel;
        if (t != null) t.networkChanged();
    };

    /**
     * Tells the tunnel when the phone's default network or its addresses change (Wi-Fi ↔ mobile): the guest looks
     * for the host at once instead of after the quiet, the host pings its players from the new address.
     */
    private void watchNetwork(boolean on) {
        ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (netWatch != null) {
            try {
                cm.unregisterNetworkCallback(netWatch);
            } catch (Exception ignored) {
                // not registered
            }
            netWatch = null;
        }
        netSig = null;
        if (!on) return;
        netWatch = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties lp) {
                note(n + " " + lp.getLinkAddresses());
            }

            @Override public void onLost(android.net.Network n) {
                note("lost " + n);
            }

            private void note(String sig) {
                ui.post(() -> {
                    boolean first = netSig == null;
                    if (sig.equals(netSig)) return;
                    netSig = sig;
                    if (first) return;  // the state at registration
                    log("сеть телефона: " + networkKind());
                    ui.removeCallbacks(netChanged);
                    ui.postDelayed(netChanged, 300);  // a switch comes as several callbacks
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netWatch, ui);
        } catch (Exception e) {
            netWatch = null;
        }
    }

    private String networkKind() {
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "нет подключения";
            String s = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "мобильная" : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "другая";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) s += " + VPN";
            return s;
        } catch (Exception e) {
            return "неизвестно";
        }
    }

    private String name() {
        String n = prefs.getString("netName", Build.MODEL);
        return n == null || n.trim().isEmpty() ? Build.MODEL : n.trim();
    }

    /** Our tunnel's ping for the HUD: {lowest, highest} ms over the live connections, or null. */
    int[] tunnelPing() {
        NetTunnel t = tunnel;
        if (t == null || t.state == NetTunnel.STOPPED || t.state == NetTunnel.FAILED || t.probing) return null;
        if (!t.host && t.state != NetTunnel.CONNECTED) return null;
        int lo = Integer.MAX_VALUE, hi = -1;
        for (NetTunnel.PeerStat s : t.stats)
            if (!s.pending && !s.lost && s.rttMs >= 0) {
                lo = Math.min(lo, s.rttMs);
                hi = Math.max(hi, s.rttMs);
            }
        return hi < 0 ? null : new int[] {lo, hi};
    }

    boolean tunnelActive() {
        NetTunnel t = tunnel;
        return t != null && t.state != NetTunnel.STOPPED && t.state != NetTunnel.FAILED;
    }

    /** A host with nobody connected: no ping to show. */
    boolean tunnelHostAlone() {
        NetTunnel t = tunnel;
        return t != null && t.host && t.stats.isEmpty();
    }

    void onPause() {
        stopCamera();
    }

    void destroy() {
        stopCamera();
        watchNetwork(false);
        NetTunnel t = tunnel;
        tunnel = null;
        if (t != null) t.stop();  // BYE to the players, the router's port mapping removed
    }

    // ------------------------------------------------------------------ NetTunnel.Listener (tunnel thread)

    @Override public void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        synchronized (logLines) {
            logLines.addLast(stamp + " " + line);
            while (logLines.size() > 40) logLines.removeFirst();
        }
        try {
            workDir.mkdirs();
            try (FileWriter w = new FileWriter(new File(workDir, "net.log"), true)) {
                w.write(new SimpleDateFormat("yyyy-MM-dd ", Locale.ROOT).format(new Date()) + stamp + " " + line + "\n");
            }
        } catch (Exception ignored) {
            // the log on screen is enough
        }
        changed();
    }

    @Override public void changed() {
        synchronized (this) {
            if (refreshPosted) return;
            refreshPosted = true;
        }
        ui.postDelayed(() -> {
            synchronized (this) {
                refreshPosted = false;
            }
            refresh();
        }, 150);
    }

    @Override public void joinRequest(long gid, String who) {
        ui.post(() -> {
            NetTunnel t = tunnel;
            if (t == null) return;
            new AlertDialog.Builder(a).setTitle(Tr.t("Новый игрок"))
                    .setMessage("«" + who + "» " + Tr.t("хочет подключиться к твоей сети."))
                    .setPositiveButton(Tr.t("Пустить"), (d, w) -> t.approve(gid, true))
                    .setNegativeButton(Tr.t("Отказать"), (d, w) -> t.approve(gid, false))
                    .setCancelable(false).show();
        });
    }

    // ------------------------------------------------------------------ refresh (UI thread)

    private void refresh() {
        NetTunnel t = tunnel;
        boolean on = t != null;
        createBtn.setVisibility(on ? View.GONE : View.VISIBLE);
        connectBtn.setVisibility(on || guestMode ? View.GONE : View.VISIBLE);
        stopBtn.setVisibility(on || guestMode ? View.VISIBLE : View.GONE);
        stopBtn.setText(on ? Tr.t("Остановить сеть") : Tr.t("Отмена"));

        setupBox.setVisibility(!on && !guestMode ? View.VISIBLE : View.GONE);

        // host
        boolean hosting = on && t.host;
        hostBox.setVisibility(hosting ? View.VISIBLE : View.GONE);
        if (hosting) {
            String inv = t.inviteText;
            if (inv != null && !inv.equals(shownInvite)) {
                shownInvite = inv;
                inviteBitmap = NetQr.make(inv, 720);
                qrView.setImageBitmap(inviteBitmap);
            }
            if (inv == null) qrView.setImageBitmap(null);
            pinView.setText(t.pinCode.isEmpty() ? "" : "PIN  " + t.pinCode);
            pinView.setVisibility(t.pinCode.isEmpty() ? View.GONE : View.VISIBLE);
            StringBuilder info = new StringBuilder();
            if (inv == null) info.append(Tr.t("Собираю адреса… (до 6 с)"));
            else {
                info.append(t.inviteExpiry == 0 ? Tr.t("Без срока") : Tr.t("Действует до ")
                        + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(t.inviteExpiry * 1000)));
                if (t.cfg.oneUse) info.append(Tr.t(" · на одного игрока"));
                if (t.cfg.approve) info.append(Tr.t(" · вход с твоего подтверждения"));
                info.append("\n").append(Tr.t("Адреса в QR: "));
                NetCodes.Invite v = NetCodes.decodeInvite(inv);
                if (v != null) for (NetCodes.Cand c : v.cands) info.append("\n  ").append(c);
                if (!t.natNote.isEmpty()) info.append("\n").append(t.natNote.trim());
            }
            inviteInfo.setText(info);
            codeView.setText(inv != null ? inv : "");
        }

        // guest
        boolean guest = guestMode && (!on || !t.host);
        guestBox.setVisibility(guest ? View.VISIBLE : View.GONE);
        if (guest) {
            boolean connected = on && t.state == NetTunnel.CONNECTED;
            connectedInfo.setVisibility(connected ? View.VISIBLE : View.GONE);
            if (connected) connectedInfo.setText(Tr.t("Готово! В игре: Мультиплеер → Локальные игры — хост там как ") + "127.0.0.1:" + t.cfg.localPort
                    + Tr.t(".\nИли «Подключиться по адресу» (адрес скопирован: в поле нажми Ctrl+V)."));
            boolean showAnswer = on && t.state == NetTunnel.CONNECTING && t.answerText != null && t.status.contains("ответный");
            answerBox.setVisibility(showAnswer ? View.VISIBLE : View.GONE);
            if (showAnswer && !t.answerText.equals(shownAnswer)) {
                shownAnswer = t.answerText;
                answerBitmap = NetQr.make(shownAnswer, 720);
                answerQr.setImageBitmap(answerBitmap);
            }
            if (connected && !("copied" + t.boundPort).equals(connectedInfo.getTag())) {
                connectedInfo.setTag("copied" + t.boundPort);
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FNative", "127.0.0.1:" + t.cfg.localPort));
            }
        }

        refreshPlayers(t);

        // the log and the status line
        StringBuilder lg = new StringBuilder();
        synchronized (logLines) {
            for (String l : logLines) lg.append(l).append('\n');
        }
        logView.setText(lg);
        String st;
        if (!on) st = guestMode ? Tr.t("Сеть: жду код приглашения") : Tr.t("Сеть: выключена");
        else {
            String state = t.state == NetTunnel.GATHERING ? "запуск" : t.state == NetTunnel.HOSTING ? "хост"
                    : t.state == NetTunnel.CONNECTING ? "соединение" : t.state == NetTunnel.CONNECTED ? "подключено"
                    : t.state == NetTunnel.FAILED ? "ошибка" : "остановлена";
            st = "Сеть: " + state + " — " + t.status;
            if (t.host) st += " · игроков: " + t.stats.size();
            if (t.foreign > 0) st += " · отброшено чужих пакетов: " + t.foreign;
        }
        status.setText(st);
    }

    private void refreshPlayers(NetTunnel t) {
        List<NetTunnel.PeerStat> list = t != null ? t.stats : java.util.Collections.emptyList();
        playersBox.setVisibility(t != null ? View.VISIBLE : View.GONE);
        if (t == null) return;
        playersHead.setText(t.host ? Tr.t("Игроки: ") + list.size() + (t.cfg.maxPlayers > 0 ? " / " + t.cfg.maxPlayers : "")
                : Tr.t("Канал к хосту"));
        StringBuilder sig = new StringBuilder();
        for (NetTunnel.PeerStat s : list) sig.append(s.gid).append(s.pending).append(',');
        if (!sig.toString().equals(playersSig)) {
            playersSig = sig.toString();
            while (playersBox.getChildCount() > 1) playersBox.removeViewAt(1);
            rowText.clear();
            if (list.isEmpty())
                playersBox.addView(note(t.host ? Tr.t("Пока никого. Покажи QR-приглашение.") : Tr.t("Ещё не соединён.")));
            for (NetTunnel.PeerStat s : list) {
                TextView tv = note("");
                tv.setTextSize(13);
                rowText.put(s.gid, tv);
                playersBox.addView(tv);
                if (!t.host) continue;
                LinearLayout r = hrow();
                if (s.pending) {
                    r.addView(button(Tr.t("Пустить"), v -> t.approve(s.gid, true)), weight());
                    r.addView(button(Tr.t("Отказать"), v -> t.approve(s.gid, false)), weight());
                } else {
                    r.addView(button(Tr.t("Отключить"), v -> t.kick(s.gid, false)), weight());
                    r.addView(button(Tr.t("Заблокировать"), v -> t.kick(s.gid, true)), weight());
                }
                playersBox.addView(r);
            }
        }
        for (NetTunnel.PeerStat s : list) {
            TextView tv = rowText.get(s.gid);
            if (tv == null) continue;
            if (s.pending) {
                tv.setText("● " + s.name + " — " + Tr.t("ждёт подтверждения") + " (" + s.addr + ")");
                continue;
            }
            tv.setText(String.format(Locale.ROOT, "● %s — %s, %s\n   пинг %s, потери %d%%, ↓%.1f ↑%.1f КБ/с, всего ↓%s ↑%s, %d мин%s%s%s",
                    s.name, s.path, s.addr, s.rttMs >= 0 ? s.rttMs + " мс" : "—", s.lossPct, s.kbIn, s.kbOut, size(s.bytesIn), size(s.bytesOut),
                    s.minutes, s.idleS >= 3 ? ", тишина " + s.idleS + " с" : "", s.replays > 0 ? ", повторов отброшено " + s.replays : "",
                    s.gameDown ? (t.host ? "\n   ⚠ игра не принимает на порту " + t.cfg.gamePort + ": создай мультиплеерную игру" : "") : ""));
        }
    }

    private static String size(long b) {
        return b < 1024 * 1024 ? String.format(Locale.ROOT, "%.0f КБ", b / 1024.0) : String.format(Locale.ROOT, "%.1f МБ", b / 1048576.0);
    }

    // ------------------------------------------------------------------ small views

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private TextView note(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(dp(8), dp(2), dp(8), dp(6));
        return t;
    }

    private LinearLayout section(String title) {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(0, dp(10), 0, 0);
        TextView h = new TextView(a);
        h.setText(title);
        h.setTextSize(15);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(dp(8), dp(4), dp(8), dp(4));
        s.addView(h);
        return s;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void check(String label, boolean value, Consumer<Boolean> set) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setTextSize(13);
        c.setChecked(value);
        c.setOnCheckedChangeListener((b, on) -> {
            set.accept(on);
            saveManual();
        });
        manualBox.addView(c);
    }

    private void number(String label, int value, IntConsumer set) {
        LinearLayout r = hrow();
        TextView l = note(label);
        r.addView(l, weight());
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setTextSize(13);
        e.setMinEms(4);
        e.addTextChangedListener(new SimpleWatcher(s -> {
            try {
                set.accept(Integer.parseInt(s.trim()));
                saveManual();  // clamped to sane ranges when used (config())
            } catch (NumberFormatException ignored) {
                // while typing
            }
        }));
        r.addView(e);
        manualBox.addView(r);
    }

    private void text(String label, String value, Consumer<String> set) {
        manualBox.addView(note(label));
        EditText e = new EditText(a);
        e.setText(value);
        e.setTextSize(13);
        e.addTextChangedListener(new SimpleWatcher(s -> {
            set.accept(s);
            saveManual();
        }));
        manualBox.addView(e);
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }

    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final Consumer<String> on;

        SimpleWatcher(Consumer<String> on) {
            this.on = on;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override public void afterTextChanged(android.text.Editable s) {
            on.accept(s.toString());
        }
    }
}
