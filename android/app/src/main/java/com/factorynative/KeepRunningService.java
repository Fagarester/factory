package com.factorynative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * "Работать в фоне": while the game runs on behind other apps, this foreground service (in the game's process) keeps
 * Android from freezing or killing it, holds the CPU awake (screen off too) and Wi-Fi at full speed. Started by
 * GameActivity when it goes to the background, stopped when it comes back.
 */
public final class KeepRunningService extends Service {
    private static final String CHANNEL = "background";
    private static final int ID = 0x4E04;
    private PowerManager.WakeLock cpu;
    private android.net.wifi.WifiManager.WifiLock wifi;

    static void start(Context c, boolean on) {
        Intent i = new Intent(c, KeepRunningService.class);
        try {
            if (on) c.startForegroundService(i);
            else c.stopService(i);
        } catch (RuntimeException ignored) {
            // not allowed now: the game pauses as before
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Tr.t("Работа в фоне"), NotificationManager.IMPORTANCE_LOW));
        Intent back = new Intent(this, GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, 0, back, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(Tr.t("FNative работает в фоне"))
                .setContentText(Tr.t("Игра и сеть не остановлены. Звук выключен. Нажми, чтобы вернуться."))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(ID, n);
        cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fnative:background");
        cpu.acquire(12 * 3600 * 1000L);  // a limit for a forgotten game
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fnative:background");
            wifi.acquire();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (cpu != null && cpu.isHeld()) cpu.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
