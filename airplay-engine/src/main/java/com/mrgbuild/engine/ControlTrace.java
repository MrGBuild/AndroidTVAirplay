package com.mrgbuild.engine;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import java.util.Set;
import java.util.function.Consumer;

/** Records before authentication, including rejected requests. No headers, bodies or peer addresses. */
public final class ControlTrace extends ChannelDuplexHandler {
    private static final Set<String> PATHS = Set.of("/info", "/server-info", "/pair-setup",
            "/pair-verify", "/pair-pin-start", "/pair-setup-pin", "/fp-setup", "/fp-setup2",
            "/auth-setup", "/feedback", "/reverse", "/play", "/stop", "/event", "/audioMode", "/");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "OPTIONS", "SETUP",
            "ANNOUNCE", "RECORD", "TEARDOWN", "FLUSH", "GET_PARAMETER", "SET_PARAMETER");
    private final Consumer<String> history;
    private final String connection;
    public ControlTrace(Consumer<String> history, int connection) {
        this.history = history; this.connection = "#" + connection + " ";
    }
    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        history.accept(connection + "TCP opened"); super.channelActive(ctx);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        history.accept(connection + "TCP closed"); super.channelInactive(ctx);
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {
            String method = request.method().name();
            String path = new QueryStringDecoder(request.uri()).path();
            history.accept(connection + (METHODS.contains(method) ? method : "OTHER")
                    + " " + (PATHS.contains(path) ? path : "[target omitted]")
                    + " (" + request.content().readableBytes() + " bytes)");
        }
        super.channelRead(ctx, msg);
    }
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse response) history.accept(connection + "Response " + response.status().code());
        super.write(ctx, msg, promise);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Exception messages can include payloads. Retain only a fixed category.
        history.accept(connection + "Protocol error; connection closed");
        ctx.close();
    }
}
