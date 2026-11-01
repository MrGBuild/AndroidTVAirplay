package com.mrgbuild.tvairplay;

import android.content.Context;
import android.net.*;
import android.net.wifi.WifiManager;
import com.mrgbuild.engine.EngineReceiver;
import com.github.serezhka.airplay.server.Playback;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Serial lifecycle off the UI thread; stale start attempts are closed before publishing. */
final class ReceiverController {
    private final Context context;
    private final Consumer<String> status;
    private final AtomicInteger generation=new AtomicInteger();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"receiver-lifecycle");t.setDaemon(true);return t;});
    private volatile EngineReceiver receiver;
    private WifiManager.MulticastLock multicast;
    private volatile String diagnostics="Receiver stopped";
    private volatile String localToken="";
    private volatile Inet4Address currentAddress;
    ReceiverController(Context context,Consumer<String> status){this.context=context.getApplicationContext();this.status=status;}
    void start(String name,String id,String password,Playback playback,com.mrgbuild.engine.MirrorQuality quality){
        int current=generation.incrementAndGet();
        status.accept("Starting receiver…");
        worker.execute(() -> {
            closeCurrent();if(generation.get()!=current)return;
            try {
                Inet4Address address=lanAddress();
                if(address==null)throw new java.io.IOException("No Wi-Fi or Ethernet IPv4 address. Connect the TV to your home network.");
                currentAddress=address;
                WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
                if(wifi!=null){multicast=wifi.createMulticastLock("TVAirPlay-discovery");multicast.setReferenceCounted(false);multicast.acquire();}
                receiver=new EngineReceiver(name,id,password,PairingIdentityStore.load(context),playback,quality);
                localToken=receiver.localPlaybackToken();
                receiver.start(address);
                if(generation.get()!=current){closeCurrent();return;}
                String nic=NetworkInterface.getByInetAddress(address).getName();
                diagnostics="Receiver: "+name+"\nNetwork: "+nic+"\nAddress: "+address.getHostAddress()+":"+receiver.port()
                        +"\nRequested mirror quality: "+quality.label+"\nDiscovery: direct IPv4 multicast DNS (_airplay._tcp and _raop._tcp)\nPassword: required\nProtocol: H.264 mirroring, ALAC/AAC audio, HLS video\nLocal registration completed; visibility on your phone still depends on LAN multicast.\nKeep this app open on the TV.";
                status.accept("Advertising “"+name+"” • "+address.getHostAddress()+":"+receiver.port());
            }catch(Exception|LinkageError e){
                closeCurrent();if(generation.get()!=current)return;
                String detail=e instanceof java.io.IOException?e.getMessage():e.getClass().getSimpleName();
                diagnostics="Receiver did not start: "+detail+"\nNo discovery success has been confirmed.";
                status.accept("Receiver did not start • "+detail);
            }
        });
    }
    String diagnostics(){EngineReceiver current=receiver;return diagnostics+"\n\nConnection history (this receiver start):\n"+(current==null?"Receiver stopped":current.connectionDiagnostics());}
    String localPlaybackToken(){return localToken;}
    int localPlaybackPort(){EngineReceiver current=receiver;return current==null?0:current.port();}
    boolean networkChanged(){Inet4Address now=lanAddress();return(currentAddress!=null&&!currentAddress.equals(now))||(diagnostics.startsWith("Receiver did not start")&&now!=null);}
    private Inet4Address lanAddress(){
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        List<Network> candidates=new ArrayList<>();Network active=manager.getActiveNetwork();if(active!=null)candidates.add(active);
        for(Network n:manager.getAllNetworks())if(!candidates.contains(n))candidates.add(n);
        for(Network network:candidates){
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ||!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
            LinkProperties properties=manager.getLinkProperties(network);if(properties==null)continue;
            for(LinkAddress link:properties.getLinkAddresses())if(link.getAddress() instanceof Inet4Address ip&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress())return ip;
        }return null;
    }
    void stop(){generation.incrementAndGet();diagnostics="Receiver stopped";worker.execute(this::closeCurrent);}
    void destroy(){stop();worker.shutdown();}
    private void closeCurrent(){
        localToken="";
        currentAddress=null;
        if(receiver!=null){receiver.close();receiver=null;}
        if(multicast!=null){if(multicast.isHeld())multicast.release();multicast=null;}
    }
}
