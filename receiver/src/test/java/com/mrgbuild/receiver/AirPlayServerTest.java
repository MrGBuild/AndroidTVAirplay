package com.mrgbuild.receiver;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AirPlayServerTest {
    private AirPlayServer server;
    private volatile String url;
    private volatile double fraction, seek;
    private volatile boolean paused, stopped;
    @Before public void start() throws Exception {
        server = new AirPlayServer(0, "02:11:22:33:44:55", new AirPlayServer.Playback() {
            public void play(String u, double f) { url = u; fraction = f; }
            public void pause(boolean p) { paused = p; }
            public void seek(double s) { seek = s; }
            public void stop() { stopped = true; }
            public AirPlayServer.State state() { return new AirPlayServer.State(120, 30, true, true); }
        }); server.start();
    }
    @After public void finish() { server.close(); }
    private String request(String method, String path, String type, byte[] body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: "
                    + type + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
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
}
