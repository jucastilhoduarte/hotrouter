package com.castilhoduarte.jlh6;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class RouterService extends Service {

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, RouterService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RouterManager mgr = RouterManager.get();
        mgr.restoreIfEnabled(this);
        scheduleStop(mgr, startId);
        return START_NOT_STICKY;
    }

    private void scheduleStop(RouterManager mgr, int startId) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mgr.getState() == RouterManager.State.STARTING) {
                    handler.postDelayed(this, 1_000);
                } else {
                    stopSelf(startId);
                }
            }
        }, 1_000);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
