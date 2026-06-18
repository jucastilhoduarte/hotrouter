package com.castilhoduarte.hotrouter;

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
 * Owns the HotRouter daemon lifecycle. All privileged work runs through {@link TelnetRoot}
 * (root shell on 127.0.0.1:23). Singleton; mutations happen on one background thread so
 * boot-start and the UI toggle can't race.
 */
public final class HotRouter {

    private static final String TAG = "HotRouter";

    static final String BASE = "/data/local/tmp";
    static final String SCRIPT = BASE + "/hotrouter.sh";
    static final String PIDFILE = BASE + "/hotrouter.pid";
    static final String STATEFILE = BASE + "/hotrouter.state";
    static final String LOGFILE = BASE + "/hotrouter.log";
    static final String ASSET = "hotrouter.sh";

    static final String KEY_ENABLED = "enableHotRouter";

    // UI-facing status modes.
    public static final String OFF = "OFF";
    public static final String STARTING = "STARTING";
    public static final String STARLINK = "STARLINK";
    public static final String FOURG = "4G";
    public static final String ERROR = "ERROR";
    /** Telnet unreachable: app installed without the exploit (uid too high). */
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

    private static volatile HotRouter instance;

    public static HotRouter get() {
        if (instance == null) {
            synchronized (HotRouter.class) {
                if (instance == null) {
                    instance = new HotRouter();
                }
            }
        }
        return instance;
    }

    private final Handler bg;
    private volatile boolean watchdogRunning = false;
    private volatile long enableTimeMs = 0L;

    private HotRouter() {
        HandlerThread t = new HandlerThread("HotRouterThread");
        t.start();
        bg = new Handler(t.getLooper());
    }

    private SharedPreferences prefs() {
        return App.prefs();
    }

    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    /** Toggle from the UI. Persists immediately, then applies on the background thread. */
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

    /** Called by BootService on boot / process start. Honors the persisted toggle. */
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

    // ---- privileged operations (background thread only) ----

    private void pushScript() {
        String b64 = readAssetBase64();
        if (b64.isEmpty()) {
            Log.e(TAG, "empty hotrouter.sh asset");
            return;
        }
        String cmd = "echo " + b64 + " | base64 -d > " + SCRIPT + " && chmod 755 " + SCRIPT;
        run(cmd);
    }

    private void startDaemon() {
        // setsid + detach so the daemon survives the telnet session closing.
        run("setsid sh " + SCRIPT + " start >" + BASE + "/hotrouter.out 2>&1 < /dev/null &");
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

    // ---- blocking reads for the UI (call off the main thread) ----

    /** Current status. Blocking; call from a worker thread. */
    public Status readStatus() {
        if (!isEnabled()) {
            return new Status(OFF, 0L);
        }
        TelnetRoot t;
        try {
            t = new TelnetRoot(CONNECT_MS, READ_MS);
        } catch (IOException e) {
            // Couldn't even open the root shell -> not installed via the exploit.
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
        // OFF in the file but pid alive => still coming up.
        return new Status(STARTING, epoch);
    }

    /** Last {@code lines} log lines, or an error string. Blocking; call off main thread. */
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
