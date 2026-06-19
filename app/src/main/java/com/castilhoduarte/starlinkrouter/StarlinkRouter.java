package com.castilhoduarte.starlinkrouter;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Gerencia o ciclo de vida do daemon StarlinkRouter. Todo trabalho privilegiado passa por {@link TelnetRoot}
 * (shell root em 127.0.0.1:23). Singleton; mutações ocorrem em uma única thread de background para
 * que o boot-start e o toggle da UI não entrem em corrida.
 */
public final class StarlinkRouter {

    private static final String TAG = "StarlinkRouter";

    static final String BASE = "/data/local/tmp";
    static final String SCRIPT = BASE + "/starlinkrouter.sh";
    static final String PIDFILE = BASE + "/starlinkrouter.pid";
    static final String STATEFILE = BASE + "/starlinkrouter.state";
    static final String LOGFILE = BASE + "/starlinkrouter.log";
    static final String ASSET = "starlinkrouter.sh";

    static final String KEY_ENABLED = "enableStarlinkRouter";

    // Modos de status visíveis pela UI.
    public static final String OFF = "OFF";
    public static final String STARTING = "STARTING";
    public static final String STARLINK = "STARLINK";
    public static final String FOURG = "4G";
    public static final String ERROR = "ERROR";
    /** Telnet inacessível: app instalado sem o exploit (uid muito alto). */
    public static final String NO_ROOT = "NO_ROOT";

    private static final int CONNECT_MS = 1500;
    private static final int READ_MS = 5000;
    private static final long WATCHDOG_MS = 60_000L;
    private static final long START_GRACE_MS = 20_000L;

    private static final String PID_ALIVE =
            "kill -0 $(cat " + PIDFILE + " 2>/dev/null) 2>/dev/null && echo ALIVE || echo DEAD";

    public static final class Status {
        public final String mode;
        public final long epochSeconds;

        Status(String mode, long epochSeconds) {
            this.mode = mode;
            this.epochSeconds = epochSeconds;
        }
    }

    private static volatile StarlinkRouter instance;

    public static StarlinkRouter get() {
        if (instance == null) {
            synchronized (StarlinkRouter.class) {
                if (instance == null) {
                    instance = new StarlinkRouter();
                }
            }
        }
        return instance;
    }

    private final Handler bg;
    private volatile boolean watchdogRunning = false;
    private volatile long enableTimeMs = 0L;

    private StarlinkRouter() {
        HandlerThread t = new HandlerThread("StarlinkRouterThread");
        t.start();
        bg = new Handler(t.getLooper());
    }

