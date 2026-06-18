package com.castilhoduarte.hotrouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground, direct-boot-aware service. Started on boot (by {@link BootReceiver}) and on
 * app launch. Its only job is to ask {@link HotRouter} to honor the persisted toggle:
 * if it was ON, the daemon is (re)started and the watchdog armed — no app open needed.
 */
public final class BootService extends Service {

    private static final String TAG = "BootService";
    private static final String CHANNEL_ID = "hotrouter";
    private static final int NOTIF_ID = 1;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BootService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.w(TAG, "service start; honoring persisted toggle");
        HotRouter.get().onServiceStart();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        // minSdk 28, so notification channels always exist.
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "HotRouter", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HotRouter")
                .setContentText("Gerenciando o roteamento do hotspot")
                .setSmallIcon(R.drawable.ic_wifi)
                .setOngoing(true)
                .build();
    }
}
