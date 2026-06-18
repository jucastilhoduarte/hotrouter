package com.castilhoduarte.hotrouter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one screen: a big on/off button, a route chip (Starlink/4G), and a logs button.
 * Status is polled every {@link #POLL_MS} ms; the blocking telnet read happens on a
 * worker thread and the result is rendered back on the main thread.
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

        // Make sure the background service (watchdog) is alive even if the daemon was
        // started purely from the UI this session.
        BootService.start(this);
    }

    private void toggle() {
        boolean target = !HotRouter.get().isEnabled();
        HotRouter.get().setEnabled(target);
        // Optimistic immediate feedback; the poll loop will reconcile.
        render(new HotRouter.Status(target ? HotRouter.STARTING : HotRouter.OFF, 0L));
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
                HotRouter.Status s = HotRouter.get().readStatus();
                main.post(() -> render(s));
            });
            if (polling) {
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    private void render(HotRouter.Status s) {
        switch (s.mode) {
            case HotRouter.STARLINK:
                paint(R.color.on_green, R.string.state_on, R.string.hint_tap_to_off);
                chip(R.color.chip_starlink, "●  " + getString(R.string.route_starlink));
                break;
            case HotRouter.FOURG:
                paint(R.color.on_green, R.string.state_on, R.string.hint_tap_to_off);
                chip(R.color.chip_4g, "●  " + getString(R.string.route_4g));
                break;
            case HotRouter.STARTING:
                paint(R.color.starting_amber, R.string.state_starting, R.string.hint_wait);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
            case HotRouter.ERROR:
                paint(R.color.error_red, R.string.state_error, R.string.hint_tap_to_off);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
            case HotRouter.NO_ROOT:
                paint(R.color.error_red, R.string.state_no_root, R.string.hint_reinstall);
                chip(R.color.chip_idle, getString(R.string.route_none));
                break;
            case HotRouter.OFF:
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
