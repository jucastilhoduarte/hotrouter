package com.castilhoduarte.jlh6;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private static final long UPDATE_WATCHDOG_MS = 120_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Switch recoverySwitch;

    private TextView versionLabel;
    private View updateIconContainer;
    private ImageView updateIcon;
    private View updateSpinner;

    private boolean updateAvailable = false;
    private boolean updating = false;
    private String remoteTag = null;

    private final CompoundButton.OnCheckedChangeListener recoveryListener =
            (button, checked) -> RouterManager.get().setAutoRecovery(this, checked);

    private final Runnable pollState = new Runnable() {
        @Override public void run() {
            updateRouterButton();
            if (RouterManager.get().getState() != RouterManager.State.DISABLED) {
                mainHandler.postDelayed(this, 500);
            }
        }
    };

    private final Runnable updateWatchdog = this::onUpdateFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.router_button).setOnClickListener(v -> onRouterTap());
        findViewById(R.id.settings_button).setOnClickListener(v -> openAndroidSettings());

        recoverySwitch = findViewById(R.id.recovery_switch);
        recoverySwitch.setChecked(RouterManager.get().isAutoRecovery(this));
        recoverySwitch.setOnCheckedChangeListener(recoveryListener);

        versionLabel = findViewById(R.id.version_label);
        updateIconContainer = findViewById(R.id.update_icon_container);
        updateIcon = findViewById(R.id.update_icon);
        updateSpinner = findViewById(R.id.update_spinner);
        updateIconContainer.setOnClickListener(v -> onUpdateTap());
        versionLabel.setText(getString(R.string.version_label_format, localVersionName()));
        refreshUpdateIcon();

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

        // Reflect the persisted flag without re-triggering the listener
        // (manual disable / timeout may have untoggled it while we were away).
        recoverySwitch.setOnCheckedChangeListener(null);
        recoverySwitch.setChecked(mgr.isAutoRecovery(this));
        recoverySwitch.setOnCheckedChangeListener(recoveryListener);

        if (mgr.getState() != RouterManager.State.DISABLED) {
            mainHandler.post(pollState);
        }

        if (!updating) checkForUpdate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(pollState);
    }

    // ---- update button ----

    private void checkForUpdate() {
        Updater.checkUpdate(localVersionCode(), (has, tag) ->
                runOnUiThread(() -> {
                    if (updating) return;
                    updateAvailable = has;
                    remoteTag = tag;
                    refreshUpdateIcon();
                }));
    }

    private void onUpdateTap() {
        if (updating || !updateAvailable) return;
        if (RouterManager.get().getState() != RouterManager.State.DISABLED) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_blocked_title)
                    .setMessage(R.string.update_blocked_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(R.string.update_dialog_message)
                .setPositiveButton(R.string.update_dialog_confirm, (d, w) -> startUpdate())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startUpdate() {
        updating = true;
        setControlsEnabled(false);
        updateIcon.setVisibility(View.GONE);
        updateSpinner.setVisibility(View.VISIBLE);
        versionLabel.setText(getString(R.string.updating_format, remoteTag));
        mainHandler.postDelayed(updateWatchdog, UPDATE_WATCHDOG_MS);
        Updater.triggerUpdate(launched ->
                runOnUiThread(() -> {
                    // launched OK: mantém bloqueado; o sucesso mata o processo e o
                    // script reabre o app. Só a falha de telnet volta atrás aqui.
                    if (!launched) onUpdateFailed();
                }));
    }

    private void onUpdateFailed() {
        if (!updating) return;
        updating = false;
        mainHandler.removeCallbacks(updateWatchdog);
        updateSpinner.setVisibility(View.GONE);
        updateIcon.setVisibility(View.VISIBLE);
        versionLabel.setText(getString(R.string.version_label_format, localVersionName()));
        setControlsEnabled(true);
        refreshUpdateIcon();
        Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
    }

    private void refreshUpdateIcon() {
        if (updating) return;
        boolean on = updateAvailable;
        updateIconContainer.setClickable(on);
        updateIcon.setAlpha(on ? 1f : 0.4f);
    }

    private void setControlsEnabled(boolean on) {
        setClickableAlpha(findViewById(R.id.settings_button), on);
        setClickableAlpha(findViewById(R.id.router_button), on);
        recoverySwitch.setEnabled(on);
        recoverySwitch.setAlpha(on ? 1f : 0.4f);
        updateIconContainer.setClickable(on);
        updateIcon.setAlpha(on ? 1f : 0.4f);
    }

    private static void setClickableAlpha(View v, boolean on) {
        v.setClickable(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    private int localVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE; // desconhecido: nunca oferece update
        }
    }

    private String localVersionName() {
        try {
            String n = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return n != null ? n : "?";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ---- router ----

    private void onRouterTap() {
        RouterManager mgr = RouterManager.get();
        RouterManager.State s = mgr.getState();
        if (s == RouterManager.State.PURGING) return;
        if (s == RouterManager.State.DISABLED) {
            // Gate first activation on the accessibility anchor — without it the router
            // would not survive a reboot. Prompt to enable it instead of starting.
            if (!RouterAccessibilityService.isEnabled(this)) {
                promptEnableAccessibility();
                return;
            }
            mgr.enable(this);
        } else {
            mgr.disable(this);
        }
        mainHandler.removeCallbacks(pollState);
        mainHandler.post(pollState);
        updateRouterButton();
    }

    private void promptEnableAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_prompt_title)
                .setMessage(R.string.accessibility_prompt_message)
                .setPositiveButton(R.string.accessibility_prompt_open, (d, w) -> openAccessibilitySettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
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
