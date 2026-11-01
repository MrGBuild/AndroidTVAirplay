package com.github.serezhka.airplay.server.internal;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
/** Android adaptation: small portable event loops, no native transport probes. */
final class NettyEventLoops {
    static EventLoopGroup create() { return new NioEventLoopGroup(1, new io.netty.util.concurrent.DefaultThreadFactory("airplay-io", true)); }
}
