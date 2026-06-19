package com.castilhoduarte.starlinkrouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Inicia o {@link BootService} na inicialização do sistema (incluindo o LOCKED_BOOT_COMPLETED
 * anterior ao desbloqueio, para que o daemon suba antes de o usuário desbloquear) e após a
 * atualização do aplicativo.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "received: " + (intent != null ? intent.getAction() : null));
        BootService.start(context);
    }
}
