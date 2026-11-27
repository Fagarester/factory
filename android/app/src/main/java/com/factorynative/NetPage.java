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

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private NetTunnel tunnel;
    private NetChat chat;  // the "Чат" page, the lines over the game and the mic (set by the activity)
    private boolean guestMode;  // the "Подключиться" tab (else "Создать сеть")
    private int preset;
    private NetConfig manual;
    private boolean refreshPosted;
    private String shownInvite, shownAnswer, playersSig = "";
    private Bitmap inviteBitmap, answerBitmap;

    private LinearLayout setupBox;  // presets and manual settings: only before "Создать сеть" (a guest takes the host's)
    private LinearLayout root, content, tabsRow, body, manualBox, hostBox, guestBox, answerBox, playersBox, scanBox;
    private TextView status, presetNote, pinView, passwordView, inviteInfo, codeView, connectedInfo, answerInfo, playersHead;
    private TextView hostTab, guestTab;
    private boolean secretShown;  // the invite's password and PIN on the screen (hidden by default)  // in the gear menu's tab look (tabPainter)
    private Button createBtn, stopBtn;
    private final java.util.function.BiConsumer<TextView, Boolean> tabPainter;
    private ImageView qrView, answerQr;
    private NetCamera camera;
    private final java.util.Map<Long, TextView> rowText = new java.util.HashMap<>();

    NetPage(Activity activity, java.util.function.BiConsumer<TextView, Boolean> tabPainter) {
        this.tabPainter = tabPainter;
        a = activity;
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

        // two tabs: "Создать сеть" (settings, then the invite) and "Подключиться" (scanning); while a network runs only
        // its own tab can be chosen. Under them: "Создать" or "Остановить сеть".
        LinearLayout tabs = hrow();
        tabsRow = tabs;
        hostTab = tab(Tr.t("Создать сеть"), v -> {
            stopCamera();  // the camera does not stay open behind another tab
            guestMode = false;
            refresh();
        });
        guestTab = tab(Tr.t("Подключиться"), v -> {
            stopCamera();  // the camera does not stay open behind another tab
            guestMode = true;
            refresh();
        });
        for (TextView t : new TextView[] {hostTab, guestTab}) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            tp.setMargins(dp(GAP), dp(GAP), 0, dp(GAP));
            tabs.addView(t, tp);
        }
        content.addView(tabs);
        // everything under the tabs: in the game's look a frame of its own that the chosen tab reaches into (paintTabs)
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        content.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        createBtn = button(Tr.t("Создать"), v -> create());
        stopBtn = button(Tr.t("Остановить сеть"), v -> stopTunnel());
        body.addView(createBtn);
        body.addView(stopBtn);

        // the camera box (scanning an invite or an answer)
        scanBox = new LinearLayout(a);
        scanBox.setOrientation(LinearLayout.VERTICAL);
        camera = new NetCamera(a);
        scanBox.addView(camera, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        scanBox.addView(button(Tr.t("Закрыть камеру"), v -> stopCamera()));
        scanBox.setVisibility(View.GONE);
        body.addView(scanBox);

        // host: the invite
        hostBox = section(Tr.t("Приглашение"));
        qrView = new ImageView(a);
        qrView.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(220), dp(220));
        ql.gravity = Gravity.CENTER_HORIZONTAL;
        hostBox.addView(qrView, ql);
        // the QR's password: new with each invite; said to the players apart from the QR (by voice, a message)
        passwordView = new TextView(a);
        passwordView.setTextSize(22);
        passwordView.setGravity(Gravity.CENTER);
        passwordView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        passwordView.setTextIsSelectable(true);
        hostBox.addView(passwordView);
        // hidden by default: the QR with its password on one screen (a stream, a screenshot) lets anyone in
        Button showSecret = button(Tr.t("Показать пароль"), null);
        showSecret.setOnClickListener(v -> {
            secretShown = !secretShown;
            showSecret.setText(secretShown ? Tr.t("Скрыть пароль") : Tr.t("Показать пароль"));
            refresh();
        });
        LinearLayout secretRow = hrow();  // show, then copy: the password is said or sent apart from the QR
        secretRow.addView(showSecret, weight());
        secretRow.addView(button(Tr.t("Копировать пароль"), v -> {
            NetTunnel t = tunnel;
            if (t != null && t.host) copySecret(t.inviteAndPassword()[1]);
        }), weight());
        hostBox.addView(secretRow);
        hostBox.addView(note(Tr.t("Пароль QR — скажи его игрокам отдельно от QR (голосом, сообщением). Без пароля QR не прочитать.")));
        hostBox.addView(button(Tr.t("Свой пароль"), v -> askOwnPassword()));
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
        // the code as text and the answer QR (when a guest cannot get through): folded under "Отладка"
        LinearLayout debug = foldable(hostBox, Tr.t("Отладка"), "netDebugOpen");
        debug.addView(codeView);
        debug.addView(note(Tr.t("Если гость не может подключиться, он покажет ответный QR — отсканируй его:")));
        debug.addView(scanRow());
        hostBox.addView(note(Tr.t("В игре: Мультиплеер → Создать игру. Если игра требует учётную запись "
                + "factorio.com, отключи в настройках сервера проверку пользователей (Verify user identity).")));
        body.addView(hostBox);

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
        body.addView(guestBox);

        // players / the connection
        playersBox = section("");
        playersHead = (TextView) playersBox.getChildAt(0);
        body.addView(playersBox);

        // name, presets, manual settings
        // the settings for creating: folded by default
        setupBox = new LinearLayout(a);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        setupBox.setPadding(0, dp(10), 0, 0);
        LinearLayout setup = foldable(setupBox, Tr.t("Настройки сети"), "netSetupOpen");
        LinearLayout nameRow = hrow();
        nameRow.addView(note(Tr.t("Имя для хоста:")));
        EditText name = new EditText(a);
        name.setSingleLine();
        name.setText(prefs.getString("netName", Build.MODEL));
        name.addTextChangedListener(new SimpleWatcher(s -> prefs.edit().putString("netName", s).apply()));
        nameRow.addView(name, weight());
        setup.addView(nameRow);
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
        body.addView(setupBox);
        showPreset();
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
        number(Tr.t("Порт для игры у гостя"), c.localPort, v -> c.localPort = v);
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
                ui.post(() -> toast(Tr.t("Сеть не запустилась: ") + NetTunnel.noAddr(e.getMessage())));
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
            askPassword(password -> new Thread(() -> {  // the password key is slow on purpose: off the UI thread
                NetCodes.Invite inv = NetCodes.decodeInvite(text, password);
                ui.post(() -> {
                    if (inv == null) {
                        toast(Tr.t("Неверный пароль (или код повреждён)"));
                        return;
                    }
                    if (inv.expiry != 0 && inv.expiry < System.currentTimeMillis() / 1000)
                        toast(Tr.t("Приглашение, похоже, устарело — пробую всё равно"));
                    if (inv.pin()) askPin(pin -> connect(inv, pin));
                    else connect(inv, "");
                });
            }, "fnet-open").start());
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

    private void askPassword(Consumer<String> then) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setHint("xxxx-xxxx-xxxx");
        new AlertDialog.Builder(a).setTitle(Tr.t("Пароль приглашения"))
                .setMessage(Tr.t("Хост видит пароль у себя под QR. Спроси его у него."))
                .setView(e)
                .setPositiveButton(Tr.t("Открыть"), (d, w) -> then.accept(e.getText().toString()))
                .setNegativeButton(Tr.t("Отмена"), null).show();
    }

    /** Host: an own password for the invite instead of the made one. */
    private void askOwnPassword() {
        NetTunnel t = tunnel;
        if (t == null || !t.host) return;
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setText(t.invitePassword);
        new AlertDialog.Builder(a).setTitle(Tr.t("Свой пароль"))
                .setMessage(Tr.t("Не короче 8 знаков; длиннее — надёжнее. QR сменится, игроки в игре останутся."))
                .setView(e)
                .setPositiveButton(Tr.t("Сохранить"), (d, w) -> {
                    if (!t.setInvitePassword(e.getText().toString())) toast(Tr.t("Слишком короткий пароль: нужно не меньше 8 знаков"));
                })
                .setNegativeButton(Tr.t("Отмена"), null).show();
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

    /** A secret to the clipboard, marked sensitive: Android 13+ then shows dots in its "copied" preview, not the text. */
    private void copySecret(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("FNative", text);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast(Tr.t("Пароль скопирован"));
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

    void setChat(NetChat c) {
        chat = c;
    }

    /** Another player on the line: chat and voice work. */
    boolean chatLinked() {
        NetTunnel t = tunnel;
        return t != null && t.linked();
    }

    /** The network runs (the mic button shows). */
    boolean chatActive() {
        return tunnelActive();
    }

    void sendChat(String text) {
        NetTunnel t = tunnel;
        if (t != null) t.sendChat(text);
    }

    void sendTyping() {
        NetTunnel t = tunnel;
        if (t != null) t.sendTyping();
    }

    void sendVoice(byte[] frame) {
        NetTunnel t = tunnel;
        if (t != null) t.sendVoice(frame);
    }

    String myName() {
        return name();
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
        ui.removeCallbacksAndMessages(null);  // its own handler: refresh and network-change timers
        stopCamera();
        watchNetwork(false);
        NetTunnel t = tunnel;
        tunnel = null;
        if (t != null) t.stop();  // BYE to the players, the router's port mapping removed
    }

    // ------------------------------------------------------------------ NetTunnel.Listener (tunnel thread)

    @Override public void log(String line) {
        changed();  // no log kept (on screen or in a file): it held addresses (2026-11-25, the user's choice)
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

    @Override public void chat(String from, String text) {
        NetChat c = chat;
        if (c != null) c.incoming(from, text);
    }

    @Override public void typing(int src, String from) {
        NetChat c = chat;
        if (c != null) c.incomingTyping(src, from);
    }

    @Override public void voice(int src, String from, byte[] frame) {
        NetChat c = chat;
        if (c != null) c.incomingVoice(src, from, frame);
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
        if (chat != null) chat.refresh();
        if (on) guestMode = !t.host;
        paintTabs();
        for (TextView b : new TextView[] {hostTab, guestTab}) b.setEnabled((b == guestTab) == guestMode || !on);
        createBtn.setVisibility(!on && !guestMode ? View.VISIBLE : View.GONE);
        stopBtn.setVisibility(on ? View.VISIBLE : View.GONE);

        setupBox.setVisibility(!on && !guestMode ? View.VISIBLE : View.GONE);

        // host
        boolean hosting = on && t.host;
        hostBox.setVisibility(hosting ? View.VISIBLE : View.GONE);
        if (hosting) {
            String[] code = t.inviteAndPassword();
            String inv = code[0];
            if (inv != null && !inv.equals(shownInvite)) {
                shownInvite = inv;
                inviteBitmap = NetQr.make(inv, 720);
                qrView.setImageBitmap(inviteBitmap);
            }
            if (inv == null) qrView.setImageBitmap(null);
            passwordView.setText(secretShown ? code[1] : hidden(code[1]));
            passwordView.setTextIsSelectable(secretShown);
            pinView.setText(t.pinCode.isEmpty() ? "" : "PIN  " + (secretShown ? t.pinCode : hidden(t.pinCode)));
            pinView.setVisibility(t.pinCode.isEmpty() ? View.GONE : View.VISIBLE);
            StringBuilder info = new StringBuilder();
            if (inv == null) info.append(Tr.t("Собираю адреса… (до 6 с)"));
            else {
                info.append(t.inviteExpiry == 0 ? Tr.t("Без срока") : Tr.t("Действует до ")
                        + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(t.inviteExpiry * 1000)));
                if (t.cfg.oneUse) info.append(Tr.t(" · на одного игрока"));
                if (t.cfg.approve) info.append(Tr.t(" · вход с твоего подтверждения"));
                if (!t.natNote.isEmpty()) info.append("\n").append(NetTunnel.noAddr(t.natNote.trim()));
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
            if (connected) connectedInfo.setText(Tr.t("Готово! В игре: Мультиплеер → Локальные игры — хост там в списке."));
            boolean showAnswer = on && t.state == NetTunnel.CONNECTING && t.answerText != null && t.status.contains("ответный");
            answerBox.setVisibility(showAnswer ? View.VISIBLE : View.GONE);
            if (showAnswer && !t.answerText.equals(shownAnswer)) {
                shownAnswer = t.answerText;
                answerBitmap = NetQr.make(shownAnswer, 720);
                answerQr.setImageBitmap(answerBitmap);
            }
        }

        refreshPlayers(t);

        // the status line
        String st;
        status.setVisibility(on ? View.VISIBLE : View.GONE);  // nothing to say while no network runs
        if (!on) st = "";
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
                tv.setText("● " + s.name + " — " + Tr.t("ждёт подтверждения"));
                continue;
            }
            tv.setText(String.format(Locale.ROOT, "● %s — %s\n   пинг %s, потери %d%%, ↓%.1f ↑%.1f КБ/с, всего ↓%s ↑%s, %d мин%s%s%s",
                    s.name, s.path, s.rttMs >= 0 ? s.rttMs + " мс" : "—", s.lossPct, s.kbIn, s.kbOut, size(s.bytesIn), size(s.bytesOut),
                    s.minutes, s.idleS >= 3 ? ", тишина " + s.idleS + " с" : "", s.replays > 0 ? ", повторов отброшено " + s.replays : "",
                    s.gameDown ? (t.host ? "\n   ⚠ игра ещё не принимает игроков: создай мультиплеерную игру" : "") : ""));
        }
    }

    private static String hidden(String secret) {
        return secret == null || secret.isEmpty() ? "" : secret.replaceAll("[^-\\s]", "•");
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

    /** A button "title ▼" in [parent] and under it a part it opens and folds (folded by default; remembered in
     * [key]). Returns the part. */
    private LinearLayout foldable(LinearLayout parent, String title, String key) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        Button fold = button("", null);
        Runnable show = () -> {
            boolean open = prefs.getBoolean(key, false);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            fold.setText(title + (open ? "  ▲" : "  ▼"));
        };
        fold.setOnClickListener(v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply();
            show.run();
        });
        show.run();
        parent.addView(fold);
        parent.addView(body);
        return body;
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

    /** A tab as the gear menu's own (its look comes from tabPainter: the theme). */
    private TextView tab(String text, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setTag("tab");  // FactorioSkin.skin leaves tabs to the painter
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setOnClickListener(l);
        return t;
    }

    private FactorioSkin skin;  // the "Factorio" theme's sprites, else null (the theme's colours)

    /** The two tabs in the theme's look, [skin] the game's look or null (the gear menu's paintMenuTabs). */
    void paintTabs(FactorioSkin skin) {
        this.skin = skin;
        paintTabs();
    }

    /** The two tabs in the theme's look: the chosen one as the menu's chosen tab; while a network runs the other
     * one is pale (it cannot be chosen). In the game's look as the menu's own tab row: the tabs stand on the frame
     * under them, the chosen one is 8 sprite pixels longer and covers the frame's top edge (its bottom has the
     * frame's colour). */
    void paintTabs() {
        if (hostTab == null) return;
        boolean on = tunnel != null;
        for (TextView t : new TextView[] {hostTab, guestTab}) {
            boolean chosen = (t == guestTab) == guestMode;
            tabPainter.accept(t, chosen);
            t.setAlpha(chosen || !on ? 1f : 0.5f);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            int h = dp(36) + (skin != null && chosen ? skin.px(8) : 0), bottom = skin != null ? 0 : dp(GAP);
            if (lp.height != h || lp.bottomMargin != bottom) {
                lp.height = h;
                lp.bottomMargin = bottom;
                t.setLayoutParams(lp);
            }
        }
        tabsRow.setPadding(skin != null ? skin.px(12) - dp(GAP) : 0, 0, 0, 0);  // as the menu's own row (tab_container)
        tabsRow.setGravity(skin != null ? Gravity.TOP : Gravity.CENTER_VERTICAL);  // centred, the chosen one stood out above
        tabsRow.setTranslationZ(skin != null ? 1f : 0f);  // drawn over the frame's edge
        LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) body.getLayoutParams();
        int top = skin != null ? -skin.px(8) : 0;
        if (bl.topMargin != top) {
            bl.topMargin = top;
            body.setLayoutParams(bl);
        }
        if (skin != null) {
            body.setBackground(skin.tabbed());
            body.setPadding(skin.px(12), skin.px(12), skin.px(12), skin.px(12));  // inside the side edges
        } else {
            body.setBackground(null);
            body.setPadding(0, 0, 0, 0);
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(40));  // a little lower than Android's default 48 dp (0.162 without a minimum: too small)
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(GAP), dp(GAP), dp(GAP), dp(GAP));  // added without its own params: full width, with the gap
        b.setLayoutParams(lp);
        return b;
    }

    // the room around each button: 4 dp on every side, 8 dp (about 1.3 mm) between two (the user's wish: 0.167 had
    // 12 dp, too much; before, the buttons looked glued together)
    private static final int GAP = 4;

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l.setMargins(dp(GAP), dp(GAP), dp(GAP), dp(GAP));
        return l;
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
