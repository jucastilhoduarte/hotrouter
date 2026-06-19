package com.castilhoduarte.starlinkrouter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mantém o contexto da aplicação e o SharedPreferences protegido por dispositivo,
 * usado para lembrar o estado ligado/desligado. O armazenamento protegido por
 * dispositivo é legível durante o LOCKED_BOOT_COMPLETED (antes de o usuário
 * desbloquear o aparelho), o que permite que o daemon inicie automaticamente
 * no boot sem que ninguém precise abrir o aplicativo.
 */
public final class App extends Application {

    static final String PREFS = "starlinkrouter_prefs";

    private static App instance;
    private Context deviceProtected;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static Context context() {
        return instance;
    }

    public static SharedPreferences prefs() {
        if (instance.deviceProtected == null) {
            instance.deviceProtected = instance.createDeviceProtectedStorageContext();
        }
        return instance.deviceProtected.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
