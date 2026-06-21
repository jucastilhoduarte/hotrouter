package com.castilhoduarte.jlh6;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

public final class MainActivity extends Activity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollState = new Runnable() {
        @Override public void run() {
            updateRouterButton();
            RouterManager.State s = RouterManager.get().getState();
            if (s == RouterManager.State.STARTING || s == RouterManager.State.PURGING) {
                mainHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.router_button).setOnClickListener(v -> onRouterTap());
        findViewById(R.id.settings_button).setOnClickListener(v -> openAndroidSettings());

        updateRouterButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(pollState);
        RouterManager mgr = RouterManager.get();
        if (mgr.getState() == RouterManager.State.DISABLED) {
            mgr.restoreIfEnabled(this);
        }
        updateRouterButton();
        RouterManager.State s = mgr.getState();
        if (s == RouterManager.State.STARTING || s == RouterManager.State.PURGING) {
            mainHandler.post(pollState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(pollState);
    }

    private void onRouterTap() {
        RouterManager mgr = RouterManager.get();
        RouterManager.State s = mgr.getState();
        if (s == RouterManager.State.PURGING) return;
        if (s == RouterManager.State.DISABLED) {
            mgr.enable(this);
        } else {
            mgr.disable(this);
        }
        mainHandler.removeCallbacks(pollState);
        mainHandler.post(pollState);
        updateRouterButton();
    }

    private void updateRouterButton() {
        TextView tv = findViewById(R.id.router_status);
        View btn = findViewById(R.id.router_button);
        switch (RouterManager.get().getState()) {
            case STARTING:
                tv.setText(R.string.router_starting);
                btn.setBackgroundResource(R.drawable.bg_button_busy);
                break;
            case ACTIVE:
                tv.setText(R.string.router_active);
                btn.setBackgroundResource(R.drawable.bg_button_active);
                break;
            case PURGING:
                tv.setText(R.string.router_purging);
                btn.setBackgroundResource(R.drawable.bg_button_busy);
                break;
            default:
                tv.setText(R.string.router_disabled);
                btn.setBackgroundResource(R.drawable.bg_button);
                break;
        }
    }

    private void openAndroidSettings() {
        Intent explicit = new Intent(Intent.ACTION_MAIN);
        explicit.setComponent(new ComponentName(
                "com.android.settings", "com.android.settings.Settings"));
        explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(explicit);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
