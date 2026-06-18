package com.castilhoduarte.hotrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts {@link BootService} on boot (including the pre-unlock LOCKED_BOOT_COMPLETED, so
 * the daemon comes up before the user unlocks) and after the app is updated.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "received: " + (intent != null ? intent.getAction() : null));
        BootService.start(context);
    }
}
