package com.sparky.tvmesaj;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.app.AlarmManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Arka planda surekli calisan servis: HTTP sunucusunu acar ve gelen mesajlari
 * film / menu / herhangi bir uygulamanin ustunde gosterir.
 */
public class MessageService extends Service {

    public static final int PORT = 8080;
    public static final String ACTION_TEST = "com.sparky.tvmesaj.TEST";
    private static final String CHANNEL = "sparky_service";

    private WebServer server;
    private OverlayManager overlay;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MessageService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void sendTest(Context ctx) {
        Intent i = new Intent(ctx, MessageService.class).setAction(ACTION_TEST);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        acquireLocks();

        overlay = new OverlayManager(this);
        server = new WebServer(this, PORT, new WebServer.Listener() {
            @Override
            public void onText(String name, String text, int seconds, boolean sound) {
                overlay.enqueueText(name, text, seconds, sound);
            }

            @Override
            public void onImage(String name, String text, byte[] data, int seconds, boolean sound) {
                overlay.enqueueImage(name, text, data, seconds, sound);
            }

            @Override
            public void onAudio(String name, String text, byte[] data, String mime, boolean sound) {
                overlay.enqueueAudio(name, text, data, mime, sound);
            }

            @Override
            public boolean canShowOverlay() {
                return OverlayManager.canDraw(MessageService.this);
            }
        });
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            overlay.enqueueText("Sparky", "Merhaba, bu akşam sinema keyfi yapalım mı? 🍿🎥🎬", 8, true);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.shutdown();
        if (overlay != null) overlay.release();
        releaseLocks();
        scheduleRestart();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    /** Sistem servisi kapatirsa 3 sn sonra yeniden ac. */
    private void scheduleRestart() {
        try {
            Intent i = new Intent(getApplicationContext(), MessageService.class);
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(this, 7, i, fl | PendingIntent.FLAG_UPDATE_CURRENT)
                    : PendingIntent.getService(this, 7, i, fl | PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000, pi);
        } catch (Exception ignored) {
        }
    }

    /** Wi-Fi'in ve islemcinin uykuya gecip baglantiyi koparmasini engeller. */
    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "Sparky:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sparky:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Sparky Mesaj servisi",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        return b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Sparky Mesaj açık")
                .setContentText("Telefondan: " + serverUrl())
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** TV'nin yerel ag adresi, ornek: http://192.168.1.34:8080 */
    public static String serverUrl() {
        String ip = localIp();
        return "http://" + (ip == null ? "TV-IP" : ip) + ":" + PORT;
    }

    public static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        String n = ni.getName();
                        if (n.startsWith("wlan") || n.startsWith("eth")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
