package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName;
    private String password;
    private String playbackToken;
    private String deviceId;
    private int timingPort;
    private int width;
    private int height;
    private int fps;
    private boolean hlsEnabled;
    private boolean mdnsLoopback;
    /** System tray menu (cast picker + quit). Bound from {@code airplay.tray-enabled}. */
    private boolean trayEnabled = true;
}
