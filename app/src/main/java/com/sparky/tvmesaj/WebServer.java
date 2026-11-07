package com.sparky.tvmesaj;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bagimliliksiz, cok kucuk bir HTTP sunucusu.
 * Telefon ayni Wi-Fi'dan http://TV-IP:8080 adresine girip mesaj gonderir.
 */
public class WebServer extends Thread {

    public interface Listener {
        void onText(String name, String text, int seconds, boolean sound);
        void onImage(String name, String text, byte[] data, int seconds, boolean sound);
        void onAudio(String name, String text, byte[] data, String mime, boolean sound);
        boolean canShowOverlay();
    }

    private static final String TAG = "SparkyWeb";
    private static final int MAX_BODY = 30 * 1024 * 1024; // 30 MB

    private final Context ctx;
    private final int port;
    private final Listener listener;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public WebServer(Context ctx, int port, Listener listener) {
        super("SparkyWebServer");
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.listener = listener;
        setDaemon(true);
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            while (running) {
                final Socket s = serverSocket.accept();
                new Thread(() -> handle(s), "SparkyClient").start();
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Sunucu hatasi", e);
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String head = readHead(in);
            if (head == null || head.isEmpty()) {
                s.close();
                return;
            }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) {
                s.close();
                return;
            }
            String method = req[0].toUpperCase(Locale.ROOT);
            String fullPath = req[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(c + 1).trim());
                }
            }

            String path = fullPath;
            Map<String, String> query = new HashMap<>();
            int q = fullPath.indexOf('?');
            if (q >= 0) {
                path = fullPath.substring(0, q);
                parseForm(fullPath.substring(q + 1), query);
            }

            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = Integer.parseInt(cl.trim());
                if (len > MAX_BODY) {
                    send(out, 413, "application/json", json(false, "Dosya cok buyuk (en fazla 30 MB)"));
                    s.close();
                    return;
                }
                body = readFully(in, len);
            }

            route(method, path, query, headers, body, out);
            out.flush();
            s.close();
        } catch (Exception e) {
            Log.e(TAG, "Istek hatasi", e);
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(String method, String path, Map<String, String> query,
                       Map<String, String> headers, byte[] body, OutputStream out) throws IOException {

        // Mesaj konumu (alt / orta / ust) telefondaki sayfadan secilir
        String pos = query.get("pos");
        if (pos != null && (pos.equals("bottom") || pos.equals("center") || pos.equals("top"))) {
            ctx.getSharedPreferences("sparky", Context.MODE_PRIVATE).edit().putString("pos", pos).apply();
        }

        if ("OPTIONS".equals(method)) {
            send(out, 204, "text/plain", new byte[0]);
            return;
        }

        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            send(out, 200, "text/html; charset=utf-8", readAsset("index.html"));
            return;
        }

        if ("GET".equals(method) && "/api/status".equals(path)) {
            String j = "{\"ok\":true,\"overlay\":" + listener.canShowOverlay() + "}";
            send(out, 200, "application/json", j.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("POST".equals(method) && "/api/send".equals(path)) {
            Map<String, String> form = new HashMap<>(query);
            parseForm(new String(body, StandardCharsets.UTF_8), form);
            String text = form.get("text");
            if (text == null || text.trim().isEmpty()) {
                send(out, 400, "application/json", json(false, "Mesaj bos"));
                return;
            }
            listener.onText(name(form), text.trim(), seconds(form, 8), sound(form));
            send(out, 200, "application/json", json(true, "Gonderildi"));
            return;
        }

        if ("POST".equals(method) && "/api/image".equals(path)) {
            if (body.length == 0) {
                send(out, 400, "application/json", json(false, "Resim yok"));
                return;
            }
            listener.onImage(name(query), nz(query.get("text")), body, seconds(query, 12), sound(query));
            send(out, 200, "application/json", json(true, "Resim gonderildi"));
            return;
        }

        if ("POST".equals(method) && "/api/audio".equals(path)) {
            if (body.length == 0) {
                send(out, 400, "application/json", json(false, "Ses yok"));
                return;
            }
            String mime = query.get("mime");
            if (mime == null) mime = headers.get("content-type");
            listener.onAudio(name(query), nz(query.get("text")), body, mime, sound(query));
            send(out, 200, "application/json", json(true, "Sesli mesaj gonderildi"));
            return;
        }

        send(out, 404, "text/plain; charset=utf-8", "Bulunamadi".getBytes(StandardCharsets.UTF_8));
    }

    // ---------- yardimcilar ----------

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String name(Map<String, String> m) {
        String n = nz(m.get("name"));
        return n.isEmpty() ? "Sparky" : n;
    }

    private static int seconds(Map<String, String> m, int def) {
        try {
            int v = Integer.parseInt(nz(m.get("sec")));
            return Math.max(3, Math.min(60, v));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean sound(Map<String, String> m) {
        String v = m.get("sound");
        return v == null || !("0".equals(v) || "false".equals(v));
    }

    private static void parseForm(String s, Map<String, String> into) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int e = pair.indexOf('=');
            try {
                if (e < 0) {
                    into.put(URLDecoder.decode(pair, "UTF-8"), "");
                } else {
                    into.put(URLDecoder.decode(pair.substring(0, e), "UTF-8"),
                            URLDecoder.decode(pair.substring(e + 1), "UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > 64 * 1024) return null;
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(data, off, len - off);
            if (r < 0) break;
            off += r;
        }
        return data;
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = is.read(b)) > 0) bos.write(b, 0, r);
        is.close();
        return bos.toByteArray();
    }

    private static byte[] json(boolean ok, String msg) {
        String j = "{\"ok\":" + ok + ",\"message\":\"" + msg.replace("\"", "'") + "\"}";
        return j.getBytes(StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 204: status = "No Content"; break;
            case 400: status = "Bad Request"; break;
            case 413: status = "Payload Too Large"; break;
            default: status = "Not Found";
        }
        String h = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }
}
