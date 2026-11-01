package com.mrgbuild.engine;

import com.github.serezhka.airplay.server.discovery.AdvertisedReceiver;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/** Direct multicast publisher, bound to an explicitly selected LAN address. */
public final class MdnsPublisher implements AutoCloseable {
    private final JmDNS dns;
    public MdnsPublisher(Inet4Address address, String name, String deviceId, String pk, int port) throws IOException {
        dns = JmDNS.create(address, "tvairplay-" + deviceId.replace(":", "").toLowerCase(java.util.Locale.ROOT));
        try {
            ServiceInfo video=ServiceInfo.create("_airplay._tcp.local.", name, port, 0, 0, videoProperties(deviceId, pk));
            ServiceInfo audio=ServiceInfo.create("_raop._tcp.local.", deviceId.replace(":", "") + "@" + name, port, 0, 0, audioProperties(deviceId, pk));
            dns.registerService(video);dns.registerService(audio);
            if(!((javax.jmdns.impl.ServiceInfoImpl)video).waitForAnnounced(8000)
                    ||!((javax.jmdns.impl.ServiceInfoImpl)audio).waitForAnnounced(8000))throw new IOException("Multicast discovery did not finish announcing");
        } catch (IOException | RuntimeException e) { dns.close(); throw e; }
    }
    public static Map<String,String> videoProperties(String id, String pk) {
        Map<String,String> p = new LinkedHashMap<>();
        p.put("deviceid", id); p.put("features", AdvertisedReceiver.featuresTxt(true));
        p.put("srcvers", AdvertisedReceiver.SOURCE_VERSION); p.put("model", AdvertisedReceiver.MODEL);
        p.put("flags", "0xc4"); p.put("vv", "2"); p.put("pw", "true"); p.put("pk", pk);
        return p;
    }
    public static Map<String,String> audioProperties(String id, String pk) {
        Map<String,String> p = new LinkedHashMap<>(videoProperties(id, pk));
        p.put("am", AdvertisedReceiver.MODEL); p.put("ft", AdvertisedReceiver.featuresTxt(true));
        p.put("ch", "2"); p.put("cn", "1,3"); p.put("et", "0,3,5"); p.put("ek", "1");
        p.put("sr", "44100"); p.put("ss", "16"); p.put("sv", "false"); p.put("da", "true");
        p.put("sm", "false"); p.put("tp", "UDP"); p.put("txtvers", "1"); p.put("sf", "0xc4");
        p.put("vs", AdvertisedReceiver.SOURCE_VERSION); p.put("vn", "65537");
        return p;
    }
    @Override public void close() throws IOException { dns.close(); }
}
