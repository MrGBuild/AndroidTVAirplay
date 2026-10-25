package com.mrgbuild.receiver;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Experimental, unauthenticated legacy media-URL receiver. No RAOP or AirPlay 2. */
public final class AirPlayServer implements AutoCloseable {
    public interface Playback {
        void play(String url, double startFraction);
        void pause(boolean paused);
        void seek(double seconds);
        void stop();
        State state();
    }
    public static final class State {
        public final double duration, position;
        public final boolean playing, ready;
        public State(double duration, double position, boolean playing, boolean ready) {
            this.duration = Math.max(0, duration);
            this.position = Math.max(0, position);
            this.playing = playing; this.ready = ready;
        }
    }
    private static final int MAX_BODY = 65536;
    private final Playback playback;
    private final String deviceId;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> { Thread t = new Thread(r, "airplay-client"); t.setDaemon(true); return t; });
    private volatile boolean running;

    public AirPlayServer(int port, String deviceId, Playback playback) throws IOException {
        this.playback = playback; this.deviceId = deviceId;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
    }
    public int port() { return server.getLocalPort(); }
    public synchronized void start() {
        if (running) return;
        running = true;
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    s.setSoTimeout(10000);
                    sockets.add(s);
                    try { clients.execute(() -> handle(s)); }
                    catch (RejectedExecutionException full) { sockets.remove(s); s.close(); }
                } catch (IOException e) { if (running) close(); }
            }
        }, "airplay-listener");
        accept.setDaemon(true); accept.start();
    }
    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (running) {
                String request = line(in);
                if (request == null) return;
                String[] parts = request.split(" ");
                if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                    reply(out, 400, "text/plain", "Invalid HTTP request"); return;
                }
                Map<String,String> headers = new HashMap<>();
                int bytes = request.length();
                for (;;) {
                    String h = line(in);
                    if (h == null) return;
                    bytes += h.length();
                    if (bytes > 16384) { reply(out, 431, "text/plain", "Headers too large"); return; }
                    if (h.isEmpty()) break;
                    int colon = h.indexOf(':');
                    if (colon < 1) { reply(out, 400, "text/plain", "Invalid header"); return; }
                    String key = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    if (headers.put(key, h.substring(colon + 1).trim()) != null) {
                        reply(out, 400, "text/plain", "Duplicate header"); return;
                    }
                }
                if (headers.containsKey("transfer-encoding")) {
                    reply(out, 400, "text/plain", "Chunked requests are unsupported"); return;
                }
                int length;
                try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
                catch (NumberFormatException e) { reply(out, 400, "text/plain", "Invalid length"); return; }
                if (length < 0 || length > MAX_BODY) { reply(out, 413, "text/plain", "Body too large"); return; }
                byte[] body = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int n = in.read(body, offset, length - offset);
                    if (n < 0) return;
                    offset += n;
                }
                try { route(out, parts[0], URI.create(parts[1]), headers, body); }
                catch (IllegalArgumentException e) { reply(out, 400, "text/plain", "Invalid media request"); }
                catch (Exception e) { reply(out, 500, "text/plain", "Receiver error"); }
                if ("close".equalsIgnoreCase(headers.get("connection"))) return;
            }
        } catch (IOException ignored) {
            // Disconnects and read timeouts are normal; do not log media URLs or headers.
        } finally { sockets.remove(socket); }
    }
    private void route(OutputStream out, String method, URI uri, Map<String,String> headers, byte[] body) throws Exception {
        String path = uri.getPath();
        if (method.equals("GET") && path.equals("/server-info")) {
            plist(out, "<key>deviceid</key><string>" + deviceId + "</string>"
                    + "<key>features</key><integer>1</integer><key>model</key><string>AppleTV2,1</string>"
                    + "<key>protovers</key><string>1.0</string><key>srcvers</key><string>130.14</string>");
        } else if (method.equals("POST") && path.equals("/play")) {
            String url; double fraction = 0;
            String type = headers.getOrDefault("content-type", "");
            if (type.contains("plist")) {
                try {
                    // Binary plists avoid XML entity parsing of requests from the LAN.
                    if (!new String(body, 0, Math.min(8, body.length), StandardCharsets.US_ASCII).equals("bplist00")) {
                        reply(out, 415, "text/plain", "Use binary plist or text/parameters"); return;
                    }
                    NSObject parsed = PropertyListParser.parse(body);
                    if (!(parsed instanceof NSDictionary)) throw new IllegalArgumentException();
                    NSDictionary d = (NSDictionary) parsed;
                    NSObject location = d.objectForKey("Content-Location");
                    if (location == null) throw new IllegalArgumentException();
                    url = location.toString();
                    if (d.objectForKey("Start-Position") != null) fraction = Double.parseDouble(d.objectForKey("Start-Position").toString());
                } catch (Exception e) { reply(out, 400, "text/plain", "Invalid binary plist"); return; }
            } else {
                Map<String,String> parameters = new HashMap<>();
                for (String entry : new String(body, StandardCharsets.UTF_8).split("\r?\n")) {
                    int colon = entry.indexOf(':');
                    if (colon > 0) parameters.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
                }
                url = parameters.get("Content-Location");
                if (parameters.containsKey("Start-Position")) fraction = Double.parseDouble(parameters.get("Start-Position"));
            }
            validateMediaUrl(url);
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalArgumentException();
            playback.play(url, fraction);
            reply(out, 200, "text/plain", "");
        } else if (method.equals("POST") && path.equals("/rate")) {
            double rate = parameter(uri, "value");
            if (rate != 0 && rate != 1) throw new IllegalArgumentException();
            playback.pause(rate == 0); reply(out, 200, "text/plain", "");
        } else if (method.equals("POST") && path.equals("/scrub")) {
            double position = parameter(uri, "position");
            if (position < 0) throw new IllegalArgumentException();
            playback.seek(position); reply(out, 200, "text/plain", "");
        } else if (method.equals("GET") && path.equals("/scrub")) {
            State state = playback.state();
            reply(out, 200, "text/parameters", "duration: " + state.duration + "\r\nposition: " + state.position + "\r\n");
        } else if (method.equals("POST") && path.equals("/stop")) {
            playback.stop(); reply(out, 200, "text/plain", "");
        } else if (method.equals("GET") && path.equals("/playback-info")) {
            State s = playback.state();
            plist(out, "<key>duration</key><real>" + s.duration + "</real><key>position</key><real>" + s.position
                    + "</real><key>rate</key><real>" + (s.playing ? 1 : 0) + "</real><key>readyToPlay</key>"
                    + (s.ready ? "<true/>" : "<false/>") + "<key>playbackBufferEmpty</key>"
                    + (s.ready ? "<false/>" : "<true/>") + "<key>playbackBufferFull</key><false/>");
        } else { reply(out, 501, "text/plain", "Unsupported legacy AirPlay operation"); }
    }
    public static void validateMediaUrl(String value) {
        if (value == null) throw new IllegalArgumentException("Missing URL");
        URI u = URI.create(value);
        if (!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))
                || u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null) {
            throw new IllegalArgumentException("Use an HTTP or HTTPS media URL");
        }
    }
    private static double parameter(URI uri, String name) {
        if (uri.getRawQuery() != null) for (String p : uri.getRawQuery().split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                double v = Double.parseDouble(kv[1]);
                if (!Double.isFinite(v)) throw new IllegalArgumentException();
                return v;
            }
        }
        throw new IllegalArgumentException("Missing parameter");
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return result.toString("US-ASCII").replaceFirst("\r$", "");
            if (result.size() >= 8192) throw new IOException("Line too long");
            result.write(b);
        }
        return null;
    }
    private static void plist(OutputStream out, String content) throws IOException {
        reply(out, 200, "text/x-apple-plist+xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>" + content + "</dict></plist>");
    }
    private static void reply(OutputStream out, int status, String type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Error";
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nServer: TVAirPlay/0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }
    @Override public synchronized void close() {
        running = false;
        try { server.close(); } catch (IOException ignored) { }
        for (Socket s : sockets) try { s.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
    }
}
