package com.castilhoduarte.hotrouter;

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
 * Minimal telnet client for the head unit's root shell on 127.0.0.1:23.
 *
 * No library. Raw {@link Socket}. The only telnet wrinkle handled is option
 * negotiation (IAC): every WILL/DO from the server is refused so it stops asking and
 * hands us a plain line-oriented root shell (prompt {@code :/ #}).
 *
 * A command is run by wrapping it between two unique sentinel echoes:
 * <pre>echo __HR_BEG__; ( cmd ); echo __HR_END__$?</pre>
 * The output is whatever appears strictly between the {@code __HR_BEG__} line and the
 * {@code __HR_END__<code>} line. This works whether or not the shell echoes input,
 * because the echoed input line is longer than the bare sentinels and never matches.
 *
 * Pure-logic parts ({@link #stripNoise}, {@link #extract}, {@link #consume}) are static
 * and Android-free so they can be unit tested on a plain JDK.
 */
public final class TelnetRoot implements AutoCloseable {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 23;

    // Telnet IAC protocol bytes.
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

    /** Result of one command: collected stdout/stderr text and the shell exit code. */
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

    /** Run a command and return its output + exit code. */
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
                // Per-read timeout fired; keep going until the overall deadline.
                continue;
            }
            if (n < 0) {
                break;
            }
            // IAC handling needs to reply on the wire, so it can't be fully static.
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

    // ---- monotonic clock (System.nanoTime is allowed; wall clock is not relied upon) ----
    private static long monoNow() {
        return System.nanoTime() / 1_000_000L;
    }

    // ---------------------------------------------------------------------------------
    // Pure, testable logic below.
    // ---------------------------------------------------------------------------------

    /**
     * Strip telnet IAC sequences from {@code buf[0..len)}, appending plain text to
     * {@code text}. For every option request (WILL/DO) we write a refusal
     * (DONT/WONT) to {@code reply} so the server stops negotiating.
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
            // IAC. Need at least one more byte.
            if (i + 1 >= len) {
                break;
            }
            int cmd = buf[i + 1] & 0xFF;
            if (cmd == IAC) {
                // Escaped 0xFF literal.
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
                // Sub-negotiation: skip until IAC SE.
                int j = i + 2;
                while (j + 1 < len && !((buf[j] & 0xFF) == IAC && (buf[j + 1] & 0xFF) == SE)) {
                    j++;
                }
                i = j + 2;
            } else {
                // Other 2-byte command (NOP, etc.) — drop it.
                i += 2;
            }
        }
    }

    /** Remove ANSI escapes and carriage returns. */
    static String stripNoise(String s) {
        return ANSI.matcher(s).replaceAll("").replace("\r", "");
    }

    /**
     * If {@code raw} contains a complete BEG..END block, return its Result; otherwise
     * null (need more bytes).
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
