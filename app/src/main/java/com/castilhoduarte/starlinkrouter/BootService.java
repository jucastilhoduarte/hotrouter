package com.castilhoduarte.starlinkrouter;

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
 * Serviço em primeiro plano com suporte a direct-boot. Iniciado na inicialização do dispositivo
 * (por {@link BootReceiver}) e ao abrir o app. Sua única função é pedir ao {@link StarlinkRouter}
 * que respeite o estado persistido: se estava ATIVO, o daemon é (re)iniciado e o watchdog
 * armado — sem necessidade de abrir o app.
 */
public final class BootService extends Service {

    private static final String TAG = "BootService";
    private static final String CHANNEL_ID = "starlinkrouter";
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
        StarlinkRouter.get().onServiceStart();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        // minSdk 28, portanto canais de notificação sempre existem.
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Starlink Router", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Starlink Router")
                .setContentText("Gerenciando o roteamento do hotspot")
                .setSmallIcon(R.drawable.ic_wifi)
                .setOngoing(true)
                .build();
    }
}