    private SharedPreferences prefs() {
        return App.prefs();
    }

    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    /** Toggle acionado pela UI. Persiste imediatamente e depois aplica na thread de background. */
    public void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).commit();
        if (enabled) {
            enableTimeMs = SystemClock.elapsedRealtime();
            bg.post(() -> {
                pushScript();
                startDaemon();
            });
            armWatchdog();
        } else {
            bg.post(this::stopDaemon);
        }
    }

    /** Chamado pelo BootService no boot / início do processo. Respeita o toggle persistido. */
    public void onServiceStart() {
        if (!isEnabled()) {
            return;
        }
        enableTimeMs = SystemClock.elapsedRealtime();
        bg.post(() -> {
            pushScript();
            if (!isAliveRemote()) {
                startDaemon();
            }
        });
        armWatchdog();
    }

    // ---- operações privilegiadas (somente na thread de background) ----

    // O shell telnet roda em modo canônico: uma linha de entrada é truncada em MAX_CANON (4096
    // bytes). O base64 do script (~13 KB) num único `echo` estourava esse limite e chegava cortado
    // no meio de um loop -> "unmatched 'for'". Enviamos o base64 em pedaços pequenos, um `echo ...
    // >> arquivo.b64` por linha (cada linha bem abaixo de 4096), e decodificamos no fim. base64 -d
    // ignora os newlines entre os pedaços, então o arquivo remontado é idêntico.
    private static final int B64_CHUNK = 1024;

    private void pushScript() {
        String b64 = readAssetBase64();
        if (b64.isEmpty()) {
            Log.e(TAG, "empty starlinkrouter.sh asset");
            return;
        }
        String b64file = SCRIPT + ".b64";
        StringBuilder cmd = new StringBuilder("rm -f ").append(b64file);
        for (int i = 0; i < b64.length(); i += B64_CHUNK) {
            int end = Math.min(i + B64_CHUNK, b64.length());
            cmd.append("\necho ").append(b64, i, end).append(" >> ").append(b64file);
        }
        cmd.append("\nbase64 -d ").append(b64file)
                .append(" > ").append(SCRIPT)
                .append(" && chmod 755 ").append(SCRIPT)
                .append(" && rm -f ").append(b64file);
        TelnetRoot.Result r = run(cmd.toString());
        if (r == null || !r.ok()) {
            Log.e(TAG, "pushScript failed: " + (r == null ? "no telnet" : r.output));
        }
    }

    private void startDaemon() {
        // setsid + detach para que o daemon sobreviva ao encerramento da sessão telnet.
        run("setsid sh " + SCRIPT + " start >" + BASE + "/starlinkrouter.out 2>&1 < /dev/null &");
        Log.w(TAG, "daemon start requested");
    }

    private void stopDaemon() {
        run("sh " + SCRIPT + " stop");
        Log.w(TAG, "daemon stop requested");
    }

    private boolean isAliveRemote() {
        TelnetRoot.Result r = run(PID_ALIVE);
        return r != null && r.output.contains("ALIVE");
    }

    private void armWatchdog() {
        bg.post(() -> {
            if (watchdogRunning) {
                return;
            }
            watchdogRunning = true;
            bg.postDelayed(watchdog, WATCHDOG_MS);
        });
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!isEnabled()) {
                watchdogRunning = false;
                return;
            }
            if (!isAliveRemote()) {
                Log.w(TAG, "watchdog: daemon dead while enabled, relaunching");
                enableTimeMs = SystemClock.elapsedRealtime();
                pushScript();
                startDaemon();
            }
            bg.postDelayed(this, WATCHDOG_MS);
        }
    };

    // ---- leituras bloqueantes para a UI (chamar fora da thread principal) ----

    /** Status atual. Bloqueante; chamar de uma thread worker. */
    public Status readStatus() {
        if (!isEnabled()) {
            return new Status(OFF, 0L);
        }
        TelnetRoot t;
        try {
            t = new TelnetRoot(CONNECT_MS, READ_MS);
        } catch (IOException e) {
            // Não foi possível nem abrir o shell root -> não instalado via exploit.
            return new Status(NO_ROOT, 0L);
        }
        try {
            TelnetRoot.Result alive = t.exec(PID_ALIVE);
            if (alive.output.contains("ALIVE")) {
                String raw = t.exec("cat " + STATEFILE + " 2>/dev/null").output.trim();
                return parseState(raw);
            }
        } catch (IOException e) {
            Log.e(TAG, "readStatus error", e);
        } finally {
            t.close();
        }
        long since = SystemClock.elapsedRealtime() - enableTimeMs;
        return new Status(since < START_GRACE_MS ? STARTING : ERROR, 0L);
    }

    private Status parseState(String raw) {
        if (raw.isEmpty()) {
            return new Status(STARTING, 0L);
        }
        String[] parts = raw.split("\\|");
        String mode = parts[0].trim();
        long epoch = 0L;
        if (parts.length > 1) {
            try {
                epoch = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (STARLINK.equals(mode) || FOURG.equals(mode)) {
            return new Status(mode, epoch);
        }
        // OFF no arquivo mas pid vivo => ainda inicializando.
        return new Status(STARTING, epoch);
    }

    /** Últimas {@code lines} linhas do log, ou uma string de erro. Bloqueante; chamar fora da thread principal. */
    public String readLog(int lines) {
        try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
            TelnetRoot.Result r = t.exec("tail -n " + lines + " " + LOGFILE + " 2>/dev/null");
            return r.output.isEmpty() ? "(sem logs ainda)" : r.output;
        } catch (IOException e) {
            return "Sem acesso root (telnet 127.0.0.1:23).\nReinstale pelo exploit.";
        }
    }

    private TelnetRoot.Result run(String cmd) {
        try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
            return t.exec(cmd);
        } catch (IOException e) {
            Log.e(TAG, "telnet run failed: " + cmd, e);
            return null;
        }
    }

    private String readAssetBase64() {
        try (InputStream is = App.context().getAssets().open(ASSET)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "read asset failed", e);
            return "";
        }
    }
}
