package com.castilhoduarte.jlh6;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("router", Context.MODE_PRIVATE);
        if (prefs.getBoolean("enabled", false)) {
            RouterService.start(context);
        }
    }
}
