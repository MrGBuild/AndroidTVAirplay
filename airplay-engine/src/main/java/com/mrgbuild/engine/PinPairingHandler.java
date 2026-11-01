package com.mrgbuild.engine;

import com.dd.plist.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.util.ReferenceCountUtil;

/** Runs after Digest authentication. A 200 alone cannot complete the SRP exchange. */
public final class PinPairingHandler extends ChannelInboundHandlerAdapter {
    private final LegacyPinPairing pairing;
    private final java.util.function.Consumer<String> diagnostics;
    public PinPairingHandler(String password,byte[] receiverKey){this(password,receiverKey,event -> {});}
    public PinPairingHandler(String password,byte[] receiverKey,java.util.function.Consumer<String> diagnostics){pairing=new LegacyPinPairing(password,receiverKey);this.diagnostics=diagnostics;}
    @Override public void channelRead(ChannelHandlerContext ctx,Object msg)throws Exception{
        if(!(msg instanceof FullHttpRequest request)||!request.method().equals(HttpMethod.POST)
                ||!(request.uri().equals("/pair-pin-start")||request.uri().equals("/pair-setup-pin"))){ctx.fireChannelRead(msg);return;}
        var response=new DefaultFullHttpResponse(request.protocolVersion(),HttpResponseStatus.OK);
        try {
            if(request.uri().equals("/pair-pin-start"))pairing.reset();
            else {
                if(request.content().readableBytes()>4096)throw new LegacyPinPairing.RequestError("request too large");
                byte[] body=new byte[request.content().readableBytes()];request.content().getBytes(request.content().readerIndex(),body);
                NSObject plist;
                try {plist=BinaryPropertyListParser.parse(body);}
                catch(Exception e){throw new LegacyPinPairing.RequestError("invalid binary plist");}
                if(!(plist instanceof NSDictionary dictionary))throw new LegacyPinPairing.RequestError("expected dictionary");
                response.content().writeBytes(BinaryPropertyListWriter.writeToArray(pairing.exchange(dictionary)));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE,"application/x-apple-binary-plist");
            }
        }catch(LegacyPinPairing.RequestError e){pairing.reset();diagnostics.accept("PIN rejected: "+e.getMessage());response.setStatus(HttpResponseStatus.BAD_REQUEST);}
        catch(java.security.GeneralSecurityException e){pairing.reset();diagnostics.accept("PIN proof or encryption rejected");response.setStatus(HttpResponseStatus.FORBIDDEN);}
        catch(Exception e){pairing.reset();diagnostics.accept("PIN failure: "+e.getClass().getSimpleName());response.setStatus(HttpResponseStatus.BAD_REQUEST);}
        String seq=request.headers().get(RtspHeaderNames.CSEQ);if(seq!=null)response.headers().set(RtspHeaderNames.CSEQ,seq);
        response.headers().set(RtspHeaderNames.SERVER,"AirTunes/220.68");HttpUtil.setContentLength(response,response.content().readableBytes());
        ctx.writeAndFlush(response);ReferenceCountUtil.release(msg);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx)throws Exception{pairing.reset();super.channelInactive(ctx);}
}
