package com.mrgbuild.tvairplay;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.LinkProperties;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
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
import com.mrgbuild.receiver.ReceiverName;
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
    private TextView status, endpoint, playbackStatus, audioStatus, passwordStatus, nameStatus;
    private Button openButton;
    private ReceiverController controller;
    private MirrorVideoPlayer mirror;
    private AirPlayAudioPlayer airAudio;
    private SurfaceView mirrorView;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Playback.Observer playbackObserver=Playback.Observer.NONE;
    private volatile String latestExchange="No sender requests received";
    private String deviceId;
    private String receiverPassword;
    private android.app.AlertDialog passwordDialog;
    private android.app.AlertDialog nameDialog;
    private String receiverName = ReceiverName.DEFAULT;
    private volatile int receiverGeneration;
    private volatile AirPlayServer.State state = new AirPlayServer.State(0, 0, false, false);
    private double startFraction;
    private volatile boolean active;
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
        var dataSources=new androidx.media3.datasource.ResolvingDataSource.Factory(new androidx.media3.datasource.DefaultDataSource.Factory(this),spec -> {
            String host=spec.uri.getHost();
            if(controller!=null&&("127.0.0.1".equals(host)||"localhost".equals(host)||"::1".equals(host))&&spec.uri.getPort()==controller.localPlaybackPort()&&!controller.localPlaybackToken().isEmpty())
                return spec.withAdditionalHeaders(java.util.Collections.singletonMap("X-TVAirPlay-Local",controller.localPlaybackToken()));
            return spec;
        });
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSources)).setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,true).build();
        mirror=new MirrorVideoPlayer(message -> runOnUiThread(() -> {if(active)playbackStatus.setText(message);}));
        airAudio=new AirPlayAudioPlayer(message -> runOnUiThread(() -> {if(active)playbackStatus.setText(message);}));
        controller=new ReceiverController(this,message -> runOnUiThread(() -> {if(active){status.setText(message);endpoint.setText("Open Connection diagnostics for network details");}}));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(INK);
        mirrorView=new SurfaceView(this);mirrorView.setTag("mirror-output");mirrorView.setVisibility(View.GONE);
        mirrorView.getHolder().addCallback(new SurfaceHolder.Callback(){
            public void surfaceCreated(SurfaceHolder holder){mirror.surface(holder.getSurface());}
            public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){mirror.surface(holder.getSurface());}
            public void surfaceDestroyed(SurfaceHolder holder){mirror.surface(null);}
        });
        root.addView(mirrorView,new FrameLayout.LayoutParams(-1,-1));
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
        status.setTag("receiver-status");
        endpoint = label("", 15, 0xFFB7C9D6); add(endpoint, 6);
        audioStatus = label("Starting legacy audio receiver…", 15, MINT); add(audioStatus, 6);
        passwordStatus = label("A receiver password is required", 15, MINT); add(passwordStatus, 6);
        nameStatus = label("Receiver name: " + receiverName, 15, MINT); add(nameStatus, 6);
        add(label("Rebuilt receiver: mirroring, audio and video. Protected streaming services may refuse playback. Phone/TV compatibility is still being tested.", 15, 0xFFB7C9D6), 16);
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
        Button password = button("Set / change password");
        password.setOnClickListener(v -> showPasswordDialog()); add(password, 10);
        Button name = button("Change receiver name");
        name.setOnClickListener(v -> showNameDialog()); add(name, 10);
        Button retry=button("Restart discovery");retry.setOnClickListener(v -> restartReceiver());add(retry,10);
        Button diagnostics=button("Connection diagnostics");diagnostics.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Connection diagnostics").setMessage(controller.diagnostics()+"\nMirror: "+mirror.diagnostics()+"\n\nLast sender event: "+latestExchange+"\n\nThis app sends no diagnostic reports to a server.").setPositiveButton("Close",null).show());add(diagnostics,10);
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
                if (value == Player.STATE_ENDED) {playbackObserver.onEnded();showHome("Playback finished");}
            }
            @Override public void onRenderedFirstFrame(){playbackObserver.onPresented();playbackObserver.onSeekDisplayed();}
            @Override public void onPlayerError(PlaybackException error) {
                showHome("Could not play media (" + error.getErrorCodeName() + "). Check the URL and format.");
            }
        });
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network network){scheduleReconnect();}
            public void onLost(Network network){scheduleReconnect();}
            public void onLinkPropertiesChanged(Network network,LinkProperties props){scheduleReconnect();}
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }
    private final Runnable reconnect=() -> {if(active&&receiverPassword!=null&&controller.networkChanged())restartReceiver();};
    private void scheduleReconnect(){main.removeCallbacks(reconnect);main.postDelayed(reconnect,1500);}
    private void restartReceiver(){stopNetworkReceivers();player.stop();player.clearMediaItems();if(active&&receiverPassword!=null)startReceivers();showHome("Restarting discovery. Refresh AirPlay on your phone.");}
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
        for (String file : new String[]{"THIRD_PARTY.txt", "Apache-2.0.txt"}) {
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
    private void showPasswordDialog() {
        if (passwordDialog != null && passwordDialog.isShowing()) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        form.addView(label("Choose 8–64 letters, numbers or symbols without spaces. Enter this password on your sender when prompted. Changing it disconnects existing senders.", 15, Color.WHITE));
        EditText password = new EditText(this); password.setSingleLine(true); password.setHint("New receiver password");
        EditText confirm = new EditText(this); confirm.setSingleLine(true); confirm.setHint("Confirm password");
        int inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
        password.setInputType(inputType); confirm.setInputType(inputType);
        password.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        confirm.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        form.addView(password); form.addView(confirm);
        CheckBox visible = new CheckBox(this); visible.setText("Show password");
        visible.setOnCheckedChangeListener((v, checked) -> {
            android.text.method.TransformationMethod transformation = checked ? null : android.text.method.PasswordTransformationMethod.getInstance();
            password.setTransformationMethod(transformation); confirm.setTransformationMethod(transformation);
        }); form.addView(visible);
        passwordDialog = new android.app.AlertDialog.Builder(this).setTitle("Receiver password").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        passwordDialog.setOnShowListener(d -> passwordDialog.getButton(-1).setOnClickListener(v -> {
            String value = password.getText().toString();
            try { PasswordStore.validate(value); }
            catch (IllegalArgumentException e) { password.setError(e.getMessage()); return; }
            if (!value.equals(confirm.getText().toString())) { confirm.setError("Passwords do not match"); return; }
            try { new PasswordStore(this).save(value); }
            catch (java.security.GeneralSecurityException | java.io.IOException | RuntimeException e) {
                password.setError("Password could not be saved securely. Existing protection has not been disabled."); return;
            }
            stopNetworkReceivers(); player.stop(); player.clearMediaItems(); startFraction = 0;
            state = new AirPlayServer.State(0, 0, false, false);
            receiverPassword = value;
            if (active) startReceivers();
            passwordDialog.dismiss(); showHome("Password saved. Senders must authenticate before controlling playback.");
        }));
        passwordDialog.show();
    }
    private void showNameDialog() {
        if (nameDialog != null && nameDialog.isShowing()) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        form.addView(label("Choose the name shown on your sender. The audio receiver adds “Audio”. Saving a new name disconnects current senders.", 15, Color.WHITE));
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Living Room");
        input.setText(receiverName); input.selectAll(); form.addView(input);
        nameDialog = new android.app.AlertDialog.Builder(this).setTitle("Receiver name").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        nameDialog.setOnShowListener(d -> nameDialog.getButton(-1).setOnClickListener(v -> {
            final String value;
            try { value = ReceiverName.validate(input.getText().toString()); }
            catch (IllegalArgumentException e) { input.setError(e.getMessage()); return; }
            if (value.equals(receiverName)) { nameDialog.dismiss(); return; }
            if (!getPreferences(0).edit().putString("receiverName", value).commit()) {
                input.setError("The name could not be saved. Try again."); return;
            }
            stopNetworkReceivers(); player.stop(); player.clearMediaItems(); startFraction = 0;
            state = new AirPlayServer.State(0, 0, false, false);
            receiverName = value; nameStatus.setText("Receiver name: " + receiverName);
            if (active && receiverPassword != null) startReceivers();
            nameDialog.dismiss(); showHome("Receiver name saved. Refresh the receiver list on your sender.");
        }));
        nameDialog.show();
    }
    @Override protected void onStart() {
        super.onStart(); active = true; main.post(refresh);
        try { receiverName = ReceiverName.validate(getPreferences(0).getString("receiverName", ReceiverName.DEFAULT)); }
        catch (IllegalArgumentException e) { receiverName = ReceiverName.DEFAULT; }
        nameStatus.setText("Receiver name: " + receiverName);
        String id = getPreferences(0).getString("deviceId", null);
        if (id == null || !getPreferences(0).getBoolean("receiverRebuildIdentityV4",false)) {
            byte[] bytes = new byte[6]; new java.security.SecureRandom().nextBytes(bytes); bytes[0] = (byte)((bytes[0] | 2) & 0xfe);
            StringBuilder b = new StringBuilder();
            for (byte value : bytes) { if (b.length() > 0) b.append(':'); b.append(String.format(Locale.ROOT, "%02X", value & 255)); }
            id = b.toString();
            if(!getPreferences(0).edit().putString("deviceId",id).putBoolean("receiverRebuildIdentityV4",true).commit()) {
                status.setText("Receiver identity could not be saved. Connections remain closed.");return;
            }
        }
        deviceId = id;
        try { receiverPassword = new PasswordStore(this).load(); }
        catch (java.security.GeneralSecurityException | java.io.IOException | RuntimeException e) {
            receiverPassword = null;
            passwordStatus.setText("Saved password could not be unlocked. Set a new password to enable connections.");
        }
        if (receiverPassword == null) {
            status.setText("Connections locked • set a receiver password");
            audioStatus.setText("Audio receiver locked"); endpoint.setText("No network receiver is listening");
            showPasswordDialog(); return;
        }
        startReceivers();
    }
    private void startReceivers() {
        if(!active||receiverPassword==null)return;
        passwordStatus.setText("Password protection is on • applies to audio and video");
        final int generation=++receiverGeneration;
        Playback playback=new Playback(){
            private boolean current(){return active&&receiverGeneration==generation;}
            private void ui(Runnable command){main.post(() -> {if(current())command.run();});}
            public void onVideoFormat(VideoStreamInfo format){
                if(!current())return;mirror.begin();
                ui(() -> {player.stop();player.clearMediaItems();playerView.setVisibility(View.GONE);((View)home.getParent()).setVisibility(View.GONE);mirrorView.setVisibility(View.VISIBLE);mirrorView.requestFocus();});
            }
            public void onVideo(byte[] data){if(current())mirror.enqueue(data);}
            public void onVideoSrcDisconnect(){if(current())mirror.stop();ui(() -> {if(mirrorView.getVisibility()==View.VISIBLE)showHome("Mirroring disconnected");});}
            public void onAudioFormat(AudioStreamInfo format){if(current())airAudio.format(format);ui(() -> {player.stop();player.clearMediaItems();playbackStatus.setText("Audio session negotiated");});}
            public void onAudio(byte[] bytes){if(current())airAudio.enqueue(bytes,0,bytes.length);}
            public void onAudio(byte[] bytes,int offset,int length){if(current())airAudio.enqueue(bytes,offset,length);}
            public void onAudioSrcDisconnect(){if(current())airAudio.stop();}
            public void onPlaylist(String url){ui(() -> play(url,0));}
            public void onPlaylistRemoved(){ui(() -> {player.stop();player.clearMediaItems();showHome("Video disconnected");});}
            public void onPause(){ui(() -> player.pause());}
            public void onResume(){ui(() -> player.play());}
            public void onSeek(double seconds){ui(() -> player.seekTo((long)(seconds*1000)));}
            public void onVolume(double volume){if(current())airAudio.volume(volume);ui(() -> player.setVolume((float)Math.max(0,Math.min(1,volume))));}
            public Info info(){AirPlayServer.State snapshot=state;return new Info(snapshot.duration,snapshot.position,snapshot.playing?1:0);}
            public void setObserver(Observer observer){if(current())playbackObserver=observer;}
            public void onControlExchange(com.github.serezhka.airplay.server.ControlExchange event){
                if(current()&&(event.method().equals("SETUP")||event.method().equals("RECORD")||event.method().equals("POST")))
                    latestExchange=event.method()+" • response "+event.responseStatus();
            }
        };
        audioStatus.setText("Video and audio share the receiver name “"+receiverName+"”");
        controller.start(receiverName,deviceId,receiverPassword,playback);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        ((View)home.getParent()).setVisibility(View.VISIBLE); playerView.setVisibility(View.GONE); mirrorView.setVisibility(View.GONE);
        playbackStatus.setText(message); openButton.requestFocus();
    }
    @Override public void play(String url, double fraction) {
        runOnUiThread(() -> {
            if (!active) return;
            mirror.stop();airAudio.stop();mirrorView.setVisibility(View.GONE);
            startFraction = fraction;
            player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play();
            ((View)home.getParent()).setVisibility(View.GONE); playerView.setVisibility(View.VISIBLE);
            playerView.requestFocus(); playerView.showController();
        });
    }
    @Override public void pause(boolean paused) { runOnUiThread(() -> { if (active) { if (paused) player.pause(); else player.play(); } }); }
    @Override public void seek(double seconds) { runOnUiThread(() -> { if (active) player.seekTo((long)(seconds * 1000)); }); }
    @Override public void stop() { runOnUiThread(() -> { if (active) { stopNetworkReceivers(); startReceivers(); player.stop(); player.clearMediaItems(); startFraction = 0; showHome("Playback stopped"); } }); }
    @Override public AirPlayServer.State state() { return state; }
    @Override public void onBackPressed() {
        if (playerView.getVisibility() == View.VISIBLE || mirrorView.getVisibility()==View.VISIBLE) stop(); else super.onBackPressed();
    }
    @Override protected void onStop() {
        active = false; main.removeCallbacks(refresh);
        stopNetworkReceivers();
        receiverPassword = null;
        player.stop(); player.clearMediaItems(); startFraction = 0;
        state = new AirPlayServer.State(0, 0, false, false);
        showHome("Receiver stopped while app is in the background");
        super.onStop();
    }
    private void stopNetworkReceivers() {
        ++receiverGeneration;playbackObserver=Playback.Observer.NONE;latestExchange="No sender requests received";
        if(controller!=null)controller.stop();
        mirror.stop();airAudio.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onDestroy() {
        if(networkCallback!=null)connectivity.unregisterNetworkCallback(networkCallback);
        main.removeCallbacks(reconnect);controller.destroy();mirror.destroy();airAudio.destroy();
        playerView.setPlayer(null);player.release();super.onDestroy();
    }
}
