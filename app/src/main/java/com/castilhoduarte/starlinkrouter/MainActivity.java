package com.castilhoduarte.starlinkrouter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A única tela: um grande botão liga/desliga, um chip de rota (Starlink/4G) e um botão de logs.
 * O status é consultado a cada {@link #POLL_MS} ms; a leitura bloqueante via telnet ocorre em uma
 * thread de trabalho e o resultado é renderizado de volta na thread principal.
 */
public final class MainActivity extends Activity {

    private static final long POLL_MS = 3000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean polling = false;

    private View bigButton;
    private TextView stateText;
    private TextView stateSub;
    private TextView routeChip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bigButton = findViewById(R.id.big_button);
        stateText = findViewById(R.id.state_text);
        stateSub = findViewById(R.id.state_sub);
        routeChip = findViewById(R.id.route_chip);

        bigButton.setOnClickListener(v -> toggle());
        findViewById(R.id.logs_button).setOnClickListener(
                v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.settings_button).setOnClickListener(v -> openAndroidSettings());

        // Garante que o serviço em segundo plano (watchdog) está ativo mesmo que o daemon tenha
        // sido iniciado apenas pela interface nesta sessão.
        BootService.start(this);
    }

    /**
     * Abre o painel completo de Configurações do Android (rede, Bluetooth, armazenamento, etc.).
     * O {@code Settings.ACTION_SETTINGS} genérico cai no painel OEM capado da head unit, então
     * miramos a activity AOSP real {@code com.android.settings/.Settings} de forma explícita —
     * o mesmo caminho usado pelo Haval Tools / Impulse. Cai para o intent genérico se a activity
     * explícita não existir nesta build.
     */
    private void openAndroidSettings() {
        Intent explicit = new Intent(Intent.ACTION_MAIN);
        explicit.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings"));
        explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(explicit);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void toggle() {
        boolean target = !StarlinkRouter.get().isEnabled();
        StarlinkRouter.get().setEnabled(target);
        // Feedback imediato otimista; o loop de polling irá reconciliar.
        render(new StarlinkRouter.Status(target ? StarlinkRouter.STARTING : StarlinkRouter.OFF, 0L));
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        main.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            io.execute(() -> {
                StarlinkRouter.Status s = StarlinkRouter.get().readStatus();
                main.post(() -> render(s));
            });
            if (polling) {
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    private void render(StarlinkRouter.Status s) {
        switch (s.mode) {
            case StarlinkRouter.STARLINK:
                paint(R.color.on_green, R.string.state_on, R.string.hint_tap_to_off);
                chip(R.color.chip_starlink, "●  " + getString(R.string.route_starlink));
                break;
            case StarlinkRouter.FOURG:
                paint(R.color.on_green, R.string.state_on, R.string.hint_tap_to_off);
                chip(R.color.chip_4g, "●  " + getString(R.string.route_4g));
                break;
            case StarlinkRouter.STARTING:
                paint(R.color.starting_amber, R.string.state_starting, R.string.hint_wait);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
            case StarlinkRouter.ERROR:
                paint(R.color.error_red, R.string.state_error, R.string.hint_tap_to_off);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
            case StarlinkRouter.NO_ROOT:
                paint(R.color.error_red, R.string.state_no_root, R.string.hint_reinstall);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
            case StarlinkRouter.OFF:
            default:
                paint(R.color.off_gray, R.string.state_off, R.string.hint_tap_to_on);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
        }
    }

    private void paint(int colorRes, int stateRes, int subRes) {
        Drawable bg = bigButton.getBackground().mutate();
        bg.setTint(getColor(colorRes));
        stateText.setText(stateRes);
        stateSub.setText(subRes);
    }

    private void chip(int colorRes, String text) {
        routeChip.getBackground().mutate().setTint(getColor(colorRes));
        routeChip.setText(text);
    }
}
