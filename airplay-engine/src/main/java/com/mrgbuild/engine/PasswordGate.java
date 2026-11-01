package com.mrgbuild.engine;

import com.mrgbuild.receiver.DigestAuth;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;

/** Discovery and crypto negotiation are public; media setup/control is password gated. */
public final class PasswordGate extends ChannelInboundHandlerAdapter {
    private final DigestAuth digest;
    private final String localToken;
    private boolean authenticated;
    public PasswordGate(String password,String localToken) { digest = new DigestAuth(password, "raop");this.localToken=localToken; }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) { ctx.fireChannelRead(msg); return; }
        String method = request.method().name(), path = new QueryStringDecoder(request.uri()).path();
        boolean badVerify=method.equals("POST")&&path.equals("/pair-verify")
                &&(request.content().readableBytes()!=68 || (request.content().getUnsignedByte(request.content().readerIndex())!=0&&request.content().getUnsignedByte(request.content().readerIndex())!=1));
        boolean fp=method.equals("POST")&&path.equals("/fp-setup")&&request.protocolVersion().text().startsWith("RTSP");
        int bodyLength=request.content().readableBytes();
        boolean badFp=fp&&(bodyLength!=16&&bodyLength!=164);
        if(fp&&!badFp)badFp=request.content().getUnsignedByte(request.content().readerIndex()+4)!=3
                ||(bodyLength==16&&request.content().getUnsignedByte(request.content().readerIndex()+14)>3);
        if(badVerify||badFp){
            var response=new DefaultFullHttpResponse(request.protocolVersion(),HttpResponseStatus.BAD_REQUEST);HttpUtil.setContentLength(response,0);
            String seq=request.headers().get(RtspHeaderNames.CSEQ);if(seq!=null)response.headers().set(RtspHeaderNames.CSEQ,seq);
            ctx.writeAndFlush(response);ReferenceCountUtil.release(msg);return;
        }
        if (method.equals("GET") && path.equals("/")) {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    io.netty.buffer.Unpooled.copiedBuffer("TV AirPlay receiver is running. Keep the TV app open and select its receiver name from your phone's AirPlay list. This address is a status page, not a media upload page.", java.nio.charset.StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            HttpUtil.setContentLength(response, response.content().readableBytes());
            ctx.writeAndFlush(response); ReferenceCountUtil.release(msg); return;
        }
        boolean negotiation = (method.equals("OPTIONS") || (method.equals("GET") && (path.equals("/info") || path.equals("/server-info")))
                || (method.equals("POST") && (path.equals("/pair-setup") || path.equals("/pair-verify") || path.equals("/fp-setup"))));
        boolean localPlaylist = method.equals("GET") && path.startsWith("/playlist")
                && ctx.channel().remoteAddress() instanceof InetSocketAddress remote && remote.getAddress().isLoopbackAddress()
                && localToken!=null&&localToken.length()>0&&request.headers().contains("X-TVAirPlay-Local")
                && java.security.MessageDigest.isEqual(localToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII),request.headers().get("X-TVAirPlay-Local").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (negotiation || localPlaylist || authenticated) { ctx.fireChannelRead(msg); return; }
        if (request.headers().getAll(HttpHeaderNames.AUTHORIZATION).size() == 1
                && digest.accepts(request.headers().get(HttpHeaderNames.AUTHORIZATION), method, request.uri())) {
            authenticated = true; ctx.fireChannelRead(msg); return;
        }
        var response = new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, digest.challenge());
        String seq = request.headers().get(RtspHeaderNames.CSEQ);
        if (seq != null) response.headers().set(RtspHeaderNames.CSEQ, seq);
        HttpUtil.setContentLength(response, 0); ctx.writeAndFlush(response);
        ReferenceCountUtil.release(msg);
    }
}
