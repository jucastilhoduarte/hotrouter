package com.castilhoduarte.jlh6;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Updater {

    public static final String SCRIPT_RAW_URL =
            "https://raw.githubusercontent.com/jucastilhoduarte/jlh6/main/scripts/install-app.sh";
    public static final String RELEASES_API =
            "https://api.github.com/repos/jucastilhoduarte/jlh6/releases/latest";

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    private static final int CONNECT_MS = 4_000;
    private static final int READ_MS = 8_000;

    public interface CheckCallback { void onResult(boolean hasUpdate, String remoteTag); }
    public interface TriggerCallback { void onResult(boolean launched); }

    private Updater() {}

    /**
     * Checa o último release do GitHub numa worker thread. O callback roda na worker
     * thread — o chamador (Activity) deve marshalar pro thread de UI. Qualquer
     * falha de rede/parse -> hasUpdate=false (fail-safe: nunca habilita por engano).
     */
    public static void checkUpdate(int localVersionCode, CheckCallback cb) {
        new Thread(() -> {
            boolean has = false;
            String tag = null;
            try {
                String json = httpGet(RELEASES_API);
                tag = parseTagName(json);
                has = isUpdateAvailable(tag, localVersionCode);
            } catch (Throwable ignored) {
            }
            cb.onResult(has, tag);
        }, "Updater-check").start();
    }

    /**
     * Dispara o install-app.sh remoto destacado, numa worker thread. launched=true
     * só significa que o telnet aceitou o comando — o sucesso real é a morte do
     * processo (pm install -r). Callback na worker thread.
     */
    public static void triggerUpdate(TriggerCallback cb) {
        new Thread(() -> {
            boolean launched;
            try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
                TelnetRoot.Result r = t.exec(buildCommand(SCRIPT_RAW_URL));
                launched = r.ok();
            } catch (Throwable e) {
                launched = false;
            }
            cb.onResult(launched);
        }, "Updater-trigger").start();
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            // GitHub API exige User-Agent, senão responde 403.
            c.setRequestProperty("User-Agent", "JLH6");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Extrai o campo tag_name de um JSON de release do GitHub. */
    public static String parseTagName(String json) {
        if (json == null) return null;
        Matcher m = TAG.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Último segmento numérico da tag (ex. "v1.0.42" -> 42). -1 se não parseável. */
    public static int remoteVersionCode(String tag) {
        if (tag == null) return -1;
        String t = tag.trim();
        int dot = t.lastIndexOf('.');
        String seg = dot >= 0 ? t.substring(dot + 1) : t;
        try {
            return Integer.parseInt(seg);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Há update se o versionCode remoto for estritamente maior que o local. */
    public static boolean isUpdateAvailable(String tag, int localVersionCode) {
        return remoteVersionCode(tag) > localVersionCode;
    }

    /**
     * Comando telnet que baixa o install-app.sh e o roda DESTACADO: o `&` em conjunto
     * com setsid + stdio redirecionado faz o script sobreviver à morte do app (que o
     * próprio pm install -r provoca no fim).
     */
    public static String buildCommand(String rawUrl) {
        return "cd /data/local/tmp && curl -fsSL " + rawUrl
                + " -o jlh6_update.sh && setsid sh jlh6_update.sh"
                + " > jlh6_update.log 2>&1 < /dev/null &";
    }
}
