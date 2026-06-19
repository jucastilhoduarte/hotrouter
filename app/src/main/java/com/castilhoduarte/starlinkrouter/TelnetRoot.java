package com.castilhoduarte.starlinkrouter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente telnet mínimo para o shell root da head unit em 127.0.0.1:23.
 *
 * Sem bibliotecas externas. {@link Socket} puro. A única particularidade telnet tratada é a
 * negociação de opções (IAC): todo WILL/DO recebido do servidor é recusado para que ele pare
 * de pedir e nos entregue um shell root simples orientado a linhas (prompt {@code :/ #}).
 *
 * Um comando é executado envolvendo-o entre dois ecos sentinela únicos:
 * <pre>echo __HR_BEG__; ( cmd ); echo __HR_END__$?</pre>
 * A saída é tudo o que aparece estritamente entre a linha {@code __HR_BEG__} e a linha
 * {@code __HR_END__<code>}. Isso funciona independentemente de o shell ecoar ou não a entrada,
 * porque a linha de entrada ecoada é mais longa que os sentinelas puros e nunca coincide.
 *
 * As partes de lógica pura ({@link #stripNoise}, {@link #extract}, {@link #consume}) são estáticas
 * e livres de Android para que possam ser testadas unitariamente em um JDK simples.
 */
public final class TelnetRoot implements AutoCloseable {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 23;

    // Bytes do protocolo IAC do telnet.
    private static final int IAC = 255;
    private static final int DONT = 254;
    private static final int DO = 253;
    private static final int WONT = 252;
    private static final int WILL = 251;
    private static final int SB = 250;
    private static final int SE = 240;

    static final String BEG = "__HR_BEG__";
    static final String END = "__HR_END__";
    private static final Pattern END_LINE = Pattern.compile("^" + END + "(-?\\d+)$");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d?]*[ -/]*[@-~]");

    /** Resultado de um comando: texto coletado de stdout/stderr e código de saída do shell. */
    public static final class Result {
        public final String output;
        public final int exitCode;

        Result(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final int readTimeoutMs;

    public TelnetRoot(int connectTimeoutMs, int readTimeoutMs) throws IOException {
        this.readTimeoutMs = readTimeoutMs;
        socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /** Executa um comando e retorna sua saída + código de saída. */
    public Result exec(String command) throws IOException {
        String line = "echo " + BEG + "; ( " + command + " ); echo " + END + "$?\n";
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.flush();

        StringBuilder text = new StringBuilder();
        byte[] buf = new byte[2048];
        long deadline = monoNow() + readTimeoutMs;

        while (monoNow() < deadline) {
            int n;
            try {
                n = in.read(buf, 0, buf.length);
            } catch (SocketTimeoutException e) {
                // Timeout por leitura disparado; continua até o prazo total.
                continue;
            }
            if (n < 0) {
                break;
            }
            // O tratamento de IAC precisa responder no socket, então não pode ser totalmente estático.
            consume(buf, n, out, text);
            Result r = extract(text.toString());
            if (r != null) {
                return r;
            }
        }
        throw new IOException("telnet: timed out waiting for sentinel; got: "
                + stripNoise(text.toString()));
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- relógio monotônico (System.nanoTime é permitido; relógio de parede não é utilizado) ----
    private static long monoNow() {
        return System.nanoTime() / 1_000_000L;
    }

    // ---------------------------------------------------------------------------------
    // Lógica pura e testável abaixo.
    // ---------------------------------------------------------------------------------

    /**
     * Remove sequências IAC do telnet de {@code buf[0..len)}, adicionando o texto simples a
     * {@code text}. Para cada requisição de opção (WILL/DO) escrevemos uma recusa
     * (DONT/WONT) em {@code reply} para que o servidor pare de negociar.
     */
    static void consume(byte[] buf, int len, OutputStream reply, StringBuilder text)
            throws IOException {
        int i = 0;
        while (i < len) {
            int b = buf[i] & 0xFF;
            if (b != IAC) {
                text.append((char) b);
                i++;
                continue;
            }
            // IAC. Necessita de pelo menos mais um byte.
            if (i + 1 >= len) {
                break;
            }
            int cmd = buf[i + 1] & 0xFF;
            if (cmd == IAC) {
                // Literal 0xFF escapado.
                text.append((char) IAC);
                i += 2;
            } else if (cmd == WILL || cmd == DO || cmd == WONT || cmd == DONT) {
                if (i + 2 >= len) {
                    break;
                }
                int opt = buf[i + 2] & 0xFF;
                if (reply != null) {
                    int response = (cmd == WILL || cmd == WONT) ? DONT : WONT;
                    reply.write(new byte[]{(byte) IAC, (byte) response, (byte) opt});
                    reply.flush();
                }
                i += 3;
            } else if (cmd == SB) {
                // Sub-negociação: ignora até IAC SE.
                int j = i + 2;
                while (j + 1 < len && !((buf[j] & 0xFF) == IAC && (buf[j + 1] & 0xFF) == SE)) {
                    j++;
                }
                i = j + 2;
            } else {
                // Outro comando de 2 bytes (NOP, etc.) — descarta.
                i += 2;
            }
        }
    }

    /** Remove sequências ANSI e retornos de carro. */
    static String stripNoise(String s) {
        return ANSI.matcher(s).replaceAll("").replace("\r", "");
    }

    /**
     * Se {@code raw} contiver um bloco BEG..END completo, retorna seu Result; caso contrário
     * retorna null (aguardando mais bytes).
     */
    static Result extract(String raw) {
        String clean = stripNoise(raw);
        String[] lines = clean.split("\n", -1);

        int begIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(BEG)) {
                begIdx = i;
                break;
            }
        }
        if (begIdx < 0) {
            return null;
        }
        for (int i = begIdx + 1; i < lines.length; i++) {
            Matcher m = END_LINE.matcher(lines[i].trim());
            if (m.matches()) {
                StringBuilder body = new StringBuilder();
                for (int k = begIdx + 1; k < i; k++) {
                    if (body.length() > 0) {
                        body.append('\n');
                    }
                    body.append(lines[k]);
                }
                int code;
                try {
                    code = Integer.parseInt(m.group(1));
                } catch (NumberFormatException e) {
                    code = -1;
                }
                return new Result(body.toString().trim(), code);
            }
        }
        return null;
    }
}
