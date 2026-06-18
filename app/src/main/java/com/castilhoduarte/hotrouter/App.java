package com.castilhoduarte.hotrouter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Holds the application context and the device-protected SharedPreferences used to
 * remember the on/off toggle. Device-protected storage is readable during
 * LOCKED_BOOT_COMPLETED (before the user unlocks), which is what lets the daemon
 * auto-start on boot without anyone opening the app.
 */
public final class App extends Application {

    static final String PREFS = "hotrouter_prefs";

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
