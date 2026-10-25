package com.mrgbuild.tvairplay;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.mrgbuild.receiver.AirPlayServer;
import java.net.*;
import java.util.*;

/** Receiver lives only while this activity is visible, including full-screen playback. */
@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity implements AirPlayServer.Playback {
    private static final int INK = Color.rgb(9, 24, 39), MINT = Color.rgb(117, 230, 206);
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout home;
    private TextView status, endpoint, playbackStatus, audioStatus;
    private Button openButton;
    private AirPlayServer receiver;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private NsdManager.RegistrationListener audioRegistration;
    private NativeRaop audioReceiver;
    private String deviceId;
    private int audioGeneration;
    private volatile AirPlayServer.State state = new AirPlayServer.State(0, 0, false, false);
    private double startFraction;
    private boolean active;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            state = new AirPlayServer.State(player.getDuration() == C.TIME_UNSET ? 0 : player.getDuration() / 1000d,
                    player.getCurrentPosition() / 1000d, player.isPlaying(), player.getPlaybackState() == Player.STATE_READY);
            main.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        player = new ExoPlayer.Builder(this).build();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(INK);
        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this);
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(56), dp(36), dp(56), dp(30));
        scroll.addView(home);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // Hide the scroll container as a unit during playback.
        scroll.setTag("home");
        setContentView(root);
        TextView eyebrow = label("TV AIRPLAY  /  EARLY PREVIEW", 13, MINT);
        eyebrow.setLetterSpacing(0.15f); home.addView(eyebrow);
        TextView title = label("Your media. The big screen.", 36, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD); add(title, 12);
        add(label("Keep this screen open and use the same trusted Wi-Fi as your sender.", 17, 0xFFB7C9D6), 10);
        status = label("Starting receiver…", 21, MINT); add(status, 24);
        endpoint = label("", 15, 0xFFB7C9D6); add(endpoint, 6);
        audioStatus = label("Starting legacy audio receiver…", 15, MINT); add(audioStatus, 6);
        add(label("Experimental legacy video and audio. AirPlay 2, screen mirroring and protected streaming services are not supported. iPhone compatibility needs testing.", 15, 0xFFB7C9D6), 16);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open = button("Open media URL");
        openButton = open;
        open.setOnClickListener(v -> showUrlDialog());
        actions.addView(open);
        Button toggle = button("Pause / resume");
        toggle.setOnClickListener(v -> { if (player.getMediaItemCount() > 0) { if (player.isPlaying()) player.pause(); else player.play(); } });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = dp(12);
        actions.addView(toggle, gap);
        Button stop = button("Stop"); stop.setOnClickListener(v -> stop());
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-2, -2); gap2.leftMargin = dp(12);
        actions.addView(stop, gap2); add(actions, 20);
        Button licenses = button("Open-source licenses");
        licenses.setOnClickListener(v -> showLicenses()); add(licenses, 10);
        playbackStatus = label("Try a direct MP4, HLS or audio URL to test playback.", 14, 0xFFB7C9D6); add(playbackStatus, 12);
        open.requestFocus();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int value) {
                if (value == Player.STATE_READY && startFraction > 0 && player.getDuration() > 0) {
                    double fraction = startFraction; startFraction = 0;
                    player.seekTo((long)(player.getDuration() * fraction));
                }
                if (value == Player.STATE_ENDED) showHome("Playback finished");
            }
            @Override public void onPlayerError(PlaybackException error) {
                showHome("Could not play media (" + error.getErrorCodeName() + "). Check the URL and format.");
            }
        });
    }
    private void add(View view, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); home.addView(view, p);
    }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setPadding(dp(20), dp(8), dp(20), dp(8));
        b.setOnFocusChangeListener((v, focus) -> {
            GradientDrawable bg = new GradientDrawable(); bg.setColor(focus ? 0xFF215D62 : 0xFF183348);
            bg.setCornerRadius(dp(10)); bg.setStroke(dp(2), focus ? MINT : 0xFF345166); v.setBackground(bg);
        });
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF183348); bg.setCornerRadius(dp(10)); b.setBackground(bg);
        return b;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showUrlDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint("https://example.com/video.mp4");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Open video or audio URL").setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Play", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            try { AirPlayServer.validateMediaUrl(url); dialog.dismiss(); play(url, 0); }
            catch (IllegalArgumentException e) { input.setError("Enter a complete HTTP or HTTPS media URL"); }
        }));
        dialog.show();
    }
    private void showLicenses() {
        StringBuilder text = new StringBuilder();
        for (String file : new String[]{"THIRD_PARTY.txt", "LGPL-2.1.txt", "GPL-2.txt", "Apache-2.0.txt"}) {
            try (java.io.InputStream in = getAssets().open("licenses/" + file)) {
                java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                byte[] bytes = new byte[4096]; int n;
                while ((n = in.read(bytes)) != -1) data.write(bytes, 0, n);
                text.append(new String(data.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");
            } catch (java.io.IOException e) { text.append("License file unavailable: ").append(file); }
        }
        TextView content = label(text.toString(), 14, Color.WHITE); content.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Close", null).show();
    }
    @Override protected void onStart() {
        super.onStart(); active = true; main.post(refresh);
        String id = getPreferences(0).getString("deviceId", null);
        if (id == null) {
            byte[] bytes = new byte[6]; new java.security.SecureRandom().nextBytes(bytes); bytes[0] = (byte)((bytes[0] | 2) & 0xfe);
            StringBuilder b = new StringBuilder();
            for (byte value : bytes) { if (b.length() > 0) b.append(':'); b.append(String.format(Locale.ROOT, "%02X", value & 255)); }
            id = b.toString(); getPreferences(0).edit().putString("deviceId", id).apply();
        }
        deviceId = id;
        nsd = (NsdManager)getSystemService(NSD_SERVICE);
        try {
            receiver = new AirPlayServer(7000, id, this); receiver.start();
            endpoint.setText("Receiver address: " + addresses() + "  •  Port " + receiver.port());
            nsd = (NsdManager)getSystemService(NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("TV AirPlay (Legacy)"); info.setServiceType("_airplay._tcp."); info.setPort(receiver.port());
            info.setAttribute("deviceid", id); info.setAttribute("features", "0x1");
            info.setAttribute("model", "AppleTV2,1"); info.setAttribute("srcvers", "130.14");
            info.setAttribute("flags", "0x4"); info.setAttribute("vv", "1");
            registration = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo s) { runOnUiThread(() -> { if (active) status.setText("Legacy receiver: " + s.getServiceName()); }); }
                public void onRegistrationFailed(NsdServiceInfo s, int error) { runOnUiThread(() -> { if (active) status.setText("Receiver running • discovery failed (" + error + ")"); }); }
                public void onServiceUnregistered(NsdServiceInfo s) { }
                public void onUnregistrationFailed(NsdServiceInfo s, int error) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) {
            if (receiver != null) { receiver.close(); receiver = null; }
            status.setText("Receiver could not start. Close other receivers and reopen the app.");
        }
        startAudioReceiver();
        if (audioReceiver != null) {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(id.replace(":", "") + "@TV AirPlay Audio");
            info.setServiceType("_raop._tcp."); info.setPort(audioReceiver.port());
            info.setAttribute("txtvers", "1"); info.setAttribute("ch", "2"); info.setAttribute("cn", "1");
            info.setAttribute("et", "1"); info.setAttribute("sv", "false"); info.setAttribute("da", "true");
            info.setAttribute("sr", "44100"); info.setAttribute("ss", "16"); info.setAttribute("pw", "false");
            info.setAttribute("vn", "3"); info.setAttribute("tp", "TCP,UDP"); info.setAttribute("vs", "130.14");
            info.setAttribute("sm", "false"); info.setAttribute("ek", "1");
            audioRegistration = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo s) { runOnUiThread(() -> { if (active) audioStatus.setText("Legacy audio receiver: TV AirPlay Audio • port 5000"); }); }
                public void onRegistrationFailed(NsdServiceInfo s, int error) { runOnUiThread(() -> { if (active) audioStatus.setText("Audio discovery failed (" + error + ")"); }); }
                public void onServiceUnregistered(NsdServiceInfo s) { }
                public void onUnregistrationFailed(NsdServiceInfo s, int error) { }
            };
            try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, audioRegistration); }
            catch (RuntimeException e) { audioStatus.setText("Audio listener started, but discovery could not start"); }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
    private void startAudioReceiver() {
        int generation = ++audioGeneration;
        if (audioReceiver != null) { audioReceiver.close(); audioReceiver = null; }
        try {
            audioReceiver = new NativeRaop(this, deviceId, (playing, message) -> runOnUiThread(() -> {
                if (!active || audioGeneration != generation) return;
                if (playing) { player.stop(); player.clearMediaItems(); startFraction = 0; showHome(message); }
                audioStatus.setText(message);
            }));
            audioStatus.setText("Legacy audio listener running • port " + audioReceiver.port());
        } catch (java.io.IOException | LinkageError | RuntimeException e) {
            audioStatus.setText("Legacy audio could not start. Check device support and port 5000.");
        }
    }
    private String addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                NetworkInterface n = all.nextElement(); if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> addresses = n.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement(); if (a instanceof Inet4Address) result.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) { }
        return result.isEmpty() ? "No IPv4 network • connect Wi-Fi or Ethernet" : String.join(", ", result);
    }
    private void showHome(String message) {
        ((View)home.getParent()).setVisibility(View.VISIBLE); playerView.setVisibility(View.GONE);
        playbackStatus.setText(message); openButton.requestFocus();
    }
    @Override public void play(String url, double fraction) {
        runOnUiThread(() -> {
            if (!active) return;
            startAudioReceiver();
            startFraction = fraction;
            player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play();
            ((View)home.getParent()).setVisibility(View.GONE); playerView.setVisibility(View.VISIBLE);
            playerView.requestFocus(); playerView.showController();
        });
    }
    @Override public void pause(boolean paused) { runOnUiThread(() -> { if (active) { if (paused) player.pause(); else player.play(); } }); }
    @Override public void seek(double seconds) { runOnUiThread(() -> { if (active) player.seekTo((long)(seconds * 1000)); }); }
    @Override public void stop() { runOnUiThread(() -> { if (active) { startAudioReceiver(); player.stop(); player.clearMediaItems(); startFraction = 0; showHome("Playback stopped"); } }); }
    @Override public AirPlayServer.State state() { return state; }
    @Override public void onBackPressed() {
        if (playerView.getVisibility() == View.VISIBLE) stop(); else super.onBackPressed();
    }
    @Override protected void onStop() {
        active = false; main.removeCallbacks(refresh);
        ++audioGeneration;
        if (audioRegistration != null && nsd != null) {
            try { nsd.unregisterService(audioRegistration); } catch (IllegalArgumentException ignored) { }
            audioRegistration = null;
        }
        if (audioReceiver != null) { audioReceiver.close(); audioReceiver = null; }
        if (registration != null && nsd != null) {
            try { nsd.unregisterService(registration); } catch (IllegalArgumentException ignored) { }
            registration = null;
        }
        if (receiver != null) { receiver.close(); receiver = null; }
        player.stop(); player.clearMediaItems(); startFraction = 0;
        state = new AirPlayServer.State(0, 0, false, false);
        showHome("Receiver stopped while app is in the background");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }
    @Override protected void onDestroy() { playerView.setPlayer(null); player.release(); super.onDestroy(); }
}
