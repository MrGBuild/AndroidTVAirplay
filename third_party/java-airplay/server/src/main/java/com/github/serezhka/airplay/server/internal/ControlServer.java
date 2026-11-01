package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.control.ControlHandler;
import com.github.serezhka.airplay.server.internal.handler.control.HlsFcupService;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class ControlServer implements Runnable {

    private final SessionManager sessionManager;
    private final HlsFcupService hlsFcupService;

    private final AirPlayConfig airPlayConfig;
    private final Playback airPlayConsumer;

    private Thread thread;
    private io.netty.channel.Channel serverChannel;
    private final io.netty.channel.group.ChannelGroup children=new io.netty.channel.group.DefaultChannelGroup(io.netty.util.concurrent.GlobalEventExecutor.INSTANCE);

    @Getter
    private volatile int port;

    private final java.util.function.Consumer<String> diagnostics;
    private final java.util.concurrent.atomic.AtomicInteger connectionIds = new java.util.concurrent.atomic.AtomicInteger();

    public ControlServer(AirPlayConfig airPlayConfig, Playback airPlayConsumer, PairingIdentity identity) {
        this(airPlayConfig, airPlayConsumer, identity, event -> {});
    }

    public ControlServer(AirPlayConfig airPlayConfig, Playback airPlayConsumer, PairingIdentity identity,
                         java.util.function.Consumer<String> diagnostics) {
        this.diagnostics = diagnostics;
        this.sessionManager = new SessionManager(identity);
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        this.hlsFcupService = new HlsFcupService(sessionManager, airPlayConsumer);
        airPlayConsumer.setObserver(new Playback.Observer() {
            @Override
            public void onEnded() {
                hlsFcupService.refreshActivePlaylists();
            }

            @Override
            public void onPresented() {
                hlsFcupService.onPlaybackPresented();
            }

            @Override
            public void onSeekDisplayed() {
                hlsFcupService.onSeekDisplayed();
            }
        });
    }

    public void start() throws InterruptedException {
        thread = new Thread(this, "airplay-control");
        thread.setDaemon(true);
        synchronized (this) {
            thread.start();
            wait(10000);
        }
        if (port == 0) throw new IllegalStateException("Receiver socket did not start");
    }

    public void stop() {
        children.close();
        if(serverChannel!=null)serverChannel.close();
        sessionManager.closeAll();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public void run() {
        var serverBootstrap = new ServerBootstrap();
        var bossGroup = eventLoopGroup();
        var workerGroup = eventLoopGroup();
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            children.add(ch);
                            ch.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(2 * 1024 * 1024),
                                    new com.mrgbuild.engine.ControlTrace(diagnostics, connectionIds.incrementAndGet()),
                                    new com.mrgbuild.engine.PasswordGate(airPlayConfig.getPassword(),airPlayConfig.getPlaybackToken()),
                                    new ControlHandler(sessionManager, hlsFcupService, airPlayConfig, airPlayConsumer),
                                    new io.netty.channel.ChannelInboundHandlerAdapter() {
                                        @Override public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                                            diagnostics.accept("Protocol handler failed; connection closed");
                                            ctx.close();
                                        }
                                    });
                        }
                    })
                    .childOption(ChannelOption.SO_RCVBUF, 256 * 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            var channelFuture = serverBootstrap.bind().sync();
            serverChannel=channelFuture.channel();

            log.info("AirPlay control server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (this) {
                this.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            log.info("AirPlay control server stopped");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return NettyEventLoops.create();
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return NioServerSocketChannel.class;
    }
}
