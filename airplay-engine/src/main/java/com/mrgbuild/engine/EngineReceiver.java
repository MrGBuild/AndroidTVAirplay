package com.mrgbuild.engine;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.ControlServer;
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import java.net.Inet4Address;

/** Caller runs startup/close off the UI thread. Socket setup precedes advertisement. */
public final class EngineReceiver implements AutoCloseable {
    private final ControlServer server;
    private MdnsPublisher publisher;
    private TimingServer timing;
    private final AirPlayConfig config;
    private final ConnectionHistory history = new ConnectionHistory();
    public EngineReceiver(String name, String id, String password, byte[] identitySeed, Playback playback) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Password required");
        config = new AirPlayConfig(); config.setServerName(name); config.setDeviceId(id); config.setPassword(password);
        byte[] token=new byte[32];new java.security.SecureRandom().nextBytes(token);StringBuilder hex=new StringBuilder();
        for(byte b:token)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));config.setPlaybackToken(hex.toString());
        config.setWidth(1920); config.setHeight(1080); config.setFps(30); config.setHlsEnabled(true);
        identity = PairingIdentity.fromSeed(identitySeed);
        this.name = name; this.id = id;
        server = new ControlServer(config, playback, identity, history::add);
    }
    private final PairingIdentity identity;
    private final String name, id;
    public void start(Inet4Address address) throws Exception {
        timing=new TimingServer();config.setTimingPort(timing.port());
        server.start();
        try { publisher = new MdnsPublisher(address, name, id, identity.publicKeyHex(), server.getPort()); }
        catch (Exception e) { server.stop(); throw e; }
    }
    public void startWithoutDiscoveryForTest() throws Exception { timing=new TimingServer();config.setTimingPort(timing.port());server.start(); }
    public int port() { return server.getPort(); }
    public String publicKey() { return identity.publicKeyHex(); }
    public String localPlaybackToken(){return config.getPlaybackToken();}
    public String connectionDiagnostics(){return history.snapshot();}
    @Override public void close() {
        server.stop();
        if(timing!=null){timing.close();timing=null;}
        if (publisher != null) { try { publisher.close(); } catch (Exception ignored) { } publisher = null; }
    }
}
