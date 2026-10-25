package com.mrgbuild.receiver;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AirPlayServerTest {
    private static final String PASSWORD = "test-password-123";
    private AirPlayServer server;
    private volatile String url;
    private volatile double fraction, seek;
    private volatile boolean paused, stopped;
    @Before public void start() throws Exception {
        server = new AirPlayServer(0, "02:11:22:33:44:55", PASSWORD, new AirPlayServer.Playback() {
            public void play(String u, double f) { url = u; fraction = f; }
            public void pause(boolean p) { paused = p; }
            public void seek(double s) { seek = s; }
            public void stop() { stopped = true; }
            public AirPlayServer.State state() { return new AirPlayServer.State(120, 30, true, true); }
        }); server.start();
    }
    @After public void finish() { server.close(); }
    private String request(String method, String path, String type, byte[] body) throws Exception {
        return request(method, path, type, body, PASSWORD);
    }
    private String request(String method, String path, String type, byte[] body, String secret) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write("GET /playback-info HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
            StringBuilder challenge = new StringBuilder();
            while (!challenge.toString().endsWith("\r\n\r\n")) {
                int b = in.read(); assertTrue("Expected digest challenge", b >= 0); challenge.append((char)b);
            }
            assertTrue(challenge.toString().startsWith("HTTP/1.1 401"));
            java.util.regex.Matcher nonce = java.util.regex.Pattern.compile("nonce=\"([^\"]+)\"").matcher(challenge);
            assertTrue(nonce.find());
            String auth = secret == null ? "" : "Authorization: " + authorization(method, path, nonce.group(1), secret) + "\r\n";
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: "
                    + type + "\r\n" + auth + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static String authorization(String method, String path, String nonce, String password) throws Exception {
        String ha1 = md5("AirPlay:airplay:" + password), ha2 = md5(method + ":" + path);
        return "Digest username=\"AirPlay\", realm=\"airplay\", nonce=\"" + nonce + "\", uri=\"" + path
                + "\", response=\"" + md5(ha1 + ":" + nonce + ":" + ha2) + "\"";
    }
    private static String md5(String value) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    private String send(String method, String path, String body) throws Exception {
        return request(method, path, "text/parameters", body.getBytes(StandardCharsets.UTF_8));
    }
    @Test public void advertisesOnlyLegacyVideo() throws Exception {
        String result = send("GET", "/server-info", "");
        assertTrue(result.contains("200 OK")); assertTrue(result.contains("<integer>1</integer>"));
        assertTrue(result.contains("02:11:22:33:44:55"));
    }
    @Test public void acceptsTextMediaAndStartFraction() throws Exception {
        assertTrue(send("POST", "/play", "Content-Location: https://example.com/a.mp4\r\nStart-Position: 0.25\r\n").contains("200 OK"));
        assertEquals("https://example.com/a.mp4", url); assertEquals(.25, fraction, 0);
    }
    @Test public void acceptsBinaryPlistAudioUrl() throws Exception {
        NSDictionary d = new NSDictionary(); d.put("Content-Location", "https://example.com/audio.mp3"); d.put("Start-Position", .5);
        assertTrue(request("POST", "/play", "application/x-apple-binary-plist", BinaryPropertyListWriter.writeToArray(d)).contains("200 OK"));
        assertEquals("https://example.com/audio.mp3", url); assertEquals(.5, fraction, 0);
    }
    @Test public void rejectsFileUrls() throws Exception {
        assertTrue(send("POST", "/play", "Content-Location: file:///etc/passwd").contains("400 Error")); assertNull(url);
    }
    @Test public void rejectsMissingMedia() throws Exception {
        assertTrue(send("POST", "/play", "").contains("400 Error")); assertNull(url);
    }
    @Test public void rejectsNonFiniteFraction() throws Exception {
        assertTrue(send("POST", "/play", "Content-Location: https://example.com/a.mp4\nStart-Position: NaN").contains("400 Error")); assertNull(url);
    }
    @Test public void rejectsOutOfRangeFraction() throws Exception {
        assertTrue(send("POST", "/play", "Content-Location: https://example.com/a.mp4\nStart-Position: 2").contains("400 Error")); assertNull(url);
    }
    @Test public void pausesResumesSeeksAndStops() throws Exception {
        assertTrue(send("POST", "/rate?value=0", "").contains("200 OK")); assertTrue(paused);
        assertTrue(send("POST", "/rate?value=1", "").contains("200 OK")); assertFalse(paused);
        assertTrue(send("POST", "/scrub?position=12.5", "").contains("200 OK")); assertEquals(12.5, seek, 0);
        assertTrue(send("POST", "/stop", "").contains("200 OK")); assertTrue(stopped);
    }
    @Test public void rejectsInvalidControls() throws Exception {
        assertTrue(send("POST", "/rate?value=2", "").contains("400 Error"));
        assertTrue(send("POST", "/scrub?position=-1", "").contains("400 Error"));
        assertTrue(send("POST", "/scrub?position=Infinity", "").contains("400 Error")); assertEquals(0, seek, 0);
    }
    @Test public void reportsPlaybackState() throws Exception {
        String result = send("GET", "/playback-info", "");
        assertTrue(result.contains("<real>120.0</real>")); assertTrue(result.contains("<real>30.0</real>"));
        assertTrue(result.contains("<key>readyToPlay</key><true/>"));
        assertTrue(send("GET", "/scrub", "").contains("position: 30.0"));
    }
    @Test public void unsupportedProtocolsAreExplicit() throws Exception {
        assertTrue(send("POST", "/pair-setup", "").contains("501 Error"));
        assertTrue(send("POST", "/reverse", "").contains("501 Error"));
    }
    @Test public void oversizedBodyIsRejected() throws Exception {
        assertTrue(request("POST", "/play", "text/parameters", new byte[65537]).contains("413 Error")); assertNull(url);
    }
    @Test public void malformedBinaryPlistIsRejected() throws Exception {
        assertTrue(request("POST", "/play", "application/x-apple-binary-plist", "bplist00broken".getBytes(StandardCharsets.US_ASCII)).contains("400 Error")); assertNull(url);
    }
    @Test public void xmlPlistDoesNotParseExternalEntities() throws Exception {
        assertTrue(request("POST", "/play", "text/x-apple-plist+xml", "<!DOCTYPE plist [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><plist>&x;</plist>".getBytes(StandardCharsets.UTF_8)).contains("415 Error"));
    }
    @Test public void nonHttpMediaUrlsAreRejected() {
        for (String s : new String[]{"content://media/1", "ftp://example.com/a", "http://user:pass@example.com/a", "https:///missing"}) {
            try { AirPlayServer.validateMediaUrl(s); fail(s); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void unauthenticatedSendersCannotControlOrReadPlayback() throws Exception {
        for (String path : new String[]{"/play", "/stop", "/rate?value=0", "/scrub?position=5"}) {
            assertTrue(request("POST", path, "text/parameters", new byte[0], null).contains("401 Unauthorized"));
        }
        for (String path : new String[]{"/playback-info", "/scrub"}) {
            assertTrue(request("GET", path, "text/parameters", new byte[0], null).contains("401 Unauthorized"));
        }
        assertNull(url); assertFalse(stopped); assertFalse(paused); assertEquals(0, seek, 0);
    }
    @Test public void incorrectPasswordCannotStartOrStopPlayback() throws Exception {
        assertTrue(request("POST", "/play", "text/parameters", "Content-Location: https://example.com/a.mp4".getBytes(StandardCharsets.UTF_8), "wrong-password").contains("401 Unauthorized"));
        assertTrue(request("POST", "/stop", "text/parameters", new byte[0], "wrong-password").contains("401 Unauthorized"));
        assertNull(url); assertFalse(stopped);
    }
    @Test public void discoveryIsAvailableButAdvertisesPassword() throws Exception {
        String result = request("GET", "/server-info", "text/parameters", new byte[0], null);
        assertTrue(result.contains("200 OK")); assertTrue(result.contains("<key>pw</key><true/>"));
        assertFalse(result.contains(PASSWORD));
    }
    @Test public void receiverCannotStartWithoutPassword() {
        assertThrows(IllegalArgumentException.class, () -> new AirPlayServer(0, "id", "", null));
        assertThrows(IllegalArgumentException.class, () -> new AirPlayServer(0, "id", null, null));
    }
}
