package com.castilhoduarte.starlinkrouter;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exibe o final do log do daemon, lido via telnet. Permite atualização. */
public final class LogActivity extends Activity {

    private static final int TAIL_LINES = 400;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView logText;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        logText = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
        findViewById(R.id.refresh_button).setOnClickListener(v -> load());
        findViewById(R.id.close_button).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void load() {
        logText.setText("carregando…");
        io.execute(() -> {
            String log = StarlinkRouter.get().readLog(TAIL_LINES);
            main.post(() -> {
                logText.setText(log);
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            });
        });
    }
}
